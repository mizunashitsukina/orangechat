/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.startWebServer
import java.net.InetSocketAddress
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = true,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null,
    val securityIssue: WebServerSecurityIssue? = null,
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        _state.value = _state.value.copy(isLoading = true, error = null, securityIssue = null)
        appScope.launch {
            val settings = settingsStore.settingsFlowRaw.first()
            val securityDecision = evaluateWebServerSecurity(
                // The persisted choice is the sole authority for listener scope. Direct service
                // intents and future call sites cannot widen the network boundary.
                requestedLocalhostOnly = settings.webServerLocalhostOnly,
                jwtEnabled = settings.webServerJwtEnabled,
                accessPassword = settings.webServerAccessPassword,
            )
            val effectiveLocalhostOnly = securityDecision.effectiveLocalhostOnly
            val host = if (effectiveLocalhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
            val baseState = WebServerState(
                port = port,
                serviceName = serviceName,
                localhostOnly = effectiveLocalhostOnly,
                securityIssue = securityDecision.issue,
            )
            if (!securityDecision.canStart) {
                Log.w(TAG, "Web server start rejected by security policy")
                _state.value = baseState.copy(error = "Web server authentication configuration is incomplete")
                return@launch
            }
            if (securityDecision.issue == WebServerSecurityIssue.LAN_AUTH_REQUIRED) {
                Log.w(TAG, "Unsafe LAN configuration restricted to localhost")
                settingsStore.update(settings.copy(webServerLocalhostOnly = true))
            }
            try {
                _state.value = baseState.copy(isLoading = true)
                Log.i(TAG, "Web server start requested")
                if (!isPortAvailable(host, port)) {
                    Log.w(TAG, "Configured web server port is unavailable")
                    _state.value = baseState.copy(error = "Configured port is unavailable")
                    return@launch
                }
                server = startWebServer(port = port, host = host) {
                    configureWebApi(
                        context = context,
                        chatService = chatService,
                        conversationRepo = conversationRepo,
                        settingsStore = settingsStore,
                        filesManager = filesManager,
                        jwtEnabled = settings.webServerJwtEnabled,
                    )
                }.start(wait = false)

                _state.value = baseState.copy(isRunning = true)
                // 仅局域网模式注册 mDNS
                if (!effectiveLocalhostOnly) {
                    runCatching {
                        nsdRegistrar.register(
                            port = port,
                            serviceName = serviceName,
                            onRegistered = { info ->
                                _state.value = _state.value.copy(
                                    serviceName = info.serviceName,
                                    hostname = info.hostname,
                                    address = info.address.hostAddress
                                )
                            }
                        )
                    }.onFailure {
                        Log.w(TAG, "NSD registration failed: ${it.javaClass.simpleName}")
                    }
                }
                Log.i(TAG, "Web server started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Web server start failed: ${e.javaClass.simpleName}")
                _state.value = baseState.copy(error = "Web server failed to start")
            }
        }
    }

    fun stop() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed: ${it.javaClass.simpleName}")
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Web server stop failed: ${e.javaClass.simpleName}")
                _state.value = _state.value.copy(isLoading = false, error = "Web server failed to stop")
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
    ) {
        stop()
        start(port, serviceName)
    }

    private fun isPortAvailable(host: String, port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val response: Response

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(
                safeRequestLog(
                    method = request.method,
                    durationMs = System.currentTimeMillis() - startTime,
                    errorType = e.javaClass.simpleName,
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime

        Logging.logRequest(
            safeRequestLog(
                method = request.method,
                responseCode = response.code,
                durationMs = durationMs,
            )
        )

        return response
    }
}

internal fun safeRequestLog(
    method: String,
    responseCode: Int? = null,
    durationMs: Long,
    errorType: String? = null,
): LogEntry.RequestLog = LogEntry.RequestLog(
    tag = "HTTP",
    url = "[redacted]",
    method = method,
    responseCode = responseCode,
    durationMs = durationMs,
    error = errorType,
)

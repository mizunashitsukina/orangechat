/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import java.util.concurrent.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

private const val TAG = "SettingsJsonMigrator"

internal enum class BackupSettingsSchemaSection(val diagnosticCode: String) {
    CORE("BR-30-C"),
    DISPLAY("BR-30-D"),
    PROVIDERS("BR-30-P"),
    ASSISTANTS("BR-30-A"),
    MCP("BR-30-M"),
    SEARCH("BR-30-S"),
    TTS("BR-30-T"),
    ASR("BR-30-R"),
    PROMPTS("BR-30-I"),
    BACKUP("BR-30-B"),
}

internal class BackupSettingsCompatibilityException(
    val section: BackupSettingsSchemaSection,
    cause: Throwable,
) : Exception("Backup settings are incompatible", cause)

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [me.rerere.rikkahub.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /** Decodes an imported Settings document while retaining only a fixed, privacy-safe failure section. */
    internal fun decodeBackupSettings(settingsJson: String, json: Json = JsonInstant): Settings {
        val migrated = migrate(settingsJson)
        return try {
            json.decodeFromString<Settings>(migrated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupSettingsCompatibilityException(classifyFailureSection(migrated, json), e)
        }
    }

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // Missing in older backups: preserve LAN only when the legacy configuration already
            // has complete authentication. Otherwise migrate to loopback. Explicit choices are
            // preserved and independently checked again by the runtime policy.
            if ("webServerLocalhostOnly" !in root) {
                val legacyJwtEnabled = root["webServerJwtEnabled"]
                    ?.jsonPrimitive
                    ?.booleanOrNull == true
                val legacyPassword = root["webServerAccessPassword"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                root["webServerLocalhostOnly"] = JsonPrimitive(
                    !(legacyJwtEnabled && legacyPassword.isNotBlank())
                )
            }

            migrateNullableModelIds(root)

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as? JsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "Settings JSON migration failed: ${it.javaClass.simpleName}")
        }.getOrDefault(settingsJson)
    }

    private fun migrateNullableModelIds(root: MutableMap<String, JsonElement>) {
        val availableModelIds = root["providers"]
            ?.let { providers -> collectModelIds(providers) }
            .orEmpty()
        val fallback = sequenceOf("fastModelId", "chatModelId")
            .mapNotNull { field -> root[field].validModelId(availableModelIds) }
            .firstOrNull()

        listOf("titleModelId", "suggestionModelId").forEach { field ->
            if (root[field] is JsonNull && fallback != null) {
                root[field] = fallback
            }
        }
    }

    private fun collectModelIds(providers: JsonElement): Set<Uuid> {
        val providerArray = runCatching { providers.jsonArray }.getOrNull() ?: return emptySet()
        return providerArray.flatMap { provider ->
            val models = runCatching { provider.jsonObject["models"]?.jsonArray }.getOrNull()
                ?: JsonArray(emptyList())
            models.mapNotNull { model ->
                runCatching { model.jsonObject["id"] }.getOrNull().parseUuidOrNull()
            }
        }.toSet()
    }

    private fun JsonElement?.validModelId(availableModelIds: Set<Uuid>): JsonPrimitive? {
        val primitive = this as? JsonPrimitive ?: return null
        val id = primitive.parseUuidOrNull() ?: return null
        return primitive.takeIf { id in availableModelIds }
    }

    private fun JsonElement?.parseUuidOrNull(): Uuid? {
        val value = (this as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?: return null
        return runCatching { Uuid.parse(value) }.getOrNull()
    }

    private fun classifyFailureSection(settingsJson: String, json: Json): BackupSettingsSchemaSection {
        val root = try {
            json.parseToJsonElement(settingsJson).jsonObject
        } catch (_: Exception) {
            return BackupSettingsSchemaSection.CORE
        }

        if (root.hasInvalid<List<CustomTheme>>(json, "customThemes") ||
            root.hasInvalid<DisplaySetting>(json, "displaySetting")
        ) {
            return BackupSettingsSchemaSection.DISPLAY
        }
        if (root.hasInvalid<List<ProviderSetting>>(json, "providers")) {
            return BackupSettingsSchemaSection.PROVIDERS
        }
        if (root.hasInvalid<List<Assistant>>(json, "assistants") ||
            root.hasInvalid<List<Tag>>(json, "assistantTags")
        ) {
            return BackupSettingsSchemaSection.ASSISTANTS
        }
        if (root.hasInvalid<List<McpServerConfig>>(json, "mcpServers")) {
            return BackupSettingsSchemaSection.MCP
        }
        if (root.hasInvalid<List<SearchServiceOptions>>(json, "searchServices") ||
            root.hasInvalid<SearchCommonOptions>(json, "searchCommonOptions")
        ) {
            return BackupSettingsSchemaSection.SEARCH
        }
        if (root.hasInvalid<List<TTSProviderSetting>>(json, "ttsProviders")) {
            return BackupSettingsSchemaSection.TTS
        }
        if (root.hasInvalid<List<ASRProviderSetting>>(json, "asrProviders")) {
            return BackupSettingsSchemaSection.ASR
        }
        if (root.hasInvalid<List<PromptInjection.ModeInjection>>(json, "modeInjections") ||
            root.hasInvalid<List<Lorebook>>(json, "lorebooks") ||
            root.hasInvalid<List<QuickMessage>>(json, "quickMessages")
        ) {
            return BackupSettingsSchemaSection.PROMPTS
        }
        if (root.hasInvalid<WebDavConfig>(json, "webDavConfig") ||
            root.hasInvalid<S3Config>(json, "s3Config") ||
            root.hasInvalid<BackupReminderConfig>(json, "backupReminderConfig")
        ) {
            return BackupSettingsSchemaSection.BACKUP
        }
        return BackupSettingsSchemaSection.CORE
    }

    private inline fun <reified T> JsonObject.hasInvalid(json: Json, key: String): Boolean {
        val value = this[key] ?: return false
        return try {
            json.decodeFromJsonElement<T>(value)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
    }
}

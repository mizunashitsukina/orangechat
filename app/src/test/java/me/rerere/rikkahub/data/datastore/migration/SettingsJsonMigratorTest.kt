/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsJsonMigratorTest {
    @Test
    fun nullTitleModelUsesAvailableFastModel() {
        val settings = decodeSettings(settingsJson(titleModelId = null, suggestionModelId = CHAT_MODEL_ID))

        assertEquals(Uuid.parse(FAST_MODEL_ID), settings.titleModelId)
        assertEquals(Uuid.parse(CHAT_MODEL_ID), settings.suggestionModelId)
    }

    @Test
    fun nullSuggestionModelUsesAvailableFastModel() {
        val settings = decodeSettings(settingsJson(titleModelId = CHAT_MODEL_ID, suggestionModelId = null))

        assertEquals(Uuid.parse(CHAT_MODEL_ID), settings.titleModelId)
        assertEquals(Uuid.parse(FAST_MODEL_ID), settings.suggestionModelId)
    }

    @Test
    fun bothNullModelIdsUseAvailableFastModel() {
        val settings = decodeSettings(settingsJson(titleModelId = null, suggestionModelId = null))

        assertEquals(Uuid.parse(FAST_MODEL_ID), settings.titleModelId)
        assertEquals(Uuid.parse(FAST_MODEL_ID), settings.suggestionModelId)
    }

    @Test
    fun unavailableFastModelFallsBackToAvailableChatModel() {
        val source = settingsJson(
            titleModelId = null,
            suggestionModelId = null,
            fastModelId = UNAVAILABLE_MODEL_ID,
        )
        val settings = decodeSettings(source)

        assertEquals(Uuid.parse(CHAT_MODEL_ID), settings.titleModelId)
        assertEquals(Uuid.parse(CHAT_MODEL_ID), settings.suggestionModelId)
    }

    @Test
    fun noAvailableFallbackLeavesNullForSafeDecodeFailure() {
        val source = """{"titleModelId":null,"providers":[]}"""
        val migrated = JsonInstant.parseToJsonElement(SettingsJsonMigrator.migrate(source)).jsonObject

        assertSame(JsonNull, migrated["titleModelId"])
        try {
            JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(migrated))
            fail("Expected incompatible settings to be rejected")
        } catch (_: SerializationException) {
            // Expected: the migration must not invent a model identifier.
        }
    }

    @Test
    fun unknownProviderTypeStillFailsDeserialization() {
        val source = settingsJson(
            titleModelId = null,
            suggestionModelId = null,
            providerType = "future-provider-type",
        )

        try {
            decodeSettings(source)
            fail("Expected unknown provider type to be rejected")
        } catch (_: SerializationException) {
            // Expected: only the two confirmed nullable fields are migrated.
        }
    }

    @Test
    fun rikkahub248PublicPolymorphicShapesReproduceCurrentSettingsFailure() {
        upstreamOnlySections().forEach { (section, rootField, _) ->
            try {
                decodeSettings(
                    settingsJson(
                        titleModelId = null,
                        suggestionModelId = null,
                        extraRootField = rootField,
                    )
                )
                fail("Expected the public RikkaHub 2.4.8 $section shape to be rejected")
            } catch (_: SerializationException) {
                // Reproduces BR-30 without retaining any user value or credential.
            }
        }
    }

    @Test
    fun rikkahub248PublicFailuresReceiveStablePrivacySafeSchemaSections() {
        upstreamOnlySections().forEach { (_, rootField, expectedSection) ->
            val failure = try {
                SettingsJsonMigrator.decodeBackupSettings(
                    settingsJson(
                        titleModelId = null,
                        suggestionModelId = null,
                        extraRootField = rootField,
                    )
                )
                fail("Expected incompatible public RikkaHub 2.4.8 schema")
                throw AssertionError("unreachable")
            } catch (e: BackupSettingsCompatibilityException) {
                e
            }

            assertEquals(expectedSection, failure.section)
            assertFalse(failure.message.orEmpty().contains(LEGACY_PRIVATE_MARKER))
            assertFalse(failure.section.diagnosticCode.contains(LEGACY_PRIVATE_MARKER))
        }
    }

    @Test
    fun unknownProviderRemainsAProviderSchemaFailure() {
        val failure = try {
            SettingsJsonMigrator.decodeBackupSettings(
                settingsJson(
                    titleModelId = null,
                    suggestionModelId = null,
                    providerType = "future-provider-$LEGACY_PRIVATE_MARKER",
                )
            )
            fail("Expected unknown provider type to be rejected")
            throw AssertionError("unreachable")
        } catch (e: BackupSettingsCompatibilityException) {
            e
        }

        assertEquals(BackupSettingsSchemaSection.PROVIDERS, failure.section)
        assertFalse(failure.message.orEmpty().contains(LEGACY_PRIVATE_MARKER))
    }

    @Test
    fun rikkahub248ScreenTimeToolAndContextLimitArePreserved() {
        val settings = SettingsJsonMigrator.decodeBackupSettings(
            settingsJson(
                titleModelId = null,
                suggestionModelId = null,
                extraRootField = """
                    "assistants":[
                      {
                        "id":"$LEGACY_ITEM_ID",
                        "chatModelId":null,
                        "name":"",
                        "avatar":{"type":"me.rerere.rikkahub.data.model.Avatar.Dummy"},
                        "useAssistantAvatar":false,
                        "tags":[],
                        "systemPrompt":"",
                        "temperature":null,
                        "topP":null,
                        "contextMessageLimit":24,
                        "streamOutput":true,
                        "enableMemory":false,
                        "useGlobalMemory":false,
                        "enableRecentChatsReference":false,
                        "messageTemplate":"{{ message }}",
                        "presetMessages":[],
                        "quickMessageIds":[],
                        "regexes":[],
                        "reasoningLevel":"auto",
                        "maxTokens":null,
                        "customHeaders":[],
                        "customBodies":[],
                        "mcpServers":[],
                        "localTools":[{"type":"screen_time"}],
                        "enableWebSearch":false,
                        "workspaceId":null,
                        "background":null,
                        "backgroundOpacity":1.0,
                        "useGradientBackground":false,
                        "modeInjectionIds":[],
                        "lorebookIds":[],
                        "enabledSkills":[],
                        "enableTimeReminder":false,
                        "allowConversationSystemPrompt":false,
                        "allowConversationPromptInjection":false
                      }
                    ]
                """.trimIndent(),
            )
        )

        val assistant = settings.assistants.single()
        assertEquals(24, assistant.contextMessageSize)
        assertEquals(listOf(LocalToolOption.LegacyScreenTime), assistant.localTools)

        val roundTripped = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings))
        assertEquals(listOf(LocalToolOption.LegacyScreenTime), roundTripped.assistants.single().localTools)
    }

    @Test
    fun unknownAssistantToolRemainsAnAssistantSchemaFailure() {
        val failure = try {
            SettingsJsonMigrator.decodeBackupSettings(
                settingsJson(
                    titleModelId = null,
                    suggestionModelId = null,
                    extraRootField = """
                        "assistants":[
                          {
                            "id":"$LEGACY_ITEM_ID",
                            "localTools":[{"type":"future-tool-$LEGACY_PRIVATE_MARKER"}]
                          }
                        ]
                    """.trimIndent(),
                )
            )
            fail("Expected an unknown assistant tool to be rejected")
            throw AssertionError("unreachable")
        } catch (e: BackupSettingsCompatibilityException) {
            e
        }

        assertEquals(BackupSettingsSchemaSection.ASSISTANTS, failure.section)
        assertFalse(failure.message.orEmpty().contains(LEGACY_PRIVATE_MARKER))
    }

    private fun decodeSettings(source: String): Settings =
        JsonInstant.decodeFromString(SettingsJsonMigrator.migrate(source))

    private fun settingsJson(
        titleModelId: String?,
        suggestionModelId: String?,
        fastModelId: String = FAST_MODEL_ID,
        providerType: String = "openai",
        extraRootField: String? = null,
    ): String = """
        {
          "chatModelId": "$CHAT_MODEL_ID",
          "fastModelId": "$fastModelId",
          "titleModelId": ${titleModelId.asJsonStringOrNull()},
          "suggestionModelId": ${suggestionModelId.asJsonStringOrNull()},
          "providers": [
            {
              "type": "$providerType",
              "id": "$PROVIDER_ID",
              "models": [
                {"id": "$FAST_MODEL_ID", "modelId": "fast"},
                {"id": "$CHAT_MODEL_ID", "modelId": "chat"}
              ]
            }
          ]${extraRootField?.let { ",\n$it" }.orEmpty()}
        }
    """.trimIndent()

    private fun String?.asJsonStringOrNull(): String = this?.let { "\"$it\"" } ?: "null"

    private fun upstreamOnlySections() = listOf(
        Triple(
            "search",
            """"searchServices":[{"type":"serper","id":"$LEGACY_ITEM_ID","apiKey":""}]""",
            BackupSettingsSchemaSection.SEARCH,
        ),
        Triple(
            "tts-step",
            """"ttsProviders":[{"type":"step","id":"$LEGACY_ITEM_ID","name":""}]""",
            BackupSettingsSchemaSection.TTS,
        ),
        Triple(
            "tts-fish",
            """"ttsProviders":[{"type":"fish-audio","id":"$LEGACY_ITEM_ID","name":""}]""",
            BackupSettingsSchemaSection.TTS,
        ),
        Triple(
            "asr-dashscope",
            """"asrProviders":[{"type":"dashscope","id":"$LEGACY_ITEM_ID","name":""}]""",
            BackupSettingsSchemaSection.ASR,
        ),
        Triple(
            "asr-step",
            """"asrProviders":[{"type":"step","id":"$LEGACY_ITEM_ID","name":""}]""",
            BackupSettingsSchemaSection.ASR,
        ),
    )

    private companion object {
        const val PROVIDER_ID = "10000000-0000-4000-8000-000000000001"
        const val FAST_MODEL_ID = "20000000-0000-4000-8000-000000000002"
        const val CHAT_MODEL_ID = "30000000-0000-4000-8000-000000000003"
        const val UNAVAILABLE_MODEL_ID = "40000000-0000-4000-8000-000000000004"
        const val LEGACY_ITEM_ID = "50000000-0000-4000-8000-000000000005"
        const val LEGACY_PRIVATE_MARKER = "private-schema-marker"
    }
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
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

    private fun decodeSettings(source: String): Settings =
        JsonInstant.decodeFromString(SettingsJsonMigrator.migrate(source))

    private fun settingsJson(
        titleModelId: String?,
        suggestionModelId: String?,
        fastModelId: String = FAST_MODEL_ID,
        providerType: String = "openai",
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
          ]
        }
    """.trimIndent()

    private fun String?.asJsonStringOrNull(): String = this?.let { "\"$it\"" } ?: "null"

    private companion object {
        const val PROVIDER_ID = "10000000-0000-4000-8000-000000000001"
        const val FAST_MODEL_ID = "20000000-0000-4000-8000-000000000002"
        const val CHAT_MODEL_ID = "30000000-0000-4000-8000-000000000003"
        const val UNAVAILABLE_MODEL_ID = "40000000-0000-4000-8000-000000000004"
    }
}

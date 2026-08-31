/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsAPIProactiveRequestTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val model = Model(modelId = "provider-request-test", abilities = listOf(ModelAbility.TOOL))
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1")

    @Test
    fun scheduledSecondProviderRequestRetainsTheCompleteFirstTurn() {
        assertSecondRequestRetainsCompleteFirstTurn("scheduled")
    }

    @Test
    fun aggressiveSecondProviderRequestRetainsTheCompleteFirstTurn() {
        assertSecondRequestRetainsCompleteFirstTurn("aggressive")
    }

    @Test
    fun providerToolJsonUsesStableFinalNameOrder() {
        val tools = listOf("z_system", "a_mcp", "m_plugin").map { name ->
            Tool(name = name, description = name, execute = { emptyList() })
        }.sortedBy { it.name }
        val request = request(
            messages = listOf(message(MessageRole.USER, "tool order request")),
            tools = tools,
        )

        val names = request["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("a_mcp", "m_plugin", "z_system"), names)
    }

    @Test
    fun internalMetadataDoesNotRemoveDynamicUserFromProviderRequest() {
        val internalUser = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    text = "provider-visible-internal-user",
                    metadata = JsonObject(
                        mapOf("orangechat.internal.proactive.kind" to JsonPrimitive("dynamic_user"))
                    ),
                )
            ),
        )

        val providerMessages = request(listOf(internalUser))["messages"]!!.jsonArray

        assertEquals(1, providerMessages.size)
        assertEquals(
            "provider-visible-internal-user",
            providerMessages.single().jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun deepSeekNativeCacheUsageTakesPrecedence() {
        val usage = api.parseTokenUsage(buildJsonObject {
            put("prompt_tokens", 1000)
            put("completion_tokens", 10)
            put("total_tokens", 1010)
            put("prompt_cache_hit_tokens", 640)
            put("prompt_cache_miss_tokens", 360)
            put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 12) })
        })!!

        assertEquals(640, usage.cachedTokens)
        assertEquals(360, usage.cacheMissTokens)
    }

    @Test
    fun standardCachedTokenDetailsRemainCompatibleAndMissesAreDerived() {
        val usage = api.parseTokenUsage(buildJsonObject {
            put("prompt_tokens", 1000)
            put("completion_tokens", 10)
            put("total_tokens", 1010)
            put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 700) })
        })!!

        assertEquals(700, usage.cachedTokens)
        assertEquals(300, usage.cacheMissTokens)
    }

    private fun assertSecondRequestRetainsCompleteFirstTurn(mode: String) {
        val stablePrefix = listOf(
            message(MessageRole.SYSTEM, "stable system"),
            message(MessageRole.USER, "original user"),
            message(MessageRole.ASSISTANT, "original assistant"),
        )
        val dynamicUser1 = message(MessageRole.USER, "$mode dynamic user 1")
        val assistant1 = message(MessageRole.ASSISTANT, "$mode assistant 1")
        val dynamicUser2 = message(MessageRole.USER, "$mode dynamic user 2")

        val firstMessages = request(stablePrefix + dynamicUser1)["messages"]!!.jsonArray
        val secondMessages = request(
            stablePrefix + dynamicUser1 + assistant1 + dynamicUser2
        )["messages"]!!.jsonArray
        val serializedAssistant = request(listOf(assistant1))["messages"]!!.jsonArray.single()

        assertEquals(firstMessages.toList() + serializedAssistant, secondMessages.dropLast(1))
        assertEquals("user", secondMessages.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("$mode dynamic user 2", secondMessages.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    private fun request(messages: List<UIMessage>, tools: List<Tool> = emptyList()) =
        api.buildChatCompletionRequest(
            messages = messages,
            params = TextGenerationParams(model = model, tools = tools),
            providerSetting = provider,
        )

    private fun message(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )
}

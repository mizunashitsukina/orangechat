/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.WechatBotSetting
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FixedConversationBindingTest {
    @Test
    fun oldWechatSettingDefaultsToUnboundConversation() {
        val setting = Json.decodeFromString<WechatBotSetting>("""{"enabled":true,"assistantId":"assistant"}""")

        assertEquals("", setting.conversationId)
    }

    @Test
    fun oldProactiveSettingDefaultsToUnboundConversation() {
        val setting = Json.decodeFromString<ProactiveMessageSetting>("""{"enabled":true,"assistantId":"assistant"}""")

        assertEquals("", setting.conversationId)
    }

    @Test
    fun exactConfiguredConversationIsUsedRegardlessOfOtherConversationRecency() {
        val assistantId = Uuid.random()
        val bound = conversation(assistantId = assistantId, title = "bound")
        val newer = conversation(assistantId = assistantId, title = "newer")

        val result = validateFixedConversationBinding(
            bound.id.toString(),
            assistantId.toString(),
            bound,
        )

        assertTrue(result is FixedConversationBinding.Valid)
        assertFalse(newer.id == bound.id)
        assertEquals(bound.id, (result as FixedConversationBinding.Valid).conversationId)
    }

    @Test
    fun resolverLoadsOnlyTheConfiguredConversationId() = runBlocking {
        val assistantId = Uuid.random()
        val bound = conversation(assistantId)
        val newer = conversation(assistantId)
        val requestedIds = mutableListOf<Uuid>()

        val result = resolveFixedConversationBinding(
            configuredConversationId = bound.id.toString(),
            configuredAssistantId = assistantId.toString(),
            loadConversation = { requestedId ->
                requestedIds += requestedId
                listOf(newer, bound).firstOrNull { it.id == requestedId }
            },
        )

        assertTrue(result is FixedConversationBinding.Valid)
        assertEquals(listOf(bound.id), requestedIds)
        assertEquals(bound.id, (result as FixedConversationBinding.Valid).conversationId)
    }

    @Test
    fun unboundMissingAndMismatchedBindingsAreRejected() {
        val assistantId = Uuid.random()
        val conversation = conversation(assistantId)

        assertTrue(validateFixedConversationBinding("", assistantId.toString(), null) is FixedConversationBinding.Unbound)
        assertTrue(
            validateFixedConversationBinding(
                Uuid.random().toString(),
                assistantId.toString(),
                null,
            ) is FixedConversationBinding.MissingConversation
        )
        assertTrue(
            validateFixedConversationBinding(
                conversation.id.toString(),
                Uuid.random().toString(),
                conversation,
            ) is FixedConversationBinding.AssistantMismatch
        )
    }

    @Test
    fun malformedConversationIdIsRejectedWithoutFallback() {
        assertTrue(
            validateFixedConversationBinding(
                "not-a-uuid",
                Uuid.random().toString(),
                null,
            ) is FixedConversationBinding.InvalidConversationId
        )
    }

    @Test
    fun onlyMessagesInBoundConversationResetProactiveTimer() {
        val boundId = Uuid.random()

        assertTrue(shouldResetProactiveTimer(boundId.toString(), boundId))
        assertFalse(shouldResetProactiveTimer(boundId.toString(), Uuid.random()))
        assertFalse(shouldResetProactiveTimer("", boundId))
    }

    @Test
    fun normalAndAggressiveModesKeepTheSameBinding() {
        val conversationId = Uuid.random().toString()
        val normal = ProactiveMessageSetting(enabled = true, conversationId = conversationId)
        val aggressive = normal.copy(enabled = false, aggressiveModeEnabled = true)

        assertEquals(conversationId, normal.conversationId)
        assertEquals(conversationId, aggressive.conversationId)
    }

    @Test
    fun wechatAndProactiveSettingsCanShareOneConversation() {
        val assistantId = Uuid.random()
        val conversation = conversation(assistantId)
        val wechat = WechatBotSetting(
            assistantId = assistantId.toString(),
            conversationId = conversation.id.toString(),
        )
        val proactive = ProactiveMessageSetting(
            assistantId = assistantId.toString(),
            conversationId = conversation.id.toString(),
        )

        assertTrue(
            validateFixedConversationBinding(
                wechat.conversationId,
                wechat.assistantId,
                conversation,
            ) is FixedConversationBinding.Valid
        )
        assertTrue(
            validateFixedConversationBinding(
                proactive.conversationId,
                proactive.assistantId,
                conversation,
            ) is FixedConversationBinding.Valid
        )
    }

    @Test
    fun dedicatedConversationBindingsRemainIsolated() {
        val assistantId = Uuid.random()
        val wechatConversation = conversation(assistantId, "微信 Bot 专用")
        val proactiveConversation = conversation(assistantId, "主动消息专用")

        assertFalse(wechatConversation.id == proactiveConversation.id)
        val wechatBinding = validateFixedConversationBinding(
            wechatConversation.id.toString(),
            assistantId.toString(),
            wechatConversation,
        )
        val proactiveBinding = validateFixedConversationBinding(
            proactiveConversation.id.toString(),
            assistantId.toString(),
            proactiveConversation,
        )
        assertTrue(wechatBinding is FixedConversationBinding.Valid)
        assertTrue(proactiveBinding is FixedConversationBinding.Valid)
        assertEquals(wechatConversation.id, (wechatBinding as FixedConversationBinding.Valid).conversationId)
        assertEquals(proactiveConversation.id, (proactiveBinding as FixedConversationBinding.Valid).conversationId)
    }

    private fun conversation(
        assistantId: Uuid,
        title: String = "conversation",
    ) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = emptyList(),
    )
}

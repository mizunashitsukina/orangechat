/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.appendProactiveDynamicUser
import me.rerere.rikkahub.data.model.isHiddenProactiveMessage
import me.rerere.rikkahub.data.model.markAsProactiveDynamicUser
import me.rerere.rikkahub.data.model.markAsProactivePassAssistant
import me.rerere.rikkahub.data.model.rollbackProactiveDynamicUser
import me.rerere.rikkahub.data.model.takeLastWithProactivePairs
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveRequestLayoutTest {
    @Test
    fun scheduledRequestsKeepSystemAndHistoryStable() {
        assertOnlyFinalUserChanges(ProactiveTriggerMode.SCHEDULED)
    }

    @Test
    fun deviceEventRequestsKeepSystemAndHistoryStable() {
        assertOnlyFinalUserChanges(ProactiveTriggerMode.DEVICE_EVENT)
    }

    @Test
    fun normalReplyPersistsAfterTheInternalDynamicUser() {
        val dynamicUser = message(MessageRole.USER, "dynamic").markAsProactiveDynamicUser()
        val assistant = message(MessageRole.ASSISTANT, "reply")
        val conversation = conversation(listOf(message(MessageRole.USER, "old")))
            .appendProactiveDynamicUser(dynamicUser)
            .copy(messageNodes = conversationNodes(
                message(MessageRole.USER, "old"), dynamicUser, assistant
            ))

        assertEquals(listOf("old", "dynamic", "reply"), conversation.currentMessages.map(::text))
        assertTrue(dynamicUser.isHiddenProactiveMessage())
        assertFalse(assistant.isHiddenProactiveMessage())
        assertEquals(
            listOf("dynamic", "reply"),
            conversation.currentMessages.takeLastWithProactivePairs(1).map(::text),
        )
    }

    @Test
    fun passReplyRemainsPairedAndBothInternalMessagesAreHidden() {
        val dynamicUser = message(MessageRole.USER, "dynamic").markAsProactiveDynamicUser()
        val pass = message(MessageRole.ASSISTANT, "[PASS]").markAsProactivePassAssistant()
        val history = listOf(message(MessageRole.USER, "old"), dynamicUser, pass)

        assertTrue(dynamicUser.isHiddenProactiveMessage())
        assertTrue(pass.isHiddenProactiveMessage())
        assertEquals(listOf("dynamic", "[PASS]"), history.takeLastWithProactivePairs(1).map(::text))
    }

    @Test
    fun failureRollbackRemovesTheIncompleteInternalTurnOnly() {
        val original = message(MessageRole.USER, "old")
        val dynamicUser = message(MessageRole.USER, "dynamic").markAsProactiveDynamicUser()
        val partialAssistant = message(MessageRole.ASSISTANT, "partial")
        val pending = conversation(listOf(original)).appendProactiveDynamicUser(dynamicUser).copy(
            messageNodes = conversationNodes(original, dynamicUser, partialAssistant),
        )

        val rolledBack = pending.rollbackProactiveDynamicUser(dynamicUser.id)

        assertEquals(listOf("old"), rolledBack.currentMessages.map(::text))
    }

    @Test
    fun proactiveToolNamesHaveStableOrdering() {
        val tools = listOf("z_plugin", "a_mcp", "m_system").map { name ->
            me.rerere.ai.core.Tool(name = name, description = name, execute = { emptyList() })
        }
        assertEquals(listOf("a_mcp", "m_system", "z_plugin"), stabilizeProactiveTools(tools).map { it.name })
    }

    private fun assertOnlyFinalUserChanges(mode: ProactiveTriggerMode) {
        val history = listOf(
            message(MessageRole.USER, "existing user turn"),
            message(MessageRole.ASSISTANT, "existing assistant turn"),
        )
        val firstDynamicContext = """
            当前时间: 2026-08-18 08:00:00
            当前位置: first-location-marker
            今日应用使用: first-app-marker
            当前前台应用: first-foreground-marker
            今日通知: first-notification-marker
            设备电量: 25%
            设备事件: first-device-event-marker
        """.trimIndent()
        val secondDynamicContext = """
            当前时间: 2026-08-18 23:59:59
            当前位置: second-location-marker
            今日应用使用: second-app-marker
            当前前台应用: second-foreground-marker
            今日通知: second-notification-marker
            设备电量: 98%
            设备事件: second-device-event-marker
        """.trimIndent()

        val first = buildProactiveRequestLayout(
            assistantSystemPrompt = "stable assistant prompt",
            stableMemories = listOf("stable memory"),
            historyMessages = history,
            idleMinutes = 7,
            dynamicContext = firstDynamicContext,
            mode = mode,
        )
        val second = buildProactiveRequestLayout(
            assistantSystemPrompt = "stable assistant prompt",
            stableMemories = listOf("stable memory"),
            historyMessages = history,
            idleMinutes = 91,
            dynamicContext = secondDynamicContext,
            mode = mode,
        )

        assertEquals(first.systemPrompt, second.systemPrompt)
        assertEquals(history, first.historyMessages)
        assertEquals(first.historyMessages, second.historyMessages)
        assertNotEquals(first.dynamicUserPrompt, second.dynamicUserPrompt)

        val firstMessages = assembleProactiveRequestMessages(
            first,
            message(MessageRole.USER, first.dynamicUserPrompt),
        )
        val secondMessages = assembleProactiveRequestMessages(
            second,
            message(MessageRole.USER, second.dynamicUserPrompt),
        )
        assertEquals(MessageRole.SYSTEM, firstMessages.first().role)
        assertEquals(MessageRole.USER, firstMessages.last().role)
        assertEquals(history, firstMessages.subList(1, firstMessages.lastIndex))
        assertEquals(history, secondMessages.subList(1, secondMessages.lastIndex))
        assertEquals(providerPayload(firstMessages.dropLast(1)), providerPayload(secondMessages.dropLast(1)))
        assertNotEquals(providerPayload(firstMessages), providerPayload(secondMessages))

        val dynamicMarkers = listOf(
            "7",
            "91",
            "2026-08-18 08:00:00",
            "2026-08-18 23:59:59",
            "first-location-marker",
            "second-location-marker",
            "first-app-marker",
            "second-app-marker",
            "first-foreground-marker",
            "second-foreground-marker",
            "first-notification-marker",
            "second-notification-marker",
            "25%",
            "98%",
            "first-device-event-marker",
            "second-device-event-marker",
        )
        dynamicMarkers.forEach { marker ->
            assertFalse("Dynamic marker leaked into SYSTEM: $marker", first.systemPrompt.contains(marker))
            assertFalse("Dynamic marker leaked into SYSTEM: $marker", second.systemPrompt.contains(marker))
        }
        assertTrue(first.dynamicUserPrompt.contains(firstDynamicContext))
        assertTrue(second.dynamicUserPrompt.contains(secondDynamicContext))
    }

    private fun message(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun providerPayload(messages: List<UIMessage>) = messages.map { message ->
        message.role to message.parts
    }

    private fun conversation(messages: List<UIMessage>) = Conversation(
        assistantId = kotlin.uuid.Uuid.random(),
        messageNodes = conversationNodes(*messages.toTypedArray()),
    )

    private fun conversationNodes(vararg messages: UIMessage) = messages.map { it.toMessageNode() }

    private fun text(message: UIMessage) = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}

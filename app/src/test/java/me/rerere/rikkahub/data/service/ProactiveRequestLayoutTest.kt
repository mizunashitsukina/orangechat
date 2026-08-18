/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
}

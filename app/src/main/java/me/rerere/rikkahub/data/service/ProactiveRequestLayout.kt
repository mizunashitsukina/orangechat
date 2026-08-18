/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal enum class ProactiveTriggerMode {
    SCHEDULED,
    DEVICE_EVENT,
}

internal data class ProactiveRequestLayout(
    val systemPrompt: String,
    val historyMessages: List<UIMessage>,
    val dynamicUserPrompt: String,
)

/**
 * Keeps the provider-visible cache prefix stable: system instructions and existing history never depend on this
 * trigger's time, idle duration, location, device state, or event payload. All such data lives in the final user turn.
 */
internal fun buildProactiveRequestLayout(
    assistantSystemPrompt: String,
    stableMemories: List<String>,
    historyMessages: List<UIMessage>,
    idleMinutes: Int,
    dynamicContext: String,
    mode: ProactiveTriggerMode,
): ProactiveRequestLayout {
    val systemPrompt = buildString {
        if (assistantSystemPrompt.isNotBlank()) {
            append(assistantSystemPrompt)
        }

        if (stableMemories.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("## 记忆")
            stableMemories.forEach { memory -> appendLine("- $memory") }
        }

        appendLine()
        appendLine()
        when (mode) {
            ProactiveTriggerMode.SCHEDULED -> {
                appendLine("## 主动消息规则（定时触发）")
                appendLine("这是定时触发的主动消息，不是设备事件触发。")
            }

            ProactiveTriggerMode.DEVICE_EVENT -> {
                appendLine("## 主动消息规则（设备事件触发）")
                appendLine("你是因为检测到用户的手机操作动向而被触发的，不是定时主动消息。")
                appendLine("请根据用户本次的手机操作动向，自然地决定是否主动发一条消息。")
            }
        }
        appendLine("绝对不要复述上一轮的对话内容，要发新的话题或新的关心。")
        appendLine("如果上一轮已经说过类似的话，这次换一个完全不同的角度。")
        appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
        appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
        appendLine("不要提及你是在定时发消息，要像自然想起对方一样。")
        appendLine("绝对不要提及任何数据来源、工具使用、传感器数据、位置服务或应用使用统计等技术细节。")
        appendLine("不要说“根据某项数据”或“我注意到某项数据”之类暴露信息来源的话。")
        appendLine("直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说。")
        appendLine("不要使用 XML 标签、思考标记或特殊格式，只输出纯文本消息。")
        appendLine("不要调用任何工具或函数，只输出纯文本回复。")
        appendLine("不要输出思考过程、推理过程或内部独白。")
    }

    val dynamicUserPrompt = buildString {
        appendLine("[本次主动消息动态上下文]")
        appendLine("距离用户上次回复已过去 $idleMinutes 分钟。")
        if (dynamicContext.isNotBlank()) {
            appendLine(dynamicContext.trim())
        }
        append(
            when (mode) {
                ProactiveTriggerMode.SCHEDULED ->
                    "请根据本次动态上下文决定是否发消息；没什么好说的就回复 [PASS]。"

                ProactiveTriggerMode.DEVICE_EVENT ->
                    "请根据本次用户动向决定是否发消息；没什么好说的就回复 [PASS]。"
            }
        )
    }

    return ProactiveRequestLayout(
        systemPrompt = systemPrompt,
        historyMessages = historyMessages.toList(),
        dynamicUserPrompt = dynamicUserPrompt,
    )
}

internal fun assembleProactiveRequestMessages(
    layout: ProactiveRequestLayout,
    processedDynamicUserMessage: UIMessage,
): List<UIMessage> {
    require(processedDynamicUserMessage.role == MessageRole.USER) {
        "The proactive dynamic context must remain a user message"
    }
    return buildList {
        add(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(layout.systemPrompt)),
            )
        )
        addAll(layout.historyMessages)
        add(processedDynamicUserMessage)
    }
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private const val INTERNAL_PROACTIVE_KIND = "orangechat.internal.proactive.kind"
private const val DYNAMIC_USER_KIND = "dynamic_user"
private const val PASS_ASSISTANT_KIND = "pass_assistant"

internal fun UIMessage.markAsProactiveDynamicUser(): UIMessage {
    require(role == MessageRole.USER)
    return markTextParts(DYNAMIC_USER_KIND)
}

internal fun UIMessage.markAsProactivePassAssistant(): UIMessage {
    require(role == MessageRole.ASSISTANT)
    return markTextParts(PASS_ASSISTANT_KIND)
}

internal fun UIMessage.isProactiveDynamicUser(): Boolean = hasInternalKind(DYNAMIC_USER_KIND)

internal fun UIMessage.isProactivePassAssistant(): Boolean = hasInternalKind(PASS_ASSISTANT_KIND)

internal fun UIMessage.isHiddenProactiveMessage(): Boolean =
    isProactiveDynamicUser() || isProactivePassAssistant()

private fun UIMessage.markTextParts(kind: String): UIMessage = copy(
    parts = parts.map { part ->
        if (part is UIMessagePart.Text) {
            part.copy(
                metadata = JsonObject(
                    part.metadata.orEmpty() + (INTERNAL_PROACTIVE_KIND to JsonPrimitive(kind))
                )
            )
        } else {
            part
        }
    }
)

private fun UIMessage.hasInternalKind(kind: String): Boolean = parts
    .filterIsInstance<UIMessagePart.Text>()
    .any { it.metadata?.get(INTERNAL_PROACTIVE_KIND) == JsonPrimitive(kind) }

/**
 * Applies the assistant context limit without separating a persisted proactive dynamic USER from
 * the immediately following ASSISTANT response. The result may exceed [size] by one message to
 * preserve that pair.
 */
internal fun List<UIMessage>.takeLastWithProactivePairs(size: Int): List<UIMessage> {
    if (size <= 0 || this.size <= size) return this
    var startIndex = this.size - size
    if (
        startIndex > 0 &&
        this[startIndex].role == MessageRole.ASSISTANT &&
        this[startIndex - 1].isProactiveDynamicUser()
    ) {
        startIndex--
    }
    return subList(startIndex, this.size)
}

internal fun Conversation.appendProactiveDynamicUser(message: UIMessage): Conversation {
    require(message.isProactiveDynamicUser())
    return copy(messageNodes = messageNodes + message.toMessageNode())
}

internal fun Conversation.rollbackProactiveDynamicUser(messageId: Uuid): Conversation {
    val userNodeIndex = messageNodes.indexOfFirst { node ->
        node.messages.any { message -> message.id == messageId && message.isProactiveDynamicUser() }
    }
    if (userNodeIndex < 0) return this
    return copy(messageNodes = messageNodes.take(userNodeIndex))
}

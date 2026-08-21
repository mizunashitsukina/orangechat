/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

internal sealed interface FixedConversationBinding {
    data class Valid(
        val conversationId: Uuid,
        val conversation: Conversation,
    ) : FixedConversationBinding

    data object Unbound : FixedConversationBinding
    data object InvalidConversationId : FixedConversationBinding
    data object MissingConversation : FixedConversationBinding
    data object AssistantMismatch : FixedConversationBinding
}

internal fun validateFixedConversationBinding(
    configuredConversationId: String,
    configuredAssistantId: String,
    conversation: Conversation?,
): FixedConversationBinding {
    if (configuredConversationId.isBlank()) return FixedConversationBinding.Unbound
    val conversationId = runCatching { Uuid.parse(configuredConversationId) }.getOrNull()
        ?: return FixedConversationBinding.InvalidConversationId
    if (conversation == null || conversation.id != conversationId) {
        return FixedConversationBinding.MissingConversation
    }
    val assistantId = runCatching { Uuid.parse(configuredAssistantId) }.getOrNull()
    if (assistantId == null || conversation.assistantId != assistantId) {
        return FixedConversationBinding.AssistantMismatch
    }
    return FixedConversationBinding.Valid(conversationId, conversation)
}

internal suspend fun resolveFixedConversationBinding(
    configuredConversationId: String,
    configuredAssistantId: String,
    loadConversation: suspend (Uuid) -> Conversation?,
): FixedConversationBinding {
    if (configuredConversationId.isBlank()) return FixedConversationBinding.Unbound
    val conversationId = runCatching { Uuid.parse(configuredConversationId) }.getOrNull()
        ?: return FixedConversationBinding.InvalidConversationId
    return validateFixedConversationBinding(
        configuredConversationId,
        configuredAssistantId,
        loadConversation(conversationId),
    )
}

internal fun shouldResetProactiveTimer(
    configuredConversationId: String,
    messageConversationId: Uuid,
): Boolean = runCatching { Uuid.parse(configuredConversationId) }.getOrNull() == messageConversationId

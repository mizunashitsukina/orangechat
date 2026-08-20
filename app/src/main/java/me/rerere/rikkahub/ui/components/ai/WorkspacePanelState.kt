/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant

internal sealed interface WorkspacePanelState {
    val assistantId: String

    data class Available(
        override val assistantId: String,
        val workspaceId: String,
        val workspaceName: String,
    ) : WorkspacePanelState

    data class Unbound(override val assistantId: String) : WorkspacePanelState

    data class Unavailable(override val assistantId: String) : WorkspacePanelState
}

internal sealed interface WorkspacePanelDestination {
    data class Files(val workspaceId: String) : WorkspacePanelDestination
    data class Binding(val assistantId: String) : WorkspacePanelDestination
}

internal fun resolveWorkspacePanelState(
    conversationAssistantId: String,
    assistants: List<Assistant>,
    workspaces: List<WorkspaceEntity>,
): WorkspacePanelState {
    val assistant = assistants.firstOrNull { it.id.toString() == conversationAssistantId }
        ?: return WorkspacePanelState.Unavailable(conversationAssistantId)
    val workspaceId = assistant.workspaceId?.toString()
        ?: return WorkspacePanelState.Unbound(conversationAssistantId)
    val workspace = workspaces.firstOrNull { it.id == workspaceId }
        ?: return WorkspacePanelState.Unavailable(conversationAssistantId)
    return WorkspacePanelState.Available(
        assistantId = conversationAssistantId,
        workspaceId = workspace.id,
        workspaceName = workspace.name,
    )
}

internal fun WorkspacePanelState.destination(): WorkspacePanelDestination = when (this) {
    is WorkspacePanelState.Available -> WorkspacePanelDestination.Files(workspaceId)
    is WorkspacePanelState.Unbound -> WorkspacePanelDestination.Binding(assistantId)
    is WorkspacePanelState.Unavailable -> WorkspacePanelDestination.Binding(assistantId)
}

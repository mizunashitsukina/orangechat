/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspacePanelStateTest {
    private val conversationAssistantId = Uuid.random()
    private val otherAssistantId = Uuid.random()
    private val workspace = workspace(Uuid.random().toString(), "Bound workspace")

    @Test
    fun boundWorkspaceUsesTheConversationAssistantAndOpensFiles() {
        val state = resolveWorkspacePanelState(
            conversationAssistantId = conversationAssistantId.toString(),
            assistants = listOf(
                Assistant(id = otherAssistantId, workspaceId = null),
                Assistant(id = conversationAssistantId, workspaceId = Uuid.parse(workspace.id)),
            ),
            workspaces = listOf(workspace),
        )

        assertEquals(
            WorkspacePanelState.Available(
                assistantId = conversationAssistantId.toString(),
                workspaceId = workspace.id,
                workspaceName = workspace.name,
            ),
            state,
        )
        assertEquals(WorkspacePanelDestination.Files(workspace.id), state.destination())
    }

    @Test
    fun unboundConversationAssistantOpensItsOwnBindingPage() {
        val state = resolveWorkspacePanelState(
            conversationAssistantId = conversationAssistantId.toString(),
            assistants = listOf(Assistant(id = conversationAssistantId, workspaceId = null)),
            workspaces = listOf(workspace),
        )

        assertTrue(state is WorkspacePanelState.Unbound)
        assertEquals(
            WorkspacePanelDestination.Binding(conversationAssistantId.toString()),
            state.destination(),
        )
    }

    @Test
    fun deletedWorkspaceIsUnavailableAndNeverOpensAnotherWorkspace() {
        val state = resolveWorkspacePanelState(
            conversationAssistantId = conversationAssistantId.toString(),
            assistants = listOf(
                Assistant(id = conversationAssistantId, workspaceId = Uuid.parse(workspace.id)),
            ),
            workspaces = listOf(workspace(Uuid.random().toString(), "Different workspace")),
        )

        assertTrue(state is WorkspacePanelState.Unavailable)
        assertEquals(
            WorkspacePanelDestination.Binding(conversationAssistantId.toString()),
            state.destination(),
        )
    }

    @Test
    fun missingConversationAssistantDoesNotFallBackToAnotherAssistant() {
        val state = resolveWorkspacePanelState(
            conversationAssistantId = conversationAssistantId.toString(),
            assistants = listOf(
                Assistant(id = otherAssistantId, workspaceId = Uuid.parse(workspace.id)),
            ),
            workspaces = listOf(workspace),
        )

        assertTrue(state is WorkspacePanelState.Unavailable)
        assertEquals(
            WorkspacePanelDestination.Binding(conversationAssistantId.toString()),
            state.destination(),
        )
    }

    private fun workspace(id: String, name: String) = WorkspaceEntity(
        id = id,
        name = name,
        root = id,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

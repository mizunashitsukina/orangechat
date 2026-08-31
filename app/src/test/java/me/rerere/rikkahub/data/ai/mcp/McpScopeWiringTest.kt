package me.rerere.rikkahub.data.ai.mcp

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-contract checks complement the executable selection tests; no external MCP is contacted. */
class McpScopeWiringTest {
    @Test
    fun allToolConstructionEntrypointsPassTheInvokingAssistantAssignments() {
        val expectedCalls = mapOf(
            "service/ChatService.kt" to 2,
            "data/service/ProactiveMessageService.kt" to 1,
            "data/ai/tools/ToolSurfaceBuilder.kt" to 1,
        )
        val scopedCall = Regex("getAllAvailableTools\\(assistant\\.mcpServers\\)")
        expectedCalls.forEach { (path, count) ->
            val source = source(path)
            assertEquals("each entrypoint must use explicit assistant scope", count, scopedCall.findAll(source).count())
            assertFalse(source.contains("getAllAvailableTools()"))
            assertTrue("approval must survive tool construction", source.contains("needsApproval ="))
        }
    }

    @Test
    fun managerDoesNotReadGlobalCurrentAssistantForSelection() {
        val source = source("data/ai/mcp/McpManager.kt")
        assertFalse(source.contains("getCurrentAssistant"))
        assertTrue(source.contains("selectAvailableMcpTools(settings.mcpServers, enabledServerIds)"))
    }

    @Test
    fun proactiveSelectionUsesValidatedBoundConversationAssistant() {
        val source = source("data/service/ProactiveMessageService.kt")
        assertTrue(source.contains("settings.assistants.find { it.id == validBinding.conversation.assistantId }"))
        assertTrue(source.contains("binding !is FixedConversationBinding.Valid || assistant == null"))
        assertFalse(source.contains("getAllAvailableTools()"))
    }

    private fun source(relativePath: String): String {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val app = listOfNotNull(cwd.resolve("app"), cwd, cwd.parent?.resolve("app"))
            .firstOrNull { Files.isRegularFile(it.resolve("src/main/AndroidManifest.xml")) }
        return Files.readString(requireNotNull(app).resolve("src/main/java/me/rerere/rikkahub/$relativePath"))
    }
}

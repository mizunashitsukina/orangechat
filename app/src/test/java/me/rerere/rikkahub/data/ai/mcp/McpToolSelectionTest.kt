/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpToolSelectionTest {
    @Test
    fun `returns no tools when the invoking assistant has no assigned servers`() {
        val serverId = Uuid.random()

        val selected = selectAvailableMcpTools(
            servers = listOf(server(serverId, "unassigned_tool")),
            enabledServerIds = emptySet(),
        )

        assertEquals(emptyList<Pair<Uuid, McpTool>>(), selected)
    }

    @Test
    fun `selects tools from the invoking assistant's servers only`() {
        val assignedServerId = Uuid.random()
        val otherServerId = Uuid.random()
        val servers = listOf(
            server(assignedServerId, "assigned_tool"),
            server(otherServerId, "other_tool"),
        )

        val selected = selectAvailableMcpTools(servers, setOf(assignedServerId))

        assertEquals(
            listOf(assignedServerId to "assigned_tool"),
            selected.map { it.first to it.second.name },
        )
    }

    @Test
    fun `excludes disabled servers and tools`() {
        val disabledServerId = Uuid.random()
        val enabledServerId = Uuid.random()
        val servers = listOf(
            server(disabledServerId, "server_disabled", serverEnabled = false),
            McpServerConfig.StreamableHTTPServer(
                id = enabledServerId,
                commonOptions = McpCommonOptions(
                    name = "enabled",
                    tools = listOf(
                        McpTool(name = "tool_disabled", enable = false),
                        McpTool(name = "tool_enabled"),
                    ),
                ),
            ),
        )

        val selected = selectAvailableMcpTools(servers, setOf(disabledServerId, enabledServerId))

        assertEquals(
            listOf(enabledServerId to "tool_enabled"),
            selected.map { it.first to it.second.name },
        )
    }

    @Test
    fun switchingAssistantsCannotReuseThePreviousToolSelection() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val servers = listOf(server(firstId, "first_tool"), server(secondId, "second_tool"))

        assertEquals(listOf(firstId), selectAvailableMcpTools(servers, setOf(firstId)).map { it.first })
        assertEquals(listOf(secondId), selectAvailableMcpTools(servers, setOf(secondId)).map { it.first })
        assertTrue(selectAvailableMcpTools(servers, emptySet()).isEmpty())
        assertEquals(listOf(firstId), selectAvailableMcpTools(servers, setOf(firstId)).map { it.first })
    }

    @Test
    fun nonexistentAssignmentsNeverFallBackToOtherServers() {
        assertTrue(selectAvailableMcpTools(listOf(server(Uuid.random(), "tool")), setOf(Uuid.random())).isEmpty())
    }

    @Test
    fun selectionPreservesApprovalAndAdvertisedParameterConstraints() {
        // Synthetic schema: proves transparent forwarding, not remote device enforcement.
        val schema = InputSchema.Obj(
            properties = Json.parseToJsonElement(
                """{"strength":{"type":"integer","minimum":0,"maximum":3},
                    "duration":{"type":"integer","minimum":1,"maximum":5}}"""
            ).jsonObject,
            required = listOf("strength", "duration"),
        )
        val tool = McpTool(name = "bounded_action", inputSchema = schema, needsApproval = true)
        val id = Uuid.random()
        val config = McpServerConfig.StreamableHTTPServer(
            id = id,
            commonOptions = McpCommonOptions(tools = listOf(tool)),
        )

        val selected = selectAvailableMcpTools(listOf(config), setOf(id)).single().second

        assertSame(tool, selected)
        assertTrue(selected.needsApproval)
        assertEquals(schema, selected.inputSchema)
        assertEquals(listOf(tool), config.commonOptions.tools)
    }

    private fun server(
        id: Uuid,
        toolName: String,
        serverEnabled: Boolean = true,
    ): McpServerConfig = McpServerConfig.StreamableHTTPServer(
        id = id,
        commonOptions = McpCommonOptions(
            enable = serverEnabled,
            name = toolName,
            tools = listOf(McpTool(name = toolName)),
        ),
    )
}

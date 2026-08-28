/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
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

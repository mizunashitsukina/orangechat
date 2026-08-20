/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMcpToolCompatibilityTest {
    @Test
    fun legacyRikkaNameMapsToUniqueEnabledTool() {
        var invokedServer = ""
        val pulse = { invokedServer = "OmbreBrain" }

        val result = resolveLegacyMcpTool(
            requestedName = "mcp__OmbreBrain__pulse",
            candidates = listOf(
                LegacyMcpToolCandidate("server-1", "OmbreBrain", "pulse", pulse),
                LegacyMcpToolCandidate("server-2", "Other", "pulse") {},
            ),
        )

        assertTrue(result is LegacyMcpToolResolution.Resolved)
        val resolved = (result as LegacyMcpToolResolution.Resolved).value
        assertSame(pulse, resolved)
        resolved()
        assertEquals("OmbreBrain", invokedServer)
    }

    @Test
    fun currentOrangeNameStillResolvesFromRegisteredTools() {
        var invocationCount = 0
        val currentTool = NamedTool("mcp_1234abcd_pulse") { invocationCount += 1 }

        val result = resolveToolByName(
            requestedName = currentTool.name,
            registeredTools = listOf(currentTool),
            nameOf = NamedTool::name,
            legacyAliases = emptyMap(),
            legacyRejections = emptyMap(),
        )

        assertTrue(result is ToolNameResolution.Resolved)
        val resolved = (result as ToolNameResolution.Resolved).value
        assertSame(currentTool, resolved)
        resolved.execute()
        assertEquals(1, invocationCount)
    }

    @Test
    fun unknownServerAndUnknownToolAreRejected() {
        val candidates = listOf(LegacyMcpToolCandidate("server-1", "OmbreBrain", "pulse", Any()))

        assertRejected(
            resolveLegacyMcpTool("mcp__Missing__pulse", candidates),
            LegacyMcpToolRejection.UNKNOWN_SERVER,
        )
        assertRejected(
            resolveLegacyMcpTool("mcp__OmbreBrain__missing", candidates),
            LegacyMcpToolRejection.UNKNOWN_TOOL,
        )
    }

    @Test
    fun duplicateServerAndToolMappingIsRejectedAsAmbiguous() {
        val candidates = listOf(
            LegacyMcpToolCandidate("server-1", "OmbreBrain", "pulse", "first"),
            LegacyMcpToolCandidate("server-2", "OmbreBrain", "pulse", "second"),
        )

        assertRejected(
            resolveLegacyMcpTool("mcp__OmbreBrain__pulse", candidates),
            LegacyMcpToolRejection.AMBIGUOUS,
        )
    }

    @Test
    fun newConversationDoesNotRegisterLegacyAliases() {
        val currentTool = NamedTool("mcp_1234abcd_pulse") {}

        val compatibility = buildLegacyMcpToolCompatibility(
            requestedNames = emptyList(),
            candidates = listOf(LegacyMcpToolCandidate("server-1", "OmbreBrain", "pulse", currentTool)),
        )

        assertTrue(compatibility.aliases.isEmpty())
        assertTrue(compatibility.rejections.isEmpty())
        assertEquals(1, listOf(currentTool).map(NamedTool::name).distinct().size)
    }

    @Test
    fun unresolvedLegacyNameNeverFallsBackToAnotherTool() {
        val currentTool = NamedTool("mcp__Missing__pulse") {}
        val compatibility = buildLegacyMcpToolCompatibility(
            requestedNames = listOf("mcp__Missing__pulse"),
            candidates = listOf(LegacyMcpToolCandidate("server-1", "OmbreBrain", "pulse", currentTool)),
        )

        val result = resolveToolByName(
            requestedName = "mcp__Missing__pulse",
            registeredTools = listOf(currentTool),
            nameOf = NamedTool::name,
            legacyAliases = compatibility.aliases,
            legacyRejections = compatibility.rejections,
        )

        assertTrue(result is ToolNameResolution.Rejected)
        assertEquals(
            LegacyMcpToolRejection.UNKNOWN_SERVER.safeMessage,
            (result as ToolNameResolution.Rejected).safeMessage,
        )
    }

    private fun assertRejected(
        result: LegacyMcpToolResolution<*>,
        expected: LegacyMcpToolRejection,
    ) {
        assertTrue(result is LegacyMcpToolResolution.Rejected)
        assertEquals(expected, (result as LegacyMcpToolResolution.Rejected).reason)
    }

    private data class NamedTool(val name: String, val execute: () -> Unit)
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

private const val LEGACY_MCP_PREFIX = "mcp__"
private const val LEGACY_MCP_SEPARATOR = "__"

internal data class LegacyMcpToolCandidate<T>(
    val serverKey: String,
    val serverName: String,
    val toolName: String,
    val value: T,
)

internal enum class LegacyMcpToolRejection(val safeMessage: String) {
    MALFORMED("Legacy MCP tool name is invalid"),
    UNKNOWN_SERVER("Legacy MCP server is unavailable"),
    UNKNOWN_TOOL("Legacy MCP tool is unavailable"),
    AMBIGUOUS("Legacy MCP tool mapping is ambiguous"),
}

internal sealed interface LegacyMcpToolResolution<out T> {
    data class Resolved<T>(val value: T) : LegacyMcpToolResolution<T>
    data class Rejected(val reason: LegacyMcpToolRejection) : LegacyMcpToolResolution<Nothing>
    data object NotLegacy : LegacyMcpToolResolution<Nothing>
}

internal data class LegacyMcpToolCompatibility<T>(
    val aliases: Map<String, T> = emptyMap(),
    val rejections: Map<String, String> = emptyMap(),
)

/**
 * Resolves the RikkaHub 2.4.8 name `mcp__<serverName>__<toolName>` against only
 * the currently enabled MCP servers and tools. A legacy call is executable only
 * when the server-name/tool-name pair identifies exactly one candidate.
 */
internal fun <T> resolveLegacyMcpTool(
    requestedName: String,
    candidates: List<LegacyMcpToolCandidate<T>>,
): LegacyMcpToolResolution<T> {
    if (!requestedName.startsWith(LEGACY_MCP_PREFIX)) {
        return LegacyMcpToolResolution.NotLegacy
    }

    val legacyBody = requestedName.substring(LEGACY_MCP_PREFIX.length)
    val separatorIndex = legacyBody.indexOf(LEGACY_MCP_SEPARATOR)
    if (separatorIndex <= 0 || separatorIndex + LEGACY_MCP_SEPARATOR.length >= legacyBody.length) {
        return LegacyMcpToolResolution.Rejected(LegacyMcpToolRejection.MALFORMED)
    }

    val serverName = legacyBody.substring(0, separatorIndex)
    val toolName = legacyBody.substring(separatorIndex + LEGACY_MCP_SEPARATOR.length)
    val serverCandidates = candidates.filter { it.serverName == serverName }
    if (serverCandidates.isEmpty()) {
        return LegacyMcpToolResolution.Rejected(LegacyMcpToolRejection.UNKNOWN_SERVER)
    }
    if (serverCandidates.map { it.serverKey }.distinct().size != 1) {
        return LegacyMcpToolResolution.Rejected(LegacyMcpToolRejection.AMBIGUOUS)
    }

    val toolCandidates = serverCandidates.filter { it.toolName == toolName }
    return when (toolCandidates.size) {
        0 -> LegacyMcpToolResolution.Rejected(LegacyMcpToolRejection.UNKNOWN_TOOL)
        1 -> LegacyMcpToolResolution.Resolved(toolCandidates.single().value)
        else -> LegacyMcpToolResolution.Rejected(LegacyMcpToolRejection.AMBIGUOUS)
    }
}

/** Builds aliases only for legacy names already present in the active conversation branch. */
internal fun <T> buildLegacyMcpToolCompatibility(
    requestedNames: Iterable<String>,
    candidates: List<LegacyMcpToolCandidate<T>>,
): LegacyMcpToolCompatibility<T> {
    val aliases = linkedMapOf<String, T>()
    val rejections = linkedMapOf<String, String>()
    requestedNames.distinct().forEach { requestedName ->
        when (val resolution = resolveLegacyMcpTool(requestedName, candidates)) {
            is LegacyMcpToolResolution.Resolved -> aliases[requestedName] = resolution.value
            is LegacyMcpToolResolution.Rejected -> rejections[requestedName] = resolution.reason.safeMessage
            LegacyMcpToolResolution.NotLegacy -> Unit
        }
    }
    return LegacyMcpToolCompatibility(aliases = aliases, rejections = rejections)
}

internal sealed interface ToolNameResolution<out T> {
    data class Resolved<T>(val value: T) : ToolNameResolution<T>
    data class Rejected(val safeMessage: String) : ToolNameResolution<Nothing>
}

internal fun <T> resolveToolByName(
    requestedName: String,
    registeredTools: List<T>,
    nameOf: (T) -> String,
    legacyAliases: Map<String, T>,
    legacyRejections: Map<String, String>,
): ToolNameResolution<T> {
    legacyAliases[requestedName]?.let {
        return ToolNameResolution.Resolved(it)
    }
    legacyRejections[requestedName]?.let {
        return ToolNameResolution.Rejected(it)
    }
    registeredTools.firstOrNull { nameOf(it) == requestedName }?.let {
        return ToolNameResolution.Resolved(it)
    }
    return ToolNameResolution.Rejected("Tool not found")
}

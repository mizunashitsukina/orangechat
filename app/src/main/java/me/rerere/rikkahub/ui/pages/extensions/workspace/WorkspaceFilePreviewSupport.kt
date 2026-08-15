/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceStorageArea

internal enum class WorkspaceFilePreviewKind {
    TEXT,
    IMAGE,
    OTHER,
}

internal data class WorkspaceFilePreviewDescriptor(
    val kind: WorkspaceFilePreviewKind,
    val mimeType: String,
    val extension: String,
)

internal data class WorkspaceToolFilePath(
    val area: WorkspaceStorageArea,
    val relativePath: String,
)

internal fun workspaceFilePreviewDescriptor(path: String): WorkspaceFilePreviewDescriptor {
    val fileName = path.replace('\\', '/').substringAfterLast('/').lowercase()
    val extension = fileName.substringAfterLast('.', "").takeIf { it != fileName }.orEmpty()
    val kind = when {
        fileName in TEXT_FILE_NAMES || extension in TEXT_EXTENSIONS -> WorkspaceFilePreviewKind.TEXT
        extension in IMAGE_EXTENSIONS -> WorkspaceFilePreviewKind.IMAGE
        else -> WorkspaceFilePreviewKind.OTHER
    }
    return WorkspaceFilePreviewDescriptor(
        kind = kind,
        mimeType = MIME_TYPES[extension] ?: when (kind) {
            WorkspaceFilePreviewKind.TEXT -> "text/plain"
            WorkspaceFilePreviewKind.IMAGE -> "image/*"
            WorkspaceFilePreviewKind.OTHER -> "application/octet-stream"
        },
        extension = extension,
    )
}

/**
 * Converts an absolute path emitted by a workspace tool into the storage area used by the file browser.
 * This is only an early UI boundary; repository access still performs canonical containment checks.
 */
internal fun resolveWorkspaceToolFilePath(path: String): WorkspaceToolFilePath {
    val normalized = path.replace('\\', '/').trim().trimEnd('/')
    require(normalized.isNotBlank() && normalized.startsWith('/')) { "Workspace path must be absolute" }
    require(!normalized.contains('\u0000')) { "Workspace path contains an invalid character" }

    val (area, relative) = if (normalized == "/workspace" || normalized.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to normalized.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to normalized.trimStart('/')
    }
    val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
    require(segments.isNotEmpty()) { "Workspace path must identify a file" }
    require(segments.none { it == ".." }) { "Workspace path escapes the workspace" }
    return WorkspaceToolFilePath(area, segments.joinToString("/"))
}

private val TEXT_FILE_NAMES = setOf(
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    "dockerfile",
    "gemfile",
    "makefile",
    "readme",
    "license",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonl", "xml", "yaml", "yml", "log", "csv", "tsv",
    "ini", "cfg", "conf", "properties", "env", "toml", "gradle", "kts", "kt", "java", "js",
    "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp", "cs", "swift",
    "sh", "bash", "zsh", "fish", "ps1", "sql", "html", "htm", "css", "scss", "sass", "less",
    "vue", "svelte", "dart", "lua", "php", "pl", "r", "scala", "clj", "ex", "exs", "erl", "hrl",
    "asm", "smali", "proto", "diff", "patch", "svg",
)

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif",
)

private val MIME_TYPES = mapOf(
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "json" to "application/json",
    "jsonl" to "application/x-ndjson",
    "xml" to "application/xml",
    "svg" to "image/svg+xml",
    "yaml" to "application/yaml",
    "yml" to "application/yaml",
    "csv" to "text/csv",
    "html" to "text/html",
    "htm" to "text/html",
    "css" to "text/css",
    "js" to "text/javascript",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "ico" to "image/x-icon",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "mp4" to "video/mp4",
)

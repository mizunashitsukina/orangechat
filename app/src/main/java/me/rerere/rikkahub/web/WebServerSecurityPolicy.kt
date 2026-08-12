/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.web

enum class WebServerSecurityIssue {
    LAN_AUTH_REQUIRED,
    JWT_PASSWORD_REQUIRED,
}

internal data class WebServerSecurityDecision(
    val canStart: Boolean,
    val effectiveLocalhostOnly: Boolean,
    val issue: WebServerSecurityIssue? = null,
)

/**
 * Computes the only runtime-safe listener mode. The returned value deliberately contains no
 * credential material so it is safe to use in state and diagnostic messages.
 */
internal fun evaluateWebServerSecurity(
    requestedLocalhostOnly: Boolean,
    jwtEnabled: Boolean,
    accessPassword: String,
): WebServerSecurityDecision {
    val hasPassword = accessPassword.isNotBlank()

    if (requestedLocalhostOnly) {
        return if (jwtEnabled && !hasPassword) {
            WebServerSecurityDecision(
                canStart = false,
                effectiveLocalhostOnly = true,
                issue = WebServerSecurityIssue.JWT_PASSWORD_REQUIRED,
            )
        } else {
            WebServerSecurityDecision(
                canStart = true,
                effectiveLocalhostOnly = true,
            )
        }
    }

    return when {
        !jwtEnabled -> WebServerSecurityDecision(
            canStart = true,
            effectiveLocalhostOnly = true,
            issue = WebServerSecurityIssue.LAN_AUTH_REQUIRED,
        )

        !hasPassword -> WebServerSecurityDecision(
            canStart = false,
            effectiveLocalhostOnly = true,
            issue = WebServerSecurityIssue.JWT_PASSWORD_REQUIRED,
        )

        else -> WebServerSecurityDecision(
            canStart = true,
            effectiveLocalhostOnly = false,
        )
    }
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.web

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServerSecurityPolicyTest {
    @Test
    fun defaultSettingsKeepServerDisabledAndLocalhostOnly() {
        val settings = Settings()

        assertFalse(settings.webServerEnabled)
        assertTrue(settings.webServerLocalhostOnly)
    }

    @Test
    fun localhostWithoutJwtCanStart() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = true,
            jwtEnabled = false,
            accessPassword = "",
        )

        assertTrue(decision.canStart)
        assertTrue(decision.effectiveLocalhostOnly)
        assertNull(decision.issue)
    }

    @Test
    fun localhostWithJwtAndPasswordCanStart() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = true,
            jwtEnabled = true,
            accessPassword = "configured-password",
        )

        assertTrue(decision.canStart)
        assertTrue(decision.effectiveLocalhostOnly)
        assertNull(decision.issue)
    }

    @Test
    fun localhostWithJwtAndEmptyPasswordIsRejected() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = true,
            jwtEnabled = true,
            accessPassword = "",
        )

        assertFalse(decision.canStart)
        assertTrue(decision.effectiveLocalhostOnly)
        assertEquals(WebServerSecurityIssue.JWT_PASSWORD_REQUIRED, decision.issue)
    }

    @Test
    fun lanWithoutJwtFallsBackToLocalhost() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = false,
            jwtEnabled = false,
            accessPassword = "configured-password",
        )

        assertTrue(decision.canStart)
        assertTrue(decision.effectiveLocalhostOnly)
        assertEquals(WebServerSecurityIssue.LAN_AUTH_REQUIRED, decision.issue)
    }

    @Test
    fun lanWithJwtAndEmptyPasswordIsRejected() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = false,
            jwtEnabled = true,
            accessPassword = "",
        )

        assertFalse(decision.canStart)
        assertTrue(decision.effectiveLocalhostOnly)
        assertEquals(WebServerSecurityIssue.JWT_PASSWORD_REQUIRED, decision.issue)
    }

    @Test
    fun lanWithJwtAndPasswordCanStart() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = false,
            jwtEnabled = true,
            accessPassword = "configured-password",
        )

        assertTrue(decision.canStart)
        assertFalse(decision.effectiveLocalhostOnly)
        assertNull(decision.issue)
    }

    @Test
    fun whitespaceOnlyPasswordIsInvalid() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = false,
            jwtEnabled = true,
            accessPassword = "   \t",
        )

        assertFalse(decision.canStart)
        assertTrue(decision.effectiveLocalhostOnly)
        assertEquals(WebServerSecurityIssue.JWT_PASSWORD_REQUIRED, decision.issue)
    }

    @Test
    fun legacySettingsWithoutListenerModeMigrateToLocalhost() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"webServerEnabled":true,"webServerJwtEnabled":false}"""
        )
        val root = JsonInstant.parseToJsonElement(migrated).jsonObject

        assertTrue(root.getValue("webServerLocalhostOnly").jsonPrimitive.boolean)
    }

    @Test
    fun legacyAuthenticatedLanConfigurationRemainsLanEnabled() {
        val migrated = SettingsJsonMigrator.migrate(
            """{
                "webServerEnabled": true,
                "webServerJwtEnabled": true,
                "webServerAccessPassword": "configured-password"
            }""".trimIndent()
        )
        val root = JsonInstant.parseToJsonElement(migrated).jsonObject

        assertFalse(root.getValue("webServerLocalhostOnly").jsonPrimitive.boolean)
    }

    @Test
    fun legacyUnsafeExplicitLanConfigurationCannotListenOnAllInterfaces() {
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = false,
            jwtEnabled = false,
            accessPassword = "legacy-password",
        )

        assertTrue(decision.effectiveLocalhostOnly)
    }

    @Test
    fun decisionDoesNotRetainPassword() {
        val password = "secret-password secret-token http://private.example.test/path?key=secret"
        val decision = evaluateWebServerSecurity(
            requestedLocalhostOnly = false,
            jwtEnabled = true,
            accessPassword = password,
        )
        val diagnostic = decision.toString()

        assertFalse(diagnostic.contains(password))
        assertFalse(diagnostic.contains("secret-token"))
        assertFalse(diagnostic.contains("private.example.test"))
    }
}

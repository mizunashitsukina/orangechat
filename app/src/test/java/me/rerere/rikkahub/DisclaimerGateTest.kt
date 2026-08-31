/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerGateTest {
    @Test
    fun initialDummyWaitsForPersistedSettings() {
        val state = resolveDisclaimerGateState(Settings.dummy())

        assertEquals(DisclaimerGateState.LOADING, state)
        assertTrue(state.rendersVisibleContent)
        assertFalse(state.allowsAcceptanceAction)
        assertFalse(state.exposesMainContent)
    }

    @Test
    fun firstInstallRequiresAcceptanceAfterSettingsLoad() {
        val state = resolveDisclaimerGateState(Settings(disclaimerAccepted = false))

        assertEquals(DisclaimerGateState.REQUIRES_ACCEPTANCE, state)
        assertTrue(state.rendersVisibleContent)
        assertTrue(state.allowsAcceptanceAction)
        assertFalse(state.exposesMainContent)
    }

    @Test
    fun acceptedSettingsProceedAfterColdRestart() {
        val state = resolveDisclaimerGateState(
            Settings(
                disclaimerAccepted = true,
                disclaimerAcceptedAt = 123,
            )
        )

        assertEquals(DisclaimerGateState.ACCEPTED, state)
        assertTrue(state.rendersVisibleContent)
        assertFalse(state.allowsAcceptanceAction)
        assertTrue(state.exposesMainContent)
    }

    @Test
    fun legacyAcceptedStateWithoutTimestampRemainsAccepted() {
        assertEquals(
            DisclaimerGateState.ACCEPTED,
            resolveDisclaimerGateState(
                Settings(
                    disclaimerAccepted = true,
                    disclaimerAcceptedAt = 0,
                )
            ),
        )
    }

    @Test
    fun timestampWithoutAcceptanceDoesNotBypassConsent() {
        assertEquals(
            DisclaimerGateState.REQUIRES_ACCEPTANCE,
            resolveDisclaimerGateState(
                Settings(
                    disclaimerAccepted = false,
                    disclaimerAcceptedAt = 123,
                )
            ),
        )
    }
}

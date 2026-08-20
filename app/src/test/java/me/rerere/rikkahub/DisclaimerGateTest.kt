/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class DisclaimerGateTest {
    @Test
    fun initialDummyWaitsForPersistedSettings() {
        assertEquals(
            DisclaimerGateState.LOADING,
            resolveDisclaimerGateState(Settings.dummy()),
        )
    }

    @Test
    fun firstInstallRequiresAcceptanceAfterSettingsLoad() {
        assertEquals(
            DisclaimerGateState.REQUIRES_ACCEPTANCE,
            resolveDisclaimerGateState(Settings(disclaimerAccepted = false)),
        )
    }

    @Test
    fun acceptedSettingsProceedAfterColdRestart() {
        assertEquals(
            DisclaimerGateState.ACCEPTED,
            resolveDisclaimerGateState(
                Settings(
                    disclaimerAccepted = true,
                    disclaimerAcceptedAt = 123,
                )
            ),
        )
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

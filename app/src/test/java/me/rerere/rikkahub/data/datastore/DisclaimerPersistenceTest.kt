/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerPersistenceTest {
    @Test
    fun firstInstallHasNoRecordedAcceptance() {
        val acceptance = mutablePreferencesOf().readDisclaimerAcceptance()

        assertFalse(acceptance.accepted)
        assertEquals(0, acceptance.acceptedAtEpochSeconds)
    }

    @Test
    fun acceptingRecordsBothFieldsForTheNextProcess() {
        val preferences = mutablePreferencesOf()

        preferences.recordDisclaimerAcceptance(acceptedAtEpochSeconds = 123)
        val acceptance = preferences.readDisclaimerAcceptance()

        assertTrue(acceptance.accepted)
        assertEquals(123, acceptance.acceptedAtEpochSeconds)
    }

    @Test
    fun legacyBooleanWithoutTimestampRemainsAccepted() {
        val preferences = mutablePreferencesOf(
            SettingsStore.DISCLAIMER_ACCEPTED to true,
        )

        val acceptance = preferences.readDisclaimerAcceptance()

        assertTrue(acceptance.accepted)
        assertEquals(0, acceptance.acceptedAtEpochSeconds)
    }

    @Test
    fun timestampAloneNeverRecordsConsent() {
        val preferences = mutablePreferencesOf(
            SettingsStore.DISCLAIMER_ACCEPTED_AT to 123,
        )

        val acceptance = preferences.readDisclaimerAcceptance()

        assertFalse(acceptance.accepted)
        assertEquals(123, acceptance.acceptedAtEpochSeconds)
    }
}

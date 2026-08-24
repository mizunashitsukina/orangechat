package me.rerere.rikkahub

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

@RunWith(AndroidJUnit4::class)
class StartupConsentSeedTest {
    @Test
    fun seedAcceptedDisclaimerForColdStartMeasurement() = runBlocking {
        val settingsStore = getKoin().get<SettingsStore>()
        withTimeout(10_000) {
            settingsStore.settingsFlow.first { !it.init }
        }
        settingsStore.update {
            copy(disclaimerAccepted = true, disclaimerAcceptedAt = 1)
        }
        val accepted = withTimeout(10_000) {
            settingsStore.settingsFlow.first { !it.init && it.disclaimerAccepted }
        }
        assertTrue(accepted.disclaimerAccepted)
    }
}

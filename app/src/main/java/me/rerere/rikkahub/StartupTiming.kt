package me.rerere.rikkahub

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic-only cold-start milestones. Values are fixed event names and elapsed milliseconds. */
internal object StartupTiming {
    private const val TAG = "OrangeStartupTiming"
    private val processStartMs = SystemClock.elapsedRealtime()
    private val emittedEvents = ConcurrentHashMap.newKeySet<String>()
    private val firstSettingsMs = AtomicLong(-1)
    private val mainFrame = StartupMainFrameProbe { mark("MAIN_FIRST_FRAME_VISIBLE") }

    fun mark(event: String) {
        if (emittedEvents.add(event)) {
            val elapsedMs = SystemClock.elapsedRealtime() - processStartMs
            if (event == "SettingsReady") firstSettingsMs.set(elapsedMs)
            Log.i(TAG, "event=$event elapsedMs=$elapsedMs")
            if (event == "ActivityOnCreate") {
                // Same-process snapshot, emitted AFTER this Activity's boundary. Preserve the
                // actual first Settings timestamp; never manufacture a later read or wait.
                val settingsMs = firstSettingsMs.get()
                if (settingsMs in 0L..elapsedMs) {
                    Log.i(TAG, "event=SettingsAlreadyReady elapsedMs=$settingsMs")
                }
            }
        }
    }

    fun mainContentDrawn(view: View) {
        mainFrame.afterDraw(
            accepted = emittedEvents.contains("GateAccepted"),
            composed = emittedEvents.contains("MainComposition"),
            nextFrame = { callback -> view.postOnAnimation { callback() } },
        )
    }
}

/** Only attached to the accepted main root, never the loading/disclaimer surface. */
@Composable
internal fun Modifier.startupMainFrameProbe(): Modifier {
    val view = LocalView.current
    return drawWithContent {
        StartupTiming.mark("MainDrawStart")
        drawContent()
        StartupTiming.mark("MainDrawEnd")
        StartupTiming.mainContentDrawn(view)
    }
}

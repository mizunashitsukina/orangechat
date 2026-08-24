package me.rerere.rikkahub

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Diagnostic-only cold-start milestones. Values are fixed event names and elapsed milliseconds. */
internal object StartupTiming {
    private const val TAG = "OrangeStartupTiming"
    private val processStartMs = SystemClock.elapsedRealtime()
    private val emittedEvents = ConcurrentHashMap.newKeySet<String>()

    fun mark(event: String) {
        if (emittedEvents.add(event)) {
            Log.i(TAG, "event=$event elapsedMs=${SystemClock.elapsedRealtime() - processStartMs}")
        }
    }
}

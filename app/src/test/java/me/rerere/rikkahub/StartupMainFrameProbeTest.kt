package me.rerere.rikkahub

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupMainFrameProbeTest {
    @Test
    fun loadingAndUnacceptedFramesCannotCompleteMainFrame() {
        var completed = 0
        val pending = mutableListOf<() -> Unit>()
        val probe = StartupMainFrameProbe { completed++ }
        probe.afterDraw(false, false) { pending.add(it) }
        probe.afterDraw(false, true) { pending.add(it) }
        probe.afterDraw(true, false) { pending.add(it) }
        assertEquals(0, pending.size)
        assertEquals(0, completed)
    }

    @Test
    fun acceptedDrawWaitsForNextFrameAndOnlyEmitsOnce() {
        var completed = 0
        val pending = mutableListOf<() -> Unit>()
        val probe = StartupMainFrameProbe { completed++ }
        probe.afterDraw(true, true) { pending.add(it) }
        probe.afterDraw(true, true) { pending.add(it) }
        assertEquals(1, pending.size)
        assertEquals(0, completed)
        pending.single().invoke()
        assertEquals(1, completed)
        probe.afterDraw(true, true) { pending.add(it) }
        assertEquals(1, pending.size)
    }
}

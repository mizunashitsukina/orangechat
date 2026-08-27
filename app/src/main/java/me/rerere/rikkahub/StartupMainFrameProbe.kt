package me.rerere.rikkahub

/**
 * Diagnostic-only, main-thread confined. Schedule once AFTER the real main root's drawContent.
 * The next animation callback occurs after that frame's UI traversal has completed (API 26).
 * This is a conservative completed-Compose-frame marker, not a hardware presentation timestamp.
 * It neither delays navigation nor requests a recomposition; loading frames cannot schedule it.
 */
internal class StartupMainFrameProbe(private val completed: () -> Unit) {
    private var scheduled = false

    fun afterDraw(accepted: Boolean, composed: Boolean, nextFrame: (() -> Unit) -> Unit) {
        if (!accepted || !composed || scheduled) return
        scheduled = true
        nextFrame { completed() }
    }
}

package app.hensuu.motif

import android.os.Handler
import android.os.Looper

/** In-process recording state shared by the service, the screen, and the Quick Settings tile. */
object RecState {
    /** Wall-clock start of the current recording, or 0 when idle. */
    @Volatile
    var startedAt: Long = 0L
        private set

    val isRecording get() = startedAt != 0L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun started(at: Long) { startedAt = at; dispatch() }
    fun stopped() { startedAt = 0L; dispatch() }

    private fun dispatch() = main.post { listeners.toList().forEach { it() } }
}

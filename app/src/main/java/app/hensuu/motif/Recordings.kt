package app.hensuu.motif

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recordings live in app-private storage; the file name is the single source of identity. */
object Recordings {

    fun dir(c: Context): File = File(c.filesDir, "recordings").apply { mkdirs() }

    fun file(c: Context, name: String) = File(dir(c), name)

    /** "2026-10-02 23-41-05.m4a" — sorts by time and reads fine in Ableton's browser. */
    fun newFile(c: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
        var f = File(dir(c), "$stamp.m4a")
        var n = 2
        while (f.exists()) f = File(dir(c), "$stamp ($n).m4a").also { n++ }
        return f
    }

    fun list(c: Context): List<File> =
        dir(c).listFiles { f -> f.isFile && f.name.endsWith(".m4a") && f.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun delete(c: Context, name: String) {
        UploadWorker.cancel(c, name)
        file(c, name).delete()
        Prefs.forget(c, name)
    }
}

package app.hensuu.motif

import android.content.Context
import android.content.SharedPreferences

/** Settings, Drive state, and per-recording upload status. */
object Prefs {
    const val FILE = "motif_prefs"

    private const val KEY_RAW_MIC = "raw_mic"
    private const val KEY_MOBILE_DATA = "mobile_data"
    private const val KEY_DRIVE_CONNECTED = "drive_connected"
    private const val KEY_FOLDER_ID = "drive_folder_id"
    private const val STATUS_PREFIX = "st_"
    private const val DURATION_PREFIX = "dur_"

    /** Upload states for one recording. */
    const val ST_QUEUED = "queued"       // waiting for network / the job to run
    const val ST_UPLOADING = "uploading"
    const val ST_DONE = "done"
    const val ST_FAILED = "failed"       // gave up; tap "retry"
    const val ST_AUTH = "auth"           // Drive access lost; reconnect, then it re-queues

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Record without noise suppression / auto gain (AudioSource.UNPROCESSED) where supported. */
    fun rawMic(c: Context) = sp(c).getBoolean(KEY_RAW_MIC, true)
    fun setRawMic(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_RAW_MIC, on).apply()

    /** Upload over mobile data too; off = Wi-Fi (unmetered) only. */
    fun mobileData(c: Context) = sp(c).getBoolean(KEY_MOBILE_DATA, true)
    fun setMobileData(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_MOBILE_DATA, on).apply()

    fun driveConnected(c: Context) = sp(c).getBoolean(KEY_DRIVE_CONNECTED, false)
    fun setDriveConnected(c: Context, on: Boolean) =
        sp(c).edit().putBoolean(KEY_DRIVE_CONNECTED, on).apply()

    fun folderId(c: Context): String? = sp(c).getString(KEY_FOLDER_ID, null)
    fun setFolderId(c: Context, id: String?) = sp(c).edit().putString(KEY_FOLDER_ID, id).apply()

    fun status(c: Context, name: String): String? = sp(c).getString(STATUS_PREFIX + name, null)
    fun setStatus(c: Context, name: String, status: String) =
        sp(c).edit().putString(STATUS_PREFIX + name, status).apply()

    fun durationMs(c: Context, name: String): Long = sp(c).getLong(DURATION_PREFIX + name, -1L)
    fun setDurationMs(c: Context, name: String, ms: Long) =
        sp(c).edit().putLong(DURATION_PREFIX + name, ms).apply()

    fun forget(c: Context, name: String) =
        sp(c).edit().remove(STATUS_PREFIX + name).remove(DURATION_PREFIX + name).apply()
}

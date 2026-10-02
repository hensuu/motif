package app.hensuu.motif

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Foreground "microphone" service that owns the MediaRecorder, so recording keeps going with the
 * screen off or another app open. Stopping hands the file to [UploadWorker] and the service exits:
 * nothing of ours runs between recordings.
 */
class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedElapsed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        val startedAt = System.currentTimeMillis()

        // Must be in the foreground within a few seconds of startForegroundService().
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(startedAt),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Mic permission missing, or started from the background on Android 14+.
            fail(R.string.error_start)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail(R.string.error_permission)
            return
        }

        val out = Recordings.newFile(this)
        val r = prepare(out, channels = 2) ?: prepare(out, channels = 1)
        if (r == null) {
            out.delete()
            fail(R.string.error_start)
            return
        }
        try {
            r.start()
        } catch (e: RuntimeException) {
            r.release()
            out.delete()
            fail(R.string.error_start)
            return
        }
        recorder = r
        file = out
        startedElapsed = SystemClock.elapsedRealtime()
        RecState.started(startedAt)
        RecordTileService.refresh(this)
    }

    /** 48 kHz / 256 kbps AAC in .m4a: small enough to upload fast, good enough for a sketch. */
    private fun prepare(out: File, channels: Int): MediaRecorder? {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        return try {
            r.setAudioSource(audioSource())
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(channels)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(if (channels == 2) 256_000 else 160_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r
        } catch (e: Exception) {
            r.release()
            null
        }
    }

    private fun audioSource(): Int {
        if (!Prefs.rawMic(this)) return MediaRecorder.AudioSource.MIC
        val am = getSystemService(AudioManager::class.java)
        val supported = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (supported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
    }

    private fun stopRecording() {
        val r = recorder
        val f = file
        recorder = null
        file = null
        if (r != null && f != null) {
            val durationMs = SystemClock.elapsedRealtime() - startedElapsed
            val ok = try {
                r.stop()
                true
            } catch (e: RuntimeException) {
                false // stopped before any audio was written
            }
            r.release()
            if (ok && f.length() > 0) {
                Prefs.setDurationMs(this, f.name, durationMs)
                UploadWorker.enqueue(this, f.name)
            } else {
                f.delete()
            }
        }
        RecState.stopped()
        RecordTileService.refresh(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed mid-recording: keep what we have.
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun fail(msg: Int) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        RecState.stopped()
        RecordTileService.refresh(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(startedAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, MotifApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_recording))
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.hensuu.motif.STOP"
        private const val NOTIFICATION_ID = 1

        fun start(c: Context) =
            ContextCompat.startForegroundService(c, Intent(c, RecorderService::class.java))

        fun stop(c: Context) {
            c.startService(stopIntent(c))
        }

        private fun stopIntent(c: Context) =
            Intent(c, RecorderService::class.java).setAction(ACTION_STOP)
    }
}

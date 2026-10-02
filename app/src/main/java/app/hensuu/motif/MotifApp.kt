package app.hensuu.motif

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class MotifApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
    }
}

package app.hensuu.motif

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile. Starting opens the app with "record now" (Android only lets a microphone
 * service start from a visible screen); stopping works straight from the tile.
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (RecState.isRecording) {
            RecorderService.stop(this)
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val recording = RecState.isRecording
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (recording) R.string.tile_stop else R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (recording) getString(R.string.notif_recording) else null
        }
        tile.updateTile()
    }

    companion object {
        /** Ask the system to re-bind the (active-mode) tile so it redraws. */
        fun refresh(c: Context) {
            try {
                requestListeningState(c, ComponentName(c, RecordTileService::class.java))
            } catch (e: Exception) {
                // Tile not added / not bindable right now; it renders fresh when shown.
            }
        }
    }
}

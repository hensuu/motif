package app.hensuu.motif

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import app.hensuu.motif.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecordingAdapter
    private val ticker = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** Set when we were opened to record (tile / shortcut) and are waiting on the mic permission. */
    private var recordAfterPermission = false

    private val recListener: () -> Unit = { renderRecording(); refreshList() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Workers write upload status from a background thread.
        runOnUiThread { renderDrive(); refreshList() }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            if (recordAfterPermission) RecorderService.start(this)
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
        recordAfterPermission = false
    }

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        try {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
            onDriveConnected()
        } catch (e: Exception) {
            showDriveError(e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecordingAdapter(onClick = ::togglePlay, onLongClick = ::showActions)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.recordButton.setOnClickListener {
            if (RecState.isRecording) RecorderService.stop(this) else startRecording()
        }
        binding.driveButton.setOnClickListener { connectDrive() }

        binding.rawMicSwitch.isChecked = Prefs.rawMic(this)
        binding.rawMicRow.setOnClickListener {
            val on = !binding.rawMicSwitch.isChecked
            binding.rawMicSwitch.isChecked = on
            Prefs.setRawMic(this, on)
        }
        binding.mobileDataSwitch.isChecked = Prefs.mobileData(this)
        binding.mobileDataRow.setOnClickListener {
            val on = !binding.mobileDataSwitch.isChecked
            binding.mobileDataSwitch.isChecked = on
            Prefs.setMobileData(this, on)
            UploadWorker.enqueuePending(this) // re-schedule with the new network rule
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_RECORD && !RecState.isRecording) startRecording()
        setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        RecState.addListener(recListener)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefsListener)
        renderRecording()
        renderDrive()
        refreshList()
    }

    override fun onStop() {
        RecState.removeListener(recListener)
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        ticker.removeCallbacksAndMessages(null)
        stopPlayback()
        super.onStop()
    }

    // --- Recording ---

    private fun startRecording() {
        stopPlayback()
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (Manifest.permission.RECORD_AUDIO !in needed) {
            RecorderService.start(this)
            // Notification permission is nice-to-have; ask once alongside, don't block on it.
            if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        } else {
            recordAfterPermission = true
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun renderRecording() {
        val rec = RecState.isRecording
        binding.recordButton.setIconResource(if (rec) R.drawable.ic_stop else R.drawable.ic_mic)
        binding.recordButton.contentDescription = getString(if (rec) R.string.stop else R.string.record)
        val bg = if (rec) R.color.status_warn else R.color.indigo
        binding.recordButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
        binding.recordHint.setText(if (rec) R.string.tap_to_stop else R.string.tap_to_record)

        ticker.removeCallbacksAndMessages(null)
        if (rec) tick() else binding.elapsed.text = RecordingAdapter.formatDuration(0)
    }

    private fun tick() {
        val started = RecState.startedAt
        if (started == 0L) return
        binding.elapsed.text = RecordingAdapter.formatDuration(System.currentTimeMillis() - started)
        ticker.postDelayed(::tick, 250)
    }

    // --- Drive ---

    private fun renderDrive() {
        val on = Prefs.driveConnected(this)
        binding.driveStatus.text =
            if (on) getString(R.string.drive_on, Drive.FOLDER_NAME) else getString(R.string.drive_off)
        binding.driveIcon.setImageResource(if (on) R.drawable.ic_cloud_done else R.drawable.ic_cloud_off)
        binding.driveButton.setText(if (on) R.string.drive_reconnect else R.string.drive_connect)
    }

    private fun connectDrive() {
        Identity.getAuthorizationClient(this).authorize(Drive.authRequest())
            .addOnSuccessListener { result: AuthorizationResult ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    onDriveConnected()
                }
            }
            .addOnFailureListener(::showDriveError)
    }

    private fun onDriveConnected() {
        Prefs.setDriveConnected(this, true)
        renderDrive()
        UploadWorker.enqueuePending(this)
    }

    private fun showDriveError(e: Exception) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drive_title)
            .setMessage(getString(R.string.drive_error, e.message ?: e.javaClass.simpleName) +
                "\n\n" + getString(R.string.drive_setup_hint))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // --- List & playback ---

    private fun refreshList() {
        val files = Recordings.list(this)
        adapter.submit(files)
        binding.empty.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun togglePlay(f: File) {
        if (adapter.playing == f.name) {
            stopPlayback()
            return
        }
        stopPlayback()
        if (RecState.isRecording) return
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            adapter.playing = f.name
        } catch (e: Exception) {
            stopPlayback()
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        if (::adapter.isInitialized && adapter.playing != null) adapter.playing = null
    }

    private fun showActions(f: File) {
        val status = Prefs.status(this, f.name)
        val actions = mutableListOf(getString(R.string.action_share) to { share(f) })
        if (status != Prefs.ST_DONE && status != Prefs.ST_UPLOADING) {
            actions += getString(R.string.action_retry) to { UploadWorker.enqueue(this, f.name) }
        }
        actions += getString(R.string.action_delete) to { confirmDelete(f, status == Prefs.ST_DONE) }

        MaterialAlertDialogBuilder(this)
            .setTitle(f.name.removeSuffix(".m4a"))
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun share(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    private fun confirmDelete(f: File, uploaded: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(if (uploaded) R.string.delete_body else R.string.delete_body_not_uploaded)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (adapter.playing == f.name) stopPlayback()
                Recordings.delete(this, f.name)
                refreshList()
            }
            .show()
    }

    companion object {
        const val ACTION_RECORD = "app.hensuu.motif.RECORD"
    }
}

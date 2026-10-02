package app.hensuu.motif

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.hensuu.motif.databinding.ItemRecordingBinding
import java.io.File

class RecordingAdapter(
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit,
) : RecyclerView.Adapter<RecordingAdapter.Holder>() {

    private var items: List<File> = emptyList()
    var playing: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(files: List<File>) {
        items = files
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val f = items[position]
        val c = holder.itemView.context
        val b = holder.b

        b.name.text = f.name.removeSuffix(".m4a")
        val size = Formatter.formatShortFileSize(c, f.length())
        val ms = Prefs.durationMs(c, f.name)
        b.details.text = if (ms >= 0) "${formatDuration(ms)} · $size" else size
        b.playIcon.setImageResource(if (playing == f.name) R.drawable.ic_stop else R.drawable.ic_play)

        val (label, icon, fg, bg) = when (Prefs.status(c, f.name)) {
            Prefs.ST_DONE -> Style(R.string.st_done, R.drawable.ic_cloud_done, R.color.status_ok, R.color.status_ok_container)
            Prefs.ST_UPLOADING -> Style(R.string.st_uploading, R.drawable.ic_cloud_upload, R.color.on_lavender, R.color.lavender)
            Prefs.ST_FAILED -> Style(R.string.st_failed, R.drawable.ic_cloud_off, R.color.status_warn, R.color.status_warn_container)
            Prefs.ST_AUTH -> Style(R.string.st_auth, R.drawable.ic_cloud_off, R.color.status_warn, R.color.status_warn_container)
            else -> Style(R.string.st_queued, R.drawable.ic_cloud_upload, R.color.text_dim, R.color.surface_dim)
        }
        val fgColor = ContextCompat.getColor(c, fg)
        b.statusText.setText(label)
        b.statusText.setTextColor(fgColor)
        b.statusIcon.setImageResource(icon)
        b.statusIcon.imageTintList = ColorStateList.valueOf(fgColor)
        b.status.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(c, bg))

        b.root.setOnClickListener { onClick(f) }
        b.root.setOnLongClickListener { onLongClick(f); true }
    }

    private data class Style(val label: Int, val icon: Int, val fg: Int, val bg: Int)

    class Holder(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

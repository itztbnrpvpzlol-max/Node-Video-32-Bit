package studio.reimagined.nodevideo

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * First functional editing slice: multi-clip import, ordered playback, timeline selection,
 * split-at-playhead and clip removal. This is a clean-room implementation, not copied code.
 */
class MainActivity : ComponentActivity() {
    private data class Clip(val id: Long, val uri: Uri, val startMs: Long = 0, val endMs: Long)

    private var player: ExoPlayer? = null
    private val clips = mutableListOf<Clip>()
    private var selectedClip = -1
    private var nextId = 1L
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var playButton: Button
    private lateinit var position: SeekBar
    private var updatingPosition = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && ::position.isInitialized && p.duration > 0) {
                updatingPosition = true
                position.progress = ((p.currentPosition * 1000L) / p.duration)
                    .toInt().coerceIn(0, 1000)
                updatingPosition = false
            }
            uiHandler.postDelayed(this, 250)
        }
    }

    private val pickVideos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) { }
                val retriever = android.media.MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(this, uri)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (_: Exception) { 0L } finally { retriever.release() }
                if (duration > 0) clips.add(Clip(nextId++, uri, 0, duration))
            }
            if (clips.isNotEmpty()) {
                if (selectedClip < 0) selectedClip = 0
                rebuildPlaylist()
                renderTimeline()
                status.text = "${clips.size} clip(s) in project"
            } else toast("Couldn't read the selected video files")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 14, 20)
        window.navigationBarColor = Color.rgb(12, 14, 20)
        buildEditor()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.text = if (isPlaying) "PAUSE" else "PLAY"
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    selectedClip = exo.currentMediaItemIndex
                    renderTimeline()
                }
            })
        }
        uiHandler.post(progressUpdater)
    }

    private fun buildEditor() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12)
            setBackgroundColor(Color.rgb(12, 14, 20))
        }
        val header = TextView(this).apply {
            text = "NODE VIDEO STUDIO"
            textSize = 17f
            letterSpacing = .1f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, LinearLayout.LayoutParams(-1, 42))
        root.addView(TextView(this).apply {
            text = "PROJECT 01     •     30 FPS     •     ARMv7"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
        }, LinearLayout.LayoutParams(-1, 24))

        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "Import media to start editing"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 30))

        position = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val p = player ?: return
                        val d = p.duration
                        if (d > 0) p.seekTo((d * progress / 1000).coerceAtLeast(0))
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(position, LinearLayout.LayoutParams(-1, 28))

        val transport = row(root)
        playButton = button("PLAY") { togglePlayback() }
        transport.addView(playButton, weighted())
        button("⟲") { player?.seekTo(0) }.also { transport.addView(it, weighted()) }
        button("SPLIT") { splitAtPlayhead() }.also { transport.addView(it, weighted()) }
        button("DELETE") { removeSelected() }.also { transport.addView(it, weighted()) }

        root.addView(TextView(this).apply {
            text = "TIMELINE  /  VIDEO 1"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(4, 12, 4, 6)
        })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            setBackgroundColor(Color.rgb(22, 27, 38))
        }
        timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6)
        }
        scroll.addView(timeline)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 76))

        val actions = row(root)
        button("＋ MEDIA") { pickVideos.launch("video/*") }.also { actions.addView(it, weighted(1.4f)) }
        button("TEXT") { toast("Text layers are next on the implementation list") }.also { actions.addView(it, weighted()) }
        button("AUDIO") { toast("Audio tracks are next on the implementation list") }.also { actions.addView(it, weighted()) }
        button("FX") { toast("Effects panel is next on the implementation list") }.also { actions.addView(it, weighted()) }

        root.addView(TextView(this).apply {
            text = "Select a clip • Split cuts at the playhead • Delete removes the selected clip"
            textSize = 9f
            setTextColor(Color.rgb(110, 125, 150))
            setPadding(4, 8, 4, 0)
        })
        setContentView(root)
    }

    private fun row(parent: LinearLayout): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            parent.addView(this, LinearLayout.LayoutParams(-1, 48))
        }
    }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, 46, weight)

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (clips.isEmpty()) { pickVideos.launch("video/*"); return }
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun rebuildPlaylist() {
        val p = player ?: return
        val items = clips.map { clip ->
            MediaItem.Builder().setMediaId(clip.id.toString()).setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build()
                ).build()
        }
        p.setMediaItems(items, selectedClip.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0)
        p.prepare()
        p.pause()
    }

    private fun splitAtPlayhead() {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        if (index !in clips.indices) { toast("Select a clip first"); return }
        val clip = clips[index]
        val absolute = clip.startMs + p.currentPosition
        if (absolute - clip.startMs < 300 || clip.endMs - absolute < 300) {
            toast("Move the playhead away from the clip edges")
            return
        }
        clips[index] = clip.copy(endMs = absolute)
        clips.add(index + 1, clip.copy(id = nextId++, startMs = absolute))
        selectedClip = index + 1
        rebuildPlaylist()
        renderTimeline()
        status.text = "Clip split into two segments"
    }

    private fun removeSelected() {
        if (selectedClip !in clips.indices) { toast("Select a clip first"); return }
        clips.removeAt(selectedClip)
        selectedClip = (selectedClip).coerceAtMost(clips.lastIndex)
        rebuildPlaylist()
        renderTimeline()
        status.text = if (clips.isEmpty()) "Timeline empty" else "${clips.size} clip(s) in project"
    }

    private fun renderTimeline() {
        timeline.removeAllViews()
        clips.forEachIndexed { index, clip ->
            val item = TextView(this).apply {
                text = "CLIP ${index + 1}\n${((clip.endMs - clip.startMs) / 1000)}s"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(10)
                setBackgroundColor(if (index == selectedClip) Color.rgb(48, 91, 145) else Color.rgb(38, 48, 65))
                setOnClickListener {
                    selectedClip = index
                    player?.seekTo(index, 0)
                    renderTimeline()
                }
            }
            val lp = LinearLayout.LayoutParams(112, 60)
            lp.setMargins(3, 2, 3, 2)
            timeline.addView(item, lp)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        uiHandler.removeCallbacks(progressUpdater)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

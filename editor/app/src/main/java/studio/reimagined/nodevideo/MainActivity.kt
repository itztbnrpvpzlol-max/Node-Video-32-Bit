package studio.reimagined.nodevideo

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.EditText
import android.app.AlertDialog
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
    private data class TextLayer(val id: Long, val value: String)
    private data class AudioClip(val id: Long, val uri: Uri, val durationMs: Long)

    private var player: ExoPlayer? = null
    private var audioPlayer: ExoPlayer? = null
    private val audioClips = mutableListOf<AudioClip>()
    private val clips = mutableListOf<Clip>()
    private var selectedClip = -1
    private var nextId = 1L
    private lateinit var playerView: PlayerView
    private lateinit var previewContainer: FrameLayout
    private lateinit var textLayers: LinearLayout
    private lateinit var audioLayers: LinearLayout
    private val textOverlays = mutableMapOf<Long, TextView>()
    private val titleLayers = mutableListOf<TextLayer>()
    private var nextTextId = 1L
    private var nextAudioId = 1L
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

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                val retriever = android.media.MediaMetadataRetriever()
                val duration = try { retriever.setDataSource(this, uri); retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } catch (_: Exception) { 0L } finally { retriever.release() }
                if (duration > 0) audioClips.add(AudioClip(nextAudioId++, uri, duration))
            }
            renderAudioTimeline(); rebuildAudioPlaylist()
            status.text = audioClips.size.toString() + " audio clip(s) in project"
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
        audioPlayer = ExoPlayer.Builder(this).build()
        restoreProject()
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

        previewContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        previewContainer.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, 0, 1f))

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

        val trimActions = row(root)
        button("TRIM IN") { trimAtPlayhead(trimStart = true) }
            .also { trimActions.addView(it, weighted()) }
        button("TRIM OUT") { trimAtPlayhead(trimStart = false) }
            .also { trimActions.addView(it, weighted()) }

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
        button("＋ TEXT") { showTextLayerDialog() }.also { actions.addView(it, weighted()) }
        button("TEXT −") { removeLatestTextLayer() }.also { actions.addView(it, weighted()) }
        button("＋ AUDIO") { pickAudio.launch(arrayOf("audio/*")) }.also { actions.addView(it, weighted()) }
        button("SAVE") { saveProject() }.also { actions.addView(it, weighted()) }
        button("LOAD") { restoreProject() }.also { actions.addView(it, weighted()) }

        root.addView(TextView(this).apply {
            text = "TIMELINE  /  TEXT LAYERS"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(4, 8, 4, 4)
        })
        textLayers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6)
            setBackgroundColor(Color.rgb(22, 27, 38))
        }
        val textScroll = HorizontalScrollView(this)
        textScroll.addView(textLayers)
        root.addView(textScroll, LinearLayout.LayoutParams(-1, 48))

        root.addView(TextView(this).apply {
            text = "TIMELINE  /  AUDIO LAYERS"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(4, 8, 4, 4)
        })
        audioLayers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(6); setBackgroundColor(Color.rgb(22, 27, 38)) }
        val audioScroll = HorizontalScrollView(this)
        audioScroll.addView(audioLayers)
        root.addView(audioScroll, LinearLayout.LayoutParams(-1, 48))

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

    private fun showTextLayerDialog() {
        val input = EditText(this).apply {
            hint = "Enter title or caption"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(24, 18, 24, 18)
            setSingleLine(false)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("Add text layer")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("ADD") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    val layer = TextLayer(nextTextId++, value)
                    titleLayers.add(layer)
                    val overlay = TextView(this).apply {
                        text = value
                        textSize = 26f
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        setShadowLayer(6f, 2f, 2f, Color.BLACK)
                        gravity = Gravity.CENTER
                        setPadding(16)
                    }
                    previewContainer.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    textOverlays[layer.id] = overlay
                    renderTextTimeline()
                    status.text = "Added text layer " + titleLayers.size
                }
            }.show()
    }

    private fun removeLatestTextLayer() {
        val layer = titleLayers.lastOrNull() ?: run {
            toast("There are no text layers to remove")
            return
        }
        titleLayers.remove(layer)
        textOverlays.remove(layer.id)?.let { previewContainer.removeView(it) }
        renderTextTimeline()
        status.text = "Removed text layer"
    }

    private fun renderTextTimeline() {
        if (!::textLayers.isInitialized) return
        textLayers.removeAllViews()
        titleLayers.forEachIndexed { index, layer ->
            val chip = TextView(this).apply {
                text = "TEXT " + (index + 1) + ": " + layer.value
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(12)
                setBackgroundColor(Color.rgb(93, 65, 135))
            }
            val lp = LinearLayout.LayoutParams(-2, 36)
            lp.setMargins(3, 2, 3, 2)
            textLayers.addView(chip, lp)
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (clips.isEmpty()) { pickVideos.launch("video/*"); return }
        if (p.isPlaying) { p.pause(); audioPlayer?.pause() } else { p.play(); audioPlayer?.play() }
    }

    private fun rebuildAudioPlaylist() {
        val ap = audioPlayer ?: return
        ap.setMediaItems(audioClips.map { MediaItem.fromUri(it.uri) })
        ap.prepare(); ap.pause()
    }

    private fun renderAudioTimeline() {
        if (!::audioLayers.isInitialized) return
        audioLayers.removeAllViews()
        audioClips.forEachIndexed { index, clip ->
            val chip = TextView(this).apply {
                text = "AUDIO " + (index + 1) + " • " + (clip.durationMs / 1000) + "s"
                textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(12)
                setBackgroundColor(Color.rgb(45, 112, 92))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Audio clip " + (index + 1))
                        .setMessage("Remove this audio clip?").setNegativeButton("CANCEL", null)
                        .setPositiveButton("REMOVE") { _, _ -> audioClips.removeAt(index); rebuildAudioPlaylist(); renderAudioTimeline(); saveProject() }.show()
                }
            }
            val lp = LinearLayout.LayoutParams(-2, 36); lp.setMargins(3, 2, 3, 2); audioLayers.addView(chip, lp)
        }
    }

    private fun saveProject() {
        try {
            val root = JSONObject(); val videos = JSONArray(); val audios = JSONArray(); val texts = JSONArray()
            clips.forEach { videos.put(JSONObject().put("id", it.id).put("uri", it.uri.toString()).put("start", it.startMs).put("end", it.endMs)) }
            audioClips.forEach { audios.put(JSONObject().put("id", it.id).put("uri", it.uri.toString()).put("duration", it.durationMs)) }
            titleLayers.forEach { texts.put(JSONObject().put("id", it.id).put("value", it.value)) }
            root.put("videos", videos).put("audio", audios).put("texts", texts).put("selectedVideo", selectedClip)
            getSharedPreferences("editor_project", Context.MODE_PRIVATE).edit().putString("project_json", root.toString()).apply()
            status.text = "Project saved on this device"; toast("Project saved")
        } catch (e: Exception) { toast("Project save failed") }
    }

    private fun restoreProject() {
        val raw = getSharedPreferences("editor_project", Context.MODE_PRIVATE).getString("project_json", null) ?: return
        try {
            val root = JSONObject(raw); clips.clear(); audioClips.clear(); titleLayers.clear()
            textOverlays.values.forEach { previewContainer.removeView(it) }; textOverlays.clear()
            val videos = root.optJSONArray("videos") ?: JSONArray()
            for (i in 0 until videos.length()) { val v = videos.getJSONObject(i); val start = v.optLong("start", 0); val end = v.optLong("end", 0); if (end > start) clips.add(Clip(v.optLong("id", nextId++), Uri.parse(v.getString("uri")), start, end)) }
            val audios = root.optJSONArray("audio") ?: JSONArray()
            for (i in 0 until audios.length()) { val a = audios.getJSONObject(i); audioClips.add(AudioClip(a.optLong("id", nextAudioId++), Uri.parse(a.getString("uri")), a.optLong("duration", 0))) }
            val texts = root.optJSONArray("texts") ?: JSONArray()
            for (i in 0 until texts.length()) { val t = texts.getJSONObject(i); titleLayers.add(TextLayer(t.optLong("id", nextTextId++), t.getString("value"))) }
            selectedClip = root.optInt("selectedVideo", 0).coerceIn(0, (clips.size - 1).coerceAtLeast(0))
            rebuildPlaylist(); rebuildAudioPlaylist(); renderTimeline(); renderAudioTimeline()
            titleLayers.forEach { layer -> val overlay = TextView(this).apply { text = layer.value; textSize = 26f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER }; previewContainer.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); textOverlays[layer.id] = overlay }
            renderTextTimeline(); status.text = "Project loaded"; toast("Project loaded")
        } catch (e: Exception) { toast("Project load failed") }
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

    private fun trimAtPlayhead(trimStart: Boolean) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        if (index !in clips.indices) { toast("Select a clip first"); return }
        val clip = clips[index]
        val cut = clip.startMs + p.currentPosition
        if (cut <= clip.startMs + 250 || cut >= clip.endMs - 250) {
            toast("Move the playhead inside the clip before trimming")
            return
        }
        clips[index] = if (trimStart) clip.copy(startMs = cut) else clip.copy(endMs = cut)
        selectedClip = index
        status.text = if (trimStart) "Trimmed the beginning of clip " + (index + 1)
            else "Trimmed the end of clip " + (index + 1)
        rebuildPlaylist()
        renderTimeline()
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
        player?.release(); audioPlayer?.release(); audioPlayer = null
        player = null
        super.onDestroy()
    }
}

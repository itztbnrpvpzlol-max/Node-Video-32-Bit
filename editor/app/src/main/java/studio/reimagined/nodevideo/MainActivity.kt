package studio.reimagined.nodevideo

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.net.Uri
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import java.io.File
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.EditText
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView

/**
 * First functional editing slice: multi-clip import, ordered playback, timeline selection,
 * split-at-playhead and clip removal. This is a clean-room implementation, not copied code.
 */
class MainActivity : ComponentActivity() {
    private data class Clip(val id: Long, val uri: Uri, val startMs: Long = 0, val endMs: Long)
    private data class TextLayer(val id: Long, val value: String)
    private data class AudioClip(val id: Long, val uri: Uri, val durationMs: Long)
    private data class ImageLayer(val id: Long, val uri: Uri)
    private data class EffectSettings(
        var preset: String = "Original",
        var brightness: Float = 0f,
        var contrast: Float = 0f,
        var saturation: Float = 0f,
        var hue: Float = 0f,
        var blur: Float = 0f,
        var zoom: Float = 1f,
        var rotation: Float = 0f,
        var opacity: Float = 1f
    )
    private val effectSettings = EffectSettings()
    private val transformKeyframes = mutableMapOf<Long, MutableList<TransformKeyframe>>()
    private lateinit var effectInfo: TextView
    private lateinit var keyframeLayers: LinearLayout

    private var player: ExoPlayer? = null
    private var audioPlayer: ExoPlayer? = null
    private val audioClips = mutableListOf<AudioClip>()
    private val imageLayers = mutableListOf<ImageLayer>()
    private val imageOverlays = mutableMapOf<Long, ImageView>()
    private val clips = mutableListOf<Clip>()
    private var selectedClip = -1
    private var nextId = 1L
    private lateinit var playerView: PlayerView
    private lateinit var previewContainer: FrameLayout
    private lateinit var textLayers: LinearLayout
    private lateinit var audioLayers: LinearLayout
    private lateinit var imageLayerTimeline: LinearLayout
    private val textOverlays = mutableMapOf<Long, TextView>()
    private val titleLayers = mutableListOf<TextLayer>()
    private var nextTextId = 1L
    private var nextAudioId = 1L
    private var nextImageId = 1L
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

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                imageLayers.add(ImageLayer(nextImageId++, uri))
                val bitmap = try { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                if (bitmap != null) {
                    val imageView = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.95f }
                    val lp = FrameLayout.LayoutParams(220, 220, Gravity.CENTER)
                    previewContainer.addView(imageView, lp)
                    imageOverlays[imageLayers.last().id] = imageView
                }
            }
            renderImageLayerTimeline()
            saveProject()
            status.text = "Added image layer(s): " + imageLayers.size
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
                    applyPreviewEffects()
                    renderKeyframeTimeline()
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
            setPadding(14)
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
        val previewFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2)
            setBackgroundColor(Color.rgb(44, 115, 224))
            addView(previewContainer, LinearLayout.LayoutParams(-1, -1))
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 220))

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

        val effectsTitle = TextView(this).apply {
            text = "EFFECTS  /  COLOR & TRANSFORM"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(4, 8, 4, 4)
        }
        root.addView(effectsTitle)
        val presets = row(root)
        listOf("Original", "B&W", "Negative", "Cinematic").forEach { preset ->
            button(preset) {
                effectSettings.preset = preset
                if (preset == "Cinematic") { effectSettings.brightness = -0.04f; effectSettings.contrast = 0.18f }
                if (preset == "Original") { effectSettings.brightness = 0f; effectSettings.contrast = 0f; effectSettings.zoom = 1f; effectSettings.rotation = 0f }
                applyPreviewEffects()
                effectInfo.text = "Preset: $preset"
            }.also { presets.addView(it, weighted()) }
        }
        effectInfo = TextView(this).apply {
            text = "Preset: Original • Brightness 0 • Contrast 0 • Zoom 1.00× • Rotation 0°"
            textSize = 9f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(effectInfo, LinearLayout.LayoutParams(-1, 24))
        root.addView(TextView(this).apply {
            text = "BRIGHTNESS"
            textSize = 9f
            setTextColor(Color.rgb(145, 160, 185))
        })
        val brightnessBar = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { effectSettings.brightness = (progress - 100) / 100f; effectSettings.preset = "Custom"; applyPreviewEffects(); updateEffectInfo() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(brightnessBar, LinearLayout.LayoutParams(-1, 30))
        root.addView(TextView(this).apply {
            text = "CONTRAST"
            textSize = 9f
            setTextColor(Color.rgb(145, 160, 185))
        })
        val contrastBar = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { effectSettings.contrast = (progress - 100) / 100f; effectSettings.preset = "Custom"; applyPreviewEffects(); updateEffectInfo() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(contrastBar, LinearLayout.LayoutParams(-1, 30))
        root.addView(TextView(this).apply {
            text = "SATURATION"
            textSize = 9f
            setTextColor(Color.rgb(145, 160, 185))
        })
        val saturationBar = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { effectSettings.saturation = (progress - 100).toFloat(); effectSettings.preset = "Custom"; applyPreviewEffects(); updateEffectInfo() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(saturationBar, LinearLayout.LayoutParams(-1, 30))
        root.addView(TextView(this).apply { text = "HUE"; textSize = 9f; setTextColor(Color.rgb(145, 160, 185)) })
        val hueBar = SeekBar(this).apply {
            max = 360
            progress = 180
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { effectSettings.hue = (progress - 180).toFloat(); effectSettings.preset = "Custom"; applyPreviewEffects(); updateEffectInfo() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(hueBar, LinearLayout.LayoutParams(-1, 30))
        val transformRow = row(root)
        button("ZOOM −") { effectSettings.zoom = (effectSettings.zoom - .1f).coerceAtLeast(.5f); applyPreviewEffects(); updateEffectInfo() }.also { transformRow.addView(it, weighted()) }
        button("ZOOM +") { effectSettings.zoom = (effectSettings.zoom + .1f).coerceAtMost(2.5f); applyPreviewEffects(); updateEffectInfo() }.also { transformRow.addView(it, weighted()) }
        button("ROTATE 90°") { effectSettings.rotation = (effectSettings.rotation + 90f) % 360f; applyPreviewEffects(); updateEffectInfo() }.also { transformRow.addView(it, weighted()) }
        button("BLUR") { effectSettings.blur = when (effectSettings.blur) { 0f -> 4f; 4f -> 9f; else -> 0f }; applyPreviewEffects(); updateEffectInfo() }.also { transformRow.addView(it, weighted()) }
        button("FADE TEXT") { effectSettings.opacity = when (effectSettings.opacity) { 1f -> 0.5f; 0.5f -> 0.2f; else -> 1f }; updateEffectInfo() }.also { transformRow.addView(it, weighted()) }
        val keyframeRow = row(root)
        button("◆ ADD KEYFRAME") { addTransformKeyframe() }.also { keyframeRow.addView(it, weighted(1.4f)) }
        button("UNDO KEY") { removeLatestKeyframe() }.also { keyframeRow.addView(it, weighted()) }
        button("CLEAR KEYS") { clearSelectedKeyframes() }.also { keyframeRow.addView(it, weighted()) }
        root.addView(TextView(this).apply {
            text = "KEYFRAME TRACK  /  SELECTED CLIP"
            textSize = 9f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(4, 6, 4, 4)
        })
        keyframeLayers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6)
            setBackgroundColor(Color.rgb(22, 27, 38))
        }
        val keyScroll = HorizontalScrollView(this)
        keyScroll.addView(keyframeLayers)
        root.addView(keyScroll, LinearLayout.LayoutParams(-1, 42))

        val exportRow = row(root)
        button("EXPORT MP4") { exportProject() }.also { exportRow.addView(it, weighted(1f)) }

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
        button("＋ IMAGE") { pickImages.launch("image/*") }.also { actions.addView(it, weighted()) }
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
            text = "TIMELINE  /  IMAGE LAYERS"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(4, 8, 4, 4)
        })
        imageLayerTimeline = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(6); setBackgroundColor(Color.rgb(22, 27, 38)) }
        val imageScroll = HorizontalScrollView(this)
        imageScroll.addView(imageLayerTimeline)
        root.addView(imageScroll, LinearLayout.LayoutParams(-1, 48))

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
        val pageScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root, android.widget.ScrollView.LayoutParams(-1, -2))
            setBackgroundColor(Color.rgb(12, 14, 20))
        }
        setContentView(pageScroll)
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
        setTextColor(Color.WHITE)
        setPadding(4, 2, 4, 2)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12f
            setColor(Color.rgb(31, 40, 56))
            setStroke(1, Color.rgb(54, 69, 91))
        }
        minHeight = 40
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
                    saveProject()
                    status.text = "Added text layer " + titleLayers.size
                }
            }.show()
    }

    private fun showEditTextLayerDialog(layer: TextLayer) {
        val input = EditText(this).apply {
            setText(layer.value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(24, 18, 24, 18)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("Edit text layer")
            .setView(input)
            .setNeutralButton("DELETE") { _, _ ->
                titleLayers.removeAll { it.id == layer.id }
                textOverlays.remove(layer.id)?.let { previewContainer.removeView(it) }
                renderTextTimeline()
                saveProject()
            }
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                val updated = input.text.toString().trim()
                if (updated.isNotEmpty()) {
                    val index = titleLayers.indexOfFirst { it.id == layer.id }
                    if (index >= 0) titleLayers[index] = layer.copy(value = updated)
                    textOverlays[layer.id]?.text = updated
                    renderTextTimeline()
                    saveProject()
                    applyPreviewEffects()
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
        saveProject()
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
                setOnClickListener { showEditTextLayerDialog(layer) }
            }
            val lp = LinearLayout.LayoutParams(-2, 36)
            lp.setMargins(3, 2, 3, 2)
            textLayers.addView(chip, lp)
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (clips.isEmpty()) { pickVideos.launch("video/*"); return }
        if (p.isPlaying) { p.pause(); audioPlayer?.pause() } else { audioPlayer?.seekTo(p.currentPosition); p.play(); audioPlayer?.play() }
    }

    private fun rebuildAudioPlaylist() {
        val ap = audioPlayer ?: return
        ap.setMediaItems(audioClips.map { MediaItem.fromUri(it.uri) })
        ap.repeatMode = Player.REPEAT_MODE_ALL
        ap.prepare(); ap.pause()
    }

    private fun renderImageLayerTimeline() {
        if (!::imageLayerTimeline.isInitialized) return
        imageLayerTimeline.removeAllViews()
        imageLayers.forEachIndexed { index, layer ->
            val chip = TextView(this).apply {
                text = "IMAGE " + (index + 1)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(12)
                setBackgroundColor(Color.rgb(55, 105, 145))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Image layer " + (index + 1))
                        .setMessage("Remove this image layer?")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("REMOVE") { _, _ ->
                            imageLayers.remove(layer)
                            imageOverlays.remove(layer.id)?.let { previewContainer.removeView(it) }
                            renderImageLayerTimeline()
                            saveProject()
                        }.show()
                }
            }
            val lp = LinearLayout.LayoutParams(-2, 36)
            lp.setMargins(3, 2, 3, 2)
            imageLayerTimeline.addView(chip, lp)
        }
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
            val images = JSONArray()
            imageLayers.forEach { images.put(JSONObject().put("id", it.id).put("uri", it.uri.toString())) }
            val keyJson = JSONArray()
            transformKeyframes.forEach { (clipId, keys) -> keys.forEach { key ->
                keyJson.put(JSONObject().put("clipId", clipId).put("time", key.timeMs).put("scale", key.scale).put("rotation", key.rotationDegrees).put("alpha", key.alpha))
            } }
            val fx = JSONObject().put("preset", effectSettings.preset).put("brightness", effectSettings.brightness)
                .put("contrast", effectSettings.contrast).put("saturation", effectSettings.saturation).put("hue", effectSettings.hue).put("blur", effectSettings.blur).put("zoom", effectSettings.zoom).put("rotation", effectSettings.rotation).put("opacity", effectSettings.opacity)
            root.put("videos", videos).put("audio", audios).put("texts", texts).put("selectedVideo", selectedClip)
                .put("keyframes", keyJson).put("effects", fx).put("images", images)
            getSharedPreferences("editor_project", Context.MODE_PRIVATE).edit().putString("project_json", root.toString()).apply()
            status.text = "Project saved on this device"; toast("Project saved")
        } catch (e: Exception) { toast("Project save failed") }
    }

    private fun restoreProject() {
        val raw = getSharedPreferences("editor_project", Context.MODE_PRIVATE).getString("project_json", null) ?: return
        try {
            val root = JSONObject(raw); clips.clear(); audioClips.clear(); titleLayers.clear(); imageLayers.clear()
            textOverlays.values.forEach { previewContainer.removeView(it) }; textOverlays.clear()
            val videos = root.optJSONArray("videos") ?: JSONArray()
            for (i in 0 until videos.length()) { val v = videos.getJSONObject(i); val start = v.optLong("start", 0); val end = v.optLong("end", 0); if (end > start) clips.add(Clip(v.optLong("id", nextId++), Uri.parse(v.getString("uri")), start, end)) }
            imageOverlays.values.forEach { previewContainer.removeView(it) }; imageOverlays.clear()
            val images = root.optJSONArray("images") ?: JSONArray()
            for (i in 0 until images.length()) {
                val image = images.getJSONObject(i)
                val layer = ImageLayer(image.optLong("id", nextImageId++), Uri.parse(image.getString("uri")))
                imageLayers.add(layer)
                val bitmap = try { contentResolver.openInputStream(layer.uri)?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                if (bitmap != null) {
                    val view = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.95f }
                    previewContainer.addView(view, FrameLayout.LayoutParams(220, 220, Gravity.CENTER))
                    imageOverlays[layer.id] = view
                }
            }
            val audios = root.optJSONArray("audio") ?: JSONArray()
            for (i in 0 until audios.length()) { val a = audios.getJSONObject(i); audioClips.add(AudioClip(a.optLong("id", nextAudioId++), Uri.parse(a.getString("uri")), a.optLong("duration", 0))) }
            transformKeyframes.clear()
            val savedKeys = root.optJSONArray("keyframes") ?: JSONArray()
            for (i in 0 until savedKeys.length()) {
                val k = savedKeys.getJSONObject(i)
                val id = k.optLong("clipId")
                transformKeyframes.getOrPut(id) { mutableListOf() }.add(
                    TransformKeyframe(k.optLong("time"), k.optDouble("scale", 1.0).toFloat(), k.optDouble("rotation", 0.0).toFloat(), k.optDouble("alpha", 1.0).toFloat())
                )
            }
            val fx = root.optJSONObject("effects")
            if (fx != null) {
                effectSettings.preset = fx.optString("preset", "Original")
                effectSettings.brightness = fx.optDouble("brightness", 0.0).toFloat()
                effectSettings.contrast = fx.optDouble("contrast", 0.0).toFloat()
                effectSettings.saturation = fx.optDouble("saturation", 0.0).toFloat()
                effectSettings.hue = fx.optDouble("hue", 0.0).toFloat()
                effectSettings.blur = fx.optDouble("blur", 0.0).toFloat()
                effectSettings.zoom = fx.optDouble("zoom", 1.0).toFloat()
                effectSettings.rotation = fx.optDouble("rotation", 0.0).toFloat()
                effectSettings.opacity = fx.optDouble("opacity", 1.0).toFloat()
                if (::effectInfo.isInitialized) updateEffectInfo()
            }
            val texts = root.optJSONArray("texts") ?: JSONArray()
            for (i in 0 until texts.length()) { val t = texts.getJSONObject(i); titleLayers.add(TextLayer(t.optLong("id", nextTextId++), t.getString("value"))) }
            selectedClip = root.optInt("selectedVideo", 0).coerceIn(0, (clips.size - 1).coerceAtLeast(0))
            rebuildPlaylist(); rebuildAudioPlaylist(); renderTimeline(); renderAudioTimeline(); renderImageLayerTimeline(); renderKeyframeTimeline(); applyPreviewEffects()
            titleLayers.forEach { layer -> val overlay = TextView(this).apply { text = layer.value; textSize = 26f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER }; previewContainer.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); textOverlays[layer.id] = overlay }
            renderTextTimeline(); status.text = "Project loaded"; toast("Project loaded")
        } catch (e: Exception) { toast("Project load failed") }
    }

    @OptIn(UnstableApi::class)
    private fun exportProject() {
        if (clips.isEmpty()) { toast("Import at least one video before exporting"); return }
        val videoItems = clips.map { clip ->
            val media = MediaItem.Builder().setUri(clip.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()
            ).build()
            EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), buildVideoEffects(clip.id))).build()
        }
        val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(videoItems)
        val sequences = mutableListOf(videoSequence)
        if (audioClips.isNotEmpty()) {
            val audioItems = audioClips.map { EditedMediaItem.Builder(MediaItem.fromUri(it.uri)).build() }
            sequences.add(EditedMediaItemSequence.withAudioFrom(audioItems).buildUpon().setIsLooping(true).build())
        }
        val composition = Composition.Builder(sequences).build()
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!outputDir.exists()) outputDir.mkdirs()
        val output = File(outputDir, "NodeVideoExport_" + System.currentTimeMillis() + ".mp4")
        status.text = "Exporting MP4…"
        val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                status.text = "Export complete: " + output.name
                AlertDialog.Builder(this@MainActivity).setTitle("Export complete")
                    .setMessage("Saved MP4 to:\n" + output.absolutePath)
                    .setPositiveButton("OK", null).show()
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                status.text = "Export failed"
                toast("Export failed: " + (exportException.message ?: "unsupported media or codec error"))
            }
        }).build()
        try { transformer.start(composition, output.absolutePath) }
        catch (e: Exception) { status.text = "Export could not start"; toast(e.message ?: "Export error") }
    }

    @OptIn(UnstableApi::class)
    private fun buildVideoEffects(clipId: Long): List<androidx.media3.common.Effect> {
        val effects = mutableListOf<androidx.media3.common.Effect>()
        when (effectSettings.preset) {
            "B&W" -> effects.add(RgbFilter.createGrayscaleFilter())
            "Negative" -> effects.add(RgbFilter.createInvertedFilter())
        }
        if (effectSettings.brightness != 0f) effects.add(Brightness(effectSettings.brightness.coerceIn(-1f, 1f)))
        if (effectSettings.contrast != 0f) effects.add(Contrast(effectSettings.contrast.coerceIn(-1f, 1f)))
        if (effectSettings.saturation != 0f || effectSettings.hue != 0f) effects.add(HslAdjustment.Builder().adjustSaturation(effectSettings.saturation.coerceIn(-100f, 100f)).adjustHue(effectSettings.hue.coerceIn(-180f, 180f)).build())
        if (effectSettings.blur > 0f) effects.add(GaussianBlur(effectSettings.blur.coerceIn(0.5f, 12f)))
        val bitmapOverlays = imageLayers.mapNotNull { layer ->
            try {
                val bitmap = contentResolver.openInputStream(layer.uri)?.use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) BitmapOverlay.createStaticBitmapOverlay(
                    bitmap, OverlaySettings.Builder().setScale(0.55f, 0.55f).build()
                ) else null
            } catch (_: Exception) { null }
        }
        if (titleLayers.isNotEmpty()) {
            val textOverlays = titleLayers.map { layer ->
                AnimatedCaptionOverlay(layer.value, transformKeyframes[clipId].orEmpty())
            }
            effects.add(OverlayEffect(textOverlays))
        }
        val keys = transformKeyframes[clipId].orEmpty()
        if (keys.isNotEmpty()) {
            effects.add(KeyframedTransform(keys, effectSettings.zoom, effectSettings.rotation))
        } else if (effectSettings.zoom != 1f || effectSettings.rotation != 0f) {
            effects.add(ScaleAndRotateTransformation.Builder()
                .setScale(effectSettings.zoom, effectSettings.zoom)
                .setRotationDegrees(effectSettings.rotation).build())
        }
        return effects
    }

    @OptIn(UnstableApi::class)
    private fun applyPreviewEffects() {
        val index = selectedClip
        if (index !in clips.indices) return
        try { player?.setVideoEffects(buildVideoEffects(clips[index].id)) }
        catch (e: Exception) { status.text = "Preview effect unavailable: " + (e.message ?: "unsupported effect") }
    }

    private fun updateEffectInfo() {
        if (!::effectInfo.isInitialized) return
        effectInfo.text = "Preset: ${effectSettings.preset} • Bright ${"%.2f".format(effectSettings.brightness)} • Contrast ${"%.2f".format(effectSettings.contrast)} • Sat ${effectSettings.saturation.toInt()} • Hue ${effectSettings.hue.toInt()}° • Blur ${effectSettings.blur.toInt()} • Zoom ${"%.2f".format(effectSettings.zoom)}× • Rotation ${effectSettings.rotation.toInt()}° • Text opacity ${(effectSettings.opacity * 100).toInt()}%"
    }


    private fun renderKeyframeTimeline() {
        if (!::keyframeLayers.isInitialized) return
        keyframeLayers.removeAllViews()
        if (selectedClip !in clips.indices) return
        val keys = transformKeyframes[clips[selectedClip].id].orEmpty().sortedBy { it.timeMs }
        if (keys.isEmpty()) {
            keyframeLayers.addView(TextView(this).apply {
                text = "No keyframes yet — move the playhead, change zoom/rotation, then tap ADD KEYFRAME"
                textSize = 9f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.rgb(135, 150, 175))
            })
            return
        }
        keys.forEach { key ->
            val chip = TextView(this).apply {
                text = "◆ " + (key.timeMs / 1000.0) + "s • " + key.scale + "× • " + key.rotationDegrees.toInt() + "°"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(10)
                setBackgroundColor(Color.rgb(105, 75, 165))
            }
            val lp = LinearLayout.LayoutParams(-2, 30)
            lp.setMargins(3, 2, 3, 2)
            keyframeLayers.addView(chip, lp)
        }
    }

    private fun addTransformKeyframe() {
        if (selectedClip !in clips.indices) { toast("Select a video clip first"); return }
        val clip = clips[selectedClip]
        val time = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
        val keys = transformKeyframes.getOrPut(clip.id) { mutableListOf() }
        keys.removeAll { kotlin.math.abs(it.timeMs - time) < 30L }
        keys.add(TransformKeyframe(time, effectSettings.zoom, effectSettings.rotation, effectSettings.opacity))
        keys.sortBy { it.timeMs }
        applyPreviewEffects()
        saveProject()
        renderKeyframeTimeline()
        status.text = "Keyframe added at ${time / 1000.0}s"
    }

    private fun removeLatestKeyframe() {
        if (selectedClip !in clips.indices) return
        val id = clips[selectedClip].id
        val keys = transformKeyframes[id] ?: return
        if (keys.isNotEmpty()) keys.removeAt(keys.lastIndex)
        if (keys.isEmpty()) transformKeyframes.remove(id)
        applyPreviewEffects(); saveProject(); renderKeyframeTimeline()
        status.text = "Last keyframe removed"
    }

    private fun clearSelectedKeyframes() {
        if (selectedClip !in clips.indices) return
        transformKeyframes.remove(clips[selectedClip].id)
        applyPreviewEffects(); saveProject(); renderKeyframeTimeline()
        status.text = "Keyframes cleared for selected clip"
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
        if (items.isNotEmpty()) {
            val selectedId = clips.getOrNull(selectedClip.coerceIn(0, clips.lastIndex))?.id
            if (selectedId != null) p.setVideoEffects(buildVideoEffects(selectedId))
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
                    applyPreviewEffects()
                    renderKeyframeTimeline()
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

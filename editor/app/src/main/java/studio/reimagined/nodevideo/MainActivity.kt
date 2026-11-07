package studio.reimagined.nodevideo

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 14, 20)
        window.navigationBarColor = Color.rgb(12, 14, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18)
            setBackgroundColor(Color.rgb(12, 14, 20))
        }

        val header = TextView(this).apply {
            text = "NODE VIDEO  /  STUDIO"
            textSize = 18f
            setTextColor(Color.WHITE)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, LinearLayout.LayoutParams(-1, 54))

        val subtitle = TextView(this).apply {
            text = "MOBILE VIDEO WORKSPACE  •  ARMv7"
            textSize = 10f
            setTextColor(Color.rgb(130, 145, 170))
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, 28))

        playerView = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "No clip loaded — import a video to begin"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 42))

        val importButton = Button(this).apply {
            text = "IMPORT VIDEO"
            setOnClickListener { pickVideo.launch("video/*") }
        }
        root.addView(importButton, LinearLayout.LayoutParams(-1, 52))

        val timelineTitle = TextView(this).apply {
            text = "TIMELINE"
            textSize = 11f
            setTextColor(Color.rgb(130, 145, 170))
            setPadding(0, 18, 0, 8)
        }
        root.addView(timelineTitle)

        val track = TextView(this).apply {
            text = "＋  VIDEO TRACK                                      AUDIO TRACK"
            textSize = 11f
            setTextColor(Color.rgb(180, 195, 220))
            setPadding(12)
            setBackgroundColor(Color.rgb(28, 33, 45))
        }
        root.addView(track, LinearLayout.LayoutParams(-1, 64))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("CUT", "SPLIT", "TEXT", "AUDIO", "FX").forEach { name ->
            val b = Button(this).apply {
                text = name
                textSize = 10f
                isEnabled = false
            }
            tools.addView(b, LinearLayout.LayoutParams(0, 52, 1f))
        }
        root.addView(tools, LinearLayout.LayoutParams(-1, 58))

        setContentView(root)
    }

    private fun loadVideo(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = false
        }
        status.text = "Clip imported — playback ready"
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

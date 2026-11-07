package studio.reimagined.nodevideo

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.common.util.UnstableApi

/** A caption overlay whose scale and rotation follow the selected clip's transform keyframes. */
@UnstableApi
class AnimatedCaptionOverlay(
    value: String,
    private val keyframes: List<TransformKeyframe>
) : TextOverlay() {
    private val styledText = SpannableString(value).apply {
        if (isNotEmpty()) {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun getText(presentationTimeUs: Long): SpannableString = styledText

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ordered = keyframes.sortedBy { it.timeMs }
        if (ordered.isEmpty()) return OverlaySettings.Builder().setScale(0.65f, 0.65f).build()
        val timeMs = presentationTimeUs / 1000L
        val values = when {
            timeMs <= ordered.first().timeMs -> listOf(ordered.first().scale, ordered.first().rotationDegrees, ordered.first().alpha, ordered.first().x, ordered.first().y)
            timeMs >= ordered.last().timeMs -> listOf(ordered.last().scale, ordered.last().rotationDegrees, ordered.last().alpha, ordered.last().x, ordered.last().y)
            else -> {
                val rightIndex = ordered.indexOfFirst { it.timeMs >= timeMs }.coerceAtLeast(1)
                val left = ordered[rightIndex - 1]
                val right = ordered[rightIndex]
                val amount = (timeMs - left.timeMs).toFloat() / (right.timeMs - left.timeMs).coerceAtLeast(1L)
                listOf(
                    left.scale + (right.scale - left.scale) * amount,
                    left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * amount,
                    left.alpha + (right.alpha - left.alpha) * amount,
                    left.x + (right.x - left.x) * amount,
                    left.y + (right.y - left.y) * amount
                )
            }
        }
        val overlayScale = (values[0] * 0.65f).coerceIn(0.1f, 1.5f)
        return OverlaySettings.Builder()
            .setScale(overlayScale, overlayScale)
            .setRotationDegrees(values[1])
            .setAlphaScale(values[2].coerceIn(0f, 1f))
            .setBackgroundFrameAnchor(values[3].coerceIn(-1f, 1f), values[4].coerceIn(-1f, 1f))
            .build()
    }
}

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
        val pair = when {
            timeMs <= ordered.first().timeMs -> ordered.first().scale to ordered.first().rotationDegrees
            timeMs >= ordered.last().timeMs -> ordered.last().scale to ordered.last().rotationDegrees
            else -> {
                val rightIndex = ordered.indexOfFirst { it.timeMs >= timeMs }.coerceAtLeast(1)
                val left = ordered[rightIndex - 1]
                val right = ordered[rightIndex]
                val amount = (timeMs - left.timeMs).toFloat() / (right.timeMs - left.timeMs).coerceAtLeast(1L)
                (left.scale + (right.scale - left.scale) * amount) to
                    (left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * amount)
            }
        }
        val overlayScale = (pair.first * 0.65f).coerceIn(0.1f, 1.5f)
        return OverlaySettings.Builder()
            .setScale(overlayScale, overlayScale)
            .setRotationDegrees(pair.second)
            .build()
    }
}

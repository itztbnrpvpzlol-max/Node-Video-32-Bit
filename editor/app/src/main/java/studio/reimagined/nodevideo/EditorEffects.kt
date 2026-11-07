package studio.reimagined.nodevideo

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation

/** A transform value captured at a clip-local timestamp. */
data class TransformKeyframe(
    val timeMs: Long,
    val scale: Float,
    val rotationDegrees: Float,
    val alpha: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f
)

/**
 * Interpolates transform keyframes for every rendered frame. Timestamps are local to a clip,
 * matching Media3's per-item effect timeline.
 */
@UnstableApi
class KeyframedTransform(
    private val keyframes: List<TransformKeyframe>,
    private val fallbackScale: Float = 1f,
    private val fallbackRotation: Float = 0f
) : MatrixTransformation {
    private fun valueAt(timeUs: Long): Pair<Float, Float> {
        if (keyframes.isEmpty()) return fallbackScale to fallbackRotation
        val t = (timeUs / 1000L).coerceAtLeast(0L)
        val ordered = keyframes.sortedBy { it.timeMs }
        if (t <= ordered.first().timeMs) return ordered.first().scale to ordered.first().rotationDegrees
        if (t >= ordered.last().timeMs) return ordered.last().scale to ordered.last().rotationDegrees
        val rightIndex = ordered.indexOfFirst { it.timeMs >= t }.coerceAtLeast(1)
        val left = ordered[rightIndex - 1]
        val right = ordered[rightIndex]
        val span = (right.timeMs - left.timeMs).coerceAtLeast(1L)
        val amount = (t - left.timeMs).toFloat() / span.toFloat()
        val scale = left.scale + (right.scale - left.scale) * amount
        val rotation = left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * amount
        return scale to rotation
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val (scale, rotation) = valueAt(presentationTimeUs)
        return Matrix().apply {
            postScale(scale.coerceIn(0.25f, 4f), scale.coerceIn(0.25f, 4f))
            postRotate(rotation)
        }
    }
}

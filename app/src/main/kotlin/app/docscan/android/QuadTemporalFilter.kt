package app.docscan.android

import kotlin.math.hypot
import kotlin.math.max

/**
 * Adaptive EMA with outlier gate (2-frame jump confirmation),
 * speed-dependent alpha, and hold last contour on missing frames.
 */
class QuadTemporalFilter {
    private var current: FloatArray? = null
    private var missingFrames = 0
    private var rejectedJump: FloatArray? = null
    private var rejectedJumpCount = 0

    /** incoming — ready PreviewView pixels (8 floats) */
    fun update(incoming: FloatArray): FloatArray {
        require(incoming.size == 8)
        val previous = current ?: run {
            current = incoming.clone()
            missingFrames = 0
            return incoming.clone()
        }
        val displacement = normalizedDisplacement(previous, incoming)
        if (displacement > 0.20f) {
            // Large jump: false candidate OR real object switch.
            // Accept only after confirmation by a second consecutive frame.
            if (rejectedJump != null && normalizedDisplacement(rejectedJump!!, incoming) < 0.06f) {
                rejectedJumpCount++
            } else {
                rejectedJump = incoming.clone()
                rejectedJumpCount = 1
            }
            if (rejectedJumpCount < 2) {
                missingFrames++
                return previous.clone()
            }
            current = incoming.clone()
            rejectedJump = null
            rejectedJumpCount = 0
            missingFrames = 0
            return incoming.clone()
        }
        rejectedJump = null
        rejectedJumpCount = 0
        missingFrames = 0

        val alpha = when {
            displacement < 0.015f -> 0.16f // almost still — strong smoothing
            displacement < 0.05f -> 0.25f
            displacement < 0.10f -> 0.38f
            else -> 0.55f // camera moving — fast response
        }
        val result = FloatArray(8)
        for (i in 0 until 8) {
            result[i] = previous[i] + alpha * (incoming[i] - previous[i])
        }
        current = result
        return result.clone()
    }

    fun onMissing(): FloatArray? {
        val previous = current ?: return null
        missingFrames++
        if (missingFrames <= 3) return previous.clone() // anti-flicker hold for ~375ms
        current = null
        rejectedJump = null
        rejectedJumpCount = 0
        return null
    }

    fun reset() {
        current = null
        rejectedJump = null
        rejectedJumpCount = 0
        missingFrames = 0
    }

    /** Displacement normalized by the size of the quad itself — scale-invariant */
    private fun normalizedDisplacement(a: FloatArray, b: FloatArray): Float {
        var sideSum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sideSum += hypot(a[i * 2] - a[j * 2], a[i * 2 + 1] - a[j * 2 + 1])
        }
        val charSize = max(1f, sideSum / 4f)
        var disp = 0f
        for (i in 0 until 4) {
            disp += hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1])
        }
        return (disp / 4f) / charSize
    }
}

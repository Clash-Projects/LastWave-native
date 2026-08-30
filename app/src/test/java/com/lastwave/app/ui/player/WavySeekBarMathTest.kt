package com.lastwave.app.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WavySeekBarMathTest {

    private fun smootherstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * c * (c * (c * 6f - 15f) + 10f)
    }

    private fun calculateWaveHeight(
        x: Float,
        thumbX: Float,
        wavelength: Float,
        amplitude: Float,
        phase: Float,
        transitionLength: Float,
    ): Float {
        val effectiveTransition = minOf(transitionLength, thumbX * 0.48f)
        val invTransition = if (effectiveTransition > 0f) 1f / effectiveTransition else 0f
        val startEnv = if (invTransition > 0f) smootherstep(x * invTransition) else 1f
        val endEnv = if (invTransition > 0f) smootherstep((thumbX - x) * invTransition) else 1f
        val envelope = startEnv * endEnv

        val invWavelength2Pi = (2 * PI / wavelength).toFloat()
        val angle = x * invWavelength2Pi - phase
        return (0.5f + 0.5f * sin(angle)) * amplitude * envelope
    }

    @Test
    fun testSmootherstepValues() {
        assertThat(smootherstep(0f)).isEqualTo(0f)
        assertThat(smootherstep(1f)).isEqualTo(1f)
        assertThat(smootherstep(0.5f)).isEqualTo(0.5f)
        assertThat(smootherstep(-0.5f)).isEqualTo(0f)
        assertThat(smootherstep(1.5f)).isEqualTo(1f)
    }

    @Test
    fun testWaveHeightBoundedAndUpward() {
        val amplitude = 12f
        val wavelength = 100f
        val thumbX = 300f
        val transition = 30f

        for (x in 0..300 step 5) {
            val height = calculateWaveHeight(
                x = x.toFloat(),
                thumbX = thumbX,
                wavelength = wavelength,
                amplitude = amplitude,
                phase = 0f,
                transitionLength = transition,
            )
            // Wave height should never be negative and never exceed amplitude
            assertThat(height).isAtLeast(0f)
            assertThat(height).isAtMost(amplitude)
        }
    }

    @Test
    fun testWavelengthPeakDistance() {
        val wavelength = 114f
        val amplitude = 10f
        val thumbX = 500f

        // Center region away from edge dampening
        val center = 250f
        val invWavelength2Pi = (2 * PI / wavelength).toFloat()

        // Find phase that puts a peak at center
        val phase = center * invWavelength2Pi - (PI / 2.0).toFloat()

        val heightAtPeak1 = calculateWaveHeight(
            x = center,
            thumbX = thumbX,
            wavelength = wavelength,
            amplitude = amplitude,
            phase = phase,
            transitionLength = 20f,
        )

        val heightAtPeak2 = calculateWaveHeight(
            x = center + wavelength,
            thumbX = thumbX,
            wavelength = wavelength,
            amplitude = amplitude,
            phase = phase,
            transitionLength = 20f,
        )

        assertThat(heightAtPeak1).isWithin(0.01f).of(amplitude)
        assertThat(heightAtPeak2).isWithin(0.01f).of(amplitude)
    }
}

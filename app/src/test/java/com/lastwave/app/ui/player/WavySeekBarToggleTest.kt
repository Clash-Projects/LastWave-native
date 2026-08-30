package com.lastwave.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import org.junit.Test

class WavySeekBarToggleTest {

    @Test
    fun testWavySeekbarEnabledDefaultsToTrue() {
        val misc = MiscSettings()
        assertThat(misc.wavySeekbarEnabled).isTrue()
    }

    @Test
    fun testWavySeekbarToggleStateCanBeDisabled() {
        val misc = MiscSettings(wavySeekbarEnabled = false)
        assertThat(misc.wavySeekbarEnabled).isFalse()
    }
}

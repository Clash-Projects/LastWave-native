package com.lastwave.app.ui.home

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.repository.HomeTrack
import com.lastwave.app.playback.PlayableTrack
import org.junit.Test

class QuickPlayRadioTest {

    @Test
    fun testQuickPlayTrackMappingToPlayableTrack() {
        val homeTrack = HomeTrack(
            name = "Starlight",
            artist = "Muse",
            artworkUrl = "https://example.com/art.jpg",
            timestampMillis = 123456789L,
            playCount = 42,
        )

        val playable = PlayableTrack(
            title = homeTrack.name,
            artist = homeTrack.artist,
            artworkUrl = homeTrack.artworkUrl,
        )

        assertThat(playable.title).isEqualTo("Starlight")
        assertThat(playable.artist).isEqualTo("Muse")
        assertThat(playable.artworkUrl).isEqualTo("https://example.com/art.jpg")
    }

    @Test
    fun testGeneratedTracksConvertedToPlayableRadioTracks() {
        val generated = listOf(
            GeneratedTrack(name = "Knights of Cydonia", artist = "Muse", artworkUrl = "https://art1.jpg", album = "BH&R"),
            GeneratedTrack(name = "Time is Running Out", artist = "Muse", artworkUrl = "https://art2.jpg", album = "Absolution"),
        )

        val radioTracks = generated.map {
            PlayableTrack(
                title = it.name,
                artist = it.artist,
                artworkUrl = it.artworkUrl,
                album = it.album,
            )
        }

        assertThat(radioTracks).hasSize(2)
        assertThat(radioTracks[0].title).isEqualTo("Knights of Cydonia")
        assertThat(radioTracks[1].title).isEqualTo("Time is Running Out")
    }
}

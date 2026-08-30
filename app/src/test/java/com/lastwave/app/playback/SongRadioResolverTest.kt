package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SongRadioResolverTest {

    private val generateRepository = mockk<GenerateRepository>()
    private val searchRepository = mockk<SearchRepository>()
    private val resolver = SongRadioResolver(generateRepository, searchRepository)

    @Test
    fun testResolveRadioTracksFromGenerateRepository() = runTest {
        val seed = PlayableTrack(title = "Radioactive", artist = "Imagine Dragons")
        coEvery { generateRepository.startMixFromTrack("Radioactive", "Imagine Dragons", any()) } returns listOf(
            GeneratedTrack(name = "Demons", artist = "Imagine Dragons", artworkUrl = "art1"),
            GeneratedTrack(name = "Counting Stars", artist = "OneRepublic", artworkUrl = "art2"),
        )

        val result = resolver.resolveRadioTracks(seed)

        assertThat(result).hasSize(2)
        assertThat(result[0].title).isEqualTo("Demons")
        assertThat(result[1].title).isEqualTo("Counting Stars")
    }

    @Test
    fun testResolveRadioTracksFallsBackToSearchRepositoryWhenGenerateFails() = runTest {
        val seed = PlayableTrack(title = "Believer", artist = "Imagine Dragons")
        coEvery { generateRepository.startMixFromTrack("Believer", "Imagine Dragons", any()) } throws RuntimeException("Network error")
        coEvery { searchRepository.similarSongsFor(any()) } returns listOf(
            GeneratedTrack(name = "Thunder", artist = "Imagine Dragons", artworkUrl = "art3"),
        )

        val result = resolver.resolveRadioTracks(seed)

        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo("Thunder")
    }

    @Test
    fun testResolveRadioTracksFallsBackToPoolWhenBothFail() = runTest {
        val seed = PlayableTrack(title = "Bones", artist = "Imagine Dragons")
        val pool = listOf(
            PlayableTrack(title = "Enemy", artist = "Imagine Dragons"),
            PlayableTrack(title = "Bones", artist = "Imagine Dragons"),
        )
        coEvery { generateRepository.startMixFromTrack("Bones", "Imagine Dragons", any()) } returns emptyList()
        coEvery { searchRepository.similarSongsFor(any()) } returns emptyList()

        val result = resolver.resolveRadioTracks(seed, fallbackPool = pool)

        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo("Enemy")
    }
}

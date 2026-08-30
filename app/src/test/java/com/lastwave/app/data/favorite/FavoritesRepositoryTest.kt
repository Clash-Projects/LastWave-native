package com.lastwave.app.data.favorite

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.ytmusic.YtMusicSyncManager
import com.lastwave.app.playback.PlayableTrack
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FavoritesRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var ytMusicSyncManager: YtMusicSyncManager
    private lateinit var innerTube: com.lastwave.app.data.music.InnerTubeMusicApi
    private lateinit var settingsPreferences: com.lastwave.app.data.local.SettingsPreferences
    private lateinit var repository: FavoritesRepository
    private val preferencesFlow = MutableStateFlow<Preferences>(preferencesOf())
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        dataStore = mockk(relaxed = true)
        playlistRepository = mockk(relaxed = true)
        ytMusicSyncManager = mockk(relaxed = true)
        innerTube = mockk(relaxed = true)
        settingsPreferences = mockk(relaxed = true)
        every { settingsPreferences.settings } returns flowOf(com.lastwave.app.data.local.MiscSettings())

        every { dataStore.data } returns preferencesFlow
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            val updated = transform(preferencesFlow.value)
            preferencesFlow.value = updated
            updated
        }

        val dummyPlaylist = SavedPlaylist(
            id = 123L,
            title = "Favorites",
            subtitle = "Custom playlist",
            mode = "custom",
            tracks = emptyList(),
            createdAtMillis = 1000L,
        )
        coEvery { playlistRepository.createCustom("Favorites") } returns dummyPlaylist

        repository = FavoritesRepository(
            dataStore = dataStore,
            playlistRepository = playlistRepository,
            ytMusicSyncManager = ytMusicSyncManager,
            innerTube = innerTube,
            settingsPreferences = settingsPreferences,
            applicationScope = testScope,
        )
    }

    @Test
    fun testKeyNormalization() {
        val key = FavoritesRepository.makeKey("  Highway To Hell  ", "  AC/DC  ")
        assertThat(key).isEqualTo("highway to hell • ac/dc")
    }

    @Test
    fun testToggleFavoriteFlow() = runTest(testDispatcher) {
        val track = PlayableTrack(title = "Thunderstruck", artist = "AC/DC")

        val initialFav = repository.isFavorite(track.title, track.artist).first()
        assertThat(initialFav).isFalse()

        val favorited = repository.toggleFavorite(track)
        assertThat(favorited).isTrue()

        val afterFav = repository.isFavorite(track.title, track.artist).first()
        assertThat(afterFav).isTrue()

        val unfavorited = repository.toggleFavorite(track)
        assertThat(unfavorited).isFalse()

        val finalFav = repository.isFavorite(track.title, track.artist).first()
        assertThat(finalFav).isFalse()
    }
}

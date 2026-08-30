package com.lastwave.app.auto

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.favorite.FavoritesRepository
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.playback.MusicPlayer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class AndroidAutoMediaSourceTest {

    private lateinit var context: Context
    private lateinit var homeRepository: HomeRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var searchRepository: SearchRepository
    private lateinit var discoverRepository: DiscoverRepository
    private lateinit var database: AppDatabase
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var mediaSource: AndroidAutoMediaSource

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        homeRepository = mockk(relaxed = true)
        playlistRepository = mockk(relaxed = true)
        favoritesRepository = mockk(relaxed = true)
        searchRepository = mockk(relaxed = true)
        discoverRepository = mockk(relaxed = true)
        database = mockk(relaxed = true)
        musicPlayer = mockk(relaxed = true)

        mediaSource = AndroidAutoMediaSource(
            context = context,
            homeRepository = homeRepository,
            playlistRepository = playlistRepository,
            favoritesRepository = favoritesRepository,
            searchRepository = searchRepository,
            discoverRepository = discoverRepository,
            database = database,
            musicPlayer = musicPlayer,
        )
    }

    @Test
    fun testRootCategoriesAreProvided() {
        val rootItems = mediaSource.getRootCategories()
        assertThat(rootItems).isNotEmpty()
        val ids = rootItems.map { it.mediaId }
        assertThat(ids).contains(AndroidAutoConstants.CATEGORY_RECENTS)
        assertThat(ids).contains(AndroidAutoConstants.CATEGORY_FAVORITES)
        assertThat(ids).contains(AndroidAutoConstants.CATEGORY_PLAYLISTS)
        assertThat(ids).contains(AndroidAutoConstants.CATEGORY_OFFLINE)
        assertThat(ids).contains(AndroidAutoConstants.CATEGORY_TOP_TRACKS)
        assertThat(ids).contains(AndroidAutoConstants.CATEGORY_RECOMMENDED)
    }

    @Test
    fun testGetChildrenForRoot() = runTest {
        val children = mediaSource.getChildren(AndroidAutoConstants.MEDIA_ROOT_ID)
        assertThat(children).hasSize(6)
    }

    @Test
    fun testGetChildrenEmptyWhenUnknown() = runTest {
        val children = mediaSource.getChildren("unknown_category_xyz")
        assertThat(children).isEmpty()
    }
}

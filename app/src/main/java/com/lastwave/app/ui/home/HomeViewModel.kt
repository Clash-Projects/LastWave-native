package com.lastwave.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.HomeSortMode
import com.lastwave.app.data.repository.HomeStats
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.repository.HomeTrack
import com.lastwave.app.data.repository.ThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack

enum class QuickPlaySource(val label: String) {
    LAST_FM("Last.fm"),
    YOUTUBE_MUSIC("YT Music")
}

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val username: String = "",
    /** The account whose data is currently loaded — equal to [username]
     *  (the signed-in user) unless viewing a friend's profile via the
     *  friend-switcher (tap the username pill), in which case this is the
     *  friend's Last.fm username instead. Every list/stat on screen
     *  reflects THIS account, not necessarily the signed-in one. */
    val viewingUsername: String = "",
    val stats: HomeStats? = null,
    val nowPlaying: HomeTrack? = null,
    val sortMode: HomeSortMode = HomeSortMode.RECENT,
    val allTracks: List<HomeTrack> = emptyList(),
    val topTracksOverall: List<HomeTrack> = emptyList(),
    val topTracks7Days: List<HomeTrack> = emptyList(),
    val topTracks30Days: List<HomeTrack> = emptyList(),
    val quickPlaySource: QuickPlaySource = QuickPlaySource.LAST_FM,
    val ytMusicQuickTracks: List<HomeTrack> = emptyList(),
    val isLoadingYtQuick: Boolean = false,
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
    val friends: List<FriendEntry> = emptyList(),
    val isLoadingFriends: Boolean = false,
    val showFriendsSheet: Boolean = false,
    val pinnedFriends: Set<String> = emptySet(),
) {
    val isViewingFriend: Boolean get() = viewingUsername.isNotBlank() && viewingUsername != username

    /** Pinned friends first (stable within each group — user.getfriends'
     *  own order otherwise), for the friend-switcher sheet. */
    val sortedFriends: List<FriendEntry> get() = friends.sortedByDescending { it.name in pinnedFriends }
}

@Immutable
sealed interface HomeRow {
    @Immutable
    data class DateHeader(val label: String) : HomeRow
    @Immutable
    data class Track(val track: HomeTrack, val badge: String?) : HomeRow
}

/** Derives the display list for the current tab — instant, smooth, and accurate. */
fun HomeUiState.visibleRows(): List<HomeRow> {
    return when (sortMode) {
        HomeSortMode.RECENT -> {
            val dated = allTracks.filter { it.timestampMillis != null }.sortedByDescending { it.timestampMillis }
            val rows = mutableListOf<HomeRow>()
            nowPlaying?.let { rows += HomeRow.Track(it, badge = null) }

            val dayGroups = dated.groupBy { dateKeyOf(it.timestampMillis!!) }
            dayGroups.forEach { (_, tracksInDay) ->
                val firstTrackInDay = tracksInDay.firstOrNull()
                if (firstTrackInDay != null && firstTrackInDay.timestampMillis != null) {
                    dateLabelOf(firstTrackInDay.timestampMillis)?.let { rows += HomeRow.DateHeader(it) }
                }
                val seenKeysInDay = mutableSetOf<String>()
                val countMap = tracksInDay.groupingBy { it.key }.eachCount()
                tracksInDay.forEach { t ->
                    if (seenKeysInDay.add(t.key)) {
                        val count = countMap[t.key] ?: 1
                        rows += HomeRow.Track(t, badge = if (count > 1) "${count}\u00d7" else null)
                    }
                }
            }
            rows
        }
        HomeSortMode.MOST_PLAYED -> {
            val candidateList = if (topTracksOverall.isNotEmpty()) topTracksOverall else allTracks
            val rows = mutableListOf<HomeRow>()
            nowPlaying?.let { rows += HomeRow.Track(it, badge = null) }
            candidateList.filter { !it.isNowPlaying }.sortedByDescending { it.playCount }.forEach { t ->
                rows += HomeRow.Track(t, badge = playCountBadge(t.playCount))
            }
            rows
        }
        HomeSortMode.LAST_7_DAYS -> {
            val candidateList = if (topTracks7Days.isNotEmpty()) topTracks7Days else {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                allTracks.filter { it.timestampMillis != null && it.timestampMillis >= cutoff }
            }
            val rows = mutableListOf<HomeRow>()
            nowPlaying?.let { rows += HomeRow.Track(it, badge = null) }
            candidateList.filter { !it.isNowPlaying }.sortedByDescending { it.playCount }.forEach { t ->
                rows += HomeRow.Track(t, badge = playCountBadge(t.playCount))
            }
            rows
        }
        HomeSortMode.LAST_30_DAYS -> {
            val candidateList = if (topTracks30Days.isNotEmpty()) topTracks30Days else {
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                allTracks.filter { it.timestampMillis != null && it.timestampMillis >= cutoff }
            }
            val rows = mutableListOf<HomeRow>()
            nowPlaying?.let { rows += HomeRow.Track(it, badge = null) }
            candidateList.filter { !it.isNowPlaying }.sortedByDescending { it.playCount }.forEach { t ->
                rows += HomeRow.Track(t, badge = playCountBadge(t.playCount))
            }
            rows
        }
    }
}

private fun playCountBadge(count: Int): String? {
    if (count <= 0) return null
    return if (count >= 1000) "%.1fk".format(count / 1000.0) else count.toString()
}

private fun dateKeyOf(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
}

private fun dateLabelOf(millis: Long): String? {
    val target = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        isSameDay(target, today) -> null
        isSameDay(target, yesterday) -> "Yesterday"
        else -> {
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${target.get(Calendar.DAY_OF_MONTH)} ${months[target.get(Calendar.MONTH)]} ${target.get(Calendar.YEAR)}"
        }
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private const val NOW_PLAYING_POLL_MS = 12_000L
private const val RECENT_TRACKS_POLL_MS = 30_000L
private const val LISTEN_TICK_MS = 1_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val sessionPreferences: SessionPreferences,
    private val themeRepository: ThemeRepository,
    private val viewingProfileState: com.lastwave.app.data.repository.ViewingProfileState,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    private val scrobbleRepository: com.lastwave.app.data.repository.ScrobbleRepository,
    private val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
    private val songRadioResolver: com.lastwave.app.playback.SongRadioResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _listenElapsedSeconds = MutableStateFlow(0)
    val listenElapsedSeconds: StateFlow<Int> = _listenElapsedSeconds.asStateFlow()

    private var cachedTopTracks: List<HomeTrack> = emptyList()
    private var quickPlayRadioJob: Job? = null

    fun setQuickPlaySource(source: QuickPlaySource) {
        _uiState.update { it.copy(quickPlaySource = source) }
        if (source == QuickPlaySource.YOUTUBE_MUSIC && _uiState.value.ytMusicQuickTracks.isEmpty()) {
            loadYtMusicQuickTracks()
        }
    }

    fun playQuickTrack(track: HomeTrack, musicPlayer: MusicPlayer) {
        val selected = PlayableTrack(
            title = track.name,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
        )
        val sourceLabel = if (_uiState.value.quickPlaySource == QuickPlaySource.YOUTUBE_MUSIC) "YT Quick Play" else "Quick Play"
        musicPlayer.play(selected, sourceLabel = sourceLabel)

        quickPlayRadioJob?.cancel()
        quickPlayRadioJob = viewModelScope.launch(Dispatchers.IO) {
            val currentGrid = if (_uiState.value.quickPlaySource == QuickPlaySource.YOUTUBE_MUSIC) {
                _uiState.value.ytMusicQuickTracks
            } else {
                _uiState.value.topTracksOverall.ifEmpty { _uiState.value.allTracks }
            }
            val fallbackPool = currentGrid.map {
                PlayableTrack(title = it.name, artist = it.artist, artworkUrl = it.artworkUrl)
            }
            val radioTracks = songRadioResolver.resolveRadioTracks(seed = selected, fallbackPool = fallbackPool)
            musicPlayer.appendQueueRecommendations(
                seed = selected,
                tracks = radioTracks,
                allowedSourceLabels = setOf("Quick Play", "YT Quick Play"),
            )
        }
    }

    fun loadYtMusicQuickTracks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingYtQuick = true) }
            val signals = runCatching { innerTube.fetchTasteSignals(recentLimit = 30, likedLimit = 30, feedLimit = 30) }.getOrNull()
            val ytTracks = (signals?.recentTracks.orEmpty() + signals?.likedTracks.orEmpty() + signals?.feedTracks.orEmpty())
                .distinctBy { "${it.title}|${it.artist}" }
                .map {
                    HomeTrack(
                        name = it.title,
                        artist = it.artist,
                        artworkUrl = it.artworkUrl,
                        timestampMillis = null,
                        playCount = 1,
                    )
                }
            _uiState.update { it.copy(ytMusicQuickTracks = ytTracks, isLoadingYtQuick = false) }
        }
    }

    init {
        loadInitial()
        viewModelScope.launch {
            settingsPreferences.settings.collect { misc ->
                _uiState.update { it.copy(pinnedFriends = misc.pinnedFriends) }
            }
        }
        viewModelScope.launch {
            scrobbleRepository.scrobbleEvents.collect {
                // Instantly update recent tracks list and scrobble count when a track scrobbles
                delay(1200L)
                refreshSilently()
            }
        }
    }

    /** Dynamic Now Playing Theme (§5): tells ThemeRepository what track is
     *  currently playing so it can resolve real artwork (through the same
     *  Last.fm -> iTunes fallback every other screen uses) and extract an
     *  accent color from it. Cheap and safe to call every time nowPlaying is
     *  (re)computed, including from every poll tick — ThemeRepository
     *  dedupes on the track's cache key internally, so this only triggers
     *  real work when the track actually changes. */
    private fun notifyNowPlayingArtwork(track: HomeTrack?) {
        themeRepository.updateNowPlayingArtwork(track?.name, track?.artist)
    }

    private fun mergeRecentWithTop(
        nowPlaying: RecentTrack?,
        recent: List<RecentTrack>,
        topTracks: List<HomeTrack>,
        previousNowPlaying: HomeTrack? = _uiState.value.nowPlaying,
    ): Pair<HomeTrack?, List<HomeTrack>> {
        val topCountByKey = topTracks.associate { "${it.name.lowercase()}|${it.artist.lowercase()}" to it.playCount }
        val recentAsHome = recent.map { t ->
            val key = "${t.name.lowercase()}|${t.artist.displayName.lowercase()}"
            HomeTrack(
                name = t.name,
                artist = t.artist.displayName,
                artworkUrl = t.artworkUrl,
                timestampMillis = t.date?.uts?.toLongOrNull()?.times(1000),
                playCount = topCountByKey[key] ?: 0,
            )
        }
        val recentKeys = recentAsHome.map { it.key }.toSet()
        val extra = topTracks.filter { it.key !in recentKeys }
        val nowPlayingHome = nowPlaying?.let { np ->
            // Keep the previous HomeTrack instance structurally identical when
            // the same track is still playing: stamping a fresh wall-clock
            // timestamp on every 12s poll changed the data-class identity and
            // forced every downstream remember/derive on Home to recompute.
            val previous = previousNowPlaying
            val sameTrackStillPlaying = previous != null && previous.isNowPlaying &&
                previous.name.equals(np.name, ignoreCase = true) &&
                previous.artist.equals(np.artist.displayName, ignoreCase = true)
            HomeTrack(
                name = np.name,
                artist = np.artist.displayName,
                artworkUrl = np.artworkUrl,
                timestampMillis = if (sameTrackStillPlaying && previous != null) previous.timestampMillis else System.currentTimeMillis(),
                playCount = 0,
                isNowPlaying = true,
            )
        }
        return nowPlayingHome to (recentAsHome + extra)
    }

    private fun loadInitial() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val username = sessionPreferences.session.first().username
            val result = homeRepository.fetchInitialData()
            result.fold(
                onSuccess = { data ->
                    cachedTopTracks = data.topTracks
                    val (nowPlaying, merged) = mergeRecentWithTop(data.recent.nowPlaying, data.recent.tracks, data.topTracks)
                    notifyNowPlayingArtwork(nowPlaying)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            username = username,
                            viewingUsername = username,
                            stats = data.stats,
                            nowPlaying = nowPlaying,
                            allTracks = merged,
                            topTracksOverall = data.topTracks,
                            page = data.recent.page,
                            totalPages = data.recent.totalPages,
                        )
                    }
                    preloadPeriodTracks()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, username = username, viewingUsername = username, error = e.message ?: "Couldn't load your data") }
                },
            )
        }
    }

    private fun preloadPeriodTracks() {
        val target = _uiState.value.viewingUsername
        viewModelScope.launch(Dispatchers.IO) {
            homeRepository.fetchTopTracksForPeriod("7day", 50, username = target).onSuccess { tracks ->
                _uiState.update { it.copy(topTracks7Days = tracks) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            homeRepository.fetchTopTracksForPeriod("1month", 50, username = target).onSuccess { tracks ->
                _uiState.update { it.copy(topTracks30Days = tracks) }
            }
        }
    }

    fun refresh() {
        val target = _uiState.value.viewingUsername
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val recent = homeRepository.fetchRecentTracks(username = target)
                recent.fold(
                    onSuccess = { page ->
                        val (nowPlaying, merged) = mergeRecentWithTop(page.nowPlaying, page.tracks, cachedTopTracks)
                        notifyNowPlayingArtwork(nowPlaying)
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                nowPlaying = nowPlaying,
                                allTracks = merged,
                                page = page.page,
                                totalPages = page.totalPages,
                                error = null,
                            )
                        }
                        preloadPeriodTracks()
                    },
                    onFailure = { e -> _uiState.update { it.copy(isRefreshing = false, error = e.message) } },
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private var pageLoadInFlight = false

    fun loadNextPage() {
        val current = _uiState.value
        if (current.page >= current.totalPages) return
        // One page request at a time: oscillation around the "near end"
        // scroll threshold used to fire duplicate concurrent requests for
        // the same page while the first fetch was still in flight.
        if (pageLoadInFlight) return
        pageLoadInFlight = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                homeRepository.fetchRecentTracks(page = current.page + 1, username = current.viewingUsername).onSuccess { page ->
                    val (_, merged) = mergeRecentWithTop(null, page.tracks, cachedTopTracks)
                    _uiState.update { state ->
                        val existingKeys = state.allTracks.map { it.key to it.timestampMillis }.toSet()
                        val newOnes = merged.filter { (it.key to it.timestampMillis) !in existingKeys }
                        state.copy(
                            allTracks = state.allTracks + newOnes,
                            page = page.page,
                            totalPages = page.totalPages,
                        )
                    }
                }
            } finally {
                pageLoadInFlight = false
            }
        }
    }

    fun setSortMode(mode: HomeSortMode) {
        _uiState.update { it.copy(sortMode = mode) }
        val target = _uiState.value.viewingUsername
        if (mode == HomeSortMode.LAST_7_DAYS && _uiState.value.topTracks7Days.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                homeRepository.fetchTopTracksForPeriod("7day", 50, username = target).onSuccess { tracks ->
                    _uiState.update { it.copy(topTracks7Days = tracks) }
                }
            }
        } else if (mode == HomeSortMode.LAST_30_DAYS && _uiState.value.topTracks30Days.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                homeRepository.fetchTopTracksForPeriod("1month", 50, username = target).onSuccess { tracks ->
                    _uiState.update { it.copy(topTracks30Days = tracks) }
                }
            }
        }
    }

    /** Opens the friend-switcher sheet — lazily loads the signed-in user's
     *  own friends list (user.getfriends) the first time it's opened. */
    fun openFriendsSheet() {
        _uiState.update { it.copy(showFriendsSheet = true) }
        if (_uiState.value.friends.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoadingFriends = true) }
                homeRepository.fetchFriends().fold(
                    onSuccess = { list -> _uiState.update { it.copy(friends = list, isLoadingFriends = false) } },
                    onFailure = { _uiState.update { it.copy(isLoadingFriends = false) } },
                )
            }
        }
    }

    fun dismissFriendsSheet() {
        _uiState.update { it.copy(showFriendsSheet = false) }
    }

    fun toggleFriendPinned(username: String) {
        viewModelScope.launch { settingsPreferences.toggleFriendPinned(username) }
    }

    /** Switches the whole Home screen over to a friend's data — every
     *  fetch (recent tracks, stats, top tracks for every period) re-runs
     *  for [friend]'s username instead of the signed-in one. */
    fun viewFriend(friend: FriendEntry) {
        _uiState.update { it.copy(showFriendsSheet = false) }
        viewingProfileState.set(friend.name)
        loadForUsername(friend.name)
    }

    /** Switches back to the signed-in user's own data. */
    fun returnToOwnProfile() {
        viewingProfileState.set(null)
        loadForUsername(_uiState.value.username)
    }

    private fun loadForUsername(username: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    viewingUsername = username,
                    // Reset period caches from the previous account — the
                    // 7-day/30-day tabs would otherwise briefly show the
                    // last-viewed account's tracks under the new one's name.
                    topTracks7Days = emptyList(),
                    topTracks30Days = emptyList(),
                )
            }
            val result = homeRepository.fetchInitialData(username = username)
            result.fold(
                onSuccess = { data ->
                    cachedTopTracks = data.topTracks
                    val (nowPlaying, merged) = mergeRecentWithTop(data.recent.nowPlaying, data.recent.tracks, data.topTracks)
                    notifyNowPlayingArtwork(nowPlaying)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stats = data.stats,
                            nowPlaying = nowPlaying,
                            allTracks = merged,
                            topTracksOverall = data.topTracks,
                            page = data.recent.page,
                            totalPages = data.recent.totalPages,
                        )
                    }
                    preloadPeriodTracks()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load that profile") }
                },
            )
        }
    }

    fun refreshSilently() {
        val target = _uiState.value.viewingUsername
        if (target.isBlank() || target.equals("Guest User", ignoreCase = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val recent = homeRepository.fetchRecentTracks(username = target, forceRefresh = true)
                recent.onSuccess { page ->
                    val (nowPlaying, merged) = mergeRecentWithTop(page.nowPlaying, page.tracks, cachedTopTracks)
                    notifyNowPlayingArtwork(nowPlaying)
                    _uiState.update { state ->
                        state.copy(
                            nowPlaying = nowPlaying,
                            allTracks = merged,
                            page = page.page,
                            totalPages = page.totalPages,
                        )
                    }
                }
                homeRepository.fetchStats(username = target, forceRefresh = true).onSuccess { freshStats ->
                    _uiState.update { it.copy(stats = freshStats) }
                }
            }
        }
    }

    /** Runs the now-playing + recent-tracks + listen-timer loops concurrently.
     *  Call from a lifecycle-aware LaunchedEffect(repeatOnLifecycle(STARTED))
     *  so polling stops the moment the screen backgrounds. Polls whichever
     *  account is currently being viewed (yours, or a friend's). */
    suspend fun pollWhileActive() = coroutineScope {
        // Immediately fetch newest tracks and stats on opening/returning to Home screen
        refreshSilently()

        launch {
            while (true) {
                delay(NOW_PLAYING_POLL_MS)
                val target = _uiState.value.viewingUsername
                if (target.isNotBlank() && !target.equals("Guest User", ignoreCase = true)) {
                    val result = runCatching { homeRepository.fetchRecentTracks(limit = 2, username = target, forceRefresh = true) }.getOrNull()
                    result?.onSuccess { page ->
                        val (nowPlaying, _) = mergeRecentWithTop(page.nowPlaying, emptyList(), cachedTopTracks)
                        val previousNp = _uiState.value.nowPlaying
                        val trackEnded = previousNp != null && nowPlaying == null
                        val trackChanged = (previousNp?.name != nowPlaying?.name || previousNp?.artist != nowPlaying?.artist)

                        notifyNowPlayingArtwork(nowPlaying)
                        if (trackChanged) _listenElapsedSeconds.value = 0
                        _uiState.update { state ->
                            state.copy(nowPlaying = nowPlaying)
                        }
                        // When a track finishes or changes, auto-refresh recent tracks list immediately
                        if (trackEnded || (trackChanged && previousNp != null)) {
                            refreshSilently()
                        }
                    }?.onFailure {
                        // On error or rate limit, back off for 15 seconds before retrying
                        delay(15_000L)
                    }
                }
            }
        }
        launch {
            while (true) {
                delay(RECENT_TRACKS_POLL_MS)
                val target = _uiState.value.viewingUsername
                if (target.isNotBlank() && !target.equals("Guest User", ignoreCase = true)) {
                    val result = runCatching { homeRepository.fetchRecentTracks(username = target, forceRefresh = true) }.getOrNull()
                    result?.onSuccess { page ->
                        val (nowPlaying, merged) = mergeRecentWithTop(page.nowPlaying, page.tracks, cachedTopTracks)
                        notifyNowPlayingArtwork(nowPlaying)
                        _uiState.update { state ->
                            val recentOnly = merged.filter { it.timestampMillis != null }
                            val existingKeys = state.allTracks.map { it.key to it.timestampMillis }.toSet()
                            val newOnes = recentOnly.filter { (it.key to it.timestampMillis) !in existingKeys }
                            val combined = (newOnes + state.allTracks).distinctBy { it.key to it.timestampMillis }
                            state.copy(nowPlaying = nowPlaying, allTracks = combined, totalPages = page.totalPages)
                        }
                    }?.onFailure {
                        // On error or rate limit, back off for 20 seconds before retrying
                        delay(20_000L)
                    }
                }
            }
        }
        launch {
            while (true) {
                delay(LISTEN_TICK_MS)
                if (_uiState.value.nowPlaying != null) {
                    _listenElapsedSeconds.update { it + 1 }
                }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}

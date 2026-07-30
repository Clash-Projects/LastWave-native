package com.lastwave.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.HomeSortMode
import com.lastwave.app.data.repository.HomeStats
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.repository.HomeTrack
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

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val username: String = "",
    val stats: HomeStats? = null,
    val nowPlaying: HomeTrack? = null,
    val listenElapsedSeconds: Int = 0,
    val sortMode: HomeSortMode = HomeSortMode.RECENT,
    val allTracks: List<HomeTrack> = emptyList(),
    val topTracksOverall: List<HomeTrack> = emptyList(),
    val topTracks7Days: List<HomeTrack> = emptyList(),
    val topTracks30Days: List<HomeTrack> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
)

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

private const val NOW_PLAYING_POLL_MS = 5_000L
private const val RECENT_TRACKS_POLL_MS = 8_000L
private const val LISTEN_TICK_MS = 1_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val sessionPreferences: SessionPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var cachedTopTracks: List<HomeTrack> = emptyList()

    init {
        loadInitial()
    }

    private fun mergeRecentWithTop(
        nowPlaying: RecentTrack?,
        recent: List<RecentTrack>,
        topTracks: List<HomeTrack>,
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
        val nowPlayingHome = nowPlaying?.let {
            HomeTrack(
                name = it.name,
                artist = it.artist.displayName,
                artworkUrl = it.artworkUrl,
                timestampMillis = System.currentTimeMillis(),
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
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            username = username,
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
                    _uiState.update { it.copy(isLoading = false, username = username, error = e.message ?: "Couldn't load your data") }
                },
            )
        }
    }

    private fun preloadPeriodTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            homeRepository.fetchTopTracksForPeriod("7day", 50).onSuccess { tracks ->
                _uiState.update { it.copy(topTracks7Days = tracks) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            homeRepository.fetchTopTracksForPeriod("1month", 50).onSuccess { tracks ->
                _uiState.update { it.copy(topTracks30Days = tracks) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRefreshing = true) }
            val recent = homeRepository.fetchRecentTracks()
            recent.fold(
                onSuccess = { page ->
                    val (nowPlaying, merged) = mergeRecentWithTop(page.nowPlaying, page.tracks, cachedTopTracks)
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
        }
    }

    fun loadNextPage() {
        val current = _uiState.value
        if (current.page >= current.totalPages) return
        viewModelScope.launch(Dispatchers.IO) {
            homeRepository.fetchRecentTracks(page = current.page + 1).onSuccess { page ->
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
        }
    }

    fun setSortMode(mode: HomeSortMode) {
        _uiState.update { it.copy(sortMode = mode) }
        if (mode == HomeSortMode.LAST_7_DAYS && _uiState.value.topTracks7Days.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                homeRepository.fetchTopTracksForPeriod("7day", 50).onSuccess { tracks ->
                    _uiState.update { it.copy(topTracks7Days = tracks) }
                }
            }
        } else if (mode == HomeSortMode.LAST_30_DAYS && _uiState.value.topTracks30Days.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                homeRepository.fetchTopTracksForPeriod("1month", 50).onSuccess { tracks ->
                    _uiState.update { it.copy(topTracks30Days = tracks) }
                }
            }
        }
    }

    /** Runs the now-playing + recent-tracks + listen-timer loops concurrently.
     *  Call from a lifecycle-aware LaunchedEffect(repeatOnLifecycle(STARTED))
     *  so polling stops the moment the screen backgrounds. */
    suspend fun pollWhileActive() = coroutineScope {
        launch {
            while (true) {
                delay(NOW_PLAYING_POLL_MS)
                homeRepository.fetchRecentTracks(limit = 2).onSuccess { page ->
                    val (nowPlaying, _) = mergeRecentWithTop(page.nowPlaying, emptyList(), cachedTopTracks)
                    _uiState.update { state ->
                        val trackChanged = nowPlaying?.name != state.nowPlaying?.name || nowPlaying?.artist != state.nowPlaying?.artist
                        state.copy(nowPlaying = nowPlaying, listenElapsedSeconds = if (trackChanged) 0 else state.listenElapsedSeconds)
                    }
                }
            }
        }
        launch {
            while (true) {
                delay(RECENT_TRACKS_POLL_MS)
                homeRepository.fetchRecentTracks().onSuccess { page ->
                    val (nowPlaying, merged) = mergeRecentWithTop(page.nowPlaying, page.tracks, cachedTopTracks)
                    _uiState.update { it.copy(nowPlaying = nowPlaying, allTracks = merged, page = page.page, totalPages = page.totalPages) }
                }
            }
        }
        launch {
            while (true) {
                delay(LISTEN_TICK_MS)
                if (_uiState.value.nowPlaying != null) {
                    _uiState.update { it.copy(listenElapsedSeconds = it.listenElapsedSeconds + 1) }
                }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}

# Song Caching with Configurable Size - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement configurable song caching with sizes from 400MB to 8GB using LFU eviction policy

**Architecture:** Extend existing caching system with LFU evictor and configurable size through settings

**Tech Stack:** Kotlin, ExoPlayer, Android

**Spec:** docs/superpowers/specs/2026-08-26-song-caching-design.md

## Global Constraints

- Kotlin version: 1.8.0 or higher
- Minimum Android API: 21
- Target Android API: 34
- Use existing ExoPlayer dependencies
- Follow existing code style and patterns

---

### Task 1: Implement LeastFrequentlyUsedCacheEvictor

**Files:**
- Create: `app/src/main/java/com/lastwave/app/playback/LeastFrequentlyUsedCacheEvictor.kt`
- Test: `app/src/test/java/com/lastwave/app/playback/LeastFrequentlyUsedCacheEvictorTest.kt`

**Interfaces:**
- Produces: `LeastFrequentlyUsedCacheEvictor` class implementing `CacheEvictor`

- [ ] **Step 1: Write the failing test for LFU evictor**

```kotlin
class LeastFrequentlyUsedCacheEvictorTest {
    @Test
    fun testEvictorEvictsLeastFrequentlyUsed() {
        val evictor = LeastFrequentlyUsedCacheEvictor(100)
        val cache = mockk<Cache>()
        val span1 = mockk<CacheSpan> { every { key } returns "span1" }
        val span2 = mockk<CacheSpan> { every { key } returns "span2" }

        // Simulate adding spans
        evictor.onSpanAdded(cache, span1)
        evictor.onSpanAdded(cache, span2)

        // Simulate touching spans
        evictor.onSpanTouched(cache, span1, span1)
        evictor.onSpanTouched(cache, span1, span1)

        // Verify eviction
        verify { cache.removeSpan("span2") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.lastwave.app.playback.LeastFrequentlyUsedCacheEvictorTest"`
Expected: FAIL with "LeastFrequentlyUsedCacheEvictor not found"

- [ ] **Step 3: Implement LFU evictor**

```kotlin
class LeastFrequentlyUsedCacheEvictor(maxSizeBytes: Long) : CacheEvictor {
    private val frequencyMap = mutableMapOf<String, Int>()
    private val accessQueue = LinkedList<String>()
    private var currentSize = 0L
    private val maxSize = maxSizeBytes

    override fun onCacheStart() {}

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        currentSize += span.length
        frequencyMap[span.key] = 1
        accessQueue.add(span.key)
        evictIfNeeded()
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        currentSize -= span.length
        frequencyMap.remove(span.key)
        accessQueue.remove(span.key)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        val key = oldSpan.key
        frequencyMap[key] = frequencyMap[key]!! + 1
        accessQueue.remove(key)
        accessQueue.add(key)
    }

    private fun evictIfNeeded() {
        while (currentSize > maxSize && accessQueue.isNotEmpty()) {
            val leastFrequentKey = findLeastFrequentlyUsed()
            leastFrequentKey?.let { key ->
                cache.removeSpan(key)
            }
        }
    }

    private fun findLeastFrequentlyUsed(): String? {
        return accessQueue.minByOrNull { frequencyMap[it]!! }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.lastwave.app.playback.LeastFrequentlyUsedCacheEvictorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/playback/LeastFrequentlyUsedCacheEvictor.kt
git add app/src/test/java/com/lastwave/app/playback/LeastFrequentlyUsedCacheEvictorTest.kt
git commit -m "feat(playback): implement LFU cache evictor"
```

---

### Task 2: Add Cache Size Configuration

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/local/MiscSettings.kt`
- Test: `app/src/test/java/com/lastwave/app/data/local/MiscSettingsTest.kt`

**Interfaces:**
- Consumes: `CACHE_SIZE_OPTIONS` list
- Produces: `cacheSizeBytes` field in `MiscSettings`

- [ ] **Step 1: Write the failing test for cache size configuration**

```kotlin
class MiscSettingsTest {
    @Test
    fun testCacheSizeOptions() {
        val settings = MiscSettings(cacheSizeBytes = 1L * 1024 * 1024 * 1024)
        assertEquals(1L * 1024 * 1024 * 1024, settings.cacheSizeBytes)
        assertTrue(settings.cacheSizeBytes in CACHE_SIZE_OPTIONS)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.lastwave.app.data.local.MiscSettingsTest"`
Expected: FAIL with "cacheSizeBytes not found"

- [ ] **Step 3: Add cache size configuration**

```kotlin
// In MiscSettings.kt
data class MiscSettings(
    // ... existing fields ...
    val cacheSizeBytes: Long = 1L * 1024 * 1024 * 1024, // Default 1GB
    val preferQobuzStreaming: Boolean = false,
    val qobuzQuality: String = "LOSSLESS",
    // ... other fields ...
)

// Cache size options
val CACHE_SIZE_OPTIONS = listOf(
    400L * 1024 * 1024,  // 400MB
    1L * 1024 * 1024 * 1024,  // 1GB
    2L * 1024 * 1024 * 1024,  // 2GB
    4L * 1024 * 1024 * 1024,  // 4GB
    8L * 1024 * 1024 * 1024,  // 8GB
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.lastwave.app.data.local.MiscSettingsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/local/MiscSettings.kt
git add app/src/test/java/com/lastwave/app/data/local/MiscSettingsTest.kt
git commit -m "feat(settings): add configurable cache size options"
```

---

### Task 3: Integrate LFU Cache with MusicPlayer

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt`
- Test: `app/src/test/java/com/lastwave/app/playback/MusicPlayerTest.kt`

**Interfaces:**
- Consumes: `LeastFrequentlyUsedCacheEvictor` and `cacheSizeBytes` from settings

- [ ] **Step 1: Write the failing test for cache integration**

```kotlin
class MusicPlayerTest {
    @Test
    fun testCacheInitializationWithSettings() {
        val context = mockk<Context>()
        val settingsPreferences = mockk<SettingsPreferences>()
        val settingsFlow = flowOf(MiscSettings(cacheSizeBytes = 2L * 1024 * 1024 * 1024))
        every { settingsPreferences.settings } returns settingsFlow

        val player = MusicPlayer(context, mockk(), mockk(), settingsPreferences, mockk(), mockk(), mockk(), mockk())

        // Verify cache is initialized with correct size
        assertNotNull(player.mediaCache)
        // Additional assertions to verify LFU evictor is used
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.lastwave.app.playback.MusicPlayerTest"`
Expected: FAIL with "mediaCache not initialized with settings"

- [ ] **Step 3: Integrate LFU cache with MusicPlayer**

```kotlin
// In MusicPlayer.kt
private val mediaCache: Cache by lazy {
    val cacheDir = java.io.File(appContext.cacheDir, "media_stream_cache")
    // Get cache size from settings
    val cacheSize = runBlocking {
        settingsPreferences.settings.first().cacheSizeBytes
    }

    // Use LFU evictor with configurable size
    val evictor = LeastFrequentlyUsedCacheEvictor(cacheSize)
    val dbProvider = StandaloneDatabaseProvider(appContext)
    SimpleCache(cacheDir, evictor, dbProvider)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.lastwave.app.playback.MusicPlayerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt
git add app/src/test/java/com/lastwave/app/playback/MusicPlayerTest.kt
git commit -m "feat(playback): integrate LFU cache with configurable size"
```

---

### Task 4: Add Cache Statistics (Optional)

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt`
- Modify: `app/src/main/java/com/lastwave/app/playback/MusicPlayerState.kt`
- Test: `app/src/test/java/com/lastwave/app/playback/MusicPlayerTest.kt`

**Interfaces:**
- Produces: Cache statistics in `MusicPlayerState`

- [ ] **Step 1: Write the failing test for cache statistics**

```kotlin
class MusicPlayerTest {
    @Test
    fun testCacheStatistics() {
        val context = mockk<Context>()
        val settingsPreferences = mockk<SettingsPreferences>()
        val settingsFlow = flowOf(MiscSettings(cacheSizeBytes = 2L * 1024 * 1024 * 1024))
        every { settingsPreferences.settings } returns settingsFlow

        val player = MusicPlayer(context, mockk(), mockk(), settingsPreferences, mockk(), mockk(), mockk(), mockk())

        // Verify cache statistics are available
        val state = player.state.value
        assertNotNull(state.cacheStats)
        assertTrue(state.cacheStats.hitCount >= 0)
        assertTrue(state.cacheStats.missCount >= 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.lastwave.app.playback.MusicPlayerTest"`
Expected: FAIL with "cacheStats not found"

- [ ] **Step 3: Add cache statistics**

```kotlin
// In MusicPlayerState.kt
data class CacheStats(
    val hitCount: Long = 0,
    val missCount: Long = 0,
    val bytesUsed: Long = 0,
    val bytesLimit: Long = 0
)

data class MusicPlayerState(
    // ... existing fields ...
    val cacheStats: CacheStats = CacheStats(),
    // ... other fields ...
)

// In MusicPlayer.kt
// Add to the existing mediaCache lazy property
private val mediaCache: Cache by lazy {
    // ... existing code ...
    val cacheStats = CacheStats(bytesLimit = cacheSize)
    val evictor = LeastFrequentlyUsedCacheEvictor(cacheSize, cacheStats)
    // ... rest of the code ...
}

// Update state with cache statistics
private fun refresh(player: Player) {
    // ... existing code ...
    val cacheStats = mediaCache.cacheStats
    _state.value = MusicPlayerState(
        // ... existing fields ...
        cacheStats = cacheStats,
        // ... other fields ...
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.lastwave.app.playback.MusicPlayerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt
git add app/src/main/java/com/lastwave/app/playback/MusicPlayerState.kt
git add app/src/test/java/com/lastwave/app/playback/MusicPlayerTest.kt
git commit -m "feat(playback): add cache statistics tracking"
```

---

### Task 5: Add Settings UI for Cache Configuration (Optional)

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/lastwave/app/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `CACHE_SIZE_OPTIONS` and `cacheSizeBytes` from settings

- [ ] **Step 1: Write the failing test for settings UI**

```kotlin
class SettingsScreenTest {
    @Test
    fun testCacheSizeSelector() {
        val context = mockk<Context>()
        val settingsPreferences = mockk<SettingsPreferences>()
        val settingsFlow = flowOf(MiscSettings(cacheSizeBytes = 1L * 1024 * 1024 * 1024))
        every { settingsPreferences.settings } returns settingsFlow

        val viewModel = SettingsViewModel(settingsPreferences, mockk(), mockk())
        val state = viewModel.uiState.value

        // Verify cache size options are available
        assertEquals(5, state.cacheSizeOptions.size)
        assertEquals(1L * 1024 * 1024 * 1024, state.selectedCacheSize)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.lastwave.app.ui.settings.SettingsScreenTest"`
Expected: FAIL with "cacheSizeOptions not found"

- [ ] **Step 3: Add settings UI for cache configuration**

```kotlin
// In SettingsScreen.kt
// Add to SettingsUiState
data class SettingsUiState(
    // ... existing fields ...
    val cacheSizeOptions: List<Long> = CACHE_SIZE_OPTIONS,
    val selectedCacheSize: Long = 1L * 1024 * 1024 * 1024,
    // ... other fields ...
)

// In SettingsViewModel.kt
// Add to init block
init {
    viewModelScope.launch {
        settingsPreferences.settings.collect { settings ->
            _uiState.update {
                it.copy(
                    selectedCacheSize = settings.cacheSizeBytes,
                    // ... other fields ...
                )
            }
        }
    }
}

// Add function to update cache size
fun updateCacheSize(size: Long) {
    viewModelScope.launch {
        settingsPreferences.updateCacheSize(size)
    }
}

// In SettingsScreen.kt
// Add cache size selector
@Composable
fun CacheSizeSelector(
    options: List<Long>,
    selected: Long,
    onSizeSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cache Size: ${formatBytes(selected)}")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { size ->
                DropdownMenuItem(
                    text = { Text(formatBytes(size)) },
                    onClick = {
                        onSizeSelected(size)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1L * 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024 * 1024)}GB"
        bytes >= 1L * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
        else -> "${bytes / 1024}KB"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.lastwave.app.ui.settings.SettingsScreenTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt
git add app/src/test/java/com/lastwave/app/ui/settings/SettingsScreenTest.kt
git commit -m "feat(ui): add cache size configuration in settings"
```

---

Plan complete and saved to `docs/superpowers/plans/2026-08-26-song-caching-implementation.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach would you prefer?**

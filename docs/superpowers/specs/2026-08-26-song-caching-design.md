# Song Caching with Configurable Size - Design Document

## Overview

This design document outlines the implementation of configurable song caching with sizes ranging from 400MB to 8GB in the LastWave music player backend. The implementation will use a Least Frequently Used (LFU) eviction policy and be configurable through settings preferences.

## Requirements

1. **Configurable Cache Size**: Allow users to configure cache size between 400MB and 8GB
2. **Eviction Policy**: Use Least Frequently Used (LFU) eviction policy
3. **Configuration Mechanism**: Settings preference with options: 400MB, 1GB, 2GB, 4GB, 8GB
4. **Integration**: Seamlessly integrate with existing playback system
5. **Behavior**: Cache like music streaming services (Spotify/YouTube Music)

## Architecture

The solution will extend the existing caching mechanism in `MusicPlayer` class by:

1. **Adding LFU Cache Evictor**: Create a custom `LeastFrequentlyUsedCacheEvictor` that extends ExoPlayer's cache eviction mechanism
2. **Configurable Cache Size**: Modify the cache initialization to use a configurable size from settings
3. **Settings Integration**: Add cache size preference to `MiscSettings` and provide UI controls
4. **Cache Statistics**: Add basic cache statistics tracking (optional)

## Components

### 1. LeastFrequentlyUsedCacheEvictor

**Responsibility**: Implement LFU eviction policy for the media cache

**Location**: `app/src/main/java/com/lastwave/app/playback/LeastFrequentlyUsedCacheEvictor.kt`

**Key Features**:
- Track access frequency for each cached entry
- Evict least frequently used items when cache exceeds limit
- Maintain O(1) time complexity for access and eviction operations

### 2. Cache Size Configuration

**Responsibility**: Provide configurable cache size through settings

**Location**: `app/src/main/java/com/lastwave/app/data/local/MiscSettings.kt`

**Key Features**:
- Add `cacheSizeBytes` field with predefined options
- Default to 1GB cache size
- Provide validation for cache size range (400MB-8GB)

### 3. MusicPlayer Integration

**Responsibility**: Modify MusicPlayer to use configurable cache

**Location**: `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt`

**Key Changes**:
- Replace hardcoded `MEDIA_STREAM_CACHE_BYTES` with configurable size
- Initialize cache with LFU evictor instead of LRU
- Add cache statistics tracking (optional)

### 4. Settings UI (Optional)

**Responsibility**: Provide UI controls for cache size configuration

**Location**: `app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt`

**Key Features**:
- Dropdown selector for cache size options
- Display current cache usage statistics
- Clear cache button

## Data Flow

```
User → Settings UI → MiscSettings (cacheSizeBytes) → MusicPlayer → LeastFrequentlyUsedCacheEvictor → Media Cache
```

1. User selects cache size in settings
2. Setting is stored in `MiscSettings.cacheSizeBytes`
3. `MusicPlayer` reads the setting and initializes cache with appropriate size
4. `LeastFrequentlyUsedCacheEvictor` manages cache eviction based on access frequency
5. Media streams are cached and retrieved through the cache system

## Implementation Details

### LeastFrequentlyUsedCacheEvictor

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
        // Update frequency and move to end of queue
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
        // Find key with minimum frequency
        // If multiple keys have same frequency, use LRU
        return accessQueue.minByOrNull { frequencyMap[it]!! }
    }
}
```

### Cache Size Configuration

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

### MusicPlayer Integration

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

## Error Handling

1. **Invalid Cache Size**: If an invalid cache size is provided, fall back to default (1GB)
2. **Cache Initialization Failure**: If cache initialization fails, fall back to no caching
3. **Cache Corruption**: If cache becomes corrupted, clear and reinitialize

## Testing Strategy

1. **Unit Tests**: Test `LeastFrequentlyUsedCacheEvictor` with various scenarios
2. **Integration Tests**: Test cache integration with `MusicPlayer`
3. **UI Tests**: Test settings UI for cache configuration (if implemented)
4. **Performance Tests**: Verify cache performance with different sizes

## Migration Plan

1. **Backward Compatibility**: Maintain existing cache behavior as default
2. **Data Migration**: No migration needed as cache is recreated on size change
3. **Feature Flag**: No feature flag needed, will be enabled by default

## Open Questions

1. Should we implement cache warming for popular tracks?
2. Should we add cache statistics to the UI?
3. Should we implement background cache cleanup?

## Success Metrics

1. Cache hit ratio improves with larger cache sizes
2. Track transitions are smoother with cached content
3. No performance degradation with LFU eviction policy
4. User can successfully configure cache size through settings

## Timeline

1. **Design Review**: 1 day
2. **Implementation**: 3-5 days
3. **Testing**: 2 days
4. **Deployment**: 1 day

## Approval

This design is ready for implementation upon approval.

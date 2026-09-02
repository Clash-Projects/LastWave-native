# Changelog

## 2026-09-02 — musaibbhat120605

### Fixed
- **WebM/Opus downloads not showing metadata in external players/file managers.**
  `AudioTagWriter.embedIntoWebm()` previously appended the `Tags`/`Attachments`
  elements at the very end of the file, after all audio `Cluster` data. This
  produced structurally valid EBML, but many players and file managers only
  scan the header region of a WebM/Matroska file (stopping once they reach
  audio data) instead of reading the whole file, so the tags were effectively
  invisible outside the app.

  Metadata is now spliced in right before the first `Cluster`, matching where
  real muxers place it:
  - Any existing `SeekHead` is dropped instead of left with stale offsets —
    compliant readers fall back to a normal sequential scan when it's absent.
  - Any existing `Tags`/`Attachments` elements are removed so duplicates
    aren't left behind.
  - The `Segment` size field is patched to match the new layout.
  - If a `Cues` index is present (rare for YouTube's DASH audio, but possible
    for other muxed sources), the old end-of-file append is used instead,
    since rewriting `Cues` byte offsets safely is out of scope for this fix.

  Files changed: `app/src/main/java/com/lastwave/app/data/download/AudioTagWriter.kt`

### Fixed
- **Home screen (Last.fm) lag / stutter.**
  `HomeUiState.visibleRows()` (filters, day-groups, sorts, and dedupes the
  full track history) was being recomputed inline inside a Compose
  `remember` block, which runs on the UI thread. Last.fm's now-playing and
  recent-tracks polling ticks every 12–30 seconds, so every time a track
  scrobbled in, this fairly expensive rebuild ran right on the frame meant
  to update the screen, causing a visible stutter tied directly to
  scrobbling. On top of that, the "Recent" track list was never capped, so
  it kept growing (and getting more expensive to rebuild) the longer Home
  stayed open in a session.

  - `HomeViewModel` now recomputes the row list on a background dispatcher
    (`Dispatchers.Default`) and exposes it as its own `StateFlow`, so the UI
    thread just collects a finished list instead of building it.
  - The 30-second recent-tracks poll now caps the merged track history at
    500 entries instead of growing it indefinitely.

  Files changed:
  `app/src/main/java/com/lastwave/app/ui/home/HomeViewModel.kt`,
  `app/src/main/java/com/lastwave/app/ui/home/HomeScreen.kt`

### Fixed
- **Downloaded tracks appearing twice in the Downloads list.**
  `TrackDownloadManager.downloadTrack()` had no check against tracks that
  were already downloaded — it only guarded against the *same* download
  running twice concurrently (`activeKeys`), which is cleared as soon as a
  download finishes. Re-downloading a track you already had correctly
  overwrote the file on disk, but `downloadedTrackDao.insert()` always
  created a brand-new row: `DownloadedTrackEntity.id` is an
  autoincrement primary key with no other unique constraint, so
  `OnConflictStrategy.REPLACE` never had anything to actually collide
  with.

  - Added a normalized `trackKey` column (`"${artist}_${title}"`,
    lowercased/trimmed) with a **unique index**, so the database itself
    can no longer hold two rows for the same track.
  - Migration `10 → 11` backfills `trackKey` for existing rows, deletes
    any duplicate rows already present (keeping the most recently
    downloaded copy of each), then creates the unique index.
  - `downloadTrack()` now checks the database first; if the track is
    already downloaded and its file still exists, it skips re-downloading
    entirely instead of re-fetching and duplicating. If the file was
    removed outside the app, it falls through and re-downloads, and the
    unique index makes that insert safely `REPLACE` the stale row instead
    of duplicating it.

  Files changed:
  `app/src/main/java/com/lastwave/app/data/local/db/DownloadedTrackEntity.kt`,
  `app/src/main/java/com/lastwave/app/di/DatabaseModule.kt`,
  `app/src/main/java/com/lastwave/app/data/download/TrackDownloadManager.kt`

### Fixed
- **Skipping to the next track did nothing when playing from Downloads.**
  `DownloadsViewModel.playTrack()` started playback via `MusicPlayer.play()`,
  which always builds a single-track queue (`listOf(track)`) regardless of
  how many tracks are downloaded. So the moment you played anything from the
  Downloads screen, the player's queue had exactly one item — there was
  never a "next" track to advance to, so `next()` correctly found no
  following item and silently did nothing.

  `playTrack()` now builds the full queue from every currently downloaded
  track (in the same order shown on screen), starting at the tapped
  track's position, via `MusicPlayer.playQueue()` instead of `play()`.
  Next/previous now move through the rest of your downloads normally.

  Files changed:
  `app/src/main/java/com/lastwave/app/ui/settings/DownloadsViewModel.kt`

## 2026-09-02 — musaibbhat120605

### Improved
- **Download speed.**
  - Removed a duplicate network call: `downloadTrack()` looked up
    `innerTube.findBestMatch()` once for artwork/album backfill, then called
    it *again* later for the YouTube fallback stream lookup. The result is
    now cached and reused, so this search only ever runs once per track.
  - Qobuz stream resolution now runs concurrently with artwork/metadata
    resolution (`coroutineScope` + `async`) instead of waiting for them to
    finish first — the two are independent, so their network round-trips
    now overlap instead of stacking up serially (previously up to ~7.5s of
    pure latency per track before the transfer even started).
  - Added a concurrency cap (`MAX_CONCURRENT_DOWNLOADS = 3`) via a
    `Semaphore` around each track's full download pipeline, matching the
    pattern already used by the Nocturne project. Uncapped concurrent
    downloads (e.g. downloading a whole playlist) were firing every track's
    network calls simultaneously, competing for the same CDN/API and
    risking throttling — working against overall speed rather than for it.

  Files changed: `app/src/main/java/com/lastwave/app/data/download/TrackDownloadManager.kt`

## 2026-09-02 — musaibbhat120605

### Fixed
- **Downloaded songs missing from the Downloads screen after reinstalling the app.**
  The files were never actually lost — `syncDownloadsFromStorage()` (which
  re-discovers tracks in the public `LastWave` folder after the app's
  database is wiped, e.g. by a reinstall) was silently finding nothing on
  Android 13+. The app targets SDK 35, `READ_EXTERNAL_STORAGE` is correctly
  capped at `maxSdkVersion="32"` for that target, but the replacement
  permission for API 33+, `READ_MEDIA_AUDIO`, was never declared — and the
  app had **no runtime permission-request code at all** for either
  permission, on any Android version. Without it, the OS returns an empty
  directory listing rather than an error, so the scan always came back
  empty and the screen looked like the downloads were gone.

  - Declared `READ_MEDIA_AUDIO` in the manifest.
  - `MainActivity` now requests the correct runtime permission on launch
    (`READ_MEDIA_AUDIO` on API 33+, `READ_EXTERNAL_STORAGE` below that),
    mirroring the existing `POST_NOTIFICATIONS` request pattern.

  Once granted, reopening the Downloads screen re-runs
  `syncDownloadsFromStorage()` and repopulates the list from the files
  already on disk — no re-downloading needed.

  Files changed:
  `app/src/main/AndroidManifest.xml`,
  `app/src/main/java/com/lastwave/app/MainActivity.kt`

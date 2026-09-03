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

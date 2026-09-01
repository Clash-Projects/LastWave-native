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

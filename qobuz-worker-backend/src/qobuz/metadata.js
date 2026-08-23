/**
 * Metadata formatting and Tag Mapping matching metadata.py & downloader.py in qobuz-dl
 */

export const DEFAULT_FOLDER_FORMAT = "{artist} - {album} ({year}) [{bit_depth}B-{sampling_rate}kHz]";
export const DEFAULT_TRACK_FORMAT = "{tracknumber}. {tracktitle}";

/**
 * Sanitize filename for safe cross-platform file saving (Windows/Linux/macOS/Android)
 */
export function sanitizeFilename(input) {
  if (!input || typeof input !== "string") return "unknown";
  return input
    .replace(/[<>:"/\\|?*\x00-\x1F]/g, "_")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 200);
}

/**
 * Clean and format version strings in titles
 */
export function formatTitle(title, version) {
  if (!title) return "";
  if (version && !title.toLowerCase().includes(version.toLowerCase())) {
    return `${title} (${version})`;
  }
  return title;
}

/**
 * Build rich tag object mapping ID3v2.4 / Vorbis comment standards
 */
export function buildAudioTags(trackMeta, albumMeta) {
  const album = albumMeta || trackMeta.album || {};
  const performer = trackMeta.performer || {};
  const composer = trackMeta.composer || {};
  const artist = performer.name || album.artist?.name || "Unknown Artist";
  const albumArtist = album.artist?.name || artist;
  const trackTitle = formatTitle(trackMeta.title, trackMeta.version);
  const albumTitle = formatTitle(album.title, album.version);
  const releaseDate = album.release_date_original || trackMeta.release_date_original || album.release_date_download || "";
  const year = releaseDate ? releaseDate.split("-")[0] : "";

  return {
    title: trackTitle,
    artist: artist,
    albumArtist: albumArtist,
    album: albumTitle,
    trackNumber: trackMeta.track_number || 1,
    totalTracks: album.tracks_count || trackMeta.album?.tracks_count || 1,
    discNumber: trackMeta.media_number || 1,
    totalDiscs: album.media_count || 1,
    year: year,
    releaseDate: releaseDate,
    genre: album.genre?.name || trackMeta.genre?.name || "",
    isrc: trackMeta.isrc || "",
    upc: album.upc || "",
    label: album.label?.name || "",
    copyright: trackMeta.copyright || album.copyright || "",
    composer: composer.name || "",
    audioQuality: {
      bitDepth: trackMeta.maximum_bit_depth || 16,
      samplingRate: trackMeta.maximum_sampling_rate || 44.1,
      isHiRes: Boolean(trackMeta.hires_streamable)
    },
    coverUrl: album.image?.large ? album.image.large.replace("_600.", "_org.") : null,
    coverUrl600: album.image?.large || null
  };
}

/**
 * Format track filename according to pattern (e.g., "01. Artist - Title.flac")
 */
export function formatTrackFilename(trackMeta, albumMeta, pattern = DEFAULT_TRACK_FORMAT, extension = ".flac") {
  const tags = buildAudioTags(trackMeta, albumMeta);
  const trackNumberPadded = String(tags.trackNumber).padStart(2, "0");

  let formatted = pattern
    .replace(/{tracknumber}/gi, trackNumberPadded)
    .replace(/{tracktitle}/gi, tags.title)
    .replace(/{artist}/gi, tags.artist)
    .replace(/{albumartist}/gi, tags.albumArtist)
    .replace(/{album}/gi, tags.album)
    .replace(/{year}/gi, tags.year)
    .replace(/{bit_depth}/gi, String(tags.audioQuality.bitDepth))
    .replace(/{sampling_rate}/gi, String(tags.audioQuality.samplingRate));

  const ext = extension.startsWith(".") ? extension : `.${extension}`;
  return sanitizeFilename(formatted) + ext;
}

/**
 * Format album folder name according to pattern
 */
export function formatAlbumFolderName(albumMeta, pattern = DEFAULT_FOLDER_FORMAT) {
  const artist = albumMeta.artist?.name || "Unknown Artist";
  const albumTitle = formatTitle(albumMeta.title, albumMeta.version);
  const releaseDate = albumMeta.release_date_original || albumMeta.release_date_download || "";
  const year = releaseDate ? releaseDate.split("-")[0] : "";
  const bitDepth = albumMeta.maximum_bit_depth || 16;
  const samplingRate = albumMeta.maximum_sampling_rate || 44.1;

  let formatted = pattern
    .replace(/{artist}/gi, artist)
    .replace(/{album}/gi, albumTitle)
    .replace(/{year}/gi, year)
    .replace(/{bit_depth}/gi, String(bitDepth))
    .replace(/{sampling_rate}/gi, String(samplingRate));

  return sanitizeFilename(formatted);
}

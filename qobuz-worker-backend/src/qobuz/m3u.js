/**
 * M3U Playlist Generator matching utils.py make_m3u in qobuz-dl
 */

import { formatTrackFilename, sanitizeFilename } from "./metadata.js";

/**
 * Generates an M3U playlist file content from tracks and album metadata
 */
export function generateM3U(tracks, albumMeta, options = {}) {
  const { useStreamUrls = false, trackFormat } = options;
  const lines = ["#EXTM3U"];

  for (const track of tracks) {
    const duration = Math.floor(track.duration || 0);
    const artist = track.performer?.name || albumMeta?.artist?.name || "Unknown Artist";
    const title = track.title || "Unknown Title";
    const filename = track.downloadFilename || formatTrackFilename(track, albumMeta, trackFormat, track.extension || ".flac");
    const target = useStreamUrls && track.streamUrl ? track.streamUrl : filename;

    lines.push(`#EXTINF:${duration}, ${artist} - ${title}`);
    lines.push(target);
  }

  return lines.join("\n") + "\n";
}

/**
 * Generates a standard M3U filename for an album or playlist
 */
export function getM3UFilename(title) {
  return `${sanitizeFilename(title || "playlist")}.m3u8`;
}

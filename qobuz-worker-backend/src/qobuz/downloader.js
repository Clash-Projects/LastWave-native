/**
 * Downloader Orchestrator matching downloader.py from qobuz-dl
 * Generates structured download manifests, audio streaming proxies,
 * album bundles, and playlist packages.
 */

import { formatTrackFilename, formatAlbumFolderName, buildAudioTags } from "./metadata.js";
import { generateM3U, getM3UFilename } from "./m3u.js";
import { smartDiscographyFilter } from "./utils.js";

export class Downloader {
  constructor(client) {
    this.client = client;
  }

  /**
   * Prepares a single track download object with stream URL, tags, and formatted filename
   */
  async resolveTrackDownload(trackId, quality = 6, fallback = true, trackFormat) {
    const track = await this.client.getTrack(trackId);
    const stream = await this.client.getTrackUrl(trackId, quality, fallback);
    const isMp3 = Number(stream.format_id) === 5;
    const extension = isMp3 ? ".mp3" : ".flac";
    const mimeType = isMp3 ? "audio/mpeg" : "audio/flac";
    const filename = formatTrackFilename(track, track.album, trackFormat, extension);
    const tags = buildAudioTags(track, track.album);

    return {
      trackId: String(trackId),
      title: track.title,
      artist: tags.artist,
      album: tags.album,
      filename: filename,
      extension: extension,
      mimeType: mimeType,
      quality: {
        formatId: stream.format_id,
        bitDepth: stream.bit_depth || (isMp3 ? 16 : 16),
        samplingRate: stream.sampling_rate || 44.1,
        isHiRes: (stream.bit_depth > 16 || stream.sampling_rate > 44.1)
      },
      streamUrl: stream.url,
      streamRestrictions: stream.restrictions || null,
      tags: tags,
      coverUrl: tags.coverUrl,
      rawTrackMeta: track
    };
  }

  /**
   * Resolves a full album download package with all tracks, booklet, cover art, and M3U playlist
   */
  async resolveAlbumDownload(albumId, quality = 6, fallback = true, folderFormat, trackFormat) {
    const album = await this.client.getAlbum(albumId);
    const folderName = formatAlbumFolderName(album, folderFormat);
    const tracksItems = album.tracks?.items || [];
    
    // Resolve all track stream URLs
    const resolvedTracks = await Promise.all(
      tracksItems.map(async (trackItem) => {
        try {
          const stream = await this.client.getTrackUrl(trackItem.id, quality, fallback);
          const isMp3 = Number(stream.format_id) === 5;
          const extension = isMp3 ? ".mp3" : ".flac";
          const filename = formatTrackFilename(trackItem, album, trackFormat, extension);
          const tags = buildAudioTags(trackItem, album);

          return {
            trackId: String(trackItem.id),
            trackNumber: trackItem.track_number,
            mediaNumber: trackItem.media_number || 1,
            title: trackItem.title,
            artist: tags.artist,
            duration: trackItem.duration,
            filename: filename,
            extension: extension,
            mimeType: isMp3 ? "audio/mpeg" : "audio/flac",
            quality: {
              formatId: stream.format_id,
              bitDepth: stream.bit_depth || (isMp3 ? 16 : 16),
              samplingRate: stream.sampling_rate || 44.1
            },
            streamUrl: stream.url,
            tags: tags
          };
        } catch (err) {
          return {
            trackId: String(trackItem.id),
            trackNumber: trackItem.track_number,
            title: trackItem.title,
            error: err.message || "Failed to resolve stream URL"
          };
        }
      })
    );

    // Booklets / Goodies
    const booklet = album.goodies && album.goodies.length > 0 ? album.goodies[0] : null;
    const coverUrl = album.image?.large ? album.image.large.replace("_600.", "_org.") : null;

    // Generate M3U playlist text
    const successfulTracks = resolvedTracks.filter(t => t.streamUrl);
    const m3uContent = generateM3U(successfulTracks, album, { trackFormat });
    const m3uFilename = getM3UFilename(album.title);

    return {
      albumId: String(albumId),
      albumTitle: album.title,
      artist: album.artist?.name || "Unknown Artist",
      folderName: folderName,
      releaseDate: album.release_date_original || album.release_date_download || "",
      totalTracks: album.tracks_count,
      totalDiscs: album.media_count || 1,
      coverUrl: coverUrl,
      booklet: booklet ? { name: "booklet.pdf", url: booklet.url } : null,
      m3u: {
        filename: m3uFilename,
        content: m3uContent
      },
      tracks: resolvedTracks
    };
  }

  /**
   * Resolves a playlist download package
   */
  async resolvePlaylistDownload(playlistId, quality = 6, fallback = true, trackFormat) {
    const playlist = await this.client.getPlaylist(playlistId, 0, 500);
    const tracksItems = playlist.tracks?.items || [];

    const resolvedTracks = await Promise.all(
      tracksItems.map(async (trackItem) => {
        try {
          const stream = await this.client.getTrackUrl(trackItem.id, quality, fallback);
          const isMp3 = Number(stream.format_id) === 5;
          const extension = isMp3 ? ".mp3" : ".flac";
          const filename = formatTrackFilename(trackItem, trackItem.album, trackFormat, extension);
          const tags = buildAudioTags(trackItem, trackItem.album);

          return {
            trackId: String(trackItem.id),
            trackNumber: trackItem.track_number,
            title: trackItem.title,
            artist: tags.artist,
            album: tags.album,
            duration: trackItem.duration,
            filename: filename,
            extension: extension,
            mimeType: isMp3 ? "audio/mpeg" : "audio/flac",
            quality: {
              formatId: stream.format_id,
              bitDepth: stream.bit_depth || 16,
              samplingRate: stream.sampling_rate || 44.1
            },
            streamUrl: stream.url,
            tags: tags
          };
        } catch (err) {
          return {
            trackId: String(trackItem.id),
            title: trackItem.title,
            error: err.message || "Failed to resolve stream URL"
          };
        }
      })
    );

    const successfulTracks = resolvedTracks.filter(t => t.streamUrl);
    const m3uContent = generateM3U(successfulTracks, { artist: { name: playlist.name } }, { trackFormat });
    const m3uFilename = getM3UFilename(playlist.name);

    return {
      playlistId: String(playlistId),
      playlistName: playlist.name,
      totalTracks: playlist.tracks_count,
      m3u: {
        filename: m3uFilename,
        content: m3uContent
      },
      tracks: resolvedTracks
    };
  }

  /**
   * Resolves an entire artist discography for batch download
   */
  async resolveDiscography(artistId, quality = 6, options = {}) {
    const { smart = true, saveSpace = false, skipExtras = false } = options;
    const artist = await this.client.getArtist(artistId, 0, 500);
    let albums = artist.albums?.items || [];

    if (smart) {
      albums = smartDiscographyFilter(albums, artist.name, { saveSpace, skipExtras });
    }

    return {
      artistId: String(artistId),
      artistName: artist.name,
      totalReleases: albums.length,
      albums: albums.map(album => ({
        id: String(album.id),
        title: album.title,
        version: album.version || null,
        releaseDate: album.release_date_original || album.release_date_download || "",
        bitDepth: album.maximum_bit_depth || 16,
        samplingRate: album.maximum_sampling_rate || 44.1,
        tracksCount: album.tracks_count,
        coverUrl: album.image?.large ? album.image.large.replace("_600.", "_org.") : null,
        downloadManifestUrl: `/api/download/album/${album.id}?quality=${quality}`
      }))
    };
  }

  /**
   * Direct audio streaming proxy supporting Range requests (HTTP 206) and Content-Disposition attachments
   */
  static async proxyAudioStream(request, targetUrl, filename, mimeType = "audio/flac") {
    const rangeHeader = request.headers.get("Range");
    const fetchHeaders = {
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/83.0"
    };

    if (rangeHeader) {
      fetchHeaders["Range"] = rangeHeader;
    }

    const upstreamResponse = await fetch(targetUrl, {
      headers: fetchHeaders
    });

    const responseHeaders = new Headers();
    responseHeaders.set("Content-Type", mimeType);
    responseHeaders.set("Accept-Ranges", "bytes");
    responseHeaders.set("Access-Control-Allow-Origin", "*");
    responseHeaders.set("Access-Control-Allow-Headers", "Range, Content-Type, Authorization, X-App-Id, X-User-Auth-Token");

    if (filename) {
      responseHeaders.set("Content-Disposition", `attachment; filename="${encodeURIComponent(filename)}"`);
    }

    const copyHeaders = ["Content-Length", "Content-Range", "Cache-Control", "ETag", "Last-Modified"];
    for (const h of copyHeaders) {
      const val = upstreamResponse.headers.get(h);
      if (val) {
        responseHeaders.set(h, val);
      }
    }

    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders
    });
  }
}

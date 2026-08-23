/**
 * Utility functions matching qobuz-dl behavior and Worker helpers
 */

export const QUALITIES = {
  5: { id: 5, name: "MP3 320", format: "MP3", bitDepth: 16, maxSampleRate: 44.1 },
  6: { id: 6, name: "FLAC 16-bit / 44.1kHz", format: "FLAC", bitDepth: 16, maxSampleRate: 44.1 },
  7: { id: 7, name: "FLAC 24-bit / <= 96kHz", format: "FLAC", bitDepth: 24, maxSampleRate: 96 },
  27: { id: 27, name: "FLAC 24-bit / > 96kHz", format: "FLAC", bitDepth: 24, maxSampleRate: 192 },
};

/**
 * Parses any Qobuz URL into { type, id }
 * Compatible with play.qobuz.com, open.qobuz.com, qobuz.com/us-en/...
 */
export function parseQobuzUrl(url) {
  if (!url || typeof url !== "string") return null;

  // Regex matching qobuz-dl get_url_info:
  // (?:https:\/\/(?:w{3}|open|play)\.qobuz\.com)?(?:\/[a-z]{2}-[a-z]{2})?\/(album|artist|track|playlist|label)(?:\/[-\w\d]+)?\/([\w\d]+)
  const regex = /(?:https?:\/\/(?:www|open|play)\.qobuz\.com)?(?:\/[a-z]{2}-[a-z]{2})?\/(album|artist|track|playlist|label)(?:\/[^/?#]+)?\/([a-zA-Z0-9_-]+)/i;
  const match = url.match(regex);

  if (match) {
    return {
      type: match[1].toLowerCase(),
      id: match[2]
    };
  }

  // Also check direct queries like "track/12345" or "album/0000000000000"
  const directMatch = url.match(/^(album|artist|track|playlist|label)\/([a-zA-Z0-9_-]+)$/i);
  if (directMatch) {
    return {
      type: directMatch[1].toLowerCase(),
      id: directMatch[2]
    };
  }

  return null;
}

/**
 * Format duration in seconds into HH:MM:SS or MM:SS
 */
export function formatDuration(durationSeconds) {
  if (!durationSeconds || isNaN(durationSeconds)) return "00:00";
  const sec = Math.floor(durationSeconds);
  const hours = Math.floor(sec / 3600);
  const minutes = Math.floor((sec % 3600) / 60);
  const seconds = sec % 60;

  const mm = String(minutes).padStart(2, "0");
  const ss = String(seconds).padStart(2, "0");

  return hours > 0 ? `${String(hours).padStart(2, "0")}:${mm}:${ss}` : `${mm}:${ss}`;
}

/**
 * Transforms standard 600x600 cover art URL to original resolution
 */
export function getOriginalCoverUrl(url) {
  if (!url) return null;
  return url.replace("_600.", "_org.");
}

/**
 * Clean up title text by appending version information if not present
 */
export function getCleanTitle(item) {
  if (!item) return "";
  const title = item.title || "";
  const version = item.version;
  if (version && !title.toLowerCase().includes(version.toLowerCase())) {
    return `${title} (${version})`;
  }
  return title;
}

/**
 * Smart discography filter ported directly from qobuz_dl/utils.py
 * Filters duplicate releases, features, and non-main discography
 */
export function smartDiscographyFilter(albumsList, requestedArtistName, options = {}) {
  if (!Array.isArray(albumsList) || albumsList.length === 0) return [];
  const { saveSpace = false, skipExtras = false } = options;

  const TYPE_REGEXES = {
    remaster: /(re)?master(ed)?/i,
    extra: /(anniversary|deluxe|live|collector|demo|expanded)/i
  };

  function essence(title) {
    if (!title) return "";
    const m = title.match(/^([^([]+)(?:\s*[([].*?[)\]])*/);
    return (m ? m[1] : title).trim().toLowerCase();
  }

  // Group by title essence
  const titleGrouped = new Map();
  for (const album of albumsList) {
    const key = essence(album.title);
    if (!titleGrouped.has(key)) {
      titleGrouped.set(key, []);
    }
    titleGrouped.get(key).push(album);
  }

  const results = [];
  for (const group of titleGrouped.values()) {
    const maxBitDepth = Math.max(...group.map(a => a.maximum_bit_depth || 16));
    const matchingBitDepth = group.filter(a => (a.maximum_bit_depth || 16) === maxBitDepth);
    
    let targetSampleRate;
    if (saveSpace) {
      targetSampleRate = Math.min(...matchingBitDepth.map(a => a.maximum_sampling_rate || 44.1));
    } else {
      targetSampleRate = Math.max(...matchingBitDepth.map(a => a.maximum_sampling_rate || 44.1));
    }

    const hasRemaster = group.some(a => 
      TYPE_REGEXES.remaster.test(`${a.title} ${a.version || ""}`)
    );

    const validAlbums = group.filter(album => {
      const bitMatch = (album.maximum_bit_depth || 16) === maxBitDepth;
      const rateMatch = (album.maximum_sampling_rate || 44.1) === targetSampleRate;
      const artistMatch = !requestedArtistName || 
        (album.artist?.name?.toLowerCase() === requestedArtistName.toLowerCase());

      const fullStr = `${album.title} ${album.version || ""}`;
      const isRemaster = TYPE_REGEXES.remaster.test(fullStr);
      const isExtra = TYPE_REGEXES.extra.test(fullStr);

      if (hasRemaster && !isRemaster) return false;
      if (skipExtras && isExtra) return false;

      return bitMatch && rateMatch && artistMatch;
    });

    if (validAlbums.length > 0) {
      results.push(validAlbums[0]);
    }
  }

  return results;
}

/**
 * Standard JSON response helper with CORS
 */
export function jsonResponse(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-App-Id, X-User-Auth-Token, X-API-Key",
      ...headers
    }
  });
}

/**
 * Handle CORS preflight OPTIONS request
 */
export function handleCors(request, allowedOrigin = "*") {
  const origin = allowedOrigin === "*" ? (request.headers.get("Origin") || "*") : allowedOrigin;
  return new Response(null, {
    status: 204,
    headers: {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-App-Id, X-User-Auth-Token, X-API-Key",
      "Access-Control-Max-Age": "86400"
    }
  });
}

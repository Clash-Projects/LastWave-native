/**
 * Qobuz Cloudflare Worker Backend
 * A public, serverless JavaScript music API and downloader backend powered by
 * clashflac with dynamic token extraction, request signatures, full audio proxying,
 * M3U generation, album/playlist packages, and multi-quality audio streaming.
 */

import { QobuzClient } from "./qobuz/client.js";
import { Downloader } from "./qobuz/downloader.js";
import { BundleScraper } from "./qobuz/bundle.js";
import { 
  QUALITIES, 
  parseQobuzUrl, 
  smartDiscographyFilter, 
  jsonResponse, 
  handleCors,
  getCleanTitle,
  getOriginalCoverUrl
} from "./qobuz/utils.js";
import { generateM3U, getM3UFilename } from "./qobuz/m3u.js";
import { formatTrackFilename, buildAudioTags } from "./qobuz/metadata.js";
import { renderDashboardHtml } from "./ui.js";
import { renderDocsHtml } from "./docs.js";

// Cached client instance for worker execution context
let cachedClient = null;

function getClient(env, request) {
  const url = request ? new URL(request.url) : null;
  const reqAuthToken = request?.headers?.get("X-User-Auth-Token") || url?.searchParams?.get("token");
  const reqAppId = request?.headers?.get("X-App-Id") || url?.searchParams?.get("app_id");

  const appId = reqAppId || env.QOBUZ_APP_ID || "798273057";
  const appSecret = env.QOBUZ_APP_SECRET || null;
  const userAuthToken = reqAuthToken || env.QOBUZ_USER_AUTH_TOKEN || null;
  const email = env.QOBUZ_EMAIL || null;
  const password = env.QOBUZ_PASSWORD || null;

  // If request provided distinct auth token or app ID, instantiate per-request client
  if (reqAuthToken || reqAppId) {
    return new QobuzClient({
      appId,
      appSecret,
      userAuthToken,
      email,
      password
    });
  }

  if (!cachedClient) {
    cachedClient = new QobuzClient({
      appId,
      appSecret,
      userAuthToken,
      email,
      password
    });
  }
  return cachedClient;
}

export default {
  async fetch(request, env, ctx) {
    // 1. Handle CORS Preflight
    if (request.method === "OPTIONS") {
      return handleCors(request, env.ALLOWED_ORIGIN || "*");
    }

    const url = new URL(request.url);
    const path = url.pathname;
    const searchParams = url.searchParams;

    // 2. API Key security check
    const activeApiKey = env.API_AUTH_KEY;

    if (activeApiKey) {
      // Protect all API, stream, download, and data routes
      if (path.startsWith("/api") || path.startsWith("/stream") || path.startsWith("/download")) {
        const secFetchSite = request.headers.get("Sec-Fetch-Site");
        const referer = request.headers.get("Referer");
        const origin = request.headers.get("Origin");

        // Allow web frontend dashboard & interactive playground running on the worker origin
        const isSameOrigin = secFetchSite === "same-origin" ||
          (referer && referer.startsWith(url.origin)) ||
          (origin && origin === url.origin);

        if (!isSameOrigin) {
          const authHeader = request.headers.get("X-API-Key") || request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
          const authParam = searchParams.get("key");
          if (authHeader !== activeApiKey && authParam !== activeApiKey) {
            return jsonResponse({ success: false, error: "Unauthorized: Invalid or missing API Key" }, 401);
          }
        }
      }
    }

    try {
      // 3. Dedicated In-House Documentation Endpoint
      if (path === "/docs") {
        return new Response(renderDocsHtml(url.origin), {
          status: 200,
          headers: {
            "Content-Type": "text/html; charset=UTF-8",
            "Access-Control-Allow-Origin": "*"
          }
        });
      }

      // 4. Interactive Playground UI
      if (path === "/" || path === "/playground" || path === "/ui") {
        const acceptHeader = request.headers.get("Accept") || "";
        const isBrowserOrHtml = acceptHeader.includes("text/html") || searchParams.has("ui") || path === "/ui" || path === "/playground";

        if (isBrowserOrHtml && !searchParams.has("json")) {
          return new Response(renderDashboardHtml(url.origin), {
            status: 200,
            headers: {
              "Content-Type": "text/html; charset=UTF-8",
              "Access-Control-Allow-Origin": "*"
            }
          });
        }
      }

      // 5. Pure JSON API Specification & Health Root (/api or /)
      if (path === "/api" || path === "/") {

        return jsonResponse({
          service: "Qobuz Cloudflare Worker Backend & Downloader",
          version: "2.0.0",
          status: "healthy",
          interactiveUi: `${url.origin}/ui`,
          availableQualities: QUALITIES,
          endpoints: {
            catalog: {
              search: "GET /api/search?q={query}&type={track|album|artist|playlist}&limit={20}",
              track: "GET /api/track/:id",
              album: "GET /api/album/:id",
              artist: "GET /api/artist/:id?smart={true}",
              playlist: "GET /api/playlist/:id",
              label: "GET /api/label/:id",
              resolve: "GET /api/resolve?url={qobuz_url}"
            },
            downloader: {
              trackStream: "GET /api/stream/:id?quality={5|6|7|27}",
              trackDownloadFile: "GET /api/download/track/:id?quality={5|6|7|27}&proxy={true}",
              trackDownloadMeta: "GET /api/track/:id/url?quality={5|6|7|27}",
              albumManifest: "GET /api/download/album/:id?quality={5|6|7|27}",
              playlistManifest: "GET /api/download/playlist/:id?quality={5|6|7|27}",
              discographyManifest: "GET /api/download/discography/:id?quality={5|6|7|27}&smart={true}",
              m3uPlaylistFile: "GET /api/download/m3u?type={album|playlist}&id={id}",
              coverArt: "GET /api/download/cover/:id?size={org|600}",
              batchManifest: "POST /api/download/batch"
            },
            auth: {
              login: "POST /api/auth/login",
              tokens: "GET /api/tokens",
              favorites: "GET /api/user/favorites?type={albums|tracks|artists}",
              userPlaylists: "GET /api/user/playlists"
            }
          }
        });
      }

      // Initialize client and downloader
      const client = getClient(env, request);
      const downloader = new Downloader(client);

      // 4. Token & Scraper Status
      if (path === "/api/tokens") {
        const scraper = new BundleScraper();
        const tokens = await scraper.getTokens();
        return jsonResponse({
          success: true,
          activeAppId: client.appId || tokens.appId,
          activeSecretSet: Boolean(client.appSecret),
          extractedTokens: tokens
        });
      }

      // 5. User Authentication (Login)
      if (path === "/api/auth/login" && request.method === "POST") {
        let body = {};
        try {
          body = await request.json();
        } catch {}
        const email = body.email || env.QOBUZ_EMAIL;
        const password = body.password || env.QOBUZ_PASSWORD;

        if (!email || !password) {
          return jsonResponse({ success: false, error: "Email and password are required" }, 400);
        }

        await client.init();
        const loginRes = await client.login(email, password);
        return jsonResponse({
          success: true,
          userAuthToken: loginRes.user_auth_token,
          user: loginRes.user
        });
      }

      // 6. Search Endpoint
      if (path === "/api/search") {
        const query = searchParams.get("q") || searchParams.get("query");
        if (!query) {
          return jsonResponse({ success: false, error: "Missing search query parameter 'q'" }, 400);
        }
        const type = searchParams.get("type") || "track";
        const limit = parseInt(searchParams.get("limit") || "20", 10);
        const offset = parseInt(searchParams.get("offset") || "0", 10);

        await client.init();
        const results = await client.search(query, type, limit, offset);
        return jsonResponse({ success: true, query, type, results });
      }

      // 7. Track Stream URL (Signed JSON)
      const trackUrlMatch = path.match(/^\/api\/track\/([^/]+)\/url$/);
      if (trackUrlMatch) {
        const trackId = trackUrlMatch[1];
        const quality = parseInt(searchParams.get("quality") || env.DEFAULT_QUALITY || "6", 10);
        const fallback = searchParams.get("fallback") !== "false";

        await client.init();
        const streamData = await client.getTrackUrl(trackId, quality, fallback);
        return jsonResponse({
          success: true,
          trackId,
          quality: QUALITIES[streamData.format_id] || { id: streamData.format_id },
          data: streamData
        });
      }

      // 8. Direct Audio Stream / Playback Redirect (HTTP 302 or direct URL)
      const streamRedirectMatch = path.match(/^\/api\/stream\/([^/]+)$/);
      if (streamRedirectMatch) {
        const trackId = streamRedirectMatch[1];
        const quality = parseInt(searchParams.get("quality") || env.DEFAULT_QUALITY || "6", 10);
        const redirect = searchParams.get("redirect") !== "false";

        await client.init();
        const streamData = await client.getTrackUrl(trackId, quality, true);

        if (!streamData.url) {
          return jsonResponse({ success: false, error: "Stream URL not available or restricted", details: streamData }, 403);
        }

        if (redirect) {
          return Response.redirect(streamData.url, 302);
        }

        return jsonResponse({ success: true, url: streamData.url, info: streamData });
      }

      // 9. Track Direct Downloader (Pipes audio with Content-Disposition & Range support)
      const downloadTrackMatch = path.match(/^\/api\/download\/track\/([^/]+)$/);
      if (downloadTrackMatch) {
        const trackId = downloadTrackMatch[1];
        const quality = parseInt(searchParams.get("quality") || env.DEFAULT_QUALITY || "6", 10);
        const proxy = searchParams.get("proxy") !== "false";
        const fallback = searchParams.get("fallback") !== "false";

        await client.init();
        const resolved = await downloader.resolveTrackDownload(trackId, quality, fallback);

        if (!resolved.streamUrl) {
          return jsonResponse({ success: false, error: "Stream URL not available", details: resolved }, 403);
        }

        if (proxy) {
          return Downloader.proxyAudioStream(request, resolved.streamUrl, resolved.filename, resolved.mimeType);
        }

        return jsonResponse({ success: true, download: resolved });
      }

      // 10. Album Complete Download Package Manifest
      const downloadAlbumMatch = path.match(/^\/api\/download\/album\/([^/]+)$/);
      if (downloadAlbumMatch) {
        const albumId = downloadAlbumMatch[1];
        const quality = parseInt(searchParams.get("quality") || env.DEFAULT_QUALITY || "6", 10);
        const fallback = searchParams.get("fallback") !== "false";

        await client.init();
        const albumPackage = await downloader.resolveAlbumDownload(albumId, quality, fallback);
        return jsonResponse({ success: true, album: albumPackage });
      }

      // 11. Playlist Complete Download Package Manifest
      const downloadPlaylistMatch = path.match(/^\/api\/download\/playlist\/([^/]+)$/);
      if (downloadPlaylistMatch) {
        const playlistId = downloadPlaylistMatch[1];
        const quality = parseInt(searchParams.get("quality") || env.DEFAULT_QUALITY || "6", 10);
        const fallback = searchParams.get("fallback") !== "false";

        await client.init();
        const playlistPackage = await downloader.resolvePlaylistDownload(playlistId, quality, fallback);
        return jsonResponse({ success: true, playlist: playlistPackage });
      }

      // 12. Artist Discography Download Manifest
      const downloadDiscographyMatch = path.match(/^\/api\/download\/discography\/([^/]+)$/);
      if (downloadDiscographyMatch) {
        const artistId = downloadDiscographyMatch[1];
        const quality = parseInt(searchParams.get("quality") || env.DEFAULT_QUALITY || "6", 10);
        const smart = searchParams.get("smart") !== "false";
        const saveSpace = searchParams.get("saveSpace") === "true";
        const skipExtras = searchParams.get("skipExtras") === "true";

        await client.init();
        const discography = await downloader.resolveDiscography(artistId, quality, { smart, saveSpace, skipExtras });
        return jsonResponse({ success: true, discography });
      }

      // 13. Download M3U / M3U8 Playlist File Directly
      if (path === "/api/download/m3u") {
        const type = searchParams.get("type") || "album";
        const itemId = searchParams.get("id");
        const useStreamUrls = searchParams.get("streamUrls") === "true";

        if (!itemId) {
          return jsonResponse({ success: false, error: "Missing 'id' query parameter" }, 400);
        }

        await client.init();
        let m3uContent = "";
        let filename = "playlist.m3u8";

        if (type === "album") {
          const album = await client.getAlbum(itemId);
          filename = getM3UFilename(album.title);
          m3uContent = generateM3U(album.tracks?.items || [], album, { useStreamUrls });
        } else if (type === "playlist") {
          const playlist = await client.getPlaylist(itemId);
          filename = getM3UFilename(playlist.name);
          m3uContent = generateM3U(playlist.tracks?.items || [], { artist: { name: playlist.name } }, { useStreamUrls });
        }

        return new Response(m3uContent, {
          status: 200,
          headers: {
            "Content-Type": "audio/x-mpegurl; charset=UTF-8",
            "Content-Disposition": `attachment; filename="${encodeURIComponent(filename)}"`,
            "Access-Control-Allow-Origin": "*"
          }
        });
      }

      // 14. Cover Art Proxy / Redirect
      const coverArtMatch = path.match(/^\/api\/download\/cover\/([^/]+)$/);
      if (coverArtMatch) {
        const albumId = coverArtMatch[1];
        const size = searchParams.get("size") || "org";
        await client.init();
        const album = await client.getAlbum(albumId);
        let coverUrl = album.image?.large;

        if (size === "org" && coverUrl) {
          coverUrl = coverUrl.replace("_600.", "_org.");
        }

        if (!coverUrl) {
          return jsonResponse({ success: false, error: "Cover art not available" }, 404);
        }

        return Response.redirect(coverUrl, 302);
      }

      // 15. Batch URL / ID Download Resolver
      if (path === "/api/download/batch" && request.method === "POST") {
        let body = {};
        try {
          body = await request.json();
        } catch {}
        const items = body.items || body.urls || [];
        const quality = parseInt(body.quality || env.DEFAULT_QUALITY || "6", 10);

        if (!Array.isArray(items) || items.length === 0) {
          return jsonResponse({ success: false, error: "Request body must include 'items' array of URLs or IDs" }, 400);
        }

        await client.init();
        const results = await Promise.all(
          items.map(async (item) => {
            try {
              const parsed = typeof item === "string" ? parseQobuzUrl(item) : item;
              const type = parsed ? parsed.type : (body.type || "track");
              const id = parsed ? parsed.id : item;

              if (type === "album") {
                return { input: item, success: true, type, data: await downloader.resolveAlbumDownload(id, quality) };
              } else if (type === "playlist") {
                return { input: item, success: true, type, data: await downloader.resolvePlaylistDownload(id, quality) };
              } else {
                return { input: item, success: true, type: "track", data: await downloader.resolveTrackDownload(id, quality) };
              }
            } catch (err) {
              return { input: item, success: false, error: err.message };
            }
          })
        );

        return jsonResponse({ success: true, total: items.length, batch: results });
      }

      // 16. Track Metadata
      const trackMetaMatch = path.match(/^\/api\/track\/([^/]+)$/);
      if (trackMetaMatch) {
        const trackId = trackMetaMatch[1];
        await client.init();
        const track = await client.getTrack(trackId);
        return jsonResponse({
          success: true,
          track: {
            ...track,
            cleanTitle: getCleanTitle(track),
            tags: buildAudioTags(track, track.album),
            formattedFilename: formatTrackFilename(track, track.album),
            originalCoverUrl: getOriginalCoverUrl(track.album?.image?.large)
          }
        });
      }

      // 17. Album Metadata
      const albumMetaMatch = path.match(/^\/api\/album\/([^/]+)$/);
      if (albumMetaMatch) {
        const albumId = albumMetaMatch[1];
        await client.init();
        const album = await client.getAlbum(albumId);
        return jsonResponse({
          success: true,
          album: {
            ...album,
            cleanTitle: getCleanTitle(album),
            originalCoverUrl: getOriginalCoverUrl(album.image?.large)
          }
        });
      }

      // 18. Artist Metadata & Discography
      const artistMetaMatch = path.match(/^\/api\/artist\/([^/]+)$/);
      if (artistMetaMatch) {
        const artistId = artistMetaMatch[1];
        const offset = parseInt(searchParams.get("offset") || "0", 10);
        const limit = parseInt(searchParams.get("limit") || "500", 10);
        const smartFilter = searchParams.get("smart") === "true";

        await client.init();
        const artist = await client.getArtist(artistId, offset, limit);

        if (smartFilter && artist.albums?.items) {
          artist.albums.items = smartDiscographyFilter(artist.albums.items, artist.name, {
            saveSpace: searchParams.get("saveSpace") === "true",
            skipExtras: searchParams.get("skipExtras") === "true"
          });
        }

        return jsonResponse({ success: true, artist });
      }

      // 19. Playlist Metadata
      const playlistMetaMatch = path.match(/^\/api\/playlist\/([^/]+)$/);
      if (playlistMetaMatch) {
        const playlistId = playlistMetaMatch[1];
        const offset = parseInt(searchParams.get("offset") || "0", 10);
        const limit = parseInt(searchParams.get("limit") || "500", 10);

        await client.init();
        const playlist = await client.getPlaylist(playlistId, offset, limit);
        return jsonResponse({ success: true, playlist });
      }

      // 20. Label Metadata
      const labelMetaMatch = path.match(/^\/api\/label\/([^/]+)$/);
      if (labelMetaMatch) {
        const labelId = labelMetaMatch[1];
        const offset = parseInt(searchParams.get("offset") || "0", 10);
        const limit = parseInt(searchParams.get("limit") || "500", 10);

        await client.init();
        const label = await client.getLabel(labelId, offset, limit);
        return jsonResponse({ success: true, label });
      }

      // 21. Universal URL Resolver
      if (path === "/api/resolve") {
        const targetUrl = searchParams.get("url");
        if (!targetUrl) {
          return jsonResponse({ success: false, error: "Missing 'url' query parameter" }, 400);
        }

        const parsed = parseQobuzUrl(targetUrl);
        if (!parsed) {
          return jsonResponse({ success: false, error: "Invalid Qobuz URL format" }, 400);
        }

        await client.init();
        let entityData = null;

        switch (parsed.type) {
          case "track":
            entityData = await client.getTrack(parsed.id);
            break;
          case "album":
            entityData = await client.getAlbum(parsed.id);
            break;
          case "artist":
            entityData = await client.getArtist(parsed.id);
            break;
          case "playlist":
            entityData = await client.getPlaylist(parsed.id);
            break;
          case "label":
            entityData = await client.getLabel(parsed.id);
            break;
        }

        return jsonResponse({
          success: true,
          type: parsed.type,
          id: parsed.id,
          data: entityData
        });
      }

      // 22. User Favorites
      if (path === "/api/user/favorites") {
        const type = searchParams.get("type") || "albums";
        const limit = parseInt(searchParams.get("limit") || "50", 10);
        const offset = parseInt(searchParams.get("offset") || "0", 10);

        await client.init();
        const favorites = await client.getUserFavorites(type, limit, offset);
        return jsonResponse({ success: true, favorites });
      }

      // 23. User Playlists
      if (path === "/api/user/playlists") {
        const limit = parseInt(searchParams.get("limit") || "50", 10);
        const offset = parseInt(searchParams.get("offset") || "0", 10);

        await client.init();
        const playlists = await client.getUserPlaylists(limit, offset);
        return jsonResponse({ success: true, playlists });
      }

      // 404 Route Not Found
      return jsonResponse({ success: false, error: `Route not found: ${path}` }, 404);

    } catch (err) {
      console.error("Worker Execution Error:", err);
      return jsonResponse({
        success: false,
        error: err.message || "Internal Server Error",
        status: err.status || 500,
        data: err.data || null
      }, err.status || 500);
    }
  }
};

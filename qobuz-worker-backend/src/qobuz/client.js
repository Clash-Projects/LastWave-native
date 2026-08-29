/**
 * Full Qobuz API Client ported from qopy.py & core.py
 * Supports signed streaming URL generation, search, metadata extraction,
 * auto-secret testing, and quality fallback.
 */

import { signTrackStreamUrl, signUserFavorites } from "./signature.js";
import { BundleScraper } from "./bundle.js";

const BASE_API_URL = "https://www.qobuz.com/api.json/0.2/";
const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:83.0) Gecko/20100101 Firefox/83.0";

export class QobuzClient {
  constructor(options = {}) {
    this.appId = options.appId || null;
    this.appSecret = options.appSecret || null;
    this.secrets = options.secrets || [];
    this.userAuthToken = options.userAuthToken || null;
    this.userEmail = options.email || null;
    this.userPassword = options.password || null;
    this.membershipLabel = null;
  }

  /**
   * Initializes tokens and secrets if not provided
   */
  async init() {
    if (!this.appId || (!this.appSecret && this.secrets.length === 0)) {
      const scraper = new BundleScraper();
      const tokens = await scraper.getTokens();
      this.appId = this.appId || tokens.appId;
      this.secrets = this.secrets.length > 0 ? this.secrets : tokens.secrets;
    }

    if (this.appSecret) {
      this.secrets = [this.appSecret, ...this.secrets.filter(s => s !== this.appSecret)];
    }

    // If email and password provided, perform auth
    if (this.userEmail && this.userPassword && !this.userAuthToken) {
      await this.login(this.userEmail, this.userPassword);
    }

    // Determine active working secret if not already set
    if (!this.appSecret && this.secrets.length > 0) {
      await this.findWorkingSecret();
    }
  }

  /**
   * Core low-level API call handler
   */
  async apiCall(endpoint, params = {}, options = {}) {
    const url = new URL(`${BASE_API_URL}${endpoint}`);
    const queryParams = new URLSearchParams();

    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        queryParams.set(key, String(value));
      }
    }

    // Ensure app_id is passed if not already present
    if (!queryParams.has("app_id") && this.appId) {
      queryParams.set("app_id", this.appId);
    }

    url.search = queryParams.toString();

    const headers = {
      "User-Agent": USER_AGENT,
      "X-App-Id": this.appId,
      "Content-Type": "application/json;charset=UTF-8"
    };

    if (this.userAuthToken) {
      headers["X-User-Auth-Token"] = this.userAuthToken;
    }

    const response = await fetch(url.toString(), {
      method: options.method || "GET",
      headers: {
        ...headers,
        ...(options.headers || {})
      }
    });

    const responseText = await response.text();
    let data;
    try {
      data = JSON.parse(responseText);
    } catch {
      data = { raw: responseText };
    }

    if (!response.ok) {
      const errorMsg = data.message || data.error || `HTTP ${response.status} ${response.statusText}`;
      const err = new Error(`Qobuz API Error (${endpoint}): ${errorMsg}`);
      err.status = response.status;
      err.data = data;
      throw err;
    }

    return data;
  }

  /**
   * Logs into Qobuz user account
   */
  async login(email, password) {
    const result = await this.apiCall("user/login", {
      email,
      password,
      app_id: this.appId
    });

    this.userAuthToken = result.user_auth_token;
    this.membershipLabel = result.user?.credential?.parameters?.short_label || "Active";
    return result;
  }

  /**
   * Tests a secret with a sample stream URL request
   */
  async testSecret(secret) {
    try {
      const signed = signTrackStreamUrl("5966783", 5, secret);
      const res = await this.apiCall("track/getFileUrl", signed);
      return Boolean(res && (res.url || res.sample !== undefined));
    } catch {
      return false;
    }
  }

  /**
   * Finds the first working app secret from the pool
   */
  async findWorkingSecret() {
    for (const secret of this.secrets) {
      if (!secret) continue;
      const isValid = await this.testSecret(secret);
      if (isValid) {
        this.appSecret = secret;
        return secret;
      }
    }

    // Default to the first available secret if test failed or restricted
    if (this.secrets.length > 0) {
      this.appSecret = this.secrets[0];
      return this.appSecret;
    }

    throw new Error("No valid Qobuz app secret found");
  }

  /**
   * Track Metadata
   */
  async getTrack(trackId) {
    return this.apiCall("track/get", { track_id: trackId });
  }

  /**
   * Album Metadata
   */
  async getAlbum(albumId) {
    return this.apiCall("album/get", { album_id: albumId });
  }

  /**
   * Artist Metadata and Releases
   */
  async getArtist(artistId, offset = 0, limit = 500) {
    return this.apiCall("artist/get", {
      artist_id: artistId,
      extra: "albums",
      offset,
      limit
    });
  }

  /**
   * Playlist Metadata and Tracks
   */
  async getPlaylist(playlistId, offset = 0, limit = 500) {
    return this.apiCall("playlist/get", {
      playlist_id: playlistId,
      extra: "tracks",
      offset,
      limit
    });
  }

  /**
   * Label Metadata and Releases
   */
  async getLabel(labelId, offset = 0, limit = 500) {
    return this.apiCall("label/get", {
      label_id: labelId,
      extra: "albums",
      offset,
      limit
    });
  }

  /**
   * Search across all or specific types
   */
  async search(query, type = "track", limit = 20, offset = 0) {
    const endpoints = {
      track: "track/search",
      album: "album/search",
      artist: "artist/search",
      playlist: "playlist/search"
    };

    const endpoint = endpoints[type] || "track/search";
    return this.apiCall(endpoint, {
      query,
      limit,
      offset
    });
  }

  /**
   * Get Signed Stream / Download URL for a track
   * Supports format_id fallback (27 -> 7 -> 6 -> 5)
   */
  async getTrackUrl(trackId, formatId = 6, fallback = true) {
    const requestedFmt = Number(formatId) || 6;
    const allowedFormats = [5, 6, 7, 27];
    if (!allowedFormats.includes(requestedFmt)) {
      throw new Error(`Invalid quality id ${requestedFmt}: choose between 5 (MP3), 6 (16-bit FLAC), 7 (24-bit 96k FLAC), or 27 (24-bit 192k FLAC)`);
    }

    if (!this.appSecret) {
      await this.init();
    }

    // Try requested format first
    const signedParams = signTrackStreamUrl(trackId, requestedFmt, this.appSecret);
    try {
      const result = await this.apiCall("track/getFileUrl", signedParams);
      
      // If result contains restrictions or is not streamable and fallback enabled, try lower qualities
      const isRestricted = Array.isArray(result.restrictions) && 
        result.restrictions.some(r => r.code === "FormatRestrictedByFormatAvailability");

      if (isRestricted && fallback && requestedFmt > 5) {
        const fallbackQualities = [7, 6, 5].filter(q => q < requestedFmt);
        for (const fbQuality of fallbackQualities) {
          try {
            const fbSigned = signTrackStreamUrl(trackId, fbQuality, this.appSecret);
            const fbResult = await this.apiCall("track/getFileUrl", fbSigned);
            if (fbResult && fbResult.url) {
              return { ...fbResult, fallbackFrom: requestedFmt, format_id: fbQuality };
            }
          } catch {
            // continue down ladder
          }
        }
      }

      return result;
    } catch (err) {
      // If signature invalid, auto-refresh secrets from BundleScraper and retry
      if (err.message && err.message.includes("Invalid Request Signature parameter")) {
        try {
          const scraper = new BundleScraper();
          const tokens = await scraper.getTokens();
          this.secrets = [...new Set([...(this.secrets || []), ...tokens.secrets])].filter(Boolean);
          await this.findWorkingSecret();
          if (this.appSecret) {
            const retrySigned = signTrackStreamUrl(trackId, requestedFmt, this.appSecret);
            return await this.apiCall("track/getFileUrl", retrySigned);
          }
        } catch {}
      }

      // If error occurs and fallback requested, step down qualities
      if (fallback && requestedFmt > 5) {
        const fallbackQualities = [7, 6, 5].filter(q => q < requestedFmt);
        for (const fbQuality of fallbackQualities) {
          try {
            const fbSigned = signTrackStreamUrl(trackId, fbQuality, this.appSecret);
            const fbResult = await this.apiCall("track/getFileUrl", fbSigned);
            if (fbResult && fbResult.url) {
              return { ...fbResult, fallbackFrom: requestedFmt, format_id: fbQuality };
            }
          } catch {
            // continue
          }
        }
      }
      throw err;
    }
  }

  /**
   * User Favorites (signed endpoint)
   */
  async getUserFavorites(type = "albums", limit = 50, offset = 0) {
    if (!this.userAuthToken) {
      throw new Error("Authentication required: user_auth_token is missing");
    }
    const signedParams = signUserFavorites(this.appId, this.userAuthToken, type, this.appSecret);
    return this.apiCall("favorite/getUserFavorites", {
      ...signedParams,
      limit,
      offset
    });
  }

  /**
   * User Playlists
   */
  async getUserPlaylists(limit = 50, offset = 0) {
    if (!this.userAuthToken) {
      throw new Error("Authentication required: user_auth_token is missing");
    }
    return this.apiCall("playlist/getUserPlaylists", {
      limit,
      offset
    });
  }
}

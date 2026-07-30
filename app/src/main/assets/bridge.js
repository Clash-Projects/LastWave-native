/* ════════════════════════════════════════════════════════════
   bridge.js — Platform Abstraction Layer
   v1.0

   Single source of truth for all communication between the
   web UI and the Android native layer (AndroidBridge).

   Rules:
     • All window.AndroidBridge checks live HERE. Nowhere else.
     • app.js and playlist.js call Platform.*  — they never
       touch window.AndroidBridge directly.
     • Platform.showToast()       → web toast always (UI feedback)
     • Platform.showNativeToast() → Android native toast only
                                    (system-level / pre-page-load use)

   Load order in index.html:
     <script src="js/bridge.js"></script>   ← must come first
     <script src="js/app.js"></script>
     <script src="js/playlist.js"></script>
   ════════════════════════════════════════════════════════════ */

'use strict';

const Platform = {

  // ── Environment ───────────────────────────────────────────────

  /** True when running inside the LastWave Android WebView. */
  isNative: () => typeof window.AndroidBridge !== 'undefined',


  // ── File Handling ─────────────────────────────────────────────

  /**
   * Save a file to disk.
   * Native : delegates to AndroidBridge.saveFile() — writes to device storage.
   * Web    : triggers a browser download via a Blob URL.
   *
   * JS call: Platform.saveFile(filename, content, mimeType)
   */
  saveFile(filename, content, mimeType) {
    if (Platform.isNative()) {
      window.AndroidBridge.saveFile(filename, content, mimeType);
      // Note: the Java side shows its own native toast ("Saved: filename").
      // No web toast here to avoid the double-toast bug.
      return;
    }

    // Web fallback: force-download via hidden <a>
    const blob = new Blob([content], { type: mimeType });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
    Platform.showToast('File downloaded ✓', 'success');
  },

  /**
   * Share a file via the platform share sheet.
   * Native : delegates to AndroidBridge.shareFileContent() — uses FileProvider.
   * Web    : attempts Web Share API (file), then text share, then clipboard.
   *
   * JS call: Platform.shareFile(filename, content, mimeType, fallbackText, fallbackTitle)
   *
   * @param {string} filename      - e.g. "my_playlist.csv"
   * @param {string} content       - file content as a string
   * @param {string} mimeType      - e.g. "text/csv" | "audio/x-mpegurl"
   * @param {string} fallbackText  - plain-text representation (used when file share unavailable)
   * @param {string} fallbackTitle - title shown in the share sheet
   */
  async shareFile(filename, content, mimeType, fallbackText, fallbackTitle) {
    if (Platform.isNative()) {
      // FIX: was incorrectly calling sharePlaylistFile (non-existent).
      // Correct Java method name is shareFileContent.
      window.AndroidBridge.shareFileContent(filename, content, mimeType);
      return;
    }

    // Web fallback 1: Web Share API with a real File object (modern mobile browsers)
    if (navigator.share && typeof File !== 'undefined') {
      try {
        const blob = new Blob([content], { type: mimeType });
        const file = new File([blob], filename, { type: mimeType });
        if (navigator.canShare && navigator.canShare({ files: [file] })) {
          await navigator.share({ files: [file], title: fallbackTitle });
          return;
        }
      } catch (e) {
        if (e.name === 'AbortError') return; // User cancelled — stop, don't fall through
        // Any other error: fall through to text share
      }
    }

    // Web fallback 2: Web Share API with plain text
    if (navigator.share && fallbackText) {
      try {
        await navigator.share({ title: fallbackTitle, text: fallbackText });
        return;
      } catch (e) {
        if (e.name === 'AbortError') return;
      }
    }

    // Web fallback 3: Clipboard copy
    if (fallbackText) {
      Platform.copyText(fallbackText);
    }
  },

  /**
   * Copy text to the clipboard.
   * Attempts navigator.clipboard first, falls back to execCommand for
   * older Android WebViews.
   *
   * JS call: Platform.copyText(text)
   */
  async copyText(text) {
    let copied = false;

    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        copied = true;
      } catch { /* fall through to execCommand */ }
    }

    if (!copied) {
      const ta       = document.createElement('textarea');
      ta.value       = text;
      ta.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;pointer-events:none';
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      try { document.execCommand('copy'); copied = true; } catch {}
      ta.remove();
    }

    Platform.showToast(
      copied ? 'Copied to clipboard ✓' : 'Could not copy text',
      copied ? 'success' : 'error'
    );
  },

  /**
   * Share plain text via the platform share sheet.
   * Unlike shareFile(), this sends no file — just a text/plain intent.
   * Native : delegates to AndroidBridge.shareText()
   * Web    : attempts Web Share API, falls back to clipboard.
   *
   * JS call: Platform.shareText(text, title)
   */
  shareText(text, title) {
    if (Platform.isNative()) {
      window.AndroidBridge.shareText(text, title);
      return;
    }

    // Web fallback 1: Web Share API
    if (navigator.share) {
      navigator.share({ title, text }).catch(e => {
        if (e.name !== 'AbortError') Platform.copyText(text);
      });
      return;
    }

    // Web fallback 2: clipboard
    Platform.copyText(text);
  },


  // ── Toast ─────────────────────────────────────────────────────

  /**
   * Show the in-app web toast. Always use this for UI feedback.
   * Requires a #toast element in the DOM.
   *
   * JS call: Platform.showToast(msg, type)
   * @param {string} msg  - message to display
   * @param {string} type - optional CSS modifier: 'success' | 'error'
   */
  _toastTimer: null,
  showToast(msg, type) {
    const el = document.getElementById('toast');
    if (!el) return;
    el.textContent = msg;
    el.className   = 'toast show' + (type ? ' ' + type : '');
    if (Platform._toastTimer) clearTimeout(Platform._toastTimer);
    Platform._toastTimer = setTimeout(() => el.classList.remove('show'), 3000);
  },

  /**
   * Show the Android native system toast.
   * Use only for system-level events or scenarios where the web UI
   * may not yet be rendered (e.g. very early init).
   * Silently ignored when running in a browser.
   *
   * JS call: Platform.showNativeToast(msg)
   */
  showNativeToast(msg) {
    if (Platform.isNative() && window.AndroidBridge.showToast) {
      window.AndroidBridge.showToast(msg);
    }
  },


  // ── Browser / Navigation ──────────────────────────────────────

  /**
   * Open a URL in the system browser (or a new tab in-browser).
   * FIX: the old openUrl() in app.js only called showToast via the bridge
   * but always used window.open() — never actually calling AndroidBridge.openUrl.
   *
   * JS call: Platform.openBrowser(url)
   */
  openBrowser(url) {
    if (Platform.isNative() && window.AndroidBridge.openUrl) {
      window.AndroidBridge.openUrl(url);
    } else {
      window.open(url, '_blank');
    }
  },

  /**
   * Open the Last.fm auth URL in Chrome Custom Tabs (native) or a new tab.
   * Kept separate from openBrowser() because auth requires the user's real
   * browser session and cookies — never the in-app WebView.
   *
   * JS call: Platform.openAuthBrowser(url)
   */
  openAuthBrowser(url) {
    if (Platform.isNative() && window.AndroidBridge.openAuthBrowser) {
      window.AndroidBridge.openAuthBrowser(url);
    } else {
      window.open(url, '_blank');
    }
  },


  // ── Last.fm Session Persistence ───────────────────────────────

  /**
   * Returns the Last.fm session key stored in Android SharedPreferences.
   * Returns '' if not native or no key is stored.
   *
   * JS call: Platform.getSavedSessionKey()
   */
  getSavedSessionKey() {
    try {
      return (Platform.isNative() && window.AndroidBridge.getSavedSessionKey)
        ? window.AndroidBridge.getSavedSessionKey()
        : '';
    } catch { return ''; }
  },

  /**
   * Persist the Last.fm session key to Android SharedPreferences.
   *
   * JS call: Platform.saveSessionKey(key)
   */
  saveSessionKey(key) {
    try {
      if (Platform.isNative() && window.AndroidBridge.saveSessionKey) {
        window.AndroidBridge.saveSessionKey(key);
      }
    } catch {}
  },

  /**
   * Clear the stored Last.fm session key (sign-out).
   *
   * JS call: Platform.clearSession()
   */
  clearSession() {
    try {
      if (Platform.isNative() && window.AndroidBridge.clearSession) {
        window.AndroidBridge.clearSession();
      }
    } catch {}
  },


  // ── Material You / Theming ────────────────────────────────────

  /**
   * Returns the device wallpaper colours as { primary, secondary, tertiary }
   * for Material You dynamic theming.
   * Returns null when not available (browser, or Android < 8.1).
   *
   * Native side: AndroidBridge.getMaterialYouColors() — on Android 12+ this
   * reads the real Monet-generated system_accent1/2/3 color slots (the same
   * ones dynamicDarkColorScheme() / DynamicColors use), not raw wallpaper
   * pixels. On Android 8.1–11 it falls back to WallpaperManager's
   * WallpaperColors as a best-effort approximation.
   *
   * JS call: Platform.getWallpaperColors()
   */
  getWallpaperColors() {
    if (Platform.isNative() && window.AndroidBridge.getMaterialYouColors) {
      try {
        const raw = window.AndroidBridge.getMaterialYouColors();
        return raw ? JSON.parse(raw) : null;
      } catch { return null; }
    }
    return null;
  },


  // ── System Insets (edge-to-edge) ──────────────────────────────

  /**
   * Applies system bar inset sizes as CSS variables on <html>.
   * Called by Java's ViewCompat inset listener, and also on page
   * load via initSystemInsets() below.
   *
   * --inset-top    : height of the status bar in px
   * --inset-bottom : height of the navigation bar in px
   */
  applySystemInsets(top, bottom) {
    const R = document.documentElement;
    R.style.setProperty('--inset-top',    top    + 'px');
    R.style.setProperty('--inset-bottom', bottom + 'px');
  },

  /**
   * Requests insets from the bridge on page load.
   * Covers the race where the Java inset listener fired before JS loaded.
   * Falls back to 0 in a browser (safe — bars are not transparent there).
   *
   * JS call: Platform.initSystemInsets()
   */
  initSystemInsets() {
    if (Platform.isNative() && window.AndroidBridge.getSystemInsets) {
      try {
        const raw = window.AndroidBridge.getSystemInsets();
        const { top, bottom } = JSON.parse(raw);
        Platform.applySystemInsets(top, bottom);
      } catch { Platform.applySystemInsets(0, 0); }
    } else {
      Platform.applySystemInsets(0, 0);
    }
  },

};

// ── Global callback for Java's ViewCompat inset listener ──────────────────────
// Java calls: window._applySystemInsets(top, bottom)
window._applySystemInsets = (top, bottom) => Platform.applySystemInsets(top, bottom);

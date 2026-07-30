/* ════════════════════════════════════════════════════════════
   settings.js — Settings screen logic  ·  Material You
   ════════════════════════════════════════════════════════════ */

'use strict';

// ─────────────────────────────────────────────────────────────
//  Color-wheel ephemeral state
// ─────────────────────────────────────────────────────────────
let _cwHue        = 0;
let _cwSat        = 0.70;
let _cwDragging   = false;
let _cwCtx        = null;
let _cwBound      = false;

// ── Cached wheel bitmap + RAF throttle ───────────────────────
// The static HSL disc is rendered ONCE into _cwWheelCache.
// Every drag frame: clearRect → blit cache (O(1) GPU copy) → draw cursor.
// clearRect is MANDATORY — without it, cursor strokes outside the disc's
// alpha coverage leave ghost rings across frames.
let _cwWheelCache = null;
let _cwAnimFrame  = null;
let _cwLastX      = 0;
let _cwLastY      = 0;
let _cwDragRect   = null;   // getBoundingClientRect cached during drag

// Geometry constants (CSS px — multiplied by DPR at draw time).
// _CW_EDGE_PAD must be ≥ _CW_CURSOR_R + half max stroke width so the
// ring is always fully within the canvas buffer (no clipping).
const _CW_CURSOR_R = 10;   // cursor ring radius (CSS px)
const _CW_EDGE_PAD = 16;   // inset from canvas edge to wheel rim (CSS px)

// ═════════════════════════════════════════════════════════════
//  SCREEN ENTRY POINT
// ═════════════════════════════════════════════════════════════
window.screen_settings = function () {
  // Register hardware-back handler so the Android back button
  // and any navigation pop returns via the history stack.
  // No top back button is injected and the bottom nav stays visible.
  window._lwScreenBackHandlers['settings'] = function () {
    _goBack();
    return true;
  };

  const elUser   = document.getElementById('settingsUsername');
  const elKey    = document.getElementById('settingsApiKey');
  const elSecret = document.getElementById('settingsApiSecret');
  if (elUser)   elUser.value   = state.username   || '';
  if (elKey)    elKey.value    = state.apiKey      || '';
  if (elSecret) elSecret.value = state.apiSecret   || '';

  _refreshToggles();
  _refreshPaletteActive();
  _refreshSeenCount();
  _refreshApiSection();
};

// ─────────────────────────────────────────────────────────────
//  Back navigation
// ─────────────────────────────────────────────────────────────
function _goBack() {
  document.body.classList.remove('settings-open');
  delete window._lwScreenBackHandlers['settings'];
  // Pop nav history stack if possible, otherwise fall back to home
  if (typeof _navHistory !== 'undefined' && _navHistory.length > 0) {
    const prev = _navHistory.pop();
    navigateTo(prev, { isBack: true });
  } else {
    navigateTo('home', { isBack: true });
  }
}

// ─────────────────────────────────────────────────────────────
//  Refresh helpers
// ─────────────────────────────────────────────────────────────
function _refreshToggles() {
  _setToggle('amoledToggle',        document.body.classList.contains('amoled-mode'));
  _setToggle('dynamicThemeToggle',  state.accentMode === 'dynamic');
  _setToggle('itunesArtworkToggle', localStorage.getItem('lw_use_itunes') !== '0');
  _setToggle('lbzArtworkToggle',    localStorage.getItem('lw_use_lbz')    !== '0');
}

function _setToggle(id, active) {
  const el = document.getElementById(id);
  if (el) el.classList.toggle('active', !!active);
}

function _refreshPaletteActive() {
  document.querySelectorAll('.palette-card').forEach(c => c.classList.remove('active'));
  const mode = state.accentMode;
  if (mode === 'monochrome') {
    const mono = document.getElementById('paletteMono');
    if (mono) mono.classList.add('active');
    return;
  }
  if (mode === 'custom') {
    const custom = document.getElementById('paletteCustom');
    if (custom) custom.classList.add('active');
    return;
  }
  if (mode === 'dynamic') return;
  if (state.accentColor) {
    const { h } = _settHexToHsl(state.accentColor);
    let bestCard = null, bestDiff = 999;
    document.querySelectorAll('.palette-card[data-hue]').forEach(card => {
      const hue  = parseInt(card.dataset.hue, 10);
      const diff = Math.min(Math.abs(hue - h), 360 - Math.abs(hue - h));
      if (diff < bestDiff) { bestDiff = diff; bestCard = card; }
    });
    if (bestCard && bestDiff < 30) bestCard.classList.add('active');
  }
}

function _refreshSeenCount() {
  const sc = document.getElementById('seenCount');
  if (sc) sc.textContent = getSeenTracksCount().toLocaleString();
}

// ─────────────────────────────────────────────────────────────
//  API section — show inputs OR logged-in state
// ─────────────────────────────────────────────────────────────
function _refreshApiSection() {
  const loggedIn   = !!(state.username && state.apiKey && state.apiSecret);
  const inputsEl   = document.getElementById('apiInputsSection');
  const loggedInEl = document.getElementById('apiLoggedInSection');
  const headerEl   = document.getElementById('apiConfigHeader');
  const userLabel  = document.getElementById('apiLoggedInUser');
  if (inputsEl)   inputsEl.classList.toggle('hidden', loggedIn);
  if (loggedInEl) loggedInEl.classList.toggle('hidden', !loggedIn);
  if (headerEl)   headerEl.classList.toggle('hidden', loggedIn);
  if (userLabel)  userLabel.textContent = state.username || '';
}

function logoutApiCredentials() {
  showModal(
    'Remove API Credentials?',
    'This will clear your username, API key and secret. Playlists and history are kept.',
    () => {
      state.username  = '';
      state.apiKey    = '';
      state.apiSecret = '';
      localStorage.removeItem('lw_username');
      localStorage.removeItem('lw_apikey');
      localStorage.removeItem('lw_apisecret');
      const elUser   = document.getElementById('settingsUsername');
      const elKey    = document.getElementById('settingsApiKey');
      const elSecret = document.getElementById('settingsApiSecret');
      if (elUser)   elUser.value   = '';
      if (elKey)    elKey.value    = '';
      if (elSecret) elSecret.value = '';
      _refreshApiSection();
      showToast('Credentials removed', 'success');
    }
  );
}

window.screen_settings_refreshAuth = function () {
  if (state.currentPage !== 'settings') return;
  _refreshToggles();
  _refreshPaletteActive();
};

// ═════════════════════════════════════════════════════════════
//  PUBLIC HANDLERS
// ═════════════════════════════════════════════════════════════

function saveApiCredentials() {
  const u  = (document.getElementById('settingsUsername')?.value  || '').trim();
  const ak = (document.getElementById('settingsApiKey')?.value    || '').trim();
  const as = (document.getElementById('settingsApiSecret')?.value || '').trim();
  if (!u)  { showToast('Username is required',    'error'); return; }
  if (!ak) { showToast('API Key is required',     'error'); return; }
  if (!as) { showToast('API Secret is required',  'error'); return; }
  state.username   = u;
  state.apiKey     = ak;
  state.apiSecret  = as;
  localStorage.setItem('lw_username',  u);
  localStorage.setItem('lw_apikey',    ak);
  localStorage.setItem('lw_apisecret', as);
  showToast('API credentials saved \u2713', 'success');
  _refreshApiSection();
}

function toggleAmoled() {
  const isOn = document.body.classList.toggle('amoled-mode');
  localStorage.setItem('lw_amoled', isOn ? '1' : '0');
  _setToggle('amoledToggle', isOn);
  if (state.accentMode === 'monochrome') {
    _applyMonochromeScheme();
  } else {
    applyAccent(state.accentColor, state.accentLight, false);
  }
}

function toggleDynamicTheme() {
  if (state.accentMode === 'dynamic') {
    state.accentMode = 'manual';
    localStorage.setItem('lw_accentMode', 'manual');
    applyAccent(state.accentColor, state.accentLight, false);
    _setToggle('dynamicThemeToggle', false);
    _refreshPaletteActive();
  } else {
    const ok = applyDynamicAccent(/*save=*/true);
    _setToggle('dynamicThemeToggle', ok);
    if (ok) _refreshPaletteActive();
  }
}

function toggleItunesArtwork() {
  const wasOn = localStorage.getItem('lw_use_itunes') !== '0';
  const next  = !wasOn;
  localStorage.setItem('lw_use_itunes', next ? '1' : '0');
  _setToggle('itunesArtworkToggle', next);
}

function toggleLbzArtwork() {
  const wasOn = localStorage.getItem('lw_use_lbz') !== '0';
  const next  = !wasOn;
  localStorage.setItem('lw_use_lbz', next ? '1' : '0');
  _setToggle('lbzArtworkToggle', next);
}

function setPaletteAccent(hue) {
  const hex  = _settHslToHex(hue, 0.65, 0.52);
  const hexL = _settHslToHex(hue, 0.65, 0.72);
  state.accentMode = 'manual';
  applyAccent(hex, hexL, /*save=*/true);
  _refreshPaletteActive();
}

function setMonochromeAccent() {
  state.accentMode = 'monochrome';
  localStorage.setItem('lw_accentMode', 'monochrome');
  _applyMonochromeScheme();
  _refreshPaletteActive();
  _setToggle('dynamicThemeToggle', false);
}

function clearSeenTracksUI() {
  clearSeenTracks();
  _refreshSeenCount();
}

function clearAllData() {
  showModal(
    'Clear All Data',
    'This will remove your credentials, playlists, and all cached data. Are you sure?',
    () => {
      localStorage.clear();
      state.username   = '';
      state.apiKey     = '';
      state.apiSecret  = '';
      state.sessionKey = '';
      loadSettings();
      const elUser   = document.getElementById('settingsUsername');
      const elKey    = document.getElementById('settingsApiKey');
      const elSecret = document.getElementById('settingsApiSecret');
      if (elUser)   elUser.value   = '';
      if (elKey)    elKey.value    = '';
      if (elSecret) elSecret.value = '';
      _refreshToggles();
      _refreshPaletteActive();
      _refreshSeenCount();
      showToast('All data cleared', 'success');
    }
  );
}

// ═════════════════════════════════════════════════════════════
//  BACKUP / RESTORE
//
//  Backup file is a JSON snapshot of the ENTIRE localStorage —
//  seen tracks, generated playlists, playlist history, saved
//  preferences and settings all live there, so a full snapshot
//  is the only way to guarantee nothing is missed.
//
//  File shape:
//  {
//    type: "lastwave-backup", schemaVersion: 1,
//    createdAt: "<ISO date>", appVersion: "1.0.0",
//    data: { "<localStorage key>": "<localStorage value>", ... }
//  }
// ═════════════════════════════════════════════════════════════

const LW_BACKUP_TYPE           = 'lastwave-backup';
const LW_BACKUP_SCHEMA_VERSION = 1;

function backupData() {
  try {
    const snapshot = {};
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      snapshot[key] = localStorage.getItem(key);
    }

    const backup = {
      type: LW_BACKUP_TYPE,
      schemaVersion: LW_BACKUP_SCHEMA_VERSION,
      createdAt: new Date().toISOString(),
      appVersion: '1.0.0',
      data: snapshot
    };

    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const filename = `lastwave_backup_${stamp}.json`;

    Platform.saveFile(filename, JSON.stringify(backup, null, 2), 'application/json');
  } catch (e) {
    showToast('Backup failed: ' + e.message, 'error');
  }
}

function triggerRestoreData() {
  const input = document.getElementById('restoreFileInput');
  if (input) {
    input.value = ''; // allow re-selecting the same file twice in a row
    input.click();
  }
}

function handleRestoreFileSelected(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onerror = () => showToast('Could not read that file', 'error');
  reader.onload = () => {
    let parsed;
    try {
      parsed = JSON.parse(reader.result);
    } catch (e) {
      showToast('That file is not a valid backup (bad JSON)', 'error');
      return;
    }

    // ── Validate shape before ever touching localStorage ──────────
    if (!parsed || typeof parsed !== 'object'
        || parsed.type !== LW_BACKUP_TYPE
        || !parsed.data || typeof parsed.data !== 'object') {
      showToast('This file is not a recognised LastWave backup', 'error');
      return;
    }
    if (typeof parsed.schemaVersion === 'number' && parsed.schemaVersion > LW_BACKUP_SCHEMA_VERSION) {
      showToast('This backup was made with a newer version of LastWave', 'error');
      return;
    }

    const trackCount = Object.keys(parsed.data).length;
    showModal(
      'Restore Data',
      `This will replace all current saved songs, playlists, preferences and settings with the contents of this backup (${trackCount} item${trackCount === 1 ? '' : 's'}). This cannot be undone. Continue?`,
      () => _applyRestore(parsed.data)
    );
  };
  reader.readAsText(file);
}

function _applyRestore(restoredData) {
  // Snapshot current data first so a mid-restore failure can be rolled
  // back — the user is never left with a half-applied or empty state.
  const previous = {};
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      previous[key] = localStorage.getItem(key);
    }

    localStorage.clear();
    Object.keys(restoredData).forEach(key => {
      const value = restoredData[key];
      localStorage.setItem(key, typeof value === 'string' ? value : JSON.stringify(value));
    });

    showToast('Backup restored ✓ Reloading…', 'success');
    setTimeout(() => location.reload(), 900);
  } catch (e) {
    // Roll back to the pre-restore snapshot so nothing is lost.
    try {
      localStorage.clear();
      Object.keys(previous).forEach(key => {
        localStorage.setItem(key, previous[key]);
      });
    } catch (rollbackError) { /* best effort */ }
    showToast('Restore failed — your previous data was kept', 'error');
  }
}

// ═════════════════════════════════════════════════════════════
//  COLOR WHEEL DIALOG  —  COMPLETE REBUILD
//
//  Architecture overview:
//    • HSL disc rendered ONCE into an offscreen canvas (_cwWheelCache).
//      Edge pixels use a 1.5 px alpha feather for a smooth, anti-aliased rim.
//    • Every drag frame:
//        1. clearRect (full canvas) — erase previous cursor + any stray pixels
//        2. drawImage (blit disc cache) — O(1) GPU copy, no JS pixel loop
//        3. Draw single cursor ring at the clamped position
//    • One rAF per display frame (60 fps) — pointer coords stored on event,
//      draw scheduled via requestAnimationFrame, guard prevents double-queuing.
//    • getBoundingClientRect cached at drag-start, released at drag-end —
//      avoids forced layout on every touchmove.
//    • Cursor sat is clamped to [0,1] → cursor centre ≤ R from wheel centre,
//      ring strokes always inside canvas thanks to _CW_EDGE_PAD margin.
//    • Dialog stays centred via flex on the overlay (no translate tricks that
//      shift under backdrop-filter on Android WebView).
// ═════════════════════════════════════════════════════════════

function openColorWheel() {
  const overlay = document.getElementById('colorWheelDialog');
  if (!overlay) return;

  // ── Anchor to document.body ───────────────────────────────
  // settings.html is loaded into a [data-screen] wrapper that has
  // overflow-y:auto + position:absolute.  On Android WebView,
  // position:fixed inside ANY overflow container fixes to that
  // container — not the true viewport — so system insets (status
  // bar / nav bar) shift the overlay upward and clip the bottom.
  // Moving to document.body makes position:fixed behave correctly.
  if (overlay.parentElement !== document.body) {
    document.body.appendChild(overlay);
  }

  overlay.classList.remove('hidden');

  // ── Lock the screen scroll container ─────────────────────
  // While the color wheel is open, prevent the underlying settings
  // screen from scrolling.  Any scroll — even 1 px — shifts the
  // visual viewport and makes position:fixed elements (the overlay)
  // appear to jump.  We restore the original overflow on close.
  const _scrollEl = document.querySelector('[data-screen="settings"]');
  if (_scrollEl) {
    _scrollEl._cwSavedOverflow = _scrollEl.style.overflow;
    _scrollEl.style.overflow = 'hidden';
  }

  const canvas = document.getElementById('colorWheelCanvas');
  if (!canvas) return;

  // ── High-DPI canvas buffer ────────────────────────────────
  const dpr  = window.devicePixelRatio || 1;
  const size = 240;   // CSS display size (px)

  canvas.width        = Math.round(size * dpr);
  canvas.height       = Math.round(size * dpr);
  canvas.style.width  = size + 'px';
  canvas.style.height = size + 'px';

  _cwCtx = canvas.getContext('2d');

  // Invalidate the cached disc — buffer dimensions may have changed
  _cwWheelCache = null;

  // Cancel any dangling rAF from a previous session
  if (_cwAnimFrame) {
    cancelAnimationFrame(_cwAnimFrame);
    _cwAnimFrame = null;
  }

  // Reset drag state
  _cwDragging = false;
  _cwDragRect  = null;

  // Seed from current accent colour
  if (state.accentColor) {
    const { h, s } = _settHexToHsl(state.accentColor);
    _cwHue = h;
    _cwSat = Math.min(s / 100, 1);
  }

  _cwDraw(canvas);

  // Bind pointer events once; canvas element is stable across opens
  if (!_cwBound) {
    _cwBindEvents(canvas);
    _cwBound = true;
  }

  _cwUpdatePreview();
}

function closeColorWheel() {
  const overlay = document.getElementById('colorWheelDialog');
  if (overlay) overlay.classList.add('hidden');
  if (_cwAnimFrame) {
    cancelAnimationFrame(_cwAnimFrame);
    _cwAnimFrame = null;
  }
  _cwDragging = false;
  _cwDragRect  = null;

  // ── Restore the screen scroll container ──────────────────
  const _scrollEl = document.querySelector('[data-screen="settings"]');
  if (_scrollEl) {
    _scrollEl.style.overflow = _scrollEl._cwSavedOverflow || '';
    delete _scrollEl._cwSavedOverflow;
  }
}

function applyColorWheel() {
  const sat  = Math.max(_cwSat, 0.30);
  const hex  = _settHslToHex(_cwHue, sat, 0.52);
  const hexL = _settHslToHex(_cwHue, sat, 0.72);
  state.accentMode = 'custom';
  localStorage.setItem('lw_accentMode', 'custom');
  applyAccent(hex, hexL, /*save=*/true);
  _refreshPaletteActive();
  closeColorWheel();
}

function _cwOverlayClick(evt) {
  if (evt.target === document.getElementById('colorWheelDialog')) closeColorWheel();
}

// ── Static disc bitmap ────────────────────────────────────────
// Builds the full-resolution HSL colour wheel into an offscreen canvas.
// Called once per dialog open; subsequent draws blit via drawImage.
function _cwBuildWheelCache(W, H, R, cx, cy) {
  const oc     = document.createElement('canvas');
  oc.width     = W;
  oc.height    = H;
  const ctx    = oc.getContext('2d');
  const img    = ctx.createImageData(W, H);
  const data   = img.data;
  const FEATHER = 1.5;  // anti-alias feather width in physical pixels

  for (let py = 0; py < H; py++) {
    for (let px = 0; px < W; px++) {
      const dx   = px - cx;
      const dy   = py - cy;
      const dist = Math.sqrt(dx * dx + dy * dy);

      // Entirely outside the feather zone — leave transparent (alpha 0)
      if (dist > R + FEATHER) continue;

      const hue = ((Math.atan2(dy, dx) * 180 / Math.PI) + 360) % 360;
      const sat = Math.min(dist / R, 1.0);
      const [r, g, b] = _cwHslToRgb(hue, sat, 0.50);

      // Alpha: fully opaque inside R, ramps to 0 over the feather band
      const alpha = Math.min((R + FEATHER - dist) / FEATHER, 1.0);

      const idx      = (py * W + px) * 4;
      data[idx]     = r;
      data[idx + 1] = g;
      data[idx + 2] = b;
      data[idx + 3] = Math.round(alpha * 255);
    }
  }

  ctx.putImageData(img, 0, 0);
  return oc;
}

// ── Draw function — called once on open, then once per rAF ───
function _cwDraw(canvas) {
  if (!_cwCtx) return;

  const dpr = window.devicePixelRatio || 1;
  const W   = canvas.width;
  const H   = canvas.height;
  const cx  = W / 2;
  const cy  = H / 2;
  const R   = W / 2 - _CW_EDGE_PAD * dpr;

  // Build or reuse cached disc bitmap
  if (!_cwWheelCache || _cwWheelCache.width !== W || _cwWheelCache.height !== H) {
    _cwWheelCache = _cwBuildWheelCache(W, H, R, cx, cy);
  }

  // ── 1. Full clear ─────────────────────────────────────────
  // Removes the previous cursor ring entirely — including the portion that
  // may have extended beyond R into transparent canvas corners where the
  // disc cache blit would NOT overwrite it (source-over + alpha=0 = no-op).
  _cwCtx.clearRect(0, 0, W, H);

  // ── 2. Blit cached disc (single GPU drawImage call) ───────
  _cwCtx.drawImage(_cwWheelCache, 0, 0);

  // ── 3. Draw cursor ring ───────────────────────────────────
  const angle   = (_cwHue * Math.PI) / 180;
  const dotDist = Math.min(_cwSat, 1.0) * R;  // clamped: centre ≤ R from origin
  const dotX    = cx + Math.cos(angle) * dotDist;
  const dotY    = cy + Math.sin(angle) * dotDist;
  const dotR    = _CW_CURSOR_R * dpr;

  // Drop-shadow ring (drawn slightly larger for depth illusion)
  _cwCtx.beginPath();
  _cwCtx.arc(dotX, dotY, dotR + 1.5 * dpr, 0, Math.PI * 2);
  _cwCtx.strokeStyle = 'rgba(0,0,0,0.50)';
  _cwCtx.lineWidth   = 2.5 * dpr;
  _cwCtx.stroke();

  // White selection ring
  _cwCtx.beginPath();
  _cwCtx.arc(dotX, dotY, dotR, 0, Math.PI * 2);
  _cwCtx.strokeStyle = '#ffffff';
  _cwCtx.lineWidth   = 2.5 * dpr;
  _cwCtx.stroke();
}

// ── Pointer / touch event handling ───────────────────────────
function _cwBindEvents(canvas) {

  // Maps a pointer/touch event to physical canvas pixel coordinates.
  // Uses a rect cached at drag-start to avoid forced layout on every
  // touchmove.  Falls back to a live query if no cached rect exists.
  function _posFromEvent(evt) {
    const rect   = _cwDragRect || canvas.getBoundingClientRect();
    const scaleX = canvas.width  / rect.width;
    const scaleY = canvas.height / rect.height;
    const src    = evt.touches ? evt.touches[0] : evt;
    return {
      x: (src.clientX - rect.left) * scaleX,
      y: (src.clientY - rect.top)  * scaleY,
    };
  }

  // rAF callback — executes at most once per display refresh cycle.
  function _commitPick() {
    _cwAnimFrame = null;

    const dpr = window.devicePixelRatio || 1;
    const cx  = canvas.width  / 2;
    const cy  = canvas.height / 2;
    const R   = canvas.width  / 2 - _CW_EDGE_PAD * dpr;

    const dx   = _cwLastX - cx;
    const dy   = _cwLastY - cy;
    const dist = Math.sqrt(dx * dx + dy * dy);

    // Hue derived from full angle — correct even when pointer goes outside disc
    _cwHue = ((Math.atan2(dy, dx) * 180 / Math.PI) + 360) % 360;
    // Saturation clamped to [0,1] so cursor ring stays inside the disc
    _cwSat = Math.min(dist / R, 1.0);

    _cwDraw(canvas);
    _cwUpdatePreview();
  }

  // Store pointer coords immediately (touch event objects may be recycled
  // by the browser before rAF fires).  Schedule at most one frame per cycle.
  function _scheduleFrame(evt) {
    // preventDefault() — stops the browser's built-in scroll gesture.
    // stopPropagation() — prevents the event from bubbling to the overflow
    // scroll container, which is the JS equivalent of returning true from
    // onTouchEvent and calling requestDisallowInterceptTouchEvent(true) on
    // the parent.  Without stopPropagation the scroll container receives the
    // touchmove and scrolls the page even after preventDefault is called.
    evt.preventDefault();
    evt.stopPropagation();
    const pos = _posFromEvent(evt);
    _cwLastX  = pos.x;
    _cwLastY  = pos.y;
    if (_cwAnimFrame) return;   // already queued for this frame
    _cwAnimFrame = requestAnimationFrame(_commitPick);
  }

  // Grab a reference to the settings screen scroll container once.
  // Used in _onStart / _onEnd to lock / unlock scrolling for the duration
  // of the drag — the JS equivalent of requestDisallowInterceptTouchEvent.
  const _scrollContainer = document.querySelector('[data-screen="settings"]');

  function _onStart(evt) {
    _cwDragging = true;
    // Cache rect at drag-start — avoids repeated forced layouts on touchmove
    _cwDragRect = canvas.getBoundingClientRect();

    // ── requestDisallowInterceptTouchEvent(true) equivalent ───────────────
    // Lock the parent scroll container for the entire drag gesture so it
    // cannot intercept touch events and scroll the page under the dialog.
    // overflow:hidden removes scroll ability; touch-action:none tells the
    // browser to hand ALL touch handling to our canvas listeners.
    if (_scrollContainer) {
      _scrollContainer.style.overflow   = 'hidden';
      _scrollContainer.style.touchAction = 'none';
    }

    _scheduleFrame(evt);
  }

  function _onMove(evt) {
    if (_cwDragging) _scheduleFrame(evt);
  }

  function _onEnd() {
    _cwDragging = false;
    _cwDragRect  = null;  // invalidate so next drag gets a fresh measurement

    // ── Re-enable parent scroll container ─────────────────────────────────
    // Restore overflow and touch-action so normal settings scrolling resumes
    // once the drag gesture is complete.
    if (_scrollContainer) {
      _scrollContainer.style.overflow   = '';
      _scrollContainer.style.touchAction = '';
    }
  }

  // Mouse events
  canvas.addEventListener('mousedown',  _onStart);
  canvas.addEventListener('mousemove',  _onMove);
  canvas.addEventListener('mouseup',    _onEnd);
  canvas.addEventListener('mouseleave', _onEnd);

  // Touch events — passive:false is required so that preventDefault() and
  // stopPropagation() inside _scheduleFrame are honoured by the browser.
  canvas.addEventListener('touchstart',  _onStart, { passive: false });
  canvas.addEventListener('touchmove',   _onMove,  { passive: false });
  canvas.addEventListener('touchend',    _onEnd);
  canvas.addEventListener('touchcancel', _onEnd);
}

function _cwUpdatePreview() {
  const sat    = Math.max(_cwSat, 0.30);
  const hex    = _settHslToHex(_cwHue, sat, 0.52);
  const swatch = document.getElementById('cwPreviewSwatch');
  const label  = document.getElementById('cwHexDisplay');
  if (swatch) swatch.style.background = hex;
  if (label)  label.textContent       = hex.toUpperCase();
}

// ═════════════════════════════════════════════════════════════
//  LOCAL COLOR MATH
// ═════════════════════════════════════════════════════════════

/** HSL (h 0-360, s 0-1, l 0-1) → [R, G, B] (each 0-255) */
function _cwHslToRgb(h, s, l) {
  h /= 360;
  if (s === 0) {
    const v = Math.round(l * 255);
    return [v, v, v];
  }
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  return [
    Math.round(_cwChann(p, q, h + 1 / 3) * 255),
    Math.round(_cwChann(p, q, h)         * 255),
    Math.round(_cwChann(p, q, h - 1 / 3) * 255),
  ];
}

function _cwChann(p, q, t) {
  if (t < 0) t += 1;
  if (t > 1) t -= 1;
  if (t < 1 / 6) return p + (q - p) * 6 * t;
  if (t < 1 / 2) return q;
  if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
  return p;
}

/** HSL (h 0-360, s 0-1, l 0-1) → '#rrggbb' */
function _settHslToHex(h, s, l) {
  const [r, g, b] = _cwHslToRgb(h, s, l);
  return '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('');
}

/** '#rrggbb' → { h: 0-360, s: 0-100, l: 0-100 } */
function _settHexToHsl(hex) {
  if (!hex || hex.length < 7) return { h: 0, s: 50, l: 50 };
  const r = parseInt(hex.slice(1, 3), 16) / 255;
  const g = parseInt(hex.slice(3, 5), 16) / 255;
  const b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l   = (max + min) / 2;
  let h = 0, s = 0;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
      case g: h = ((b - r) / d + 2) / 6;               break;
      case b: h = ((r - g) / d + 4) / 6;               break;
    }
  }
  return {
    h: Math.round(h * 360),
    s: Math.round(s * 100),
    l: Math.round(l * 100),
  };
}

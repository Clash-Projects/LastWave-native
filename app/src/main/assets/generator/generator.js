/* ════════════════════════════════════════════════════════════
   generator.js — Generator screen logic
   ════════════════════════════════════════════════════════════ */

'use strict';

// All known mode IDs (including the two new visual modes)
const GEN_MODES = [
  'top', 'recent', 'similar-tracks', 'similar-artists',
  'tag', 'mix', 'recommendations', 'library'
];

// opt-common (slider) is now always visible — no modes hide it
const GEN_HIDE_LIMIT = new Set(); // kept for compat but always empty

// ── Screen init (called by nav.js every time generator loads) ─
function screen_generator() {
  if (state.visualMode) {
    _restoreGeneratorUI(state.visualMode);
  }
  // Sync slider to current state on every visit
  _syncSliderToState();
}

/**
 * Restores the generator UI to show the given mode as selected.
 * Unlike selectMode(), this never deselects — it is safe to call
 * on every navigation visit even when state.visualMode is already set.
 */
function _restoreGeneratorUI(mode) {
  document.querySelectorAll('.gen-mode-row').forEach(c => {
    c.classList.toggle('selected', c.dataset.mode === mode);
  });
  GEN_MODES.forEach(s => {
    const el = document.getElementById('opts-' + s);
    if (el) el.classList.add('hidden');
  });
  const target = document.getElementById('opts-' + mode);
  if (target) target.classList.remove('hidden');
  const optCommon = document.querySelector('.opt-common');
  if (optCommon) optCommon.classList.toggle('hidden', GEN_HIDE_LIMIT.has(mode));
  document.getElementById('modeOptions')?.classList.remove('hidden');
}

// ── Mode selection ────────────────────────────────────────────
function selectMode(mode) {
  // Toggle: tapping the already-selected mode deselects it
  if (state.visualMode === mode) {
    state.selectedMode = null;
    state.visualMode   = null;
    document.querySelectorAll('.gen-mode-row').forEach(c => c.classList.remove('selected'));
    document.getElementById('modeOptions').classList.add('hidden');
    return;
  }

  state.selectedMode = mode;
  // Preserve the UI selection so playlist.js can use the right icon/title
  // even after the alias remapping below collapses it to 'mix' or 'top'.
  state.visualMode = mode;

  // Highlight selected row
  document.querySelectorAll('.gen-mode-row').forEach(c => {
    c.classList.toggle('selected', c.dataset.mode === mode);
  });

  // Show only the matching options section
  GEN_MODES.forEach(s => {
    const el = document.getElementById('opts-' + s);
    if (el) el.classList.add('hidden');
  });
  const target = document.getElementById('opts-' + mode);
  if (target) target.classList.remove('hidden');

  // Hide the common limit row for modes with their own count chip
  const optCommon = document.querySelector('.opt-common');
  if (optCommon) optCommon.classList.toggle('hidden', GEN_HIDE_LIMIT.has(mode));

  document.getElementById('modeOptions').classList.remove('hidden');

  // Map new UI modes to existing generation logic
  if (mode === 'recommendations') state.selectedMode = 'mix';
  if (mode === 'library')         state.selectedMode = 'top';
}

// ── Chip selection (period only — count/limit now uses slider) ──
function selectChip(el, group) {
  el.closest('.chip-row, .tag-suggestions').querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
  el.classList.add('active');
  if (el.dataset.period) state.chipSelections.period = el.dataset.period;
  if (el.dataset.count)  state.chipSelections.count  = el.dataset.count;
  if (el.dataset.limit)  state.chipSelections.limit  = el.dataset.limit;
}

// ── Unified track count slider ────────────────────────────────
function selectSliderCount(val) {
  const n   = Math.max(5, Math.min(35, parseInt(val) || 25));
  // pct drives ONLY the CSS custom property used by the ::webkit-slider-runnable-track
  // rule in generator.css.  We must NOT also set slider.style.background inline —
  // that paints the element box (which ignores thumb margin-top) and creates a
  // visible offset between the filled portion and the thumb position.
  const pct = ((n - 5) / 30 * 100).toFixed(1) + '%';
  state.chipSelections.count = String(n);
  state.chipSelections.limit = String(n);
  const label  = document.getElementById('genSliderValue');
  const slider = document.getElementById('genTrackSlider');
  if (label)  label.textContent = n;
  if (slider) {
    if (slider.value !== String(n)) slider.value = n;
    // Drive the track-fill via CSS custom property so the gradient
    // is painted on the ::webkit-slider-runnable-track, which shares
    // the same coordinate space as the thumb — zero alignment drift.
    slider.style.setProperty('--slider-pct', pct);
    // Clear any stale inline background that may have been written by
    // an older version of this function (prevents override of the CSS rule).
    slider.style.background = '';
  }
}

/** Sync slider UI to current state on screen restore */
function _syncSliderToState() {
  const cur = parseInt(state.chipSelections.limit || state.chipSelections.count) || 25;
  selectSliderCount(cur);
}

// ── Tag shortcut ──────────────────────────────────────────────
function setTag(tag) {
  document.getElementById('tagInput').value = tag;
}

// ── Seed track picker ─────────────────────────────────────────
async function loadTopTracksForSeed() {
  if (!state.username || !state.apiKey) { showToast('Set username and API key first', 'error'); return; }
  try {
    showToast('Loading top tracks…');
    const data   = await lfmCall({ method: 'user.gettoptracks', user: state.username, limit: 20, period: 'overall' });
    const tracks = data.toptracks.track;
    const list   = document.getElementById('seedTrackList');
    list.innerHTML = '';
    tracks.forEach(t => {
      const el = document.createElement('div');
      el.className = 'seed-item';
      el.innerHTML = `<div class="seed-item-track">${esc(t.name)}</div><div class="seed-item-artist">${esc(t.artist.name)}</div>`;
      el.onclick = () => {
        list.querySelectorAll('.seed-item').forEach(i => i.classList.remove('selected'));
        el.classList.add('selected');
        document.getElementById('seedTrackName').value  = t.name;
        document.getElementById('seedArtistName').value = t.artist.name;
      };
      list.appendChild(el);
    });
  } catch (e) { showToast(e.message, 'error'); }
}

// ── Seed artist picker ────────────────────────────────────────
async function loadTopArtistsForSeed() {
  if (!state.username || !state.apiKey) { showToast('Set username and API key first', 'error'); return; }
  try {
    showToast('Loading top artists…');
    const data    = await lfmCall({ method: 'user.gettopartists', user: state.username, limit: 20, period: 'overall' });
    const artists = data.topartists.artist;
    const list    = document.getElementById('seedArtistList');
    list.innerHTML = '';
    artists.forEach(a => {
      const el = document.createElement('div');
      el.className = 'seed-item';
      el.innerHTML = `<div class="seed-item-track">${esc(a.name)}</div>`;
      el.onclick = () => {
        list.querySelectorAll('.seed-item').forEach(i => i.classList.remove('selected'));
        el.classList.add('selected');
        document.getElementById('seedArtistInput').value = a.name;
      };
      list.appendChild(el);
    });
  } catch (e) { showToast(e.message, 'error'); }
}

// ── Seed track search ─────────────────────────────────────────
async function _searchSeedTrack() {
  const trackName  = document.getElementById('seedTrackName')?.value.trim();
  const artistName = document.getElementById('seedArtistName')?.value.trim();
  if (!trackName) { showToast('Enter a track name to search', 'error'); return; }
  if (!state.apiKey) { showToast('Set your API key in Settings first', 'error'); return; }

  showToast('Searching\u2026');
  const list = document.getElementById('seedTrackList');
  list.innerHTML = '<div class="seed-search-loading"><span class="material-symbols-rounded" style="animation:spin 0.8s linear infinite;font-size:20px;color:var(--text3)">refresh</span></div>';

  try {
    const params = { method: 'track.search', track: trackName, limit: 15 };
    if (artistName) params.artist = artistName;
    const data   = await lfmCall(params);
    const raw    = data?.results?.trackmatches?.track || [];
    const tracks = Array.isArray(raw) ? raw : (raw ? [raw] : []);
    list.innerHTML = '';
    if (!tracks.filter(t => t?.name).length) {
      list.innerHTML = '<div class="seed-search-empty">No results found</div>';
      return;
    }
    tracks.filter(t => t?.name && t?.artist).forEach(t => {
      const el       = document.createElement('div');
      el.className   = 'seed-item';
      el.innerHTML   = `<div class="seed-item-track">${esc(t.name)}</div><div class="seed-item-artist">${esc(t.artist)}</div>`;
      el.onclick     = () => {
        list.querySelectorAll('.seed-item').forEach(i => i.classList.remove('selected'));
        el.classList.add('selected');
        document.getElementById('seedTrackName').value  = t.name;
        document.getElementById('seedArtistName').value = t.artist;
      };
      list.appendChild(el);
    });
  } catch (e) {
    list.innerHTML = '<div class="seed-search-empty">Search failed — try again</div>';
    showToast(e.message, 'error');
  }
}

// ── Seed artist search ────────────────────────────────────────
async function _searchSeedArtist() {
  const artistName = document.getElementById('seedArtistInput')?.value.trim();
  if (!artistName) { showToast('Enter an artist name to search', 'error'); return; }
  if (!state.apiKey) { showToast('Set your API key in Settings first', 'error'); return; }

  showToast('Searching\u2026');
  const list = document.getElementById('seedArtistList');
  list.innerHTML = '<div class="seed-search-loading"><span class="material-symbols-rounded" style="animation:spin 0.8s linear infinite;font-size:20px;color:var(--text3)">refresh</span></div>';

  try {
    const data    = await lfmCall({ method: 'artist.search', artist: artistName, limit: 15 });
    const raw     = data?.results?.artistmatches?.artist || [];
    const artists = Array.isArray(raw) ? raw : (raw ? [raw] : []);
    list.innerHTML = '';
    if (!artists.filter(a => a?.name).length) {
      list.innerHTML = '<div class="seed-search-empty">No results found</div>';
      return;
    }
    artists.filter(a => a?.name).forEach(a => {
      const el     = document.createElement('div');
      el.className = 'seed-item';
      const sub    = a.listeners ? `${parseInt(a.listeners).toLocaleString()} listeners` : '';
      el.innerHTML = `<div class="seed-item-track">${esc(a.name)}</div>${sub ? `<div class="seed-item-artist">${sub}</div>` : ''}`;
      el.onclick   = () => {
        list.querySelectorAll('.seed-item').forEach(i => i.classList.remove('selected'));
        el.classList.add('selected');
        document.getElementById('seedArtistInput').value = a.name;
      };
      list.appendChild(el);
    });
  } catch (e) {
    list.innerHTML = '<div class="seed-search-empty">Search failed — try again</div>';
    showToast(e.message, 'error');
  }
}

// ── Fix 5: Recent Tracks icon rotation animation ───────────────
/**
 * Rotates the "history" icon 360° clockwise on click.
 * Only applies to the Recent Tracks row — not other icons.
 */
function _animateRecentIcon(rowEl) {
  const icon = rowEl
    ? rowEl.querySelector('#recentTracksIcon')
    : document.getElementById('recentTracksIcon');
  if (!icon) return;
  icon.classList.remove('gen-icon-spin');
  void icon.offsetWidth; // force reflow to restart if clicked rapidly
  icon.classList.add('gen-icon-spin');
  icon.addEventListener('animationend', () => icon.classList.remove('gen-icon-spin'), { once: true });
}

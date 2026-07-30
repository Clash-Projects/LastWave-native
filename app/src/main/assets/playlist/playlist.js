/* ════════════════════════════════════════════════════════════
   playlist.js — Playlist screen logic
   v2: Export dialog with CSV / M3U + Save + Share
   Performance: lazy track rendering, RAF-batched DOM writes
   ════════════════════════════════════════════════════════════ */

'use strict';

const PL_STORAGE_KEY = 'lw_playlists';
const PL_MAX_SAVED   = 20;

// ── In-memory playlist cache ──────────────────────────────────
// Avoids repeated JSON.parse(localStorage.getItem(...)) on every render,
// card expand, track row build, and art enrichment pass.
// Invalidated (set to null) on every save or delete so the next
// _plLoad() call repopulates it from storage.
let _plCache = null;

const PL_MODE_META = {
  'top':             { icon: 'leaderboard'     },
  'recent':          { icon: 'history'          },
  'similar-tracks':  { icon: 'music_note'       },
  'similar-artists': { icon: 'people'           },
  'tag':             { icon: 'sell'             },
  'mix':             { icon: 'shuffle'          },
  'recommendations': { icon: 'auto_awesome'     },
  'library':         { icon: 'library_music'    },
};

// ── Export dialog state ───────────────────────────────────────
let _plExportCurrentId  = null;
let _plExportFormat     = 'csv';   // 'csv' | 'm3u'

// ── Screen init ───────────────────────────────────────────────
function screen_playlist() {
  // Invalidate the in-memory cache so we always read the freshest
  // localStorage state when entering the tab (handles the case where
  // a playlist was saved from another screen without the cache being
  // updated — e.g. if _plSave had an error path that skipped the update).
  if (!_plJustGenerated) {
    _plCache = null;
    _plRenderSaved();
  }
  _plJustGenerated = false;
}

let _plJustGenerated = false;

// ── Called by app.js ──────────────────────────────────────────
function showLoading(show) {
  _plEl('plLoading').classList.toggle('hidden', !show);
  _plEl('plEmpty').classList.add('hidden');
  _plEl('plList').classList.add('hidden');
}

function setLoadingText(msg) {
  const el = _plEl('plLoadingText');
  if (el) el.textContent = msg || 'Building your playlist\u2026';
}

function renderResults() {
  showLoading(false);
  if (!state.playlist || state.playlist.length === 0) {
    showResultsEmpty(); return;
  }
  _plSave({
    id:       Date.now(),
    title:    state.playlistTitle || 'My Playlist',
    subtitle: state.playlistSubtitle || '',
    mode:     state.visualMode || state.selectedMode || '',
    tracks:   state.playlist,
    date:     Date.now()
  });
  _plJustGenerated = true;
  _plRenderSaved(true);
}

function showResultsEmpty() {
  showLoading(false);
  const saved = _plLoad();
  if (saved.length === 0) {
    _plEl('plEmpty').classList.remove('hidden');
    _plEl('plList').classList.add('hidden');
  } else {
    _plRenderSaved();
  }
}

// ── Render ────────────────────────────────────────────────────
function _plRenderSaved(highlightNewest) {
  const playlists = _plLoad();
  const listEl    = _plEl('plList');
  const emptyEl   = _plEl('plEmpty');
  const cardsEl   = _plEl('plCards');
  const banner    = _plEl('plNewBanner');

  if (playlists.length === 0) {
    emptyEl.classList.remove('hidden');
    listEl.classList.add('hidden');
    return;
  }

  emptyEl.classList.add('hidden');
  listEl.classList.remove('hidden');

  if (highlightNewest) {
    banner.classList.remove('hidden');
    setTimeout(() => banner.classList.add('hidden'), 3000);
  } else {
    banner.classList.add('hidden');
  }

  // Build card shells WITHOUT track rows — track rows are lazy-rendered
  // on first expand.  For 20 playlists × 30 tracks this cuts initial
  // DOM work from ~600 nodes to ~60, dramatically improving load time.
  cardsEl.innerHTML = playlists
    .slice()
    .reverse()
    .map((pl, idx) => _plCardHTML(pl, idx === 0 && highlightNewest))
    .join('');

  if (highlightNewest && playlists.length > 0) {
    const newestId = playlists[playlists.length - 1].id;
    const card     = cardsEl.querySelector(`[data-pl-id="${newestId}"]`);
    if (card) {
      card.classList.add('expanded');
      // Eagerly render tracks for the newest card since it opens expanded
      _plEnsureTracksRendered(card);
    }
  }

  if (typeof bindLongPressCopy === 'function') {
    // Only bind to already-rendered track rows (newly expanded cards
    // call bindLongPressCopy themselves in _plEnsureTracksRendered).
    bindLongPressCopy(cardsEl, '[data-lp-name]');
  }
  _plBindCardLongPress(cardsEl);
  _plRenderRegenBar();
}

// ── Long-press copy for playlist card titles ──────────────────
// 500 ms hold on any playlist card header copies the playlist name
// to clipboard and shows a "Copied" toast. Normal tap (expand/collapse)
// is NOT affected — the click event is suppressed only after a trigger.
function _plBindCardLongPress(container) {
  if (!container) return;
  const LONG_MS = 500;

  container.querySelectorAll('.pl-card-header').forEach(header => {
    if (header._plLpBound) return;
    header._plLpBound = true;

    let _timer     = null;
    let _startX    = 0;
    let _startY    = 0;
    let _triggered = false;

    const cancel = () => { clearTimeout(_timer); _timer = null; };

    header.addEventListener('touchstart', (e) => {
      _triggered = false;
      _startX = e.touches[0].clientX;
      _startY = e.touches[0].clientY;
      cancel();
      _timer = setTimeout(() => {
        _triggered = true;
        // Read the title baked into the card element at render time —
        // avoids any _plLoad() lookup ambiguity that could return meta text.
        const name = (header.closest('.pl-card')?.dataset?.plTitle || '').trim();
        if (!name) return;
        if (navigator.clipboard?.writeText) {
          navigator.clipboard.writeText(name)
            .then(() => showToast('Copied: ' + name, 'success'))
            .catch(() => _plLpFallback(name));
        } else {
          _plLpFallback(name);
        }
        try { navigator.vibrate?.(30); } catch {}
      }, LONG_MS);
    }, { passive: true });

    header.addEventListener('touchmove', (e) => {
      const dx = e.touches[0].clientX - _startX;
      const dy = e.touches[0].clientY - _startY;
      if (Math.hypot(dx, dy) > 8) cancel();
    }, { passive: true });

    header.addEventListener('touchend',    cancel, { passive: true });
    header.addEventListener('touchcancel', cancel, { passive: true });

    // Block the expand/collapse click from firing after a long-press
    header.addEventListener('click', (e) => {
      if (_triggered) { e.stopPropagation(); e.preventDefault(); _triggered = false; }
    });
  });
}

function _plLpFallback(text) {
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;pointer-events:none';
    document.body.appendChild(ta);
    ta.focus(); ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    showToast('Copied: ' + text, 'success');
  } catch {}
}

function _plRenderRegenBar() {
  const bar = _plEl('plRegenBar');
  if (!bar) return;
  if (!state.selectedMode) { bar.classList.add('hidden'); return; }
  bar.classList.remove('hidden');
}

// ── Card HTML — shell only (no track rows) ────────────────────
// Track rows are injected lazily by _plEnsureTracksRendered when
// the card is first expanded.  This keeps initial render fast.
function _plCardHTML(pl, isNewest) {
  const count    = pl.tracks.length;
  const dateStr  = _plFmtDate(pl.date);
  const meta     = PL_MODE_META[pl.mode] || null;
  const iconName = meta ? meta.icon : 'queue_music';
  const title    = _plStripEmoji(pl.title);

  const modeLabels = {
    'top':             'Top Tracks',
    'recent':          'Recent Tracks',
    'similar-tracks':  'Similar Tracks',
    'similar-artists': 'Similar Artists',
    'tag':             'Genre Mix',
    'mix':             'My Mix',
    'recommendations': 'My Recommendations',
    'library':         'My Library',
    'start-mix':       'Track Mix',
  };
  const templateLabel = pl.subtitle
    || (pl.mode ? (modeLabels[pl.mode] || '') : '');

  // ── 4-cover art grid ──────────────────────────────────────
  const seenImgs  = new Set();
  const artTracks = pl.tracks.filter(t => {
    if (!t.image || !t.image.trim()) return false;
    if (seenImgs.has(t.image)) return false;
    seenImgs.add(t.image);
    return true;
  }).slice(0, 4);

  let coverGridHTML = '';
  if (artTracks.length >= 2) {
    const cells = Array.from({ length: 4 }, (_, i) => {
      const t = artTracks[i];
      return t
        ? `<img src="${esc(t.image)}" alt="" class="pl-cover-cell"
               loading="lazy" onerror="this.parentElement.style.background='var(--surface2)';this.remove()">`
        : `<div class="pl-cover-cell pl-cover-placeholder"></div>`;
    }).join('');
    coverGridHTML = `<div class="pl-cover-grid">${cells}</div>`;
  } else {
    coverGridHTML = `
      <div class="pl-cover-grid pl-cover-single">
        <span class="material-symbols-rounded pl-cover-icon">${iconName}</span>
      </div>`;
  }

  const regenBtn = isNewest && state.selectedMode
    ? `<button class="pl-card-action-btn regen"
               onclick="event.stopPropagation();regeneratePlaylist()">
        <span class="material-symbols-rounded">refresh</span>Regenerate
       </button>`
    : '';

  return `
    <div class="pl-card${isNewest ? ' pl-card-newest' : ''}" data-pl-id="${pl.id}" data-pl-title="${escAttr(title)}">

      <!-- Header: ONLY this triggers expand/collapse -->
      <div class="pl-card-header" onclick="_plToggle(this.closest('.pl-card'))">
        ${coverGridHTML}
        <div class="pl-card-header-left">
          <div class="pl-card-title">
            <span class="material-symbols-rounded pl-card-type-icon">${iconName}</span>${esc(title)}
          </div>
          <div class="pl-card-meta">
            ${count} track${count !== 1 ? 's' : ''}&nbsp;·&nbsp;${dateStr}
            ${templateLabel ? `<span class="pl-card-template">· ${esc(templateLabel)}</span>` : ''}
          </div>
        </div>
      </div>

      <!-- Actions -->
      <div class="pl-card-actions" onclick="event.stopPropagation()">
        ${regenBtn}
        <button class="pl-card-action-btn"
                onclick="event.stopPropagation();_plShowExportDialog(${pl.id})">
          <span class="material-symbols-rounded">download</span>Export
        </button>
        <button class="pl-card-action-btn similar"
                onclick="event.stopPropagation();_plGenerateSimilar(${pl.id})">
          <span class="material-symbols-rounded">auto_awesome</span>Generate Similar
        </button>
        <button class="pl-card-action-btn danger"
                onclick="event.stopPropagation();_plDelete(${pl.id})">
          <span class="material-symbols-rounded">delete</span>Delete
        </button>
      </div>

      <!-- Track list: empty shell — filled on first expand -->
      <div class="pl-track-list">
        <div class="pl-track-list-inner"></div>
      </div>

    </div>
  `;
}

// ── Lazy track rendering ──────────────────────────────────────
// Called when a card is expanded (or pre-expanded for newest card).
// Renders tracks in a requestAnimationFrame so the expand CSS
// animation starts immediately without being blocked by DOM work.
function _plEnsureTracksRendered(cardEl) {
  const inner = cardEl.querySelector('.pl-track-list-inner');
  if (!inner || inner._tracksRendered) return;
  inner._tracksRendered = true;

  const plId = parseInt(cardEl.dataset.plId, 10);
  const pl   = _plLoad().find(p => p.id === plId);
  if (!pl) return;

  requestAnimationFrame(() => {
    inner.innerHTML = pl.tracks
      .map((t, i) => _plTrackRowHTML(t, i, pl.id))
      .join('');

    if (typeof bindLongPressCopy === 'function') {
      bindLongPressCopy(inner, '[data-lp-name]');
    }

    // Enrich artwork for tracks in this card only
    _plEnrichCardArt(inner);
  });
}

// ── Per-track row HTML ────────────────────────────────────────
function _plTrackRowHTML(t, i, plId) {
  const hasImg = t.image && t.image.trim() !== '';
  const imgTag = hasImg
    ? `<img src="${esc(t.image)}" alt="" class="pl-track-art"
           loading="lazy"
           onerror="this.classList.add('errored');this.nextElementSibling.style.display='block'">`
    : '';
  const fallbackStyle = hasImg ? 'style="display:none"' : 'style="display:block"';
  return `
    <div class="pl-track-row"
         data-lp-name="${escAttr(t.name)}"
         data-lp-artist="${escAttr(t.artist)}"
         onclick="event.stopPropagation();openTrackOnYouTube('${escAttr(t.name)}','${escAttr(t.artist)}')">
      <span class="pl-track-num">${i + 1}</span>
      <div class="pl-track-art-wrap">
        ${imgTag}
        <span class="material-symbols-rounded pl-track-art-fallback" ${fallbackStyle}>music_note</span>
      </div>
      <div class="pl-track-info">
        <div class="pl-track-name">${esc(t.name)}</div>
        <div class="pl-track-artist">${esc(t.artist)}</div>
      </div>
      <button
        class="pl-track-menu-btn"
        aria-label="More options"
        onclick="event.stopPropagation();_plOpenTrackMenu(this,'${escAttr(t.name)}','${escAttr(t.artist)}',${plId})"
      >
        <span class="material-symbols-rounded">more_vert</span>
      </button>
    </div>`;
}

// ── Toggle expand/collapse ────────────────────────────────────
function _plToggle(cardEl) {
  const wasExpanded = cardEl.classList.contains('expanded');
  cardEl.classList.toggle('expanded');
  // Render tracks on first expand — deferred to avoid blocking animation
  if (!wasExpanded) {
    _plEnsureTracksRendered(cardEl);
  }
}

// ══════════════════════════════════════════════════════════════
//  PER-TRACK 3-DOT MENU
// ══════════════════════════════════════════════════════════════

let _activePlMenu = null;

function _plClosePlMenu() {
  if (!_activePlMenu) return;
  _activePlMenu.classList.add('track-dropdown-leaving');
  const m = _activePlMenu;
  _activePlMenu = null;
  setTimeout(() => m?.remove(), 160);
}

function _plOpenTrackMenu(btn, trackName, artistName, plId) {
  _plClosePlMenu();

  const items = [
    {
      icon: 'content_copy', label: 'Copy song name',
      fn: () => {
        const txt = trackName;
        navigator.clipboard?.writeText(txt)
          .then(() => showToast('Copied', 'success'))
          .catch(() => _lpFallbackCopy(txt));
      }
    },
    {
      icon: 'person', label: 'Copy artist',
      fn: () => {
        const txt = artistName;
        navigator.clipboard?.writeText(txt)
          .then(() => showToast('Copied', 'success'))
          .catch(() => _lpFallbackCopy(txt));
      }
    },
    {
      icon: 'open_in_new', label: 'Open in Last.fm',
      fn: () => openTrackOnLastFm(trackName, artistName)
    },
    {
      icon: 'smart_display', label: 'Play on YouTube',
      fn: () => openTrackOnYouTube(trackName, artistName)
    },
    {
      icon: 'image_search', label: 'Refresh Cover Art',
      fn: async () => {
        showToast('Refreshing cover art\u2026');
        const url = typeof _refreshTrackArtwork === 'function'
          ? await _refreshTrackArtwork(trackName, artistName)
          : (typeof _resolveTrackArt === 'function'
              ? await _resolveTrackArt(trackName, artistName)
              : '');
        if (url) {
          document.querySelectorAll(
            `.pl-track-row[data-lp-name="${CSS.escape(trackName)}"][data-lp-artist="${CSS.escape(artistName)}"]`
          ).forEach(row => _plPatchTrackArt(row, url));
          showToast('Cover art updated \u2713', 'success');
        } else {
          showToast('Cover art not available', 'error');
        }
      }
    },
    {
      icon: 'delete', label: 'Delete Scrobble',
      fn: () => {
        if (!state.sessionKey) { showToast('Sign in to delete scrobbles', 'error'); return; }
        showModal(
          'Delete Scrobble?',
          `Remove \u201c${trackName}\u201d by ${artistName} from your Last.fm history?`,
          async () => {
            const ok = await _lfmDeleteScrobble(trackName, artistName, null);
            if (ok) {
              document.querySelectorAll(
                `.pl-track-row[data-lp-name="${CSS.escape(trackName)}"][data-lp-artist="${CSS.escape(artistName)}"]`
              ).forEach(row => row.remove());
            }
          }
        );
      }
    },
  ];

  const menu = document.createElement('div');
  menu.className = 'track-dropdown-menu';
  menu.setAttribute('role', 'menu');

  const genreRow = document.createElement('div');
  genreRow.className = 'track-dropdown-genre';
  genreRow.innerHTML = `<span class="material-symbols-rounded">sell</span><span><span class="track-dropdown-genre-label">Genre:</span><span class="td-genre-val"> \u2026</span></span>`;
  menu.appendChild(genreRow);

  const exploreBtn = document.createElement('button');
  exploreBtn.className = 'track-dropdown-item track-dropdown-explore';
  exploreBtn.setAttribute('role', 'menuitem');
  exploreBtn.style.display = 'none';
  exploreBtn.innerHTML =
    `<span class="material-symbols-rounded track-dropdown-icon" style="color:var(--md-primary)">bolt</span>` +
    `<span style="color:var(--md-primary);font-weight:500">Explore this genre</span>`;
  menu.appendChild(exploreBtn);

  const divider = document.createElement('div');
  divider.className = 'track-dropdown-divider';
  menu.appendChild(divider);

  items.forEach(({ icon, label, fn }) => {
    const el = document.createElement('button');
    el.className = 'track-dropdown-item';
    el.setAttribute('role', 'menuitem');
    el.innerHTML = `<span class="material-symbols-rounded track-dropdown-icon">${icon}</span><span>${label}</span>`;
    el.addEventListener('click', e => {
      e.stopPropagation();
      _plClosePlMenu();
      fn();
    });
    menu.appendChild(el);
  });

  document.body.appendChild(menu);
  _activePlMenu = menu;

  const rect = btn.getBoundingClientRect();
  const mw   = 220;
  const mh   = menu.offsetHeight || (items.length * 52 + 108);
  let top  = rect.bottom + 4;
  let left = rect.right  - mw;
  if (top + mh > window.innerHeight - 16) top  = rect.top - mh - 4;
  if (left < 8)                            left = 8;
  if (left + mw > window.innerWidth - 8)  left = window.innerWidth - mw - 8;
  menu.style.top  = `${Math.max(top, 8)}px`;
  menu.style.left = `${left}px`;

  if (typeof _resolveTrackGenre === 'function') {
    _resolveTrackGenre(trackName, artistName)
      .then(genre => {
        const el = menu.querySelector('.td-genre-val');
        if (el) el.textContent = genre ? ` ${genre}` : ' Unknown';
        if (genre && genre.toLowerCase() !== 'unknown' && genre !== '—') {
          exploreBtn.style.display = '';
          exploreBtn.addEventListener('click', e => {
            e.stopPropagation();
            _plClosePlMenu();
            if (typeof _doExploreGenrePlaylist === 'function') {
              _doExploreGenrePlaylist(genre, { source: 'playlist' });
            } else {
              navigateTo('genres');
            }
          });
        }
      })
      .catch(() => {
        const el = menu.querySelector('.td-genre-val');
        if (el) el.textContent = ' Unknown';
      });
  } else {
    const el = menu.querySelector('.td-genre-val');
    if (el) el.textContent = ' —';
  }

  setTimeout(() => {
    document.addEventListener('click', _plClosePlMenu, { once: true });
    document.addEventListener('touchstart', _plClosePlMenu, { once: true, passive: true });
  }, 0);
}

// ══════════════════════════════════════════════════════════════
//  EXPORT DIALOG
// ══════════════════════════════════════════════════════════════

function _plShowExportDialog(id) {
  _plExportCurrentId = id;
  _plExportFormat    = 'csv';

  const radios = document.querySelectorAll('input[name="plExportFmt"]');
  radios.forEach(r => { r.checked = (r.value === 'csv'); });
  _plExportUpdateOptionUI();

  const dialog = document.getElementById('plExportDialog');
  if (!dialog) return;

  if (dialog.parentNode !== document.body) {
    document.body.appendChild(dialog);
  }

  const shareBtn = document.getElementById('plExportShareBtn');
  if (shareBtn) shareBtn.style.display = '';

  dialog.classList.remove('hidden');
  requestAnimationFrame(() => dialog.classList.add('open'));
}

function _plExportClose() {
  const dialog = document.getElementById('plExportDialog');
  if (!dialog) return;
  dialog.classList.remove('open');
  setTimeout(() => dialog.classList.add('hidden'), 280);
}

function _plExportBackdropClick(e) {
  if (e.target === e.currentTarget) _plExportClose();
}

function _plExportSetFormat(fmt) {
  _plExportFormat = fmt;
  _plExportUpdateOptionUI();
}

function _plExportUpdateOptionUI() {
  ['csv', 'm3u'].forEach(fmt => {
    const el = document.getElementById(`plExportOpt${fmt.toUpperCase()}`);
    if (el) el.classList.toggle('selected', _plExportFormat === fmt);
  });
}

function _plExportSave() {
  const pl = _plLoad().find(p => p.id === _plExportCurrentId);
  if (!pl) { _plExportClose(); return; }
  if (_plExportFormat === 'csv') {
    _doExportCSV(pl);
  } else {
    _doExportM3U(pl);
  }
  _plExportClose();
}

async function _plExportShare() {
  const pl = _plLoad().find(p => p.id === _plExportCurrentId);
  if (!pl) { _plExportClose(); return; }

  let content, mimeType, filename;
  if (_plExportFormat === 'm3u') {
    content  = ['#EXTM3U', `#PLAYLIST:${pl.title}`, ...pl.tracks.map(t =>
      `#EXTINF:-1,${t.artist} - ${t.name}\n${t.url || ''}`
    )].join('\n');
    mimeType = 'audio/x-mpegurl';
    filename = _plM3UFilename(pl);
  } else {
    content  = ['Track,Artist', ...pl.tracks.map(t =>
      `"${t.name.replace(/"/g,'""')}","${t.artist.replace(/"/g,'""')}"`
    )].join('\n');
    mimeType = 'text/csv';
    filename = sanitizeFilename(pl.title) + '.csv';
  }

  const lines = [`${pl.title} (${pl.tracks.length} tracks)`, ''];
  pl.tracks.forEach((t, i) => lines.push(`${i + 1}. ${t.name} — ${t.artist}`));
  const fallbackText = lines.join('\n');

  _plExportClose();
  await Platform.shareFile(filename, content, mimeType, fallbackText, pl.title);
}

const _PL_TEMPLATE_LABELS = {
  'mix':             'AiMix',
  'recommendations': 'MyRecommendation',
  'tag':             'ByGenre',
  'similar-tracks':  'SimilarTracks',
  'start-mix':       'SimilarTracks',
  'similar-artists': 'SimilarTracks',
  'top':             'MyMix',
  'library':         'MyMix',
  'recent':          'MyMix',
};

function _plM3UFilename(pl) {
  const name     = sanitizeFilename((pl && pl.title) ? pl.title : 'Playlist');
  const template = (pl && _PL_TEMPLATE_LABELS[pl.mode]) || 'MyMix';
  return `${name}(${template}).m3u`;
}

function _doExportCSV(pl) {
  const rows     = ['Track,Artist', ...pl.tracks.map(t => `"${t.name.replace(/"/g,'""')}","${t.artist.replace(/"/g,'""')}"`
  )];
  const filename = sanitizeFilename(pl.title) + '.csv';
  triggerSave(filename, rows.join('\n'), 'text/csv');
}

function _doExportM3U(pl) {
  const lines = [
    '#EXTM3U',
    `#PLAYLIST:${pl.title}`,
    ...pl.tracks.map(t => `#EXTINF:-1,${t.artist} - ${t.name}\n${t.url || ''}`)
  ];
  triggerSave(_plM3UFilename(pl), lines.join('\n'), 'audio/x-mpegurl');
}

// ── Delete ────────────────────────────────────────────────────
// ── Generate Similar ─────────────────────────────────────────
/**
 * Derives a name for a new "similar" playlist generated from an
 * existing one. Uses the same app-wide multi-bank unique name
 * generator as every other playlist creation flow — no numeric
 * suffixes (e.g. no more "Echo 2", "Echo 3").
 */
function _plNextSimilarName(sourceName) {
  if (typeof _generateSmartPlaylistName === 'function') {
    return _generateSmartPlaylistName();
  }
  // Extremely defensive fallback if app.js somehow isn't loaded yet.
  return `${sourceName || 'Playlist'} Mix`;
}

/**
 * Generates a new playlist that is similar to the given one.
 * Seeds: up to 5 tracks from the source playlist.
 * Fetches similar tracks for each seed, deduplicates against
 * the original, shuffles and saves with a sequential name.
 */
async function _plGenerateSimilar(id) {
  const source = _plLoad().find(p => p.id === id);
  if (!source || !source.tracks?.length) {
    showToast('No tracks to base similar playlist on', 'error');
    return;
  }

  // Disable the button while running to prevent double-taps
  const btn = document.querySelector(
    `.pl-card[data-pl-id="${id}"] .pl-card-action-btn.similar`
  );
  if (btn) { btn.disabled = true; btn.style.opacity = '0.5'; }

  showToast('Generating similar playlist…');

  try {
    const targetCount = Math.max(source.tracks.length, 15);

    // Build exclusion set from original tracks
    const excluded = new Set(
      source.tracks.map(t => `${t.name}|${t.artist}`.toLowerCase())
    );

    // Pick up to 5 diverse seed tracks (spread across the playlist)
    const step  = Math.max(1, Math.floor(source.tracks.length / 5));
    const seeds = [];
    for (let i = 0; i < source.tracks.length && seeds.length < 5; i += step) {
      const t = source.tracks[i];
      if (t?.name && t?.artist) seeds.push(t);
    }

    // Collect candidates from track.getsimilar for each seed
    let candidates = [];
    await Promise.allSettled(seeds.map(async seed => {
      try {
        const data = await lfmCall({
          method:      'track.getsimilar',
          track:       seed.name,
          artist:      seed.artist,
          limit:       Math.min(targetCount * 4, 100),
          autocorrect: 1
        });
        const tracks = normaliseTracks(data?.similartracks?.track);
        candidates   = candidates.concat(tracks);
      } catch { /* skip failed seeds silently */ }
    }));

    // Also pull top tracks from the unique artists in the source playlist
    // to ensure variety when track.getsimilar returns thin results
    const uniqueArtists = [...new Set(
      source.tracks.map(t => t.artist).filter(Boolean)
    )].slice(0, 4);

    await Promise.allSettled(uniqueArtists.map(async artist => {
      try {
        const data = await lfmCall({
          method: 'artist.getsimilar',
          artist,
          limit:  8
        });
        const simArtists = shuffleArray(data?.similarartists?.artist || []).slice(0, 3);
        await Promise.allSettled(simArtists.map(async sa => {
          try {
            const d = await lfmCall({
              method: 'artist.gettoptracks',
              artist: sa.name,
              limit:  Math.ceil(targetCount / 4)
            });
            candidates = candidates.concat(normaliseTracks(d?.toptracks?.track));
          } catch {}
        }));
      } catch {}
    }));

    // Deduplicate candidates against themselves and the source playlist
    const seen  = new Set();
    const fresh = [];
    for (const t of candidates) {
      if (!t?.name || !t?.artist) continue;
      const k = `${t.name}|${t.artist}`.toLowerCase();
      if (excluded.has(k) || seen.has(k)) continue;
      seen.add(k);
      fresh.push(t);
    }

    if (fresh.length === 0) {
      showToast('Couldn\'t find enough new tracks — try again', 'error');
      return;
    }

    // Artist diversity cap: max 2 tracks per artist
    const artistCount = {};
    const diverse = [];
    for (const t of shuffleArray(fresh)) {
      const a = t.artist.toLowerCase();
      if ((artistCount[a] || 0) >= 2) continue;
      artistCount[a] = (artistCount[a] || 0) + 1;
      diverse.push(t);
      if (diverse.length >= targetCount) break;
    }

    const newTitle = _plNextSimilarName(source.title);

    // ── Pre-enrich artwork for cover grid ───────────────────────
    // The tracks from track.getsimilar / artist.gettoptracks rarely
    // carry image URLs.  Fetch art for the first 4 tracks now so the
    // 2×2 cover grid in the card header is populated immediately.
    // Uses the same priority chain as _resolveTrackArt:
    //   1. Keep existing track.image if already valid
    //   2. Last.fm track.getInfo album art
    //   3. iTunes fallback (when enabled)
    if (typeof _resolveTrackArt === 'function') {
      await Promise.allSettled(diverse.slice(0, 4).map(async (t, i) => {
        // Skip if the track already has a valid image from the API response
        if (t.image && t.image.trim()) return;
        try {
          const url = await _resolveTrackArt(t.name, t.artist);
          if (url) diverse[i] = { ...diverse[i], image: url };
        } catch { /* silently skip — card will enrich lazily on expand */ }
      }));
    }

    // Invalidate cache before saving so _plNextSimilarName sees the
    // new title on any immediate re-call
    _plCache = null;

    _plSave({
      id:       Date.now(),
      title:    newTitle,
      subtitle: `Similar to "${source.title}"`,
      mode:     source.mode || '',
      tracks:   diverse,
      date:     Date.now()
    });

    // Invalidate again after save so the render reads fresh data
    _plCache = null;
    _plRenderSaved(true);

    showToast(`"${newTitle}" created ✓`, 'success');
  } catch (err) {
    showToast('Failed to generate: ' + err.message, 'error');
  } finally {
    if (btn) { btn.disabled = false; btn.style.opacity = ''; }
  }
}

function _plDelete(id) {
  showModal(
    'Delete playlist?',
    'This action cannot be undone.',
    () => {
      const saved = _plLoad().filter(p => p.id !== id);
      _plCache = saved;   // update in-memory cache before writing storage
      localStorage.setItem(PL_STORAGE_KEY, JSON.stringify(saved));
      _plRenderSaved();
    }
  );
}

// ── localStorage ──────────────────────────────────────────────
function _plSave(playlist) {
  const saved = _plLoad();
  const isDupe = saved.some(p =>
    p.title === playlist.title &&
    p.tracks[0]?.name === playlist.tracks[0]?.name
  );
  if (!isDupe) {
    saved.push(playlist);
    if (saved.length > PL_MAX_SAVED) saved.splice(0, saved.length - PL_MAX_SAVED);
    localStorage.setItem(PL_STORAGE_KEY, JSON.stringify(saved));
    _plCache = saved;   // update in-memory cache to match what was written
  }
}

function _plLoad() {
  if (_plCache !== null) return _plCache;
  try {
    _plCache = JSON.parse(localStorage.getItem(PL_STORAGE_KEY) || '[]');
  } catch {
    _plCache = [];
  }
  return _plCache;
}

// ── Tiny helpers ──────────────────────────────────────────────
function _plEl(id) { return document.getElementById(id); }

function _plFmtDate(ts) {
  if (!ts) return '';
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

function _plStripEmoji(title) {
  if (!title) return '';
  return title.replace(/^[\p{Emoji_Presentation}\p{Extended_Pictographic}]\uFE0F?\s*/gu, '').trim();
}

// ── Background artwork enrichment ────────────────────────────
/**
 * Enriches artwork for tracks visible in a specific card's inner element.
 * Called after lazy-rendering track rows for an expanded card.
 * Only processes rows currently in the DOM — collapsed cards are skipped.
 */
async function _plEnrichCardArt(innerEl) {
  if (!state.apiKey || typeof _resolveTrackArt !== 'function') return;
  if (!innerEl) return;

  const rows = [...innerEl.querySelectorAll('.pl-track-row')].filter(row => {
    const img = row.querySelector('.pl-track-art');
    return !img || img.classList.contains('errored') || !img.getAttribute('src');
  });
  if (!rows.length) return;

  const BATCH = 5;
  for (let i = 0; i < rows.length; i += BATCH) {
    await Promise.allSettled(rows.slice(i, i + BATCH).map(async row => {
      const name   = row.dataset.lpName;
      const artist = row.dataset.lpArtist;
      if (!name || !artist) return;
      try {
        const url = await _resolveTrackArt(name, artist);
        if (url) _plPatchTrackArt(row, url);
      } catch {}
    }));
  }
}

/**
 * Legacy entry point — scans all currently rendered (expanded) cards.
 * With lazy rendering, this only finds rows that have already been
 * injected into the DOM, which is the correct and efficient behaviour.
 */
async function _plEnrichArt() {
  if (!state.apiKey || typeof _resolveTrackArt !== 'function') return;
  const cardsEl = _plEl('plCards');
  if (!cardsEl) return;
  await _plEnrichCardArt(cardsEl);
}

/**
 * Patch a single .pl-track-row DOM element with a resolved artwork URL.
 */
function _plPatchTrackArt(row, url) {
  const wrap     = row.querySelector('.pl-track-art-wrap');
  const fallback = row.querySelector('.pl-track-art-fallback');
  if (!wrap || !url) return;
  let img = wrap.querySelector('.pl-track-art');
  if (!img) {
    img = document.createElement('img');
    img.className = 'pl-track-art';
    img.alt       = '';
    img.style.cssText = 'opacity:0;transition:opacity 0.28s ease';
    img.onerror = function () { this.classList.add('errored'); if (fallback) fallback.style.display = 'block'; };
    wrap.insertBefore(img, wrap.firstChild);
  } else {
    img.style.opacity    = '0';
    img.style.transition = 'opacity 0.28s ease';
    img.classList.remove('errored');
  }
  img.onload = () => {
    img.style.opacity = '1';
    if (fallback) fallback.style.display = 'none';
  };
  img.src = url;
}
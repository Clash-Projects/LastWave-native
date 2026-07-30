/* ════════════════════════════════════════════════════════════
   search.js — Global Search screen logic
   Direct Last.fm API calls (no lfmCall wrapper) for reliability.
   Searches tracks / artists / albums in real time (350ms debounce).

   CHANGES v2:
   ─ Image enrichment: track.search → track.getInfo → artist image → placeholder
   ─ In-memory image cache (session-scoped, 300-entry LRU)
   ─ Chevron replaced with 3-dot menu (more_vert)
   ─ Popup uses shared .track-dropdown-menu classes (same as home)
   ─ Item tap → YouTube (unchanged); 3-dot → popup (no nav)
   ════════════════════════════════════════════════════════════ */

'use strict';

let _searchTab        = 'tracks';
let _searchDebounce   = null;
let _searchLastQuery  = '';
let _searchInputBound = false;

// ── Image cache (session-scoped, max 300 entries) ─────────────
const _imgCache    = new Map();
const _IMG_MAX     = 300;

// ── Active dropdown instance ──────────────────────────────────
let _activeDropdown = null;

// ═════════════════════════════════════════════════════════════
//  SCREEN INIT  (called by nav.js on every visit)
// ═════════════════════════════════════════════════════════════
function screen_search() {
  _switchTab(_searchTab);
  _positionTabIndicator();

  const input = document.getElementById('searchInput');
  if (input && !_searchInputBound) {
    _searchInputBound = true;
    input.addEventListener('input', _onSearchInput);
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); _flushSearch(); }
    });
  }

  setTimeout(() => {
    if (input) {
      input.focus();
      const q = input.value.trim();
      _updateClearBtn(input.value);
      if (q) { _showState('loading'); _doSearch(q); }
      else     _showState('idle');
    }
  }, 260);
}

// ── Input handler ─────────────────────────────────────────────
function _onSearchInput(e) {
  const q = e.target.value.trim();
  _updateClearBtn(e.target.value);
  clearTimeout(_searchDebounce);
  if (!q) { _searchLastQuery = ''; _showState('idle'); return; }
  _showState('loading');
  _searchDebounce = setTimeout(() => _doSearch(q), 350);
}

function _flushSearch() {
  clearTimeout(_searchDebounce);
  const q = (document.getElementById('searchInput')?.value || '').trim();
  if (q) _doSearch(q);
}

function _searchClear() {
  clearTimeout(_searchDebounce);
  const input = document.getElementById('searchInput');
  if (input) { input.value = ''; input.focus(); }
  _updateClearBtn('');
  _searchLastQuery = '';
  _showState('idle');
}

function _updateClearBtn(val) {
  document.getElementById('searchClearBtn')?.classList.toggle('hidden', !val);
}

// ── Tab switcher ──────────────────────────────────────────────
function _switchTab(tab) {
  _searchTab = tab;
  ['tracks', 'artists', 'albums'].forEach(t => {
    const id = 'tab' + t[0].toUpperCase() + t.slice(1);
    const el = document.getElementById(id);
    if (!el) return;
    const on = t === tab;
    el.classList.toggle('active', on);
    el.setAttribute('aria-selected', on ? 'true' : 'false');
  });
  _positionTabIndicator();
  const q = (document.getElementById('searchInput')?.value || '').trim();
  if (q) { clearTimeout(_searchDebounce); _showState('loading'); _doSearch(q); }
}

function _positionTabIndicator() {
  const ind = document.getElementById('searchTabIndicator');
  if (!ind) return;
  const tabs = ['tracks', 'artists', 'albums'];
  const idx  = tabs.indexOf(_searchTab);
  if (idx < 0) return;
  const pct = 100 / tabs.length;
  ind.style.left  = `${idx * pct}%`;
  ind.style.width = `${pct}%`;
}

// ═════════════════════════════════════════════════════════════
//  CORE SEARCH  ─  direct fetch, no lfmCall wrapper
// ═════════════════════════════════════════════════════════════

async function _doSearch(query) {
  const apiKey = (typeof state !== 'undefined' ? state.apiKey : '') ||
                  localStorage.getItem('lw_apikey') || '';

  if (!apiKey) {
    if (typeof showToast === 'function') showToast('Add your API key in Settings to search', 'error');
    _showState('idle');
    return;
  }

  _searchLastQuery = query;

  try {
    let results = [];
    switch (_searchTab) {
      case 'tracks':  results = await _fetchTracks(query, apiKey);  break;
      case 'artists': results = await _fetchArtists(query, apiKey); break;
      case 'albums':  results = await _fetchAlbums(query, apiKey);  break;
    }

    // Discard stale response if user typed something new
    if (query !== _searchLastQuery) return;

    if (results.length) {
      _showState('results');
      _renderResults(results);
      // Enrich missing images in the background (non-blocking)
      _enrichResultImages(results, query, apiKey);
    } else {
      _showState('empty');
    }

  } catch (err) {
    if (query !== _searchLastQuery) return;
    console.warn('[Search]', err);
    if (typeof showToast === 'function') showToast('Search failed — try again', 'error');
    _showState('empty');
  }
}

// ── Last.fm fetch helper ──────────────────────────────────────

async function _lfmGet(params, apiKey) {
  const url = new URL('https://ws.audioscrobbler.com/2.0/');
  const p   = { ...params, api_key: apiKey, format: 'json' };
  Object.entries(p).forEach(([k, v]) => url.searchParams.set(k, String(v)));
  const res  = await fetch(url.toString());
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  if (data.error) throw new Error(`Last.fm error ${data.error}: ${data.message}`);
  return data;
}

// ── Entity fetchers ───────────────────────────────────────────

async function _fetchTracks(query, apiKey) {
  const data = await _lfmGet({ method: 'track.search', track: query, limit: 30 }, apiKey);
  const raw  = data?.results?.trackmatches?.track;
  if (!raw) return [];
  const arr  = Array.isArray(raw) ? raw : [raw];
  return arr
    .filter(t => t && t.name && t.artist)
    .map(t => ({
      type:    'track',
      name:    t.name,
      sub:     t.artist,
      image:   _bestImg(t.image),
      _track:  t.name,
      _artist: t.artist,
      _url:    t.url || '',
    }));
}

async function _fetchArtists(query, apiKey) {
  const data = await _lfmGet({ method: 'artist.search', artist: query, limit: 30 }, apiKey);
  const raw  = data?.results?.artistmatches?.artist;
  if (!raw) return [];
  const arr  = Array.isArray(raw) ? raw : [raw];
  return arr
    .filter(a => a && a.name)
    .map(a => ({
      type:    'artist',
      name:    a.name,
      sub:     a.listeners ? parseInt(a.listeners).toLocaleString() + ' listeners' : '',
      image:   _bestImg(a.image),
      _artist: a.name,
      _url:    a.url || '',
    }));
}

async function _fetchAlbums(query, apiKey) {
  const data = await _lfmGet({ method: 'album.search', album: query, limit: 30 }, apiKey);
  const raw  = data?.results?.albummatches?.album;
  if (!raw) return [];
  const arr  = Array.isArray(raw) ? raw : [raw];
  return arr
    .filter(a => a && a.name)
    .map(a => ({
      type:    'album',
      name:    a.name,
      sub:     a.artist || '',
      image:   _bestImg(a.image),
      _artist: a.artist || '',
      _url:    a.url || '',
    }));
}

// ── Best image from Last.fm image array ───────────────────────
const _NO_ART = '2a96cbd8b46e442fc41c2b86b821562f';

function _bestImg(imgArr) {
  if (!Array.isArray(imgArr)) return '';
  const real = imgArr.filter(i => i?.['#text'] && !i['#text'].includes(_NO_ART));
  return (
    real.find(i => i.size === 'extralarge')?.['#text'] ||
    real.find(i => i.size === 'large'     )?.['#text'] ||
    real.find(i => i.size === 'medium'    )?.['#text'] ||
    real[0]?.['#text'] || ''
  );
}

// ═════════════════════════════════════════════════════════════
//  IMAGE ENRICHMENT  ─  fallback chain + LRU cache
// ═════════════════════════════════════════════════════════════

/**
 * Resolves the best image URL for a search item.
 * Fallback chain:
 *   Track  → album image (track.getInfo) → artist image → iTunes → ''
 *   Artist → iTunes (Last.fm artist images deprecated 2019) → Last.fm fallback → ''
 *   Album  → album.getInfo → iTunes → artist image → ''
 * Results are memoised in _imgCache (session) AND the persistent disk cache.
 */
async function _resolveImage(item, apiKey) {
  const cacheKey = `${item.type}:${(item._track || item.name)}:${(item._artist || item.sub || '')}`.toLowerCase();

  // 1. Memory cache (fastest)
  if (_imgCache.has(cacheKey)) {
    const val = _imgCache.get(cacheKey);
    _imgCache.delete(cacheKey);
    _imgCache.set(cacheKey, val); // LRU refresh
    return val;
  }

  // 2. Persistent disk cache — survives app relaunches
  const diskKey = `search:${cacheKey}`;
  if (typeof _artDiskGet === 'function') {
    const diskVal = _artDiskGet(diskKey);
    if (diskVal !== null) {
      _cacheImg(cacheKey, diskVal);
      return diskVal;
    }
  }

  // For tracks that already have a (non-artist) image from initial search, trust it
  if (item.image && item.type === 'track') {
    _cacheImg(cacheKey, item.image);
    if (typeof _artDiskSet === 'function') _artDiskSet(diskKey, item.image);
    return item.image;
  }

  let imgUrl = '';

  try {
    if (item.type === 'track') {
      // Step 1: track.getInfo → album art (best quality)
      try {
        const d     = await _lfmGet({ method: 'track.getInfo', track: item._track, artist: item._artist, autocorrect: 1 }, apiKey);
        const album = d.track?.album;
        if (album?.image) imgUrl = _bestImg(album.image);
        if (!imgUrl && d.track?.image) imgUrl = _bestImg(d.track.image);
      } catch {}
      // Step 2: artist image
      if (!imgUrl && item._artist) {
        try {
          const d = await _lfmGet({ method: 'artist.getInfo', artist: item._artist, autocorrect: 1 }, apiKey);
          imgUrl  = _bestImg(d.artist?.image);
        } catch {}
      }
      // Step 3: iTunes fallback
      if (!imgUrl && typeof _itunesFetchArtwork === 'function') {
        try { imgUrl = await _itunesFetchArtwork(item._track, item._artist, 'track'); } catch {}
      }

    } else if (item.type === 'artist') {
      // Last.fm deprecated ALL artist images in 2019.
      // artist.search and artist.getInfo now return generic placeholder silhouettes.
      // iTunes returns real album artwork for the artist — use it first.
      if (typeof _itunesFetchArtwork === 'function') {
        try { imgUrl = await _itunesFetchArtwork(item.name, '', 'artist'); } catch {}
      }
      // Only accept Last.fm image if it looks like a genuine user-upload (/u/ path)
      if (!imgUrl) {
        try {
          const d = await _lfmGet({ method: 'artist.getInfo', artist: item.name, autocorrect: 1 }, apiKey);
          const lfmImg = _bestImg(d.artist?.image);
          if (lfmImg && lfmImg.includes('/u/')) imgUrl = lfmImg;
        } catch {}
      }

    } else if (item.type === 'album') {
      // Step 1: album.getInfo
      try {
        const d = await _lfmGet({ method: 'album.getInfo', album: item.name, artist: item._artist || '', autocorrect: 1 }, apiKey);
        imgUrl  = _bestImg(d.album?.image);
      } catch {}
      // Step 2: iTunes (often better quality than Last.fm for albums)
      if (!imgUrl && typeof _itunesFetchArtwork === 'function') {
        try { imgUrl = await _itunesFetchArtwork(item.name, item._artist, 'album'); } catch {}
      }
      // Step 3: artist image as last resort
      if (!imgUrl && item._artist) {
        try {
          const d = await _lfmGet({ method: 'artist.getInfo', artist: item._artist, autocorrect: 1 }, apiKey);
          imgUrl  = _bestImg(d.artist?.image);
        } catch {}
      }
    }
  } catch {}

  // Save to both caches (empty string = confirmed no art, prevents future network calls)
  _cacheImg(cacheKey, imgUrl);
  if (typeof _artDiskSet === 'function') _artDiskSet(diskKey, imgUrl);
  return imgUrl;
}

function _cacheImg(key, val) {
  // Evict oldest entry if over capacity
  if (_imgCache.size >= _IMG_MAX) {
    _imgCache.delete(_imgCache.keys().next().value);
  }
  _imgCache.set(key, val);
}

/**
 * Background image enrichment pass.
 * For each result with no image, resolves one and patches the DOM.
 * Runs in batches of 4 to avoid flooding the API.
 */
async function _enrichResultImages(items, querySnapshot, apiKey) {
  const container = document.getElementById('searchResults');
  if (!container) return;

  const toEnrich = items
    .map((item, idx) => ({ item, idx }))
    // Always enrich:
    //   · items with no image
    //   · albums  (album.search rarely returns art)
    //   · artists (Last.fm deprecated artist images in 2019; all returned URLs are placeholders)
    .filter(({ item }) => !item.image || item.type === 'album' || item.type === 'artist');

  if (!toEnrich.length) return;

  const BATCH = 6;
  for (let i = 0; i < toEnrich.length; i += BATCH) {
    // Abort if the user has triggered a new search
    if (querySnapshot !== _searchLastQuery) return;

    await Promise.allSettled(
      toEnrich.slice(i, i + BATCH).map(async ({ item, idx }) => {
        const imgUrl = await _resolveImage(item, apiKey);
        if (!imgUrl) return;                            // no image found, keep placeholder
        if (querySnapshot !== _searchLastQuery) return; // search changed, discard

        item.image = imgUrl;

        // Patch the matching DOM row
        const el = container.querySelector(`[data-search-idx="${idx}"]`);
        if (!el) return;

        const wrap     = el.querySelector('.search-result-art-wrap');
        const fallback = el.querySelector('.search-result-art-fallback');
        if (!wrap) return;

        let img = el.querySelector('.search-result-art');
        if (!img) {
          img = document.createElement('img');
          img.className = 'search-result-art';
          img.alt       = '';
          img.loading   = 'lazy';
          img.style.cssText = 'opacity:0;transition:opacity 0.26s ease';
          wrap.insertBefore(img, fallback);
        } else {
          img.style.opacity    = '0';
          img.style.transition = 'opacity 0.26s ease';
        }

        img.onload  = () => {
          img.style.opacity = '1';
          if (fallback) fallback.style.display = 'none';
        };
        img.onerror = () => {
          img.style.display = 'none';
          if (fallback) fallback.style.display = 'flex';
        };
        img.src = imgUrl;
      })
    );
  }
}

// ═════════════════════════════════════════════════════════════
//  RESULT RENDERING
// ═════════════════════════════════════════════════════════════

function _renderResults(items) {
  const container = document.getElementById('searchResults');
  if (!container) return;
  container.innerHTML = '';

  const frag = document.createDocumentFragment();

  items.forEach((item, idx) => {
    const isArtist   = item.type === 'artist';
    const hasImg     = !!item.image;
    const fallbackIcon = isArtist ? 'person' : item.type === 'album' ? 'album' : 'music_note';

    const el = document.createElement('div');
    el.className = 'search-result-item';
    el.setAttribute('role', 'listitem');
    el.dataset.searchIdx = idx;                   // used by enrichment patcher
    el.style.animationDelay = `${Math.min(idx * 0.025, 0.20)}s`;

    el.innerHTML = `
      <div class="search-result-art-wrap${isArtist ? ' artist-shape' : ''}">
        ${hasImg
          ? `<img src="${_esc(item.image)}" alt="" class="search-result-art" loading="lazy"
                  onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
          : ''}
        <span class="material-symbols-rounded search-result-art-fallback"
              style="${hasImg ? 'display:none' : 'display:flex'}">${fallbackIcon}</span>
      </div>
      <div class="search-result-text">
        <div class="search-result-title">${_esc(item.name)}</div>
        ${item.sub ? `<div class="search-result-sub">${_esc(item.sub)}</div>` : ''}
      </div>
      <button class="search-result-menu-btn" aria-label="More options" aria-haspopup="true">
        <span class="material-symbols-rounded">more_vert</span>
      </button>`;

    // Row tap → navigate (YouTube for tracks, Last.fm for artists/albums)
    el.addEventListener('click', e => {
      if (e.target.closest('.search-result-menu-btn')) return;
      _handleClick(item);
    });

    // 3-dot tap → dropdown
    el.querySelector('.search-result-menu-btn').addEventListener('click', e => {
      e.stopPropagation();
      _showSearchDropdown(item, e.currentTarget);
    });

    frag.appendChild(el);
  });

  container.appendChild(frag);
}

// ── Item tap action ───────────────────────────────────────────

function _handleClick(item) {
  switch (item.type) {
    case 'track':
      if (typeof openTrackOnYouTube === 'function')
        openTrackOnYouTube(item._track, item._artist);
      break;
    case 'artist':
      if (typeof viewArtistOnLastFm === 'function')
        viewArtistOnLastFm(item._artist);
      else if (item._url)
        window.open(item._url, '_blank');
      break;
    case 'album':
      if (item._url) {
        if (typeof openUrl === 'function') openUrl(item._url);
        else window.open(item._url, '_blank');
      }
      break;
  }
}

// ═════════════════════════════════════════════════════════════
//  3-DOT DROPDOWN MENU
//  Uses the same .track-dropdown-menu CSS already in app.css.
//  Appended to <body> so it escapes any overflow:hidden parents.
// ═════════════════════════════════════════════════════════════

function _showSearchDropdown(item, anchorBtn) {
  _closeSearchDropdown();          // close any existing dropdown first

  const isTrack  = item.type === 'track';
  const isArtist = item.type === 'artist';

  // ── Build menu items ──────────────────────────────────────
  const menuItems = [];

  // "Start Mix from this" — tracks only
  if (isTrack) {
    menuItems.push({
      icon:  'queue_music',
      label: 'Start Mix from this',
      fn() {
        if (typeof startMixFromTrack === 'function')
          startMixFromTrack(item._track, item._artist);
        else if (typeof showToast === 'function')
          showToast('startMixFromTrack not available', 'error');
      }
    });
  }

  // "Open in Last.fm" — all types
  menuItems.push({
    icon:  'open_in_new',
    label: 'Open in Last.fm',
    fn() {
      let url = '';
      if (isTrack) {
        url = `https://www.last.fm/music/${encodeURIComponent(item._artist)}/_/${encodeURIComponent(item._track)}`;
      } else if (isArtist) {
        url = `https://www.last.fm/music/${encodeURIComponent(item.name)}`;
      } else {
        url = item._url || `https://www.last.fm/music/${encodeURIComponent(item._artist || '')}/${encodeURIComponent(item.name)}`;
      }
      if (typeof openUrl === 'function') openUrl(url);
      else window.open(url, '_blank');
    }
  });

  // "Play on YouTube" — tracks only
  if (isTrack) {
    menuItems.push({
      icon:  'smart_display',
      label: 'Play on YouTube',
      fn() {
        if (typeof openTrackOnYouTube === 'function')
          openTrackOnYouTube(item._track, item._artist);
      }
    });
  }

  // "Refresh Cover Art" — tracks only
  if (isTrack) {
    menuItems.push({
      icon:  'image_search',
      label: 'Refresh Cover Art',
      async fn() {
        if (typeof showToast === 'function') showToast('Refreshing cover art\u2026');
        const url = typeof _refreshTrackArtwork === 'function'
          ? await _refreshTrackArtwork(item._track, item._artist)
          : '';
        if (url) {
          item.image = url;
          // Patch all visible DOM rows for this search result
          const container = document.getElementById('searchResults');
          if (container) {
            container.querySelectorAll('.search-result-item').forEach(row => {
              const titleEl = row.querySelector('.search-result-title');
              const subEl   = row.querySelector('.search-result-sub');
              if (titleEl?.textContent.trim() === item._track && subEl?.textContent.trim() === item._artist) {
                const wrap     = row.querySelector('.search-result-art-wrap');
                const fallback = row.querySelector('.search-result-art-fallback');
                if (!wrap) return;
                let img = row.querySelector('.search-result-art');
                if (!img) {
                  img = document.createElement('img');
                  img.className = 'search-result-art';
                  img.alt       = '';
                  img.loading   = 'lazy';
                  img.style.cssText = 'opacity:0;transition:opacity 0.26s ease';
                  wrap.insertBefore(img, fallback);
                }
                img.onload  = () => { img.style.opacity = '1'; if (fallback) fallback.style.display = 'none'; };
                img.onerror = () => { img.style.display = 'none'; if (fallback) fallback.style.display = 'flex'; };
                img.src = url;
              }
            });
          }
          if (typeof showToast === 'function') showToast('Cover art updated \u2713', 'success');
        } else {
          if (typeof showToast === 'function') showToast('Cover art not available', 'error');
        }
      }
    });
  }

  // "Delete Scrobble" — tracks only
  if (isTrack) {
    menuItems.push({
      icon:  'delete',
      label: 'Delete Scrobble',
      fn() {
        if (!state.sessionKey) { showToast('Sign in to delete scrobbles', 'error'); return; }
        showModal(
          'Delete Scrobble?',
          `Remove \u201c${item._track}\u201d by ${item._artist} from your Last.fm history?`,
          async () => { await _lfmDeleteScrobble(item._track, item._artist, null); }
        );
      }
    });
  }

  // ── Build DOM ─────────────────────────────────────────────
  const menuEl = document.createElement('div');
  menuEl.className  = 'track-dropdown-menu';
  menuEl.setAttribute('role', 'menu');

  // Genre row for tracks (async fill-in)
  if (isTrack) {
    const genreRow = document.createElement('div');
    genreRow.className = 'track-dropdown-genre';
    genreRow.innerHTML = `<span class="material-symbols-rounded">sell</span><span><span class="track-dropdown-genre-label">Genre:</span><span class="td-genre-val"> \u2026</span></span>`;
    menuEl.appendChild(genreRow);

    // "Explore this genre" — hidden until genre resolves
    const exploreBtn = document.createElement('button');
    exploreBtn.className = 'track-dropdown-item track-dropdown-explore';
    exploreBtn.setAttribute('role', 'menuitem');
    exploreBtn.style.display = 'none';
    exploreBtn.innerHTML =
      `<span class="material-symbols-rounded track-dropdown-icon" style="color:var(--md-primary)">bolt</span>` +
      `<span style="color:var(--md-primary);font-weight:500">Explore this genre</span>`;
    menuEl.appendChild(exploreBtn);

    const divider = document.createElement('div');
    divider.className = 'track-dropdown-divider';
    menuEl.appendChild(divider);

    if (typeof _resolveTrackGenre === 'function') {
      _resolveTrackGenre(item._track, item._artist).then(genre => {
        const el = menuEl.querySelector('.td-genre-val');
        if (el) el.textContent = genre ? ` ${genre}` : ' Unknown';
        // Reveal explore button only when a real genre is known
        if (genre && genre.toLowerCase() !== 'unknown' && genre !== '—') {
          // Use primary genre (first entry before comma)
          const primaryGenre = genre.split(',')[0].trim();
          exploreBtn.style.display = '';
          exploreBtn.addEventListener('click', e => {
            e.stopPropagation();
            _closeSearchDropdown();
            if (typeof _doExploreGenrePlaylist === 'function') {
              _doExploreGenrePlaylist(primaryGenre, { source: 'search' });
            } else {
              navigateTo('genres');
            }
          });
        }
      }).catch(() => {
        const el = menuEl.querySelector('.td-genre-val');
        if (el) el.textContent = ' Unknown';
      });
    }
  }

  menuItems.forEach((mi) => {
    const btn = document.createElement('button');
    btn.className = 'track-dropdown-item';
    btn.setAttribute('role', 'menuitem');
    btn.innerHTML = `<span class="material-symbols-rounded track-dropdown-icon">${mi.icon}</span>${_esc(mi.label)}`;
    btn.addEventListener('click', e => {
      e.stopPropagation();
      _closeSearchDropdown();
      mi.fn();
    });
    menuEl.appendChild(btn);
  });

  document.body.appendChild(menuEl);
  _activeDropdown = menuEl;

  // ── Position ──────────────────────────────────────────────
  _positionDropdown(menuEl, anchorBtn);

  // ── Outside-click dismissal ───────────────────────────────
  function _outside(e) {
    if (!menuEl.contains(e.target)) {
      _closeSearchDropdown();
      document.removeEventListener('click',      _outside, { capture: true });
      document.removeEventListener('touchstart', _outside, { capture: true });
    }
  }
  setTimeout(() => {
    document.addEventListener('click',      _outside, { capture: true });
    document.addEventListener('touchstart', _outside, { capture: true, passive: true });
    menuEl._outsideFn = _outside;
  }, 0);
}

function _positionDropdown(menuEl, anchorBtn) {
  const _doPos = () => {
    const rect  = anchorBtn.getBoundingClientRect();
    const menuW = 224;
    const menuH = menuEl.offsetHeight || 180;
    const vw    = window.innerWidth;
    const vh    = window.innerHeight;

    let top  = rect.bottom + 4;
    let left = rect.right  - menuW;

    // Clamp horizontally
    if (left < 8)               left = 8;
    if (left + menuW > vw - 8)  left = vw - menuW - 8;

    // Flip upward if not enough space below
    if (top + menuH > vh - 8)   top  = rect.top - menuH - 4;

    menuEl.style.top  = `${Math.max(Math.round(top), 8)}px`;
    menuEl.style.left = `${Math.round(left)}px`;
  };

  // Run immediately; if height is still 0 (not yet painted), re-run in next frame
  _doPos();
  if (!menuEl.offsetHeight) requestAnimationFrame(_doPos);
}

function _closeSearchDropdown() {
  if (!_activeDropdown) return;
  const el = _activeDropdown;
  _activeDropdown = null;

  // Remove outside-click listener if it was attached
  if (el._outsideFn) {
    document.removeEventListener('click',      el._outsideFn, { capture: true });
    document.removeEventListener('touchstart', el._outsideFn, { capture: true });
  }

  el.classList.add('track-dropdown-leaving');
  el.addEventListener('animationend', () => el.remove(), { once: true });
  setTimeout(() => { if (el.parentNode) el.remove(); }, 250); // safety removal
}

// ═════════════════════════════════════════════════════════════
//  STATE HELPERS
// ═════════════════════════════════════════════════════════════

function _showState(s) {
  const map = {
    idle:    'searchIdle',
    loading: 'searchLoading',
    empty:   'searchEmpty',
    results: 'searchResults',
  };
  Object.entries(map).forEach(([key, id]) => {
    const el = document.getElementById(id);
    if (el) el.classList.toggle('hidden', key !== s);
  });
}

function _esc(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&')
    .replace(/</g, '<')
    .replace(/>/g, '>')
    .replace(/"/g, '&quot;');
}
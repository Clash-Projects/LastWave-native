/* ════════════════════════════════════════════════════════════
   genres.js — Genre analytics + Genre Detail screen
   v3: A-Z sort replaces Mix · Cover art enrichment for tracks
   ════════════════════════════════════════════════════════════ */

'use strict';

// ── Module state ──────────────────────────────────────────────
let _genresFilterOpen    = false;
let _genresPeriod        = 'overall';

// Genre detail state
let _gDetailName        = '';
let _gDetailSort        = 'popular';   // 'popular' | 'az'
let _gDetailPage        = 1;
let _gDetailLoading     = false;
let _gDetailAllTracks   = [];          // all loaded tracks (for A-Z client sort)
let _gDetailForceGlobal = false;       // true when "Find New" is active

// ── User-genre data (built during genre fetch, used in detail) ─
let _genreArtistMap      = {};   // genreLowercase → [artistName, …]
let _userTopArtistsCache = null; // { period, artists[] }

const _GENRE_PERIOD_LABELS = {
  '7day':    'Past 7 Days',
  '1month':  'This Month',
  '12month': 'Last 12 Months',
  'overall': 'Overall'
};

// ── Image cache (shared, max 400 entries) ─────────────────────
const _gdImgCache  = new Map();
const _GD_IMG_MAX  = 400;
const _GD_NO_ART   = '2a96cbd8b46e442fc41c2b86b821562f';

// ── Screen init ───────────────────────────────────────────────
async function screen_genres() {
  // Register hardware-back handler:
  // 1st press closes the detail sheet if open; 2nd press lets nav.js pop the stack.
  window._lwScreenBackHandlers['genres'] = function () {
    // Close active dropdown first
    if (_activeGDDropdown) {
      _activeGDDropdown.classList.add('track-dropdown-leaving');
      setTimeout(() => { if (_activeGDDropdown) { _activeGDDropdown.remove(); _activeGDDropdown = null; } }, 160);
      return true;
    }
    // Close genre detail overlay if open
    const overlay = document.getElementById('genreDetailOverlay');
    if (overlay && overlay.classList.contains('open')) {
      _closeGenreDetail();
      return true;
    }
    // Nothing intercepted — let nav.js handle the back (pop history)
    return false;
  };

  if (!state.username || !state.apiKey) {
    _genresShowError('Enter your username and API key in Settings first.', 'No credentials found');
    return;
  }
  await _genresLoad();
}

async function _genresLoad() {
  _genresShowLoading(true);
  try {
    const genres = await _genresFetch(_genresPeriod);
    _genresRender(genres);
    const sub = document.getElementById('genresSub');
    if (sub) sub.textContent = `Based on ${state.username}'s listening history`;
    if (typeof _initRipples === 'function') {
      _initRipples(document.querySelector('[data-screen="genres"]'));
    }
  } catch (err) {
    _genresShowError(err?.message || 'Could not load genres — try again.', 'Something went wrong');
  } finally {
    _genresShowLoading(false);
  }
}

// ── Filter ────────────────────────────────────────────────────
function toggleGenreFilter() {
  _genresFilterOpen = !_genresFilterOpen;
  const menu    = document.getElementById('genresFilterMenu');
  const chevron = document.getElementById('genresFilterChevron');
  menu?.classList.toggle('hidden', !_genresFilterOpen);
  chevron?.classList.toggle('open', _genresFilterOpen);
  if (_genresFilterOpen) {
    setTimeout(() => {
      document.addEventListener('click', _closeGenreFilterOutside, { once: true });
    }, 0);
  }
}

function _closeGenreFilterOutside(e) {
  if (!document.getElementById('genresFilterWrap')?.contains(e.target)) {
    _genresFilterOpen = false;
    document.getElementById('genresFilterMenu')?.classList.add('hidden');
    document.getElementById('genresFilterChevron')?.classList.remove('open');
  }
}

function setGenrePeriod(period) {
  _genresFilterOpen = false;
  document.getElementById('genresFilterMenu')?.classList.add('hidden');
  document.getElementById('genresFilterChevron')?.classList.remove('open');
  if (period === _genresPeriod) return;
  _genresPeriod = period;
  const labelEl = document.getElementById('genresFilterLabel');
  if (labelEl) labelEl.textContent = _GENRE_PERIOD_LABELS[period] || 'Overall';
  ['7day', '1month', '12month', 'overall'].forEach(p => {
    const suffix = p === '7day' ? '7day' : p === '1month' ? '1month' : p === '12month' ? '12month' : 'Overall';
    const opt   = document.getElementById('gfOpt' + suffix);
    const check = document.getElementById('gfCheck' + suffix);
    const isActive = p === period;
    opt?.classList.toggle('active', isActive);
    check?.classList.toggle('hidden', !isActive);
  });
  _genresLoad();
}


// ── Data fetch ────────────────────────────────────────────────
async function _genresFetch(period) {
  try {
    const params = { method: 'user.getTopTags', user: state.username };
    const res = await lfmCall(params);
    const tags = res?.toptags?.tag;
    if (Array.isArray(tags) && tags.length > 0) {
      const filtered = tags.filter(t => parseInt(t.count) > 0).slice(0, 18);
      if (filtered.length >= 3) {
        return _genresNormalise(filtered.map(t => ({ name: t.name, count: parseInt(t.count) })));
      }
    }
  } catch {}
  return _genresDeriveFromArtists(period);
}

async function _genresDeriveFromArtists(period) {
  const safeP = period || _genresPeriod || 'overall';
  const artistsRes = await lfmCall({
    method: 'user.gettopartists', user: state.username, period: safeP, limit: 30
  });
  const artists = artistsRes?.topartists?.artist || [];
  if (!artists.length) throw new Error('No listening history found for this user.');

  const tagMap   = {};
  const genreMap = {};   // genreLowercase → [artistName]
  const batch    = artists.slice(0, 12);

  await Promise.allSettled(batch.map(async (artist) => {
    try {
      const td = await lfmCall({ method: 'artist.getTopTags', artist: artist.name });
      const artistTags = td?.toptags?.tag?.slice(0, 5) || [];
      const weight = parseInt(artist.playcount || 1);
      artistTags.forEach(t => {
        const name = t.name.toLowerCase().trim();
        if (!name || name.length < 3) return;
        tagMap[name] = (tagMap[name] || 0) + parseInt(t.count || 1) * weight;
        // Build genre → artist map for user-specific genre detail
        if (!genreMap[name]) genreMap[name] = [];
        if (!genreMap[name].includes(artist.name)) genreMap[name].push(artist.name);
      });
    } catch {}
  }));

  // Persist for genre detail use
  _genreArtistMap      = genreMap;
  _userTopArtistsCache = { period: safeP, artists: batch };

  const genres = Object.entries(tagMap)
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 15);
  if (!genres.length) throw new Error('No genre data found. Listen to more music then try again!');
  return _genresNormalise(genres);
}

function _genresNormalise(genres) {
  const max = genres[0]?.count || 1;
  return genres.map(g => ({
    name:  g.name,
    count: g.count,
    pct:   Math.max(1, Math.round((g.count / max) * 100))
  }));
}

// ── Rendering ─────────────────────────────────────────────────
function _genresRender(genres) {
  const list = document.getElementById('genresList');
  if (!list) return;
  list.innerHTML = '';
  genres.forEach((g, i) => {
    const row = document.createElement('div');
    row.className = 'genre-row ripple-item';
    row.style.animationDelay = `${i * 40}ms`;
    row.style.cursor = 'pointer';
    row.setAttribute('role', 'button');
    row.setAttribute('tabindex', '0');
    row.setAttribute('aria-label', `View ${g.name} genre`);
    row.innerHTML =
      `<span class="genre-name" title="${_esc(g.name)}">${_esc(g.name)}</span>` +
      `<div class="genre-bar-wrap"><div class="genre-bar" data-pct="${g.pct}"></div></div>` +
      `<span class="genre-pct">${g.pct}%</span>`;

    row.addEventListener('click', () => _openGenreDetail(g.name));
    list.appendChild(row);
  });

  document.getElementById('genresEmpty').classList.add('hidden');
  list.classList.remove('hidden');

  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      list.querySelectorAll('.genre-bar').forEach((bar, i) => {
        bar.style.transitionDelay = `${i * 40}ms`;
        bar.style.width = bar.dataset.pct + '%';
      });
    });
  });
}

// ══════════════════════════════════════════════════════════════
//  GENRE DETAIL  — bottom-sheet overlay
// ══════════════════════════════════════════════════════════════

function _openGenreDetail(genreName) {
  _gDetailName        = genreName;
  _gDetailSort        = 'popular';
  _gDetailPage        = 1;
  _gDetailAllTracks   = [];
  _gDetailForceGlobal = false;
  _gdSortMenuOpen     = false;

  const overlay = document.getElementById('genreDetailOverlay');
  if (!overlay) return;

  // ── Stability fix: position:fixed inside overflow-y:auto scroll containers
  // is unreliable on Android WebView (the "fixed" element scrolls with the page).
  // Moving the overlay to document.body takes it out of the scroll context so
  // it stays correctly anchored and does not drift during scroll.
  if (overlay.parentElement !== document.body) {
    document.body.appendChild(overlay);
  }

  const titleEl = document.getElementById('genreDetailTitle');
  if (titleEl) titleEl.textContent = genreName;

  const subEl   = document.getElementById('genreDetailSub');
  if (subEl) subEl.textContent = 'Your Tracks';

  _gDetailUpdateSortUI();

  overlay.classList.remove('hidden');
  requestAnimationFrame(() => overlay.classList.add('open'));

  _gDetailLoadTracks(true);
}

function _closeGenreDetail() {
  const overlay = document.getElementById('genreDetailOverlay');
  if (!overlay) return;
  overlay.classList.remove('open');
  setTimeout(() => overlay.classList.add('hidden'), 300);
}

function _gDetailUpdateSortUI() {
  // Update Sort By pill label
  const labelEl   = document.getElementById('gdSortByLabel');
  const chevronEl = document.getElementById('gdSortByChevron');
  const labels    = { popular: 'Popular', newest: 'Newest', az: 'A–Z' };
  if (labelEl)   labelEl.textContent = labels[_gDetailSort] || 'Popular';

  // Update checkmarks
  ['popular', 'newest', 'az'].forEach(s => {
    const opt   = document.getElementById(`gdSortOpt${s[0].toUpperCase() + s.slice(1)}`);
    const check = document.getElementById(`gdSortCheck${s[0].toUpperCase() + s.slice(1)}`);
    opt?.classList.toggle('active', _gDetailSort === s);
    check?.classList.toggle('hidden', _gDetailSort !== s);
  });
}

// ── Sort By popup menu ────────────────────────────────────────

let _gdSortMenuOpen = false;

function _toggleGDSortMenu() {
  _gdSortMenuOpen = !_gdSortMenuOpen;
  const menu    = document.getElementById('gdSortByMenu');
  const chevron = document.getElementById('gdSortByChevron');
  const btn     = document.getElementById('gdSortByBtn');
  menu?.classList.toggle('hidden', !_gdSortMenuOpen);
  if (chevron) chevron.style.transform = _gdSortMenuOpen ? 'rotate(180deg)' : '';
  btn?.setAttribute('aria-expanded', _gdSortMenuOpen ? 'true' : 'false');
  if (_gdSortMenuOpen) {
    setTimeout(() => {
      document.addEventListener('click', _closeGDSortMenu, { once: true });
    }, 0);
  }
}

function _closeGDSortMenu(e) {
  const wrap = document.getElementById('gdSortByWrap');
  if (wrap && wrap.contains(e?.target)) return;
  _gdSortMenuOpen = false;
  document.getElementById('gdSortByMenu')?.classList.add('hidden');
  const chevron = document.getElementById('gdSortByChevron');
  if (chevron) chevron.style.transform = '';
  document.getElementById('gdSortByBtn')?.setAttribute('aria-expanded', 'false');
}

function _setGDSort(sort) {
  _closeGDSortMenu();
  if (_gDetailSort === sort) return;
  _gDetailSort = sort;
  _gDetailUpdateSortUI();

  const listEl = document.getElementById('genreDetailList');
  if (!listEl) return;

  if (sort === 'az') {
    if (_gDetailAllTracks.length > 0) {
      const sorted = [..._gDetailAllTracks].sort((a, b) =>
        (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' })
      );
      _gDetailRenderTracks(sorted, listEl, true);
    } else {
      _gDetailPage = 1;
      _gDetailLoadTracks(true);
    }
  } else if (sort === 'newest') {
    // Newest = reverse popularity order within loaded tracks
    if (_gDetailAllTracks.length > 0) {
      const reversed = [..._gDetailAllTracks].reverse();
      _gDetailRenderTracks(reversed, listEl, true);
    } else {
      _gDetailPage = 1;
      _gDetailLoadTracks(true);
    }
  } else {
    // Popular: reload from API in rank order
    _gDetailPage = 1;
    _gDetailLoadTracks(true);
  }
}

// Legacy alias (kept for any remaining callers)
function setGenreDetailSort(sort) { _setGDSort(sort); }

async function _gDetailLoadTracks(reset) {
  if (_gDetailLoading) return;
  _gDetailLoading = true;

  const listEl    = document.getElementById('genreDetailList');
  const loadingEl = document.getElementById('genreDetailLoading');
  const emptyEl   = document.getElementById('genreDetailEmpty');
  if (!listEl) { _gDetailLoading = false; return; }

  if (reset) {
    listEl.innerHTML = '';
    emptyEl?.classList.add('hidden');
    if (_gDetailSort !== 'az') _gDetailAllTracks = [];
  }
  loadingEl?.classList.remove('hidden');

  try {
    if (!state.apiKey) throw new Error('No API key');

    const page          = reset ? 1 : _gDetailPage;
    const userArtists   = _genreArtistMap[_gDetailName.toLowerCase()] || [];
    const useUserData   = userArtists.length > 0 && !_gDetailForceGlobal;

    let tracks = [];

    if (useUserData) {
      // ── User-data path: fetch top tracks from artists the user
      //    actually listens to in this genre (3 artists per page)
      const artistSlice = userArtists.slice((page - 1) * 3, page * 3);
      if (artistSlice.length === 0) {
        // All pages exhausted — nothing more to load
        loadingEl?.classList.add('hidden');
        _gDetailLoading = false;
        return;
      }
      const results = await Promise.allSettled(
        artistSlice.map(a =>
          lfmCall({ method: 'artist.gettoptracks', artist: a, limit: 8 })
        )
      );
      results.forEach(r => {
        if (r.status !== 'fulfilled') return;
        const t = r.value?.toptracks?.track || [];
        tracks = tracks.concat(Array.isArray(t) ? t : [t]);
      });
    } else {
      // ── Global fallback / Find New path: tag.gettoptracks
      const data = await lfmCall({
        method: 'tag.gettoptracks',
        tag:    _gDetailName,
        limit:  50,
        page,
      });
      tracks = data?.tracks?.track || [];
      if (!Array.isArray(tracks)) tracks = tracks ? [tracks] : [];
    }

    if (!tracks.length && reset) {
      emptyEl?.classList.remove('hidden');
    } else {
      _gDetailAllTracks = [..._gDetailAllTracks, ...tracks];

      let displayTracks = tracks;
      if (_gDetailSort === 'az') {
        displayTracks = [..._gDetailAllTracks].sort((a, b) =>
          (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' })
        );
        _gDetailRenderTracks(displayTracks, listEl, true);
      } else {
        _gDetailRenderTracks(displayTracks, listEl, reset);
      }
      _gDetailPage = page + 1;
      _gDetailEnrichArt(tracks, listEl);
    }

    loadingEl?.classList.add('hidden');

  } catch (e) {
    loadingEl?.classList.add('hidden');
    if (reset) {
      emptyEl?.classList.remove('hidden');
      const msgEl = emptyEl?.querySelector('.gd-empty-msg');
      if (msgEl) msgEl.textContent = e.message || 'Could not load tracks';
    }
  } finally {
    _gDetailLoading = false;
  }
}

function _gdBestImg(imgArr) {
  if (!Array.isArray(imgArr)) return '';
  const real = imgArr.filter(i => i?.['#text'] && !i['#text'].includes(_GD_NO_ART));
  return (
    real.find(i => i.size === 'extralarge')?.['#text'] ||
    real.find(i => i.size === 'large'     )?.['#text'] ||
    real.find(i => i.size === 'medium'    )?.['#text'] ||
    real[0]?.['#text'] || ''
  );
}

function _gDetailRenderTracks(tracks, listEl, reset) {
  if (reset) listEl.innerHTML = '';

  const frag = document.createDocumentFragment();
  tracks.forEach((t, i) => {
    const name   = t.name   || '';
    const artist = (typeof t.artist === 'string') ? t.artist : (t.artist?.name || '');
    if (!name || !artist) return;

    const imgUrl = _gdBestImg(Array.isArray(t.image) ? t.image : []);
    const rank   = t['@attr']?.rank || (reset ? i + 1 : _gDetailPage * 50 + i - 50);

    const row = document.createElement('div');
    row.className = 'gd-track-row';
    row.setAttribute('data-gd-name',   name);
    row.setAttribute('data-gd-artist', artist);
    row.setAttribute('data-lp-name',   name);
    row.setAttribute('data-lp-artist', artist);
    row.style.animationDelay = `${Math.min(i * 30, 180)}ms`;

    row.innerHTML = `
      <span class="gd-track-num">${rank}</span>
      <div class="gd-track-art-wrap">
        ${imgUrl
          ? `<img src="${_esc(imgUrl)}" alt="" class="gd-track-art gd-art-fade" loading="lazy"
                 onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
          : ''}
        <span class="material-symbols-rounded gd-track-art-fallback"
              style="${imgUrl ? 'display:none' : 'display:flex'}">music_note</span>
      </div>
      <div class="gd-track-info">
        <div class="gd-track-name">${_esc(name)}</div>
        <div class="gd-track-artist">${_esc(artist)}</div>
      </div>
      <button class="gd-track-menu-btn" aria-label="More options"
              onclick="event.stopPropagation();_openGDDropdown(this,'${_escAttr(name)}','${_escAttr(artist)}')">
        <span class="material-symbols-rounded">more_vert</span>
      </button>`;

    row.addEventListener('click', e => {
      if (e.target.closest('.gd-track-menu-btn')) return;
      if (typeof openTrackOnYouTube === 'function') openTrackOnYouTube(name, artist);
    });

    frag.appendChild(row);
  });

  listEl.appendChild(frag);
  _gDetailSetupScroll();
}

// ══════════════════════════════════════════════════════════════
//  IMAGE ENRICHMENT for genre detail tracks
// ══════════════════════════════════════════════════════════════

/**
 * Enriches missing track art for genre detail rows.
 *
 * SOURCE ORDER (per spec — genres need better hit rate than other screens):
 *   1. Local gd mem-cache  — instant, no I/O
 *   2. iTunes Search API   — PRIMARY: best coverage for tag/genre tracks
 *   3. Last.fm track.getInfo → album art  — fallback
 *   4. Last.fm artist.getInfo → artist image  — last resort
 *
 * Results cached in _gdImgCache (LRU, 400 entries) to avoid repeat calls.
 * Patches DOM rows in-place with a fade-in animation.
 */
async function _gDetailEnrichArt(tracks, listEl) {
  if (!state.apiKey || !listEl) return;

  // Only enrich tracks that have no valid image in the raw API response
  const toEnrich = tracks.filter(t => {
    const img = _gdBestImg(Array.isArray(t.image) ? t.image : []);
    return !img;
  });
  if (!toEnrich.length) return;

  const BATCH = 6;
  for (let i = 0; i < toEnrich.length; i += BATCH) {
    // Stop if the sheet was closed or genre changed
    if (!document.getElementById('genreDetailOverlay')?.classList.contains('open')) return;

    await Promise.allSettled(
      toEnrich.slice(i, i + BATCH).map(async (t) => {
        const name   = t.name || '';
        const artist = (typeof t.artist === 'string') ? t.artist : (t.artist?.name || '');
        if (!name || !artist) return;

        const cacheKey = `gd:${name}:${artist}`.toLowerCase();

        // ── Step 1: Local memory cache — no network ──────────────
        let imgUrl = null;
        if (_gdImgCache.has(cacheKey)) {
          imgUrl = _gdImgCache.get(cacheKey);
        }

        // ── Steps 2-4: Resolve via network if not cached ─────────
        if (imgUrl === null) {
          imgUrl = '';

          // Step 2: iTunes (PRIMARY — best coverage for genre/tag tracks)
          if (typeof _itunesFetchArtwork === 'function') {
            try { imgUrl = await _itunesFetchArtwork(name, artist, 'track'); } catch {}
          }

          // Step 3: Last.fm track.getInfo → album art (fallback)
          if (!imgUrl) {
            try {
              const d     = await lfmCall({ method: 'track.getInfo', track: name, artist, autocorrect: 1 });
              const album = d?.track?.album;
              if (album?.image) imgUrl = _gdBestImg(album.image);
              if (!imgUrl && d?.track?.image) imgUrl = _gdBestImg(d.track.image);
            } catch {}
          }

          // Step 4: Last.fm artist.getInfo → artist image (last resort)
          if (!imgUrl) {
            try {
              const d = await lfmCall({ method: 'artist.getInfo', artist, autocorrect: 1 });
              imgUrl = _gdBestImg(d?.artist?.image) || '';
            } catch {}
          }

          // ── Save to local cache ('' = confirmed no art) ───────
          if (_gdImgCache.size >= _GD_IMG_MAX) _gdImgCache.delete(_gdImgCache.keys().next().value);
          _gdImgCache.set(cacheKey, imgUrl);
        }

        if (!imgUrl) return;

        // ── Patch all matching rows in the list ──────────────────
        listEl.querySelectorAll(`[data-gd-name="${CSS.escape(name)}"][data-gd-artist="${CSS.escape(artist)}"]`)
          .forEach(row => {
            const wrap     = row.querySelector('.gd-track-art-wrap');
            const fallback = row.querySelector('.gd-track-art-fallback');
            if (!wrap) return;
            let img = wrap.querySelector('.gd-track-art');
            if (!img) {
              img = document.createElement('img');
              img.className = 'gd-track-art gd-art-fade';
              img.alt       = '';
              img.loading   = 'lazy';
              wrap.insertBefore(img, fallback);
            }
            img.style.opacity    = '0';
            img.style.transition = 'opacity 0.28s ease';
            img.onload  = () => {
              img.style.opacity = '1';
              if (fallback) fallback.style.display = 'none';
            };
            img.onerror = () => {
              // Auto-retry once with a slight delay before showing placeholder
              if (!img._retried) {
                img._retried = true;
                setTimeout(() => { img.src = imgUrl + (imgUrl.includes('?') ? '&' : '?') + '_r=1'; }, 1200);
              } else {
                img.style.display = 'none';
                if (fallback) fallback.style.display = 'flex';
              }
            };
            img.src = imgUrl;
          });
      })
    );
  }
}

function _gDetailSetupScroll() {
  const scrollEl = document.getElementById('genreDetailBody');
  if (!scrollEl || scrollEl._gdScrollBound) return;
  scrollEl._gdScrollBound = true;
  scrollEl.addEventListener('scroll', () => {
    if (_gDetailLoading || _gDetailSort === 'az') return;
    const { scrollTop, scrollHeight, clientHeight } = scrollEl;
    if (scrollTop + clientHeight >= scrollHeight - 200) {
      _gDetailLoadTracks(false);
    }
  }, { passive: true });
}

// ── Genre Detail 3-dot dropdown ───────────────────────────────

let _activeGDDropdown = null;

function _openGDDropdown(btn, trackName, artistName) {
  if (_activeGDDropdown) {
    _activeGDDropdown.classList.add('track-dropdown-leaving');
    setTimeout(() => { _activeGDDropdown?.remove(); _activeGDDropdown = null; }, 160);
  }

  const items = [
    { icon: 'shuffle',       label: 'Start Mix from this', fn: () => { _closeGenreDetail(); startMixFromTrack(trackName, artistName); } },
    { icon: 'open_in_new',   label: 'Open in Last.fm',     fn: () => openTrackOnLastFm(trackName, artistName) },
    { icon: 'smart_display', label: 'Play on YouTube',     fn: () => openTrackOnYouTube(trackName, artistName) },
    { icon: 'image_search',  label: 'Refresh Cover Art',   fn: async () => {
      if (typeof showToast === 'function') showToast('Refreshing cover art\u2026');
      const url = typeof _refreshTrackArtwork === 'function'
        ? await _refreshTrackArtwork(trackName, artistName)
        : '';
      if (url) {
        const listEl = document.getElementById('genreDetailList');
        if (listEl) {
          listEl.querySelectorAll(`[data-gd-name="${CSS.escape(trackName)}"][data-gd-artist="${CSS.escape(artistName)}"]`)
            .forEach(row => {
              const wrap     = row.querySelector('.gd-track-art-wrap');
              const fallback = row.querySelector('.gd-track-art-fallback');
              if (!wrap) return;
              let img = wrap.querySelector('.gd-track-art');
              if (!img) {
                img = document.createElement('img');
                img.className = 'gd-track-art gd-art-fade';
                img.alt       = '';
                img.loading   = 'lazy';
                wrap.insertBefore(img, fallback);
              }
              img.style.opacity    = '0';
              img.style.transition = 'opacity 0.28s ease';
              img.onload  = () => { img.style.opacity = '1'; if (fallback) fallback.style.display = 'none'; };
              img.onerror = () => { img.style.display = 'none'; if (fallback) fallback.style.display = 'flex'; };
              img.src = url;
            });
        }
        if (typeof showToast === 'function') showToast('Cover art updated \u2713', 'success');
      } else {
        if (typeof showToast === 'function') showToast('Cover art not available', 'error');
      }
    }},
    { icon: 'delete',        label: 'Delete Scrobble',     fn: async () => {
      await _lfmDeleteScrobble(trackName, artistName, null);
    }},
  ];

  const menu = document.createElement('div');
  menu.className = 'track-dropdown-menu';
  menu.setAttribute('role', 'menu');

  // ── Genre row (we're inside the genre detail, so genre = _gDetailName) ──
  const genreRow = document.createElement('div');
  genreRow.className = 'track-dropdown-genre';
  genreRow.innerHTML = `<span class="material-symbols-rounded">sell</span><span><span class="track-dropdown-genre-label">Genre:</span><span class="td-genre-val"> ${_esc(_gDetailName || '\u2026')}</span></span>`;
  menu.appendChild(genreRow);

  // ── "Explore this genre" button — immediately available since we know the genre ──
  const exploreBtn = document.createElement('button');
  exploreBtn.className = 'track-dropdown-item track-dropdown-explore';
  exploreBtn.setAttribute('role', 'menuitem');
  exploreBtn.innerHTML =
    `<span class="material-symbols-rounded track-dropdown-icon" style="color:var(--md-primary)">bolt</span>` +
    `<span style="color:var(--md-primary);font-weight:500">Explore this genre</span>`;
  exploreBtn.addEventListener('click', e => {
    e.stopPropagation();
    if (_activeGDDropdown) {
      _activeGDDropdown.classList.add('track-dropdown-leaving');
      setTimeout(() => { _activeGDDropdown?.remove(); _activeGDDropdown = null; }, 160);
    }
    _closeGenreDetail();
    setTimeout(() => _doExploreGenrePlaylist(_gDetailName, { source: 'genres' }), 320);
  });
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
      if (_activeGDDropdown) {
        _activeGDDropdown.classList.add('track-dropdown-leaving');
        setTimeout(() => { _activeGDDropdown?.remove(); _activeGDDropdown = null; }, 160);
      }
      fn();
    });
    menu.appendChild(el);
  });

  document.body.appendChild(menu);
  _activeGDDropdown = menu;

  const rect  = btn.getBoundingClientRect();
  const mw    = 224;
  const mh    = menu.offsetHeight || (items.length * 52 + 100);
  let top  = rect.bottom + 4;
  let left = rect.right  - mw;
  if (top + mh > window.innerHeight - 16) top  = rect.top - mh - 4;
  if (left < 8)                            left = 8;
  if (left + mw > window.innerWidth - 8)  left = window.innerWidth - mw - 8;
  menu.style.top  = `${Math.max(top, 8)}px`;
  menu.style.left = `${left}px`;

  // Async genre fetch — also resolves the genre val for any track not already known
  if (typeof _resolveTrackGenre === 'function') {
    _resolveTrackGenre(trackName, artistName).then(genre => {
      const el = menu.querySelector('.td-genre-val');
      if (el && !_gDetailName) el.textContent = genre ? ` ${genre}` : ' Unknown';
    }).catch(() => {});
  }

  setTimeout(() => {
    document.addEventListener('click', () => {
      if (_activeGDDropdown) {
        _activeGDDropdown.classList.add('track-dropdown-leaving');
        setTimeout(() => { _activeGDDropdown?.remove(); _activeGDDropdown = null; }, 160);
      }
    }, { once: true });
  }, 0);
}

// ══════════════════════════════════════════════════════════════
//  GENRE DETAIL ACTION BUTTONS
// ══════════════════════════════════════════════════════════════

/**
 * Start Mix — generates a playlist seeded from all of the user's
 * tracks in this genre (uses the tag playlist generation mode).
 */
function _genreStartMix(genreName) {
  if (!state.apiKey) { showToast('Add your API key in Settings', 'error'); return; }
  _closeGenreDetail();
  state.selectedMode  = 'tag';
  state.visualMode    = 'tag';
  state.playlistTitle = `${genreName} Mix`;
  if (!state.lastInputs) state.lastInputs = {};
  state.lastInputs.tagInput = genreName;
  const tagEl = document.getElementById('tagInput');
  if (tagEl) tagEl.value = genreName;
  if (typeof generatePlaylist === 'function') generatePlaylist(false);
}

/**
 * Discover More — generates a NEW playlist of tracks the user hasn't heard,
 * using similar artists + tag.getTopTracks. Then navigates to the playlist screen.
 *
 * This is intentionally different from Start Mix (which uses existing listened tracks).
 * Discover More finds FRESH tracks outside the user's listening history.
 */
function _genreDiscoverMore(genreName) {
  if (!state.apiKey) { showToast('Add your API key in Settings', 'error'); return; }
  _closeGenreDetail();
  // Short delay to let the sheet close, then kick off discovery
  setTimeout(() => _doOpenDiscoverMore(genreName), 320);
}

async function _doOpenDiscoverMore(genreName) {
  if (!state.username || !state.apiKey) {
    showToast('Add your API key in Settings', 'error');
    return;
  }

  // Navigate to results screen and show loading
  await navigateTo('results');
  if (typeof _startLoadingCycle === 'function') _startLoadingCycle('Finding new tracks for you…');
  if (typeof showLoading === 'function') showLoading(true);

  try {
    const tag          = genreName.toLowerCase();
    const limit        = 30;
    const pool         = [];
    const seenKeys     = new Set();

    // ── Helper: normalise a raw track object ──────────────────
    const norm = (t, overrideArtist) => {
      if (!t?.name) return null;
      const artist = overrideArtist ||
        (typeof t.artist === 'string' ? t.artist : (t.artist?.name || t.artist?.['#text'] || ''));
      if (!artist || !t.name) return null;
      const imgArr = Array.isArray(t.image) ? t.image : [];
      const realImg = (url) => typeof _isRealImg === 'function'
        ? _isRealImg(url)
        : (url && url.trim() !== '' && !url.includes('2a96cbd8b46e442fc41c2b86b821562f'));
      const imgEntry =
        imgArr.find(i => i.size === 'extralarge' && realImg(i['#text'])) ||
        imgArr.find(i => i.size === 'large'      && realImg(i['#text'])) ||
        imgArr.find(i => i.size === 'medium'     && realImg(i['#text'])) ||
        imgArr.find(i => realImg(i['#text']));
      return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '' };
    };

    const addTrack = (t) => {
      if (!t) return;
      const k = `${t.name}|${t.artist}`.toLowerCase();
      if (!seenKeys.has(k)) { seenKeys.add(k); pool.push(t); }
    };

    // ── SOURCE 1: tag.getTopTracks (fresh global popular tracks) ─
    try {
      const page = Math.floor(Math.random() * 6) + 1;
      const d    = await lfmCall({ method: 'tag.gettoptracks', tag, limit: 50, page });
      const raw  = d?.tracks?.track || [];
      (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t)));
    } catch {}

    // ── SOURCE 2: similar artists → their top tracks ─────────
    // Use the user's known genre artists (from _genreArtistMap) as seeds
    const seedArtists = (_genreArtistMap[tag] || []).slice(0, 5);
    if (seedArtists.length > 0) {
      for (const seedArtist of seedArtists.slice(0, 3)) {
        try {
          const d       = await lfmCall({ method: 'artist.getSimilar', artist: seedArtist, limit: 10 });
          const similar = d?.similarartists?.artist || [];
          const picked  = (Array.isArray(similar) ? similar : [similar]).slice(0, 4);
          await Promise.allSettled(picked.map(async a => {
            try {
              const page = Math.ceil(Math.random() * 3);
              const d2   = await lfmCall({ method: 'artist.gettoptracks', artist: a.name, limit: 8, page });
              const raw  = d2?.toptracks?.track || [];
              (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t, a.name)));
            } catch {}
          }));
        } catch {}
      }
    } else {
      // No known artists for this genre — use tag.getTopArtists as seeds
      try {
        const d       = await lfmCall({ method: 'tag.gettopartists', tag, limit: 10 });
        const artists = d?.topartists?.artist || [];
        const picked  = (Array.isArray(artists) ? artists : [artists]).slice(0, 5);
        await Promise.allSettled(picked.map(async a => {
          try {
            const d2  = await lfmCall({ method: 'artist.gettoptracks', artist: a.name, limit: 6 });
            const raw = d2?.toptracks?.track || [];
            (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t, a.name)));
          } catch {}
        }));
      } catch {}
    }

    // ── SOURCE 3: track.getSimilar for each seed track ────────
    const userGenreTracks = pool.slice(0, 3); // use first pool entries as seeds
    await Promise.allSettled(userGenreTracks.map(async seed => {
      try {
        const d   = await lfmCall({ method: 'track.getSimilar', track: seed.name, artist: seed.artist, limit: 10 });
        const raw = d?.similartracks?.track || [];
        (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t)));
      } catch {}
    }));

    // ── Filter: remove tracks user has already heard ──────────
    const userHeard = new Set();
    try {
      const d   = await lfmCall({ method: 'user.gettoptracks', user: state.username, period: 'overall', limit: 200 });
      const raw = d?.toptracks?.track || [];
      (Array.isArray(raw) ? raw : [raw]).forEach(t => {
        if (t?.name) {
          const a = typeof t.artist === 'string' ? t.artist : (t.artist?.name || '');
          userHeard.add(`${t.name}|${a}`.toLowerCase());
        }
      });
    } catch {}

    let fresh = pool.filter(t => !userHeard.has(`${t.name}|${t.artist}`.toLowerCase()));
    // If filtering leaves too few tracks, fall back to full pool
    if (fresh.length < 10) fresh = pool;

    // Shuffle for freshness and cap at limit
    const shuffled = fresh.sort(() => Math.random() - 0.5).slice(0, limit);

    if (!shuffled.length) throw new Error('No new tracks found for this genre');

    // ── Save and render ───────────────────────────────────────
    state.playlist      = shuffled;
    state.playlistTitle = typeof _generateSmartPlaylistName === 'function'
      ? _generateSmartPlaylistName('tag', { tagInput: genreName })
      : `Discover: ${genreName}`;
    state.playlistSubtitle = typeof _generatePlaylistSubtitle === 'function'
      ? _generatePlaylistSubtitle('tag', { tagInput: genreName })
      : `Genre Mix · ${genreName}`;
    state.selectedMode  = 'tag';
    state.lastInputs    = { ...(state.lastInputs || {}), tagInput: genreName };

    if (typeof setLoadingText === 'function') setLoadingText('Loading artwork…');
    if (typeof enrichTracksWithArt === 'function') await enrichTracksWithArt(state.playlist);
    if (typeof _stopLoadingCycle  === 'function') _stopLoadingCycle();
    if (typeof showLoading        === 'function') showLoading(false);
    if (typeof renderResults      === 'function') renderResults();

  } catch (err) {
    if (typeof _stopLoadingCycle  === 'function') _stopLoadingCycle();
    if (typeof showLoading        === 'function') showLoading(false);
    if (typeof showToast          === 'function') showToast(err.message || 'Could not generate discovery playlist', 'error');
    if (typeof showResultsEmpty   === 'function') showResultsEmpty();
  }
}

// Legacy aliases
function _genreFindNew(g) { _genreDiscoverMore(g); }
function _doOpenFindNew(g) { _doOpenDiscoverMore(g); }

// ══════════════════════════════════════════════════════════════
//  EXPLORE THIS GENRE  — Personalized genre playlist
//  1-tap from 3-dot menu → unique playlist matching user's taste
// ══════════════════════════════════════════════════════════════

/**
 * Generates a highly personalized playlist for a genre, then saves it
 * and opens the Playlist tab automatically. Personalisation works by:
 *
 *  1. Collecting MULTIPLE sources of genre tracks
 *  2. SCORING each track by the user's taste profile:
 *       +3  → artist is in user's all-time top artists
 *       +2  → artist is in user's top artists for this period
 *       +1  → artist is similar to a genre artist the user listens to
 *        0  → global genre track with no personal connection
 *  3. Sorting by score DESC, then shuffling within equal-score groups
 *     so the result feels personal but not repetitive
 *  4. Enriching with cover art
 *  5. Giving it a smart unique name and navigating to Playlist tab
 */
/**
 * @param {string} genreName
 * @param {{ source?: 'home_recent' | 'genres' | 'playlist' | 'now_playing' }} [ctx={}]
 *   source 'home_recent' — boost score for recently-played artists (+4)
 *   source 'genres'      — default: strong genre focus (no change)
 *   source 'playlist'    — blend with current playlist vibe (no change)
 *   source 'now_playing' — prioritize current track mood (no change)
 */
async function _doExploreGenrePlaylist(genreName, ctx = {}) {
  if (!state.username || !state.apiKey) {
    showToast('Add your credentials in Settings', 'error'); return;
  }

  // Close any open dropdown / detail sheet
  if (_activeGDDropdown) {
    _activeGDDropdown.classList.add('track-dropdown-leaving');
    setTimeout(() => { _activeGDDropdown?.remove(); _activeGDDropdown = null; }, 160);
  }

  await navigateTo('results');
  const loadingLabel = ctx?.source === 'home_recent'
    ? `Building your recent ${genreName} vibe\u2026`
    : ctx?.source === 'now_playing'
    ? `Matching the ${genreName} mood\u2026`
    : `Building your ${genreName} playlist\u2026`;
  if (typeof _startLoadingCycle === 'function') _startLoadingCycle(loadingLabel);
  if (typeof showLoading        === 'function') showLoading(true);

  try {
    const tag    = genreName.toLowerCase().trim();
    const limit  = parseInt(state.chipSelections?.limit || state.chipSelections?.count) || 25;
    const pool   = [];
    const seenK  = new Set();

    // ── Helper: normalise a raw track object ──────────────────
    const norm = (t, overrideArtist) => {
      if (!t?.name) return null;
      const artist = overrideArtist ||
        (typeof t.artist === 'string' ? t.artist : (t.artist?.name || t.artist?.['#text'] || ''));
      if (!artist) return null;
      const imgArr = Array.isArray(t.image) ? t.image : [];
      const realImg = (url) => url && url.trim() !== '' && !url.includes('2a96cbd8b46e442fc41c2b86b821562f');
      const imgEntry =
        imgArr.find(i => i.size === 'extralarge' && realImg(i['#text'])) ||
        imgArr.find(i => i.size === 'large'      && realImg(i['#text'])) ||
        imgArr.find(i => realImg(i['#text']));
      return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', _score: 0 };
    };

    const addTrack = (t) => {
      if (!t) return;
      const k = `${t.name}|${t.artist}`.toLowerCase();
      if (!seenK.has(k)) { seenK.add(k); pool.push(t); }
    };

    // ── Step 1: Build user taste profile ─────────────────────
    if (typeof setLoadingText === 'function') setLoadingText('Reading your taste profile\u2026');

    let topArtistNames = new Set();
    let genreArtistNames = new Set();

    // User's all-time top 50 artists
    try {
      const d = await lfmCall({ method: 'user.gettopartists', user: state.username, period: 'overall', limit: 50 });
      (d?.topartists?.artist || []).forEach(a => topArtistNames.add(a.name.toLowerCase()));
    } catch {}

    // User's known artists for this specific genre (built during genre screen load)
    const knownGenreArtists = (_genreArtistMap[tag] || []);
    knownGenreArtists.forEach(a => genreArtistNames.add(a.toLowerCase()));

    // ── Context-aware boost: home_recent → favour recently-played artists ──
    // When triggered from the Home list we know the user is in a "recent listening"
    // headspace, so we give recently-heard artists a score bump (+4) that beats
    // even the all-time top-artists bonus (+3), keeping the playlist fresh but
    // grounded in their current listening session.
    const recentArtistNames = new Set();
    if (ctx?.source === 'home_recent') {
      try {
        if (typeof setLoadingText === 'function') setLoadingText('Reading your recent plays\u2026');
        const d = await lfmCall({ method: 'user.getrecenttracks', user: state.username, limit: 50 });
        const raw = d?.recenttracks?.track;
        const arr = Array.isArray(raw) ? raw : (raw ? [raw] : []);
        arr.filter(t => !(t['@attr']?.nowplaying === 'true')).forEach(t => {
          const a = typeof t.artist === 'string' ? t.artist : (t.artist?.['#text'] || '');
          if (a) recentArtistNames.add(a.toLowerCase());
        });
      } catch {}
    }

    // ── Step 2: Gather genre tracks from multiple sources ─────
    if (typeof setLoadingText === 'function') setLoadingText(`Gathering ${genreName} tracks\u2026`);

    // SOURCE A: tag.getTopTracks — global popular tracks for this genre
    try {
      const page = Math.floor(Math.random() * 4) + 1;
      const d    = await lfmCall({ method: 'tag.gettoptracks', tag, limit: 50, page });
      const raw  = d?.tracks?.track || [];
      (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t)));
    } catch {}

    // SOURCE B: The user's known genre artists → their top tracks (HIGH relevance)
    if (knownGenreArtists.length > 0) {
      await Promise.allSettled(knownGenreArtists.slice(0, 6).map(async a => {
        try {
          const d   = await lfmCall({ method: 'artist.gettoptracks', artist: a, limit: 10 });
          const raw = d?.toptracks?.track || [];
          (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t, a)));
        } catch {}
      }));
    } else {
      // No known genre artists — use tag.getTopArtists as seeds
      try {
        const d       = await lfmCall({ method: 'tag.gettopartists', tag, limit: 8 });
        const artists = d?.topartists?.artist || [];
        await Promise.allSettled((Array.isArray(artists) ? artists : [artists]).slice(0, 5).map(async a => {
          try {
            const d2  = await lfmCall({ method: 'artist.gettoptracks', artist: a.name, limit: 8 });
            const raw = d2?.toptracks?.track || [];
            (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t, a.name)));
          } catch {}
        }));
      } catch {}
    }

    // SOURCE C: Similar artists to genre artists → their top tracks (MEDIUM relevance)
    if (typeof setLoadingText === 'function') setLoadingText('Finding similar artists\u2026');
    const seedsForSimilar = knownGenreArtists.slice(0, 3);
    const similarArtistNames = new Set();

    await Promise.allSettled(seedsForSimilar.map(async seed => {
      try {
        const d       = await lfmCall({ method: 'artist.getSimilar', artist: seed, limit: 8 });
        const similar = d?.similarartists?.artist || [];
        (Array.isArray(similar) ? similar : [similar]).slice(0, 4).forEach(a => {
          similarArtistNames.add(a.name.toLowerCase());
        });
        await Promise.allSettled(
          (Array.isArray(similar) ? similar : [similar]).slice(0, 3).map(async a => {
            try {
              const d2  = await lfmCall({ method: 'artist.gettoptracks', artist: a.name, limit: 6 });
              const raw = d2?.toptracks?.track || [];
              (Array.isArray(raw) ? raw : [raw]).forEach(t => addTrack(norm(t, a.name)));
            } catch {}
          })
        );
      } catch {}
    }));

    // ── Step 3: Score every track by taste profile ────────────
    if (typeof setLoadingText === 'function') setLoadingText('Personalising your playlist\u2026');

    pool.forEach(t => {
      const aLow = t.artist.toLowerCase();
      // Context-aware bonus: recently-played artists get highest score (home_recent source)
      if (recentArtistNames.size > 0 && recentArtistNames.has(aLow)) t._score = 4;
      else if (topArtistNames.has(aLow))          t._score = 3; // user's top artist
      else if (genreArtistNames.has(aLow))         t._score = 2; // user's genre artist
      else if (similarArtistNames.has(aLow))       t._score = 1; // similar to genre artist
      else                                         t._score = 0; // global genre track
    });

    // Shuffle within each score group for freshness, then stable sort by score
    const shuffled = pool.sort(() => Math.random() - 0.5); // shuffle first
    shuffled.sort((a, b) => b._score - a._score);          // then stable sort by score

    // If we have enough personal tracks (score ≥ 1), drop score-0 tracks
    const personalTracks = shuffled.filter(t => t._score >= 1);
    let final = personalTracks.length >= Math.ceil(limit * 0.6)
      ? personalTracks.slice(0, limit)
      : shuffled.slice(0, limit);

    if (!final.length) throw new Error(`No tracks found for ${genreName}`);

    // ── Step 4: Smart unique name ─────────────────────────────
    const smartName = typeof _generateSmartPlaylistName === 'function'
      ? _generateSmartPlaylistName('tag', { tagInput: genreName })
      : (typeof _vibeNameForTag === 'function'
          ? (typeof _deduplicateName === 'function'
              ? _deduplicateName(_vibeNameForTag(genreName))
              : _vibeNameForTag(genreName))
          : `${genreName} Mix`);

    state.playlist      = final;
    state.playlistTitle = smartName;
    state.playlistSubtitle = typeof _generatePlaylistSubtitle === 'function'
      ? _generatePlaylistSubtitle('tag', { tagInput: genreName })
      : `Genre Mix · ${genreName}`;
    state.selectedMode  = 'tag';
    state.visualMode    = 'tag';
    state.lastInputs    = { ...(state.lastInputs || {}), tagInput: genreName };

    // ── Step 5: Enrich artwork + render ──────────────────────
    if (typeof setLoadingText === 'function') setLoadingText('Loading artwork\u2026');
    if (typeof enrichTracksWithArt === 'function') await enrichTracksWithArt(state.playlist);
    if (typeof _stopLoadingCycle   === 'function') _stopLoadingCycle();
    if (typeof showLoading         === 'function') showLoading(false);
    if (typeof renderResults       === 'function') renderResults();

  } catch (err) {
    if (typeof _stopLoadingCycle === 'function') _stopLoadingCycle();
    if (typeof showLoading       === 'function') showLoading(false);
    if (typeof showToast         === 'function') showToast(err.message || 'Could not generate playlist', 'error');
    if (typeof showResultsEmpty  === 'function') showResultsEmpty();
  }
}

// ── UI state helpers ──────────────────────────────────────────
function _genresShowLoading(on) {
  document.getElementById('genresSkeleton')?.classList.toggle('hidden', !on);
  document.getElementById('genresList')?.classList.toggle('hidden', on);
  if (on) document.getElementById('genresEmpty')?.classList.add('hidden');
}

function _genresShowError(sub, title) {
  _genresShowLoading(false);
  document.getElementById('genresList')?.classList.add('hidden');
  const empty  = document.getElementById('genresEmpty');
  const msgEl  = empty?.querySelector('.genres-empty-msg');
  const subEl  = empty?.querySelector('.genres-empty-sub');
  if (msgEl) msgEl.textContent = title || 'No genre data yet';
  if (subEl) subEl.textContent = sub   || '';
  empty?.classList.remove('hidden');
}

// ── Tiny helpers ──────────────────────────────────────────────
function _esc(str) {
  return String(str || '').replace(/&/g,'&').replace(/</g,'<').replace(/>/g,'>').replace(/"/g,'&quot;');
}
function _escAttr(str) {
  return String(str || '').replace(/'/g,"\\'").replace(/"/g,'\\"');
}

// ── Backdrop click (close detail when tapping outside sheet) ─
function _gdOverlayBackdropClick(e) {
  if (e.target === e.currentTarget) _closeGenreDetail();
}
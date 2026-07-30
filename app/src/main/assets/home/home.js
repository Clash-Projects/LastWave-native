/* ════════════════════════════════════════════════════════════
   home.js — LastWave Home Screen

   SCROLL MODEL (single container)
   ══════════════════════════════════════════════════════════
   screen_home() forces [data-screen="home"] to overflow:hidden
   + flex-column. #dailyMixList is the ONLY scroll container.

   AUTO-REFRESH: REMOVED. Data loads only on:
     1. Initial load
     2. Pull-to-refresh (gesture, scrollTop === 0 only)
     3. Sort/tab change

   DATA: All tracks rendered always. No slice(), no cap.

   NOW PLAYING: Injected at top of ALL four sort modes.
                Deduplicated — never appears twice.
   ════════════════════════════════════════════════════════════ */

'use strict';

// ── Module state ──────────────────────────────────────────────
let _homeSortMode      = 'recent';
let _homeAllTracks     = [];
let _homeTrackFreq     = {};
let _sortMenuOpen      = false;
let _pendingListFadeIn = false;

// ── Data-load guards ──────────────────────────────────────────
let _homeIsFetching    = false;
let _homeDataLoaded    = false;
let _homeShellAnimated = false;   // true after first successful render; prevents re-animation on tab revisit

// ── Pagination ────────────────────────────────────────────────
let _homeCurrentPage   = 1;
let _homeIsLoadingMore = false;
let _homeHasMore       = true;

// ── Period top-tracks cache ───────────────────────────────────
let _homeDateTracksCache  = { 7: null, 30: null };
let _homeDateFetching     = { 7: false, 30: false };
let _homePeriodFetchToken = 0;

// ── Now Playing ───────────────────────────────────────────────
let _nowPlayingTrack          = null;   // { name, artist, image } | null
let _npPollTimer              = null;   // interval handle for real-time NP polling
let _recentTracksPollTimer    = null;   // interval handle for recent tracks polling
let _lastFetchedRecentUts     = 0;      // timestamp of last scrobble detected by polling
let _homeVisibilityBound      = false;  // guard — visibilitychange listener added only once

// ── Render serialisation (Issue 3 fix) ───────────────────────
// Incremented on every full data fetch / tab switch / PTR.  Every
// _renderList() call captures the token at call time; if it has
// changed by the time the DOM write happens the render is dropped.
// This prevents a stale NP-poll _renderList() from overwriting the
// result of a concurrent tab-switch or load-more operation.
let _renderToken = 0;
// Tracks the Last.fm UTS timestamp of the most-recently-seen completed scrobble
// for the *current* track, so we can detect repeated plays of the same song
// without waiting for a track change (Issue: same-track replay scrobble fix).
let _lastSameTrackScrobbleUts = 0;

// ══════════════════════════════════════════════════════════════
//  SCREEN INIT
// ══════════════════════════════════════════════════════════════

function screen_home() {
  // ── Force wrapper to overflow:hidden + flex-column ──
  // nav.js injects overflow-y:auto on the wrapper. We override it here
  // so that #dailyMixList is the ONLY element that can scroll.
  const wrapper = document.querySelector('[data-screen="home"]');
  if (wrapper) {
    // Assign directly — avoids cssText += accumulation on repeated visits
    wrapper.style.overflowX     = 'hidden';
    wrapper.style.overflowY     = 'hidden';
    wrapper.style.display       = 'flex';
    wrapper.style.flexDirection = 'column';
  }

  if (state.username) {
    document.getElementById('homeUsernameSection').classList.add('hidden');
    document.getElementById('homeHeader').classList.remove('hidden');
    _updateHeaderUsername();

    if (_homeDataLoaded) {
      _showHomeLoading(false);
      _showHomeCards(true);
      _syncListScrollClass();
      // Replace the container node to guarantee a fresh layout on every screen visit.
      _replaceListContainer(_homeSortMode);
      // Re-render data into the fresh container on every revisit.
      // Without this the list is blank until the user manually refreshes (Issue 4).
      if (_homeSortMode === 'last7days' || _homeSortMode === 'last30days') {
        const days = _homeSortMode === 'last7days' ? 7 : 30;
        if (_homeDateTracksCache[days]) {
          _renderList();
        } else {
          // Cache was lost between visits — re-fetch silently, no PTR needed
          _homePeriodFetchToken++;
          const token  = _homePeriodFetchToken;
          const period = days === 7 ? '7day' : '1month';
          _showPeriodLoadingState();
          _fetchTopTracksForPeriod(period, days, token);
        }
      } else {
        _renderList();
      }
      _rebindListListeners();
      if (!_homeShellAnimated) {
        _triggerEntryAnimations();        // first render — animate the shell
      } else {
        _setupInfiniteScroll();           // subsequent visits — just ensure scroll is wired up
      }
      // Sync Now Playing on every tab visit — don't wait for the next poll tick
      if (state.apiKey) _checkNowPlaying();
    } else if (!_homeIsFetching) {
      _destroyListenTimer();
      _homeCurrentPage   = 1;
      _homeIsLoadingMore = false;
      _homeHasMore       = true;
      _showHomeLoading(true);
      _showHomeCards(false);
      _fetchHomeData();
    }
    // Fetch in-flight — wait for it to finish.

  } else {
    _showHomeLoading(false);
    _showHomeCards(false);
    document.getElementById('homeHeader').classList.add('hidden');
    document.getElementById('homeUsernameSection').classList.remove('hidden');
  }

  _setupPullToRefresh();
}

// Reset scroll position when switching to non-recent tabs
function _syncListScrollClass() {
  const listEl = document.getElementById('dailyMixList');
  if (!listEl) return;
  if (_homeSortMode !== 'recent') listEl.scrollTop = 0;
}

// ── Unicode small-caps ────────────────────────────────────────
const _SMALL_CAPS = {
  a:'ᴀ',b:'ʙ',c:'ᴄ',d:'ᴅ',e:'ᴇ',f:'ꜰ',g:'ɢ',h:'ʜ',i:'ɪ',
  j:'ᴊ',k:'ᴋ',l:'ʟ',m:'ᴍ',n:'ɴ',o:'ᴏ',p:'ᴘ',q:'ǫ',r:'ʀ',
  s:'ꜱ',t:'ᴛ',u:'ᴜ',v:'ᴠ',w:'ᴡ',x:'x',y:'ʏ',z:'ᴢ'
};
function _toSmallCaps(str) {
  if (!str) return str;
  return str.toLowerCase().split('').map(c => _SMALL_CAPS[c] || c).join('');
}
function _updateHeaderUsername() {
  const el = document.getElementById('homeHeaderUsername');
  if (el) el.textContent = _toSmallCaps(state.username) || '—';
}

// ══════════════════════════════════════════════════════════════
//  DATA FETCH  — runs only on initial load, PTR, or tab change
// ══════════════════════════════════════════════════════════════

async function _fetchHomeData() {
  if (_homeIsFetching) return;
  _homeIsFetching = true;
  _homeDataLoaded = false;
  _renderToken++;   // invalidate any pending stale _renderList() from NP poll (Issue 3 fix)

  // Failsafe: if loading stalls > 12 s show error state
  const _loadGuard = setTimeout(() => {
    _homeIsFetching = false;
    _showHomeLoading(false);
    _showHomeCards(true);
    if (!_homeAllTracks.length) {
      const list = document.getElementById('dailyMixList');
      if (list) {
        let w = list.querySelector('.home-tracks-wrap');
        if (!w) { w = document.createElement('div'); w.className = 'home-tracks-wrap'; list.appendChild(w); }
        w.innerHTML = `<div style="padding:32px 18px;text-align:center;color:var(--md-outline,#888);font-size:13px;">
          <span class="material-symbols-rounded" style="font-size:40px;display:block;margin-bottom:12px;opacity:.5">wifi_off</span>
          Could not load — check your connection.<br>
          <button onclick="_homeRetry()" style="margin-top:14px;padding:9px 20px;border-radius:100px;border:none;background:var(--accent,#E03030);color:#fff;font-size:13px;font-weight:700;cursor:pointer">Retry</button>
        </div>`;
      }
    }
  }, 12000);

  if (!state.apiKey) {
    _updateHeaderUsername();
    ['profileScrobbles','statTracks','statArtists','statAlbums'].forEach(id => {
      const el = document.getElementById(id); if (el) el.textContent = '—';
    });
    _homeAllTracks = [];
    _renderList();
    _showHomeLoading(false);
    _showHomeCards(true);
    clearTimeout(_loadGuard);
    _homeIsFetching = false;
    showToast('Add your API key in Settings to load data', 'error');
    return;
  }

  try {
    const [profileData, tracksData, artistsData, albumsData, recentData, topData] =
      await Promise.allSettled([
        lfmCall({ method: 'user.getinfo',         user: state.username }),
        lfmCall({ method: 'user.gettoptracks',    user: state.username, period: 'overall', limit: 1 }),
        lfmCall({ method: 'user.gettopartists',   user: state.username, period: 'overall', limit: 1 }),
        lfmCall({ method: 'user.gettopalbums',    user: state.username, period: 'overall', limit: 1 }),
        lfmCall({ method: 'user.getrecenttracks', user: state.username, limit: 50 }),
        lfmCall({ method: 'user.gettoptracks',    user: state.username, period: 'overall', limit: 50 }),
      ]);

    _updateHeaderUsername();

    let totalSeconds = 0;
    if (profileData.status === 'fulfilled') {
      const u = profileData.value.user;
      document.getElementById('profileScrobbles').textContent = parseInt(u.playcount || 0).toLocaleString();
      totalSeconds = parseInt(u.playcount || 0) * 210;
      const imgEntry = u.image && (
        u.image.find(i => i.size === 'large'  && _isRealHomeImg(i['#text'])) ||
        u.image.find(i => i.size === 'medium' && _isRealHomeImg(i['#text'])) ||
        u.image.find(i => _isRealHomeImg(i['#text']))
      );
      if (imgEntry?.['#text']) _applyTopbarAvatar(imgEntry['#text']);
    } else {
      document.getElementById('profileScrobbles').textContent = '—';
    }

    if (tracksData.status  === 'fulfilled') { const t = tracksData.value?.toptracks?.['@attr']?.total;   document.getElementById('statTracks').textContent   = t ? parseInt(t).toLocaleString() : '—'; }
    if (artistsData.status === 'fulfilled') { const t = artistsData.value?.topartists?.['@attr']?.total; document.getElementById('statArtists').textContent  = t ? parseInt(t).toLocaleString() : '—'; }
    if (albumsData.status  === 'fulfilled') { const t = albumsData.value?.topalbums?.['@attr']?.total;   document.getElementById('statAlbums').textContent   = t ? parseInt(t).toLocaleString() : '—'; }

    // ── Recent tracks ────────────────────────────────────────
    let recentTracks = [];
    if (recentData.status === 'fulfilled') {
      const raw    = recentData.value?.recenttracks?.track;
      const rawArr = Array.isArray(raw) ? raw : (raw ? [raw] : []);
      recentTracks = rawArr
        .filter(t => t && t.name && !(t['@attr']?.nowplaying === 'true'))
        .map((t, i) => {
          const imgArr   = Array.isArray(t.image) ? t.image : [];
          const imgEntry =
            imgArr.find(img => img.size === 'extralarge' && _isRealHomeImg(img['#text'])) ||
            imgArr.find(img => img.size === 'large'      && _isRealHomeImg(img['#text'])) ||
            imgArr.find(img => img.size === 'medium'     && _isRealHomeImg(img['#text'])) ||
            imgArr.find(img =>                              _isRealHomeImg(img['#text']));
          const artist = t.artist
            ? (typeof t.artist === 'string' ? t.artist : (t.artist['#text'] || t.artist.name || ''))
            : '';
          return {
            name:         t.name, artist,
            url:          t.url || '',
            image:        imgEntry?.['#text'] || '',
            album:        t.album?.['#text'] || '',
            _recentIndex: i,
            _timestamp:   t.date?.uts ? parseInt(t.date.uts, 10) * 1000 : null,
          };
        });
    }

    // ── Overall top tracks (fill in any gaps) ────────────────
    let topTracks = [];
    if (topData.status === 'fulfilled') {
      const rawArr = Array.isArray(topData.value?.toptracks?.track)
        ? topData.value.toptracks.track
        : (topData.value?.toptracks?.track ? [topData.value.toptracks.track] : []);
      topTracks = rawArr.filter(t => t && t.name).map(t => {
        const imgArr   = Array.isArray(t.image) ? t.image : [];
        const imgEntry =
          imgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) ||
          imgArr.find(i => i.size === 'large'      && _isRealHomeImg(i['#text'])) ||
          imgArr.find(i => i.size === 'medium'     && _isRealHomeImg(i['#text'])) ||
          imgArr.find(i =>                            _isRealHomeImg(i['#text']));
        const artist = t.artist
          ? (typeof t.artist === 'string' ? t.artist : (t.artist.name || t.artist['#text'] || ''))
          : '';
        return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', _playCount: parseInt(t.playcount || 0) };
      });
    }

    const recentKeys  = new Set(recentTracks.map(t => `${t.name}|${t.artist}`.toLowerCase()));
    const extra       = topTracks.filter(t => !recentKeys.has(`${t.name}|${t.artist}`.toLowerCase()));
    // Merge _playCount from topTracks into matching recentTracks — required for mostPlayed count pills
    const topCountMap = new Map(topTracks.map(t => [`${t.name.toLowerCase()}|${t.artist.toLowerCase()}`, t._playCount || 0]));
    recentTracks.forEach(t => {
      const c = topCountMap.get(`${t.name.toLowerCase()}|${t.artist.toLowerCase()}`);
      if (c) t._playCount = c;
    });
    // extra entries (all-time top tracks not in recent 50) have no _timestamp.
    // The Recent render path already hard-filters to _isToday(), so they are
    // never shown as phantom today-tracks. They remain in _homeAllTracks for
    // the mostPlayed/period tabs which do not apply the today filter (Issues 2/3/5 fix).
    _homeAllTracks   = [...recentTracks, ...extra];

    _syncListScrollClass();
    _renderList();
    _showHomeLoading(false);
    _showHomeCards(true);
    _triggerEntryAnimations();

    _homeDataLoaded = true;
    _homeIsFetching = false;
    clearTimeout(_loadGuard);

    // Background tasks — do not block UI
    _enrichHomeArt(_homeAllTracks);
    _initListenTimer(totalSeconds);

  } catch (err) {
    clearTimeout(_loadGuard);
    _homeIsFetching = false;
    _showHomeLoading(false);
    _showHomeCards(true);
  }
}

function _homeRetry() {
  _homeIsFetching    = false;
  _homeDataLoaded    = false;
  _homeShellAnimated = false;
  _homeAllTracks  = [];
  _homeCurrentPage = 1; _homeIsLoadingMore = false; _homeHasMore = true;
  _showHomeLoading(true);
  _showHomeCards(false);
  _fetchHomeData();
}

// ══════════════════════════════════════════════════════════════
//  FULL REFRESH  — identical to first app load.
//  Resets ALL module state then re-runs _fetchHomeData() so
//  the header, stats card, and track list all get fresh data.
//  This is what pull-to-refresh triggers.
// ══════════════════════════════════════════════════════════════
async function _refreshAll() {
  if (!state.username || !state.apiKey) return;
  _homeIsFetching      = false;
  _homeDataLoaded      = false;
  _homeShellAnimated   = false;   // replay entry animations on next render (Issue 7 fix)
  _homeCurrentPage     = 1;
  _homeIsLoadingMore   = false;
  _homeHasMore         = true;
  _homeDateTracksCache = { 7: null, 30: null };
  _homeDateFetching    = { 7: false, 30: false };
  _homePeriodFetchToken++;
  _nowPlayingTrack          = null;
  _lastSameTrackScrobbleUts = 0;
  _lastFetchedRecentUts     = 0;  // Reset polling tracker
  _renderToken++;   // drop any stale NP-poll renders queued before this refresh (Issue 3/4 fix)
  // DO NOT clear _homeAllTracks — keep old data visible during the fetch so the
  // list is never blank during pull-to-refresh. _fetchHomeData() replaces it
  // atomically when fresh data arrives (Issue 7 fix).
  await _fetchHomeData();

  // _fetchHomeData() only loads recent + overall top tracks.
  // If the user is on a period or mostPlayed tab, kick off that fetch too
  // so the tab is never left blank after a pull-to-refresh (Issue 4 fix).
  if (_homeSortMode === 'last7days' || _homeSortMode === 'last30days') {
    const days   = _homeSortMode === 'last7days' ? 7 : 30;
    const period = _homeSortMode === 'last7days' ? '7day' : '1month';
    _homePeriodFetchToken++;
    const token = _homePeriodFetchToken;
    _showPeriodLoadingState();
    _fetchTopTracksForPeriod(period, days, token);
  } else if (_homeSortMode === 'mostPlayed') {
    _fetchMostPlayedData();
  }
}

// ══════════════════════════════════════════════════════════════
//  SOFT REFRESH  — updates ONLY the track list.
//  Header, stats card, and layout are NOT touched.
//  No button, no pill — gesture only.
// ══════════════════════════════════════════════════════════════
// ══════════════════════════════════════════════════════════════
//  SOFT REFRESH  — updates ONLY #dailyMixList content.
//  Triggered by pull-to-refresh gesture (delta >= 70px).
//  Header, stats card, and layout are never touched.
// ══════════════════════════════════════════════════════════════
async function _refreshTrackList() {
  if (!state.username || !state.apiKey) return;

  if (_homeSortMode === 'last7days' || _homeSortMode === 'last30days') {
    // Period tabs — invalidate cache and re-fetch via existing pipeline
    const days   = _homeSortMode === 'last7days' ? 7 : 30;
    const period = _homeSortMode === 'last7days' ? '7day' : '1month';
    _homeDateTracksCache[days] = null;
    _homeDateFetching[days]    = false;
    _homePeriodFetchToken++;
    const token = _homePeriodFetchToken;
    _homeCurrentPage = 1; _homeHasMore = true;
    _showPeriodLoadingState();
    await _checkNowPlaying(); // Bug 6 fix: sync NP state so the card updates on PTR too
    await _fetchTopTracksForPeriod(period, days, token);
    return;
  }

  // Recent / Most Played — fetch fresh page 1 from API
  // Reset same-track tracker so a stale UTS from before the refresh
  // doesn't immediately trigger a spurious _scrobblePlay unshift (Problem 3 fix).
  _lastSameTrackScrobbleUts = 0;
  _lastFetchedRecentUts = 0;  // Reset polling tracker so first poll after refresh fetches fresh data
  try {
    const recentData = await lfmCall({ method: 'user.getrecenttracks', user: state.username, limit: 50 });
    const raw    = recentData?.recenttracks?.track;
    const rawArr = Array.isArray(raw) ? raw : (raw ? [raw] : []);

    // Sync now-playing state
    const npItem = rawArr.find(t => t?.['@attr']?.nowplaying === 'true');
    if (npItem) {
      const npImgArr = Array.isArray(npItem.image) ? npItem.image : [];
      const npImg    =
        npImgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) ||
        npImgArr.find(i =>                            _isRealHomeImg(i['#text']));
      const npArtist = typeof npItem.artist === 'string' ? npItem.artist : (npItem.artist?.['#text'] || '');
      const prevKey  = _nowPlayingTrack ? `${_nowPlayingTrack.name}|${_nowPlayingTrack.artist}`.toLowerCase() : null;
      const curKey   = `${npItem.name}|${npArtist}`.toLowerCase();
      if (prevKey !== curKey) _nowPlayingTrack = { name: npItem.name, artist: npArtist, image: npImg?.['#text'] || '' };
    } else {
      _nowPlayingTrack = null;
    }

    const freshTracks = rawArr
      .filter(t => t && t.name && !(t?.['@attr']?.nowplaying === 'true'))
      .map((t, i) => {
        const imgArr   = Array.isArray(t.image) ? t.image : [];
        const imgEntry =
          imgArr.find(img => img.size === 'extralarge' && _isRealHomeImg(img['#text'])) ||
          imgArr.find(img => img.size === 'large'      && _isRealHomeImg(img['#text'])) ||
          imgArr.find(img => img.size === 'medium'     && _isRealHomeImg(img['#text'])) ||
          imgArr.find(img =>                              _isRealHomeImg(img['#text']));
        const artist = t.artist
          ? (typeof t.artist === 'string' ? t.artist : (t.artist['#text'] || t.artist.name || ''))
          : '';
        return {
          name: t.name, artist,
          url: t.url || '',
          image: imgEntry?.['#text'] || '',
          album: t.album?.['#text'] || '',
          _recentIndex: i,
          _timestamp: t.date?.uts ? parseInt(t.date.uts, 10) * 1000 : null,
        };
      });

    if (_homeSortMode === 'mostPlayed') {
      const topData = await lfmCall({ method: 'user.gettoptracks', user: state.username, period: 'overall', limit: 50 });
      const topRaw  = topData?.toptracks?.track;
      const topArr  = Array.isArray(topRaw) ? topRaw : (topRaw ? [topRaw] : []);
      const topTracks = topArr.filter(t => t && t.name).map(t => {
        const imgArr   = Array.isArray(t.image) ? t.image : [];
        const imgEntry = imgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) || imgArr.find(i => i.size === 'large' && _isRealHomeImg(i['#text'])) || imgArr.find(i => _isRealHomeImg(i['#text']));
        const artist   = t.artist ? (typeof t.artist === 'string' ? t.artist : (t.artist.name || t.artist['#text'] || '')) : '';
        return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', _playCount: parseInt(t.playcount || 0) };
      });
      const recentKeys = new Set(freshTracks.map(t => `${t.name}|${t.artist}`.toLowerCase()));
      _homeAllTracks   = [...freshTracks, ...topTracks.filter(t => !recentKeys.has(`${t.name}|${t.artist}`.toLowerCase()))];
    } else {
      _homeAllTracks = freshTracks;
    }

    _homeCurrentPage = 1; _homeHasMore = true;
    _fadeOutList(() => {
      _syncListScrollClass();
      _renderList();
      // Enrich art for any tracks that came back without images —
      // same call _fetchHomeData makes; soft refresh must match.
      _enrichHomeArt(_homeAllTracks.filter(t => !t.image));
    });
  } catch {
    showToast('Refresh failed — check your connection', 'error');
  }
}

// ── Background art enrichment ─────────────────────────────────
async function _enrichHomeArt(tracks) {
  if (!state.apiKey) return;
  const missing = tracks.filter(t => !t.image || !t.image.trim()).slice(0, 50);
  if (!missing.length) return;
  const BATCH = 4;
  for (let i = 0; i < missing.length; i += BATCH) {
    await Promise.allSettled(
      missing.slice(i, i + BATCH).map(async t => {
        try {
          const url = typeof _resolveTrackArt === 'function'
            ? await _resolveTrackArt(t.name, t.artist)
            : await _resolveTrackArtLocal(t.name, t.artist);
          if (url) { t.image = url; _patchTrackArt(t.name, t.artist, url); }
        } catch { /* silent */ }
      })
    );
  }
}

async function _resolveTrackArtLocal(name, artist) {
  let url = '';
  try {
    const data  = await lfmCall({ method: 'track.getInfo', track: name, artist, autocorrect: 1 });
    const album = data?.track?.album;
    if (album?.image) {
      const img =
        album.image.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) ||
        album.image.find(i => i.size === 'large'      && _isRealHomeImg(i['#text'])) ||
        album.image.find(i => i.size === 'medium'     && _isRealHomeImg(i['#text'])) ||
        album.image.find(i => _isRealHomeImg(i['#text']));
      if (img) url = img['#text'];
    }
  } catch {}
  if (!url && typeof _itunesFetchArtwork === 'function') {
    try { url = await _itunesFetchArtwork(name, artist, 'track'); } catch {}
  }
  return url;
}

// ── Cover art error/load helpers ─────────────────────────────
// Retry image load once on error before falling back to placeholder.
// Prevents transient network blips from leaving blank art permanently.
function _homeImgError(img) {
  if (img._retried) {
    img.classList.add('errored');
    return;
  }
  img._retried = true;
  const src = img.src;
  img.src = '';                           // clear stale src
  setTimeout(() => {
    if (src) { img.src = src; }          // retry after brief pause
    else     { img.classList.add('errored'); }
  }, 400);
}
// Fade the image in once loaded and hide the fallback icon.
function _homeImgLoad(img) {
  img.style.opacity = '1';
  const fb = img.closest('.home-track-art-wrap')?.querySelector('.home-track-art-fallback');
  if (fb) fb.style.display = 'none';
}

function _patchTrackArt(name, artist, url) {
  const list = document.getElementById('dailyMixList');
  if (!list) return;
  list.querySelectorAll('.home-track-item').forEach(row => {
    const nameEl   = row.querySelector('.home-track-name');
    const artistEl = row.querySelector('.home-track-artist');
    if (!nameEl || !artistEl) return;
    if (nameEl.textContent.trim() !== name || artistEl.textContent.trim() !== artist) return;
    const wrap = row.querySelector('.home-track-art-wrap');
    if (!wrap) return;
    let img = wrap.querySelector('.home-track-art');
    const fallback = wrap.querySelector('.home-track-art-fallback');
    if (!img) {
      img = document.createElement('img');
      img.className  = 'home-track-art';
      img.alt        = '';
      img.loading    = 'lazy';
      img.decoding   = 'async';
      img.style.cssText = 'opacity:0;transition:opacity 0.2s ease';
      img.onload  = function() { _homeImgLoad(this); };
      img.onerror = function() { _homeImgError(this); };
      wrap.insertBefore(img, wrap.firstChild);
    }
    // Always assign a fresh src — prevents stale cached DOM reuse
    img.classList.remove('errored');
    img._retried   = false;
    img.style.opacity = '0';
    img.src = '';              // force browser to treat next assignment as new load
    img.src = url;
    if (fallback) fallback.style.display = 'none';
  });
}

// ══════════════════════════════════════════════════════════════
//  LIVE LISTENING TIMER
//  ONLY the per-second tick uses setInterval.
//  No network polling. One-shot now-playing check on init.
// ══════════════════════════════════════════════════════════════
let _listenTimerBase = 0;
let _listenLiveSecs  = 0;
let _listenIsPlaying = false;
let _listenTickTimer = null;

function _initListenTimer(totalSeconds) {
  _listenTimerBase = totalSeconds || 0;
  _listenLiveSecs  = 0;
  _listenIsPlaying = false;
  _stopListenTick();
  _updateTimerDisplay();
  _checkNowPlaying();   // immediate check on init
  _startNpPolling();    // poll every 5 s for real-time NP updates
  _startRecentTracksPolling();  // poll every 8 s for new scrobbles

  // Register visibilitychange only ONCE across all _initListenTimer calls —
  // multiple calls (e.g. after PTR full refresh) must not stack listeners.
  if (!_homeVisibilityBound) {
    _homeVisibilityBound = true;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') _checkNowPlaying();
      else { _stopListenTick(true); _listenIsPlaying = false; }
    });
  }

  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.setActionHandler('play', () => {
        _listenIsPlaying = true;
        _startListenTick();
      });
      // On pause/stop: stop timer AND immediately clear NP so the card
      // disappears without waiting for the next 30 s poll (Issue 2 fix).
      navigator.mediaSession.setActionHandler('pause', () => {
        _listenIsPlaying = false;
        _stopListenTick(true);
        if (_nowPlayingTrack) { _nowPlayingTrack = null; _renderList(); }
      });
      navigator.mediaSession.setActionHandler('stop', () => {
        _listenIsPlaying = false;
        _stopListenTick(true);
        if (_nowPlayingTrack) { _nowPlayingTrack = null; _renderList(); }
      });
    } catch {}
  }
}
function _destroyListenTimer() { _stopListenTick(); _stopNpPolling(); _stopRecentTracksPolling(); }

// Poll every 5 s — real-time NP updates; detects track changes and pauses promptly.
function _startNpPolling() {
  if (_npPollTimer) return;
  _npPollTimer = setInterval(async () => {
    if (state.username && state.apiKey) await _checkNowPlaying();
  }, 5000);
}
function _stopNpPolling() {
  if (_npPollTimer) { clearInterval(_npPollTimer); _npPollTimer = null; }
}

// ── Recent tracks polling (detects new scrobbles) ──────────────────────
// Separate from NOW PLAYING polling — fetches full recent list every 8 s
// to detect new scrobbles that weren't caught by track-change detection.
// This ensures real-time updates even when user isn't currently playing.

function _startRecentTracksPolling() {
  if (_recentTracksPollTimer) return;
  _recentTracksPollTimer = setInterval(async () => {
    if (state.username && state.apiKey && _homeSortMode === 'recent') {
      await _pollRecentTracksForNewScrobbles();
    }
  }, 8000);  // Poll every 8 seconds
}

function _stopRecentTracksPolling() {
  if (_recentTracksPollTimer) { clearInterval(_recentTracksPollTimer); _recentTracksPollTimer = null; }
}

async function _pollRecentTracksForNewScrobbles() {
  if (!state.username || !state.apiKey) return;
  try {
    const recentData = await lfmCall({ method: 'user.getrecenttracks', user: state.username, limit: 50 });
    
    if (!recentData || !recentData.recenttracks) return;

    const raw    = recentData.recenttracks?.track;
    const rawArr = Array.isArray(raw) ? raw : (raw ? [raw] : []);
    
    if (!rawArr.length) return;
    
    // Get the latest scrobble's timestamp
    const latest = rawArr[0];
    if (!latest || latest['@attr']?.nowplaying === 'true') return;
    
    const latestUts = parseInt(latest?.date?.uts || 0);
    if (!latestUts || latestUts <= 0) return;
    
    // If timestamp changed, replace the ENTIRE list with fresh data
    if (latestUts !== _lastFetchedRecentUts) {
      _lastFetchedRecentUts = latestUts;
      await _updateRecentTracksFromPoll(rawArr);
    }
  } catch (err) {
    console.error('[LastWave] Error in _pollRecentTracksForNewScrobbles:', err);
  }
}

async function _updateRecentTracksFromPoll(rawArr) {
  try {
    if (!rawArr || !Array.isArray(rawArr)) return;

    // Parse all tracks from the API response
    const freshTracks = rawArr
      .filter(t => t && t.name && !(t?.['@attr']?.nowplaying === 'true'))
      .map((t, i) => {
        try {
          const imgArr   = Array.isArray(t.image) ? t.image : [];
          const imgEntry =
            imgArr.find(img => img.size === 'extralarge' && _isRealHomeImg(img['#text'])) ||
            imgArr.find(img => img.size === 'large'      && _isRealHomeImg(img['#text'])) ||
            imgArr.find(img => img.size === 'medium'     && _isRealHomeImg(img['#text'])) ||
            imgArr.find(img =>                              _isRealHomeImg(img['#text']));
          const artist = t.artist
            ? (typeof t.artist === 'string' ? t.artist : (t.artist['#text'] || t.artist.name || ''))
            : '';
          return {
            name: t.name, artist,
            url: t.url || '',
            image: imgEntry?.['#text'] || '',
            album: t.album?.['#text'] || '',
            _recentIndex: i,
            _timestamp: t.date?.uts ? parseInt(t.date.uts, 10) * 1000 : null,
          };
        } catch (err) {
          console.warn('[LastWave] Error parsing track:', t, err);
          return null;
        }
      })
      .filter(t => t !== null);

    if (!Array.isArray(freshTracks) || freshTracks.length === 0) return;
    if (!Array.isArray(_homeAllTracks)) return;

    // IMPORTANT: Always replace the full list with fresh data
    // This ensures new scrobbles appear immediately without dedup blocking them
    _homeAllTracks = freshTracks;
    _renderList();
  } catch (err) {
    console.error('[LastWave] Error in _updateRecentTracksFromPoll:', err);
  }
}

async function _checkNowPlaying() {
  if (!state.username || !state.apiKey) return;
  try {
    const url = new URL(LASTFM_BASE);
    // limit=2: slot[0] = currently playing / latest; slot[1] = last completed scrobble.
    // We need slot[1] to detect when the same track has been scrobbled again
    // without a track change occurring (repeated play fix).
    const p   = { method: 'user.getrecenttracks', user: state.username, limit: 2, api_key: state.apiKey, format: 'json' };
    Object.entries(p).forEach(([k, v]) => url.searchParams.set(k, v));
    const res    = await fetch(url.toString(), { cache: 'no-store' });
    if (!res.ok) return;
    const data   = await res.json();
    const tracks = data?.recenttracks?.track;
    const latest = Array.isArray(tracks) ? tracks[0] : tracks;
    const second = Array.isArray(tracks) ? (tracks[1] || null) : null;
    const playing = latest?.['@attr']?.nowplaying === 'true';

    if (playing) {
      const trackName  = latest.name || '';
      const artistName = typeof latest.artist === 'string' ? latest.artist : (latest.artist?.['#text'] || '');
      const imgArr     = Array.isArray(latest.image) ? latest.image : [];
      const imgEntry   =
        imgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) ||
        imgArr.find(i => i.size === 'large'      && _isRealHomeImg(i['#text'])) ||
        imgArr.find(i => i.size === 'medium'     && _isRealHomeImg(i['#text'])) ||
        imgArr.find(i => _isRealHomeImg(i['#text']));
      const image = imgEntry?.['#text'] || '';

      const prevKey = _nowPlayingTrack ? `${_nowPlayingTrack.name}|${_nowPlayingTrack.artist}`.toLowerCase() : null;
      const curKey  = `${trackName}|${artistName}`.toLowerCase();

      if (prevKey !== curKey) {
        // ── Track changed ──────────────────────────────────────────────
        if (_nowPlayingTrack && prevKey) {
          const prevName   = _nowPlayingTrack.name;
          const prevArtist = _nowPlayingTrack.artist;
          const existing   = _homeAllTracks.find(
            t => `${t.name}|${t.artist}`.toLowerCase() === prevKey
          );
          if (!existing) {
            // Track played but wasn't in the list yet — add it
            _homeAllTracks.unshift({
              name: prevName, artist: prevArtist,
              image: _nowPlayingTrack.image || '',
              _recentIndex: -1, _timestamp: Date.now(), _liveSession: true,
            });
          }
          // Fetch the real updated play count from the API instead of
          // incrementing locally — prevents stale/wrong pill values (Issue 1).
          _refreshTrackPlayCount(prevName, prevArtist);
        }
        // Reset same-track scrobble tracker for the new track.
        _lastSameTrackScrobbleUts = 0;
        _nowPlayingTrack = { name: trackName, artist: artistName, image };
        // Render unconditionally — _renderList() no-ops when home DOM is unmounted.
        _renderList();
        if (!image) _resolveNowPlayingArt(trackName, artistName);
      } else {
        // ── Same track still playing — check for a repeated-play scrobble ──
        // When the user replays the same track, Last.fm scrobbles it again and it
        // appears in slot[1] of user.getrecenttracks with a fresh UTS timestamp.
        // We detect this by comparing the UTS of slot[1] against the last one we saw.
        if (second) {
          const secondName   = second.name || '';
          const secondArtist = typeof second.artist === 'string' ? second.artist : (second.artist?.['#text'] || '');
          const secondKey    = `${secondName}|${secondArtist}`.toLowerCase();
          const secondUts    = parseInt(second?.date?.uts || 0);
          if (secondKey === curKey && secondUts > 0) {
            if (_lastSameTrackScrobbleUts === 0) {
              // First poll tick after init/refresh — record the current slot[1] UTS
              // as the baseline so we don't misfire a duplicate entry for a scrobble
              // that already happened before the session started (Issue 1 fix).
              _lastSameTrackScrobbleUts = secondUts;
            } else if (secondUts > _lastSameTrackScrobbleUts) {
              // A genuinely new scrobble of the same track arrived since the last poll.
              _lastSameTrackScrobbleUts = secondUts;
              const existingEntry = _homeAllTracks.find(
                t => `${t.name}|${t.artist}`.toLowerCase() === secondKey && !t._liveSession
              );
              _homeAllTracks.unshift({
                name:          secondName,
                artist:        secondArtist,
                image:         existingEntry?.image || _nowPlayingTrack?.image || '',
                url:           existingEntry?.url   || '',
                _recentIndex:  -1,
                _timestamp:    secondUts * 1000,   // ms — real scrobble time from Last.fm
                _scrobblePlay: true,
              });
              // Render immediately — pill increments in the current poll tick.
              _renderList();
              // Fetch authoritative server count for mostPlayed/_playCount accuracy.
              _refreshTrackPlayCount(trackName, artistName);
            }
          }
        }
      }
      if (!_listenIsPlaying) { _listenIsPlaying = true; _startListenTick(); }
    } else {
      if (_listenIsPlaying) { _listenIsPlaying = false; _stopListenTick(true); }
      if (_nowPlayingTrack) {
        _nowPlayingTrack = null;
        // Render unconditionally — same reason as above (Issue 2 / Issue 5 fix).
        _renderList();
      }
    }
  } catch {}
}

function _removeNowPlayingRow() {
  const row = document.querySelector('.home-track-nowplaying');
  if (!row) return;
  row.style.transition = 'opacity 0.28s ease';
  row.style.opacity    = '0';
  setTimeout(() => row.remove(), 300);
}

// ── Post-scrobble play-count refresh ─────────────────────────
// Called when a track change is detected (implies the previous track was
// scrobbled). Fetches the real userplaycount from Last.fm's track.getInfo
// endpoint and patches _homeAllTracks + the live DOM so the pill shows the
// authoritative server value instead of a local guess.
async function _refreshTrackPlayCount(name, artist) {
  if (!state.username || !state.apiKey) return;
  try {
    const data  = await lfmCall({
      method: 'track.getInfo', track: name, artist,
      autocorrect: 1, username: state.username,
    });
    const count = parseInt(data?.track?.userplaycount || 0);
    if (!count) return;
    // Update _playCount on every entry for this track (there may be multiple
    // after same-track replay detection adds _scrobblePlay entries).
    let patched = false;
    _homeAllTracks.forEach(t => {
      if (t.name.toLowerCase()   === name.toLowerCase() &&
          t.artist.toLowerCase() === artist.toLowerCase()) {
        t._playCount = count;
        patched = true;
      }
    });
    if (patched) _renderList();
  } catch { /* silent — stale value is better than a crash */ }
}

async function _resolveNowPlayingArt(name, artist) {
  try {
    const url = typeof _resolveTrackArt === 'function' ? await _resolveTrackArt(name, artist) : '';
    if (!url) return;
    if (!_nowPlayingTrack || _nowPlayingTrack.name !== name || _nowPlayingTrack.artist !== artist) return;
    _nowPlayingTrack.image = url;
    const row = document.querySelector('.home-track-nowplaying');
    if (!row) return;
    const wrap = row.querySelector('.home-np-art-wrap');
    const fb   = row.querySelector('.home-track-art-fallback');
    if (!wrap) return;
    let img = wrap.querySelector('.home-np-art');
    if (!img) {
      img = document.createElement('img');
      img.className = 'home-track-art home-np-art'; img.alt = ''; img.loading = 'eager';
      img.onerror = () => img.classList.add('errored');
      wrap.insertBefore(img, wrap.firstChild);
    }
    img.style.opacity = '0'; img.style.transition = 'opacity 0.3s ease';
    img.onload = () => { img.style.opacity = '1'; if (fb) fb.style.display = 'none'; };
    img.src = url;
  } catch {}
}

function _startListenTick() {
  if (_listenTickTimer) return;
  _listenTickTimer = setInterval(() => { _listenLiveSecs++; _updateTimerDisplay(); }, 1000);
}
function _stopListenTick(keepLiveSecs = false) {
  if (_listenTickTimer) { clearInterval(_listenTickTimer); _listenTickTimer = null; }
  if (!keepLiveSecs) _listenLiveSecs = 0;
  _updateTimerDisplay();
}
function _updateTimerDisplay() {
  const el = document.getElementById('homeTimerValue');
  if (!el) return;
  const t = _listenTimerBase + _listenLiveSecs;
  if (!t) { el.textContent = '--:--:--:--'; return; }
  const d = Math.floor(t / 86400), h = Math.floor((t % 86400) / 3600),
        m = Math.floor((t % 3600) / 60), s = t % 60;
  el.textContent = `${String(d).padStart(2,'0')}:${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
  document.getElementById('homeListenTimer')?.classList.toggle('timer-live', _listenIsPlaying);
}

// ══════════════════════════════════════════════════════════════
//  TAB SWITCH ANIMATION  (slide left out / slide right in)
// ══════════════════════════════════════════════════════════════

function _fadeOutList(callback) {
  const listEl = document.getElementById('dailyMixList');
  if (!listEl) { callback(); return; }
  _pendingListFadeIn = true;
  listEl.classList.add('switching-out');
  listEl.classList.remove('switching-in');
  setTimeout(callback, 205);
}

function _fadeInListIfNeeded() {
  if (!_pendingListFadeIn) return;
  _pendingListFadeIn = false;
  const listEl = document.getElementById('dailyMixList');
  if (!listEl) return;
  listEl.classList.remove('switching-out');
  listEl.classList.add('switching-in');
  setTimeout(() => { listEl.classList.remove('switching-in'); }, 260);
}

// ══════════════════════════════════════════════════════════════
//  LIST CONTAINER REPLACEMENT  (React key equivalent)
//  Destroys the current #dailyMixList node and inserts a fresh
//  one in its place. Guarantees zero stale inline style, zero
//  stale class, and a clean layout calculation in WebView —
//  identical to what React achieves with a changing `key` prop.
// ══════════════════════════════════════════════════════════════
function _replaceListContainer(mode) {
  const old = document.getElementById('dailyMixList');
  if (!old) return null;
  const fresh = document.createElement('div');
  fresh.id        = 'dailyMixList';
  fresh.className = 'home-mix-list';
  if (mode === 'recent') fresh.classList.add('recent');
  old.parentNode.replaceChild(fresh, old);
  return fresh;
}

// Re-bind every listener that targets #dailyMixList after a node swap.
function _rebindListListeners() {
  // PTR — _ptrBound flag lives on old node, new node has none → rebinds cleanly.
  _homePtr.active = false;
  _setupPullToRefresh();

  // Infinite scroll — _homeInfScrollBound lives on old node → rebinds cleanly.
  _setupInfiniteScroll();

  // Scroll shadow on the card header.
  const listEl  = document.getElementById('dailyMixList');
  const listCard = document.getElementById('dailyMixCard');
  if (listEl && listCard) {
    let raf = false;
    listEl.addEventListener('scroll', () => {
      if (raf) return; raf = true;
      requestAnimationFrame(() => {
        listCard.classList.toggle('scrolled', listEl.scrollTop > 4);
        raf = false;
      });
    }, { passive: true });
  }
}

// ══════════════════════════════════════════════════════════════
//  SORT MENU
// ══════════════════════════════════════════════════════════════

function toggleSortMenu() {
  _sortMenuOpen = !_sortMenuOpen;
  document.getElementById('homeSortMenu')?.classList.toggle('hidden', !_sortMenuOpen);
  document.getElementById('homeSortChevron')?.classList.toggle('open', _sortMenuOpen);
  if (_sortMenuOpen) setTimeout(() => document.addEventListener('click', _closeSortMenuOutside, { once: true }), 0);
}
function _closeSortMenuOutside(e) {
  if (!document.getElementById('homeSortWrap')?.contains(e.target)) {
    _sortMenuOpen = false;
    document.getElementById('homeSortMenu')?.classList.add('hidden');
    document.getElementById('homeSortChevron')?.classList.remove('open');
  }
}

// ── setSortMode ───────────────────────────────────────────────
// FIXED: explicit mode↔element mapping — no string-transform that
//        silently mis-capitalises 'last7days' → 'last7Days'.
const _SORT_MODE_MAP = {
  //  mode key       optEl ID        checkEl ID
  recent:     { opt: 'sortOptRecent',     check: 'checkRecent'     },
  mostPlayed: { opt: 'sortOptMostPlayed', check: 'checkMostPlayed' },
  last7days:  { opt: 'sortOptLast7Days',  check: 'checkLast7Days'  },
  last30days: { opt: 'sortOptLast30Days', check: 'checkLast30Days' },
};

function setSortMode(mode) {
  _sortMenuOpen = false;
  document.getElementById('homeSortMenu')?.classList.add('hidden');
  document.getElementById('homeSortChevron')?.classList.remove('open');

  _homeSortMode = mode;
  _homeCurrentPage = 1; _homeIsLoadingMore = false; _homeHasMore = true;

  // Update sort label
  const labels = { recent: 'Recent', mostPlayed: 'Most Played', last7days: 'Last 7 Days', last30days: 'Last 30 Days' };
  const labelEl = document.getElementById('homeSortLabel');
  if (labelEl) labelEl.textContent = labels[mode] || mode;

  // Update active state + checkmarks using the explicit map (Bug 1 fix)
  Object.entries(_SORT_MODE_MAP).forEach(([key, ids]) => {
    const isActive = (key === mode);
    document.getElementById(ids.opt)?.classList.toggle('active', isActive);
    document.getElementById(ids.check)?.classList.toggle('hidden', !isActive);
  });

  // Animate out → replace container → fetch/render → animate in → re-bind listeners.
  // Container is replaced INSIDE the callback so old content stays visible during
  // the fade-out — prevents blank flash on tab switch (Issue 6 fix).
  _fadeOutList(() => {
    const listEl = _replaceListContainer(mode);
    if (listEl) listEl.scrollTop = 0;
    _renderToken++;   // new container → invalidate any poll-triggered render from old tab (Issue 3 fix)

    if (mode === 'last7days' || mode === 'last30days') {
      _homePeriodFetchToken++;
      const token = _homePeriodFetchToken;
      const days  = mode === 'last7days' ? 7 : 30;
      // Do NOT null the cache here — keep old data visible until new data
      // arrives so the list is never blank during the fetch (Issue 4 fix).
      // Only show the loading spinner if there's genuinely no data yet.
      _homeDateFetching[days] = false;
      if (!_homeDateTracksCache[days]) _showPeriodLoadingState();
      else _renderList();   // render stale data immediately; new data will replace it
      _fetchTopTracksForPeriod(mode === 'last7days' ? '7day' : '1month', days, token);
    } else if (mode === 'mostPlayed') {
      // Always fetch fresh top-tracks so play counts are accurate — do not rely on
      // the merged _homeAllTracks which may have incomplete or stale play counts.
      _showPeriodLoadingState();
      _fetchMostPlayedData();
    } else {
      _renderList();
    }
    _rebindListListeners();
  });
}

function _showPeriodLoadingState() {
  const list = document.getElementById('dailyMixList');
  if (!list) return;
  let wrap = list.querySelector('.home-tracks-wrap');
  if (!wrap) { wrap = document.createElement('div'); wrap.className = 'home-tracks-wrap'; list.appendChild(wrap); }
  wrap.innerHTML = `
    <div style="display:flex;justify-content:center;align-items:center;padding:48px 0;gap:10px;color:var(--md-outline,#888);font-size:13px;">
      <span class="material-symbols-rounded" style="animation:spin 1s linear infinite;font-size:22px;">refresh</span>
      Loading…
    </div>`;
  _fadeInListIfNeeded();
}

async function _fetchTopTracksForPeriod(period, days, token) {
  if (!state.username || !state.apiKey) return;
  const targetMode        = days === 7 ? 'last7days' : 'last30days';
  _homeDateFetching[days] = true;
  const accumulated       = [];
  let page = 1, totalPages = 1;

  try {
    do {
      if (_homeSortMode !== targetMode || _homePeriodFetchToken !== token) return;
      const data = await lfmCall({ method: 'user.gettoptracks', user: state.username, period, limit: 50, page });
      if (_homeSortMode !== targetMode || _homePeriodFetchToken !== token) return;
      totalPages = parseInt(data?.toptracks?.['@attr']?.totalPages || 1, 10);
      const raw    = data?.toptracks?.track;
      const rawArr = Array.isArray(raw) ? raw : (raw ? [raw] : []);
      accumulated.push(...rawArr.filter(t => t && t.name).map(t => {
        const imgArr   = Array.isArray(t.image) ? t.image : [];
        const imgEntry =
          imgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) ||
          imgArr.find(i => i.size === 'large'      && _isRealHomeImg(i['#text'])) ||
          imgArr.find(i => i.size === 'medium'     && _isRealHomeImg(i['#text'])) ||
          imgArr.find(i =>                            _isRealHomeImg(i['#text']));
        const artist = t.artist ? (typeof t.artist === 'string' ? t.artist : (t.artist.name || t.artist['#text'] || '')) : '';
        return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', _playCount: parseInt(t.playcount || 0) };
      }));
      if (_homeSortMode === targetMode && _homePeriodFetchToken === token) {
        _homeDateTracksCache[days] = [...accumulated];
        _homeHasMore = page < totalPages;
        _renderList();
      }
      page++; break; // first page only; infinite-scroll handles the rest
    } while (page <= totalPages);
    if (_homeSortMode === targetMode && _homePeriodFetchToken === token)
      _enrichHomeArt(accumulated.filter(t => !t.image));
  } catch {
    if (_homeSortMode === targetMode && _homePeriodFetchToken === token) {
      _homeDateTracksCache[days] = _homeDateTracksCache[days] ?? [];
      _renderList();
    }
  } finally { _homeDateFetching[days] = false; }
}

// ── Most Played: dedicated top-tracks fetch ───────────────────
// Called by setSortMode('mostPlayed') — always fetches fresh overall
// top tracks so play counts are accurate, independent of _homeAllTracks.
async function _fetchMostPlayedData() {
  if (!state.username || !state.apiKey) { _renderList(); return; }
  try {
    const data   = await lfmCall({ method: 'user.gettoptracks', user: state.username, period: 'overall', limit: 50 });
    if (_homeSortMode !== 'mostPlayed') return;  // user switched away
    const raw    = data?.toptracks?.track;
    const rawArr = Array.isArray(raw) ? raw : (raw ? [raw] : []);
    const fresh  = rawArr.filter(t => t && t.name).map(t => {
      const imgArr   = Array.isArray(t.image) ? t.image : [];
      const imgEntry =
        imgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) ||
        imgArr.find(i => i.size === 'large'      && _isRealHomeImg(i['#text'])) ||
        imgArr.find(i => i.size === 'medium'     && _isRealHomeImg(i['#text'])) ||
        imgArr.find(i =>                            _isRealHomeImg(i['#text']));
      const artist = t.artist ? (typeof t.artist === 'string' ? t.artist : (t.artist.name || t.artist['#text'] || '')) : '';
      return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', _playCount: parseInt(t.playcount || 0) };
    });
    // Merge fresh play counts back into _homeAllTracks so the dedup sort is accurate
    const topMap = new Map(fresh.map(t => [`${t.name.toLowerCase()}|${t.artist.toLowerCase()}`, t._playCount]));
    _homeAllTracks.forEach(t => {
      const cnt = topMap.get(`${t.name.toLowerCase()}|${t.artist.toLowerCase()}`);
      if (cnt !== undefined) t._playCount = cnt;
    });
    // Append any top tracks not already present
    const existKeys = new Set(_homeAllTracks.map(t => `${t.name}|${t.artist}`.toLowerCase()));
    const extras    = fresh.filter(t => !existKeys.has(`${t.name}|${t.artist}`.toLowerCase()));
    if (extras.length) _homeAllTracks = [..._homeAllTracks, ...extras];
    if (_homeSortMode === 'mostPlayed') {
      _renderList();
      _enrichHomeArt(_homeAllTracks.filter(t => !t.image));
    }
  } catch {
    if (_homeSortMode === 'mostPlayed') _renderList();
  }
}

// ══════════════════════════════════════════════════════════════
//  NOW PLAYING INJECTION
//  Prepends the NP card to ANY array before rendering.
//  The existing list entry for this track is intentionally kept —
//  it must never disappear. Its play count is updated live via
//  _refreshTrackPlayCount() when a scrobble is confirmed.
// ══════════════════════════════════════════════════════════════
function _injectNowPlayingIntoArray(arr) {
  if (!_nowPlayingTrack) return arr;
  // Prepend the NP card. Do NOT filter arr — the list entry stays
  // so the play count pill remains visible throughout the session.
  return [{
    name:          _nowPlayingTrack.name,
    artist:        _nowPlayingTrack.artist,
    image:         _nowPlayingTrack.image || '',
    _timestamp:    Date.now(),
    _isNowPlaying: true,
  }, ...arr];
}

// ── Track frequency map (duplicate counting for pills) ──────────────
// Counts scrobbles per unique track (name|artist) to show how many times
// each track appears in the Recent list. Only counts confirmed scrobbles
// (entries with real _timestamp, excluding _liveSession speculative entries).
function _rebuildTrackFreq() {
  _homeTrackFreq = {};
  _homeAllTracks.forEach(t => {
    if (t._isNowPlaying)  return;  // NP card is not a completed scrobble
    if (!t._timestamp)    return;  // skip entries with no real timestamp
    if (t._liveSession)   return;  // skip speculative entries before Last.fm confirms the scrobble
    // Count all scrobbles in Recent mode, not just today's (allows pills to show accurate counts for recent history)
    const key = `${t.name.toLowerCase()}|${t.artist.toLowerCase()}`;
    _homeTrackFreq[key] = (_homeTrackFreq[key] || 0) + 1;
  });
}

// ══════════════════════════════════════════════════════════════
//  RENDER
// ══════════════════════════════════════════════════════════════

function _renderTrackRowHTML(t, rowIdx) {
  const hasImg = t.image && t.image.trim();
  const imgSrc = hasImg ? esc(t.image) : '';

  // ── Now Playing row ──
  if (t._isNowPlaying) {
    return `
    <div class="home-track-item home-track-nowplaying"
         data-lp-name="${escAttr(t.name)}" data-lp-artist="${escAttr(t.artist)}"
         onclick="openTrackOnYouTube('${escAttr(t.name)}','${escAttr(t.artist)}')">
      <div class="home-track-art-wrap home-np-art-wrap">
        ${hasImg ? `<img src="${imgSrc}" alt="" class="home-track-art home-np-art" loading="eager" decoding="async" style="opacity:0;transition:opacity 0.2s ease" onload="_homeImgLoad(this)" onerror="_homeImgError(this)">` : ''}
        <span class="material-symbols-rounded home-track-art-fallback"${hasImg ? '' : ' style="display:block"'}>graphic_eq</span>
        <div class="home-np-wave" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
      </div>
      <div class="home-track-info">
        <div class="home-track-name">${esc(t.name)}</div>
        <div class="home-track-artist">${esc(t.artist)}</div>
      </div>
      <div class="home-np-right">
        <div class="home-np-badge"><span class="home-np-dot" aria-hidden="true"></span><span>Now Playing</span></div>
        <button class="home-track-menu"
                onclick="event.stopPropagation();_openTrackDropdown(this,'${escAttr(t.name)}','${escAttr(t.artist)}')"
                aria-label="More options">
          <span class="material-symbols-rounded">more_vert</span>
        </button>
      </div>
    </div>`;
  }

  // ── Standard row ──
  const imgTag     = hasImg ? `<img src="${imgSrc}" alt="" class="home-track-art" loading="${rowIdx < 3 ? 'eager' : 'lazy'}" decoding="async" style="opacity:0;transition:opacity 0.2s ease" onload="_homeImgLoad(this)" onerror="_homeImgError(this)">` : '';
  const fbVis      = hasImg ? '' : 'style="display:block"';
  let   rightBadge = '';
  if (_homeSortMode === 'recent') {
    const cnt = _homeTrackFreq[`${t.name.toLowerCase()}|${t.artist.toLowerCase()}`] || 0;
    if (cnt > 1) rightBadge = `<span class="home-track-dup-count">${cnt}\u00d7</span>`;
  } else if (t._playCount) {
    const lbl = t._playCount >= 1000 ? (t._playCount / 1000).toFixed(1) + 'k' : String(t._playCount);
    rightBadge = `<span class="home-track-count">${lbl}</span>`;
  }
  const animStyle = rowIdx < 3 ? ` style="animation:hw-fade-slide 0.3s ease ${rowIdx * 0.06}s both"` : '';
  return `
    <div class="home-track-item"
         data-lp-name="${escAttr(t.name)}" data-lp-artist="${escAttr(t.artist)}"${animStyle}
         onclick="openTrackOnYouTube('${escAttr(t.name)}','${escAttr(t.artist)}')">
      <div class="home-track-art-wrap">${imgTag}
        <span class="material-symbols-rounded home-track-art-fallback" ${fbVis}>music_note</span>
      </div>
      <div class="home-track-info">
        <div class="home-track-name">${esc(t.name)}</div>
        <div class="home-track-artist">${esc(t.artist)}</div>
      </div>
      ${rightBadge}
      <button class="home-track-menu"
              onclick="event.stopPropagation();_openTrackDropdown(this,'${escAttr(t.name)}','${escAttr(t.artist)}')"
              aria-label="More options">
        <span class="material-symbols-rounded">more_vert</span>
      </button>
    </div>`;
}

// Renders tracks as a flat list — no date group headers.
// Date/time info is surfaced only in the 3-dot track menu.
// Renders recent tracks with Today / Yesterday / date group headers (Issue 5 fix).
function _renderGroupedByDate(tracks) {
  if (!tracks.length) return '';
  let html = '';
  let lastDateKey = null;
  tracks.forEach((t, i) => {
    // NP row — always first, never gets a date header
    if (t._isNowPlaying) { html += _renderTrackRowHTML(t, i); return; }
    // Inject a group header whenever the calendar date changes
    if (t._timestamp) {
      const dk = _getDateKey(t._timestamp);
      if (dk !== lastDateKey) {
        lastDateKey = dk;
        const label = _getDateLabel(t._timestamp); // null = today (no header), "Yesterday", or "D Mon YYYY"
        if (label) {
          html += `<div class="home-date-header" data-date-key="${escAttr(dk)}">${esc(label)}</div>`;
        }
      }
    }
    html += _renderTrackRowHTML(t, i);
  });
  return html;
}

function _renderList() {
  const list = document.getElementById('dailyMixList');
  if (!list) return;

  // Capture token at call time; abort if a newer render has already been scheduled
  // (e.g. a tab switch or PTR that arrived while an NP-poll render was in flight).
  const myToken = _renderToken;

  let wrap = list.querySelector('.home-tracks-wrap');
  if (!wrap) {
    wrap = document.createElement('div');
    wrap.className = 'home-tracks-wrap';
    list.appendChild(wrap);
  }

  // Drop stale render — a newer token means a tab switch / PTR / full fetch
  // has already started a fresher render; let that one win (Issue 3 fix).
  if (myToken !== _renderToken) return;

  // SAFETY: Validate data before rendering
  if (!Array.isArray(_homeAllTracks)) {
    console.warn('[LastWave] _homeAllTracks is not an array:', _homeAllTracks);
    wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">Loading... please wait.</div>`;
    return;
  }

  // Always clear before rendering — prevents duplicate / appended nodes
  wrap.innerHTML = '';

  if (!_homeAllTracks.length && !_nowPlayingTrack) {
    wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">No tracks yet — start listening on Last.fm!</div>`;
    _fadeInListIfNeeded();
    return;
  }

  if (_homeSortMode === 'mostPlayed') {
    // Dedup by name|artist (case-insensitive), keep highest playcount, sort desc
    const seen = new Map();
    for (const t of _homeAllTracks) {
      try {
        if (!t || !t.name || !t.artist) continue;
        const k = `${t.name.toLowerCase()}|${t.artist.toLowerCase()}`;
        const ex = seen.get(k);
        if (!ex || (t._playCount || 0) > (ex._playCount || 0)) seen.set(k, t);
      } catch (err) {
        console.warn('[LastWave] Error processing track in mostPlayed:', t, err);
      }
    }
    try {
      const display = _injectNowPlayingIntoArray([...seen.values()].sort((a, b) => (b._playCount || 0) - (a._playCount || 0)));
      wrap.innerHTML = '';
      wrap.innerHTML = display.map((t, i) => {
        try {
          return _renderTrackRowHTML(t, i);
        } catch (err) {
          console.error('[LastWave] Error rendering track:', t, err);
          return '';
        }
      }).join('');
    } catch (err) {
      console.error('[LastWave] Error rendering mostPlayed view:', err);
      wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">Error loading tracks</div>`;
    }
    return;

  } else if (_homeSortMode === 'last7days' || _homeSortMode === 'last30days') {
    try {
      const days   = _homeSortMode === 'last7days' ? 7 : 30;
      const source = _homeDateTracksCache[days];
      if (!source) return;  // period fetch in-flight; it will call _renderList again
      // Dedup period tracks before rendering
      const uniquePeriod = [];
      const seenPeriod = new Set();
      source.forEach(t => {
        try {
          if (!t || !t.name || !t.artist) return;
          const key = `${t.name.toLowerCase()}|${t.artist.toLowerCase()}`;
          if (!seenPeriod.has(key)) { seenPeriod.add(key); uniquePeriod.push(t); }
        } catch (err) {
          console.warn('[LastWave] Error processing period track:', t, err);
        }
      });
      const display = _injectNowPlayingIntoArray([...uniquePeriod].sort((a, b) => (b._playCount || 0) - (a._playCount || 0)));
      wrap.innerHTML = '';
      wrap.innerHTML = display.length
        ? display.map((t, i) => {
            try {
              return _renderTrackRowHTML(t, i);
            } catch (err) {
              console.error('[LastWave] Error rendering period track:', t, err);
              return '';
            }
          }).join('')
        : `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">No tracks found for this period.</div>`;
    } catch (err) {
      console.error('[LastWave] Error rendering period view:', err);
      wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">Error loading period tracks</div>`;
    }

  } else {
    // Recent — show all entries with real scrobble timestamps (up to 50 from API).
    // Entries without _timestamp (synthetic entries without API confirmation) are excluded.
    try {
      const recentTracks = _homeAllTracks.filter(t => t && t._timestamp);
      const uniqueTracks = [];
      const seenRecent = new Set();
      recentTracks.forEach(track => {
        try {
          if (!track || !track.name || !track.artist) return;
          const key = `${track.name.toLowerCase()}|${track.artist.toLowerCase()}`;
          if (!seenRecent.has(key)) { seenRecent.add(key); uniqueTracks.push(track); }
        } catch (err) {
          console.warn('[LastWave] Error processing recent track:', track, err);
        }
      });
      const sorted = [...uniqueTracks].sort((a, b) => {
      if (a._timestamp && b._timestamp) return b._timestamp - a._timestamp;
      if (a._timestamp) return -1;
      if (b._timestamp) return  1;
      return (a._recentIndex || 0) - (b._recentIndex || 0);
    });
    _rebuildTrackFreq();
    wrap.innerHTML = '';
    if (sorted.length || _nowPlayingTrack) {
      try {
        wrap.innerHTML = _renderGroupedByDate(_injectNowPlayingIntoArray(sorted));
      } catch (err) {
        console.error('[LastWave] Error rendering recent view:', err);
        wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">Error loading recent tracks</div>`;
      }
    } else {
      wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">No tracks played today — start listening!</div>`;
    }
    } catch (err) {
      console.error('[LastWave] Error in Recent mode rendering:', err);
      wrap.innerHTML = `<div style="padding:24px 18px;text-align:center;color:var(--md-outline);font-size:13px;">Error loading tracks</div>`;
    }
  }

  _bindLongPressCopy(wrap);
  _fadeInListIfNeeded();
}

function _bindLongPressCopy(container) { bindLongPressCopy(container, '[data-lp-name]'); }

// ══════════════════════════════════════════════════════════════
//  INFINITE SCROLL
//  Single listener on #dailyMixList — works for both Recent
//  (flex:1 scroll) and bounded tabs (fixed-height scroll).
// ══════════════════════════════════════════════════════════════
function _setupInfiniteScroll() {
  const listEl = document.getElementById('dailyMixList');
  if (!listEl || listEl._homeInfScrollBound) return;
  listEl._homeInfScrollBound = true;
  listEl.addEventListener('scroll', () => {
    const threshold = listEl.scrollHeight - listEl.clientHeight - 120;
    if (listEl.scrollTop >= threshold && _homeHasMore && !_homeIsLoadingMore) _loadMoreTracks();
  }, { passive: true });
}

async function _loadMoreTracks() {
  if (_homeIsLoadingMore || !_homeHasMore || !state.username || !state.apiKey) return;
  _homeIsLoadingMore = true;
  _homeCurrentPage++;
  const wrap = document.querySelector('.home-tracks-wrap');
  const spinner = document.createElement('div');
  spinner.id = 'homeLoadMoreSpinner';
  spinner.style.cssText = 'display:flex;justify-content:center;padding:16px 0;';
  spinner.innerHTML = '<span class="material-symbols-rounded" style="animation:spin 1s linear infinite;color:var(--md-outline);font-size:22px">refresh</span>';
  wrap?.appendChild(spinner);
  try {
    const isPeriod = _homeSortMode === 'last7days' || _homeSortMode === 'last30days';
    const isTop    = _homeSortMode === 'mostPlayed' || isPeriod;
    const period   = _homeSortMode === 'last7days' ? '7day' : _homeSortMode === 'last30days' ? '1month' : 'overall';
    const params   = isTop
      ? { method: 'user.gettoptracks',    user: state.username, period, limit: 50, page: _homeCurrentPage }
      : { method: 'user.getrecenttracks', user: state.username, limit: 50, page: _homeCurrentPage };
    const data = await lfmCall(params);
    let newTracks = [], totalPages = 1;

    if (isTop) {
      const raw  = data?.toptracks?.track; totalPages = parseInt(data?.toptracks?.['@attr']?.totalPages || 1);
      const arr  = Array.isArray(raw) ? raw : (raw ? [raw] : []);
      newTracks  = arr.filter(t => t && t.name).map(t => {
        const imgArr   = Array.isArray(t.image) ? t.image : [];
        const imgEntry = imgArr.find(i => i.size === 'extralarge' && _isRealHomeImg(i['#text'])) || imgArr.find(i => i.size === 'large' && _isRealHomeImg(i['#text'])) || imgArr.find(i => _isRealHomeImg(i['#text']));
        const artist   = t.artist ? (typeof t.artist === 'string' ? t.artist : (t.artist.name || t.artist['#text'] || '')) : '';
        return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', _playCount: parseInt(t.playcount || 0) };
      });
    } else {
      const raw  = data?.recenttracks?.track; totalPages = parseInt(data?.recenttracks?.['@attr']?.totalPages || 1);
      const arr  = Array.isArray(raw) ? raw : (raw ? [raw] : []);
      newTracks  = arr.filter(t => t && t.name && !(t['@attr']?.nowplaying === 'true')).map((t, i) => {
        const imgArr   = Array.isArray(t.image) ? t.image : [];
        const imgEntry = imgArr.find(img => img.size === 'extralarge' && _isRealHomeImg(img['#text'])) || imgArr.find(img => img.size === 'large' && _isRealHomeImg(img['#text'])) || imgArr.find(img => img.size === 'medium' && _isRealHomeImg(img['#text'])) || imgArr.find(img => _isRealHomeImg(img['#text']));
        const artist   = t.artist ? (typeof t.artist === 'string' ? t.artist : (t.artist['#text'] || t.artist.name || '')) : '';
        return { name: t.name, artist, url: t.url || '', image: imgEntry?.['#text'] || '', album: t.album?.['#text'] || '', _recentIndex: (_homeCurrentPage - 1) * 50 + i, _timestamp: t.date?.uts ? parseInt(t.date.uts, 10) * 1000 : null };
      });
    }
    _homeHasMore = _homeCurrentPage < totalPages;
    if (newTracks.length) {
      if (isPeriod) {
        const days = _homeSortMode === 'last7days' ? 7 : 30;
        const existing = _homeDateTracksCache[days] || [];
        const existKeys = new Set(existing.map(t => `${t.name}|${t.artist}`.toLowerCase()));
        const fresh = newTracks.filter(t => !existKeys.has(`${t.name}|${t.artist}`.toLowerCase()));
        if (fresh.length) { _homeDateTracksCache[days] = [...existing, ...fresh]; _appendTracksToDOM(fresh); _enrichHomeArt(fresh.filter(t => !t.image)); }
      } else if (isTop) {
        const existKeys = new Set(_homeAllTracks.map(t => `${t.name}|${t.artist}`.toLowerCase()));
        const fresh = newTracks.filter(t => !existKeys.has(`${t.name}|${t.artist}`.toLowerCase()));
        if (fresh.length) { _homeAllTracks = [..._homeAllTracks, ...fresh]; _appendTracksToDOM(fresh); _enrichHomeArt(fresh); }
      } else {
        const existTs   = new Set(_homeAllTracks.filter(t => t._timestamp).map(t => t._timestamp));
        const existKeys = new Set(_homeAllTracks.filter(t => !t._timestamp).map(t => `${t.name}|${t.artist}`.toLowerCase()));
        const fresh = newTracks.filter(t => t._timestamp ? !existTs.has(t._timestamp) : !existKeys.has(`${t.name}|${t.artist}`.toLowerCase()));
        if (fresh.length) { _homeAllTracks = [..._homeAllTracks, ...fresh]; _appendTracksToDOM(fresh); _enrichHomeArt(fresh); }
      }
    }
  } catch { _homeCurrentPage--; }
  finally   { _homeIsLoadingMore = false; document.getElementById('homeLoadMoreSpinner')?.remove(); }
}

function _appendTracksToDOM(tracks) {
  const wrap = document.querySelector('.home-tracks-wrap');
  if (!wrap || !tracks.length) return;

  if (_homeSortMode !== 'recent') {
    // Flat list — append in order
    const frag = document.createDocumentFragment();
    tracks.forEach((t, i) => {
      const tmp = document.createElement('div'); tmp.innerHTML = _renderTrackRowHTML(t, i);
      frag.appendChild(tmp.firstElementChild);
    });
    wrap.appendChild(frag);
  } else {
    // Recent — honour date grouping
    _rebuildTrackFreq();
    const existingRendered = new Set();
    wrap.querySelectorAll('.home-track-item:not(.home-track-nowplaying)').forEach(el => {
      const n = el.querySelector('.home-track-name')?.textContent.trim().toLowerCase()   || '';
      const a = el.querySelector('.home-track-artist')?.textContent.trim().toLowerCase() || '';
      if (n) existingRendered.add(`${n}|${a}`);
    });
    const existingHeaders = wrap.querySelectorAll('.home-date-header');
    let   lastDateKey     = existingHeaders.length ? existingHeaders[existingHeaders.length - 1].dataset.dateKey || null : null;
    const sorted          = [...tracks].sort((a, b) => {
      if (a._timestamp && b._timestamp) return b._timestamp - a._timestamp;
      if (a._timestamp) return -1; if (b._timestamp) return 1; return 0;
    });
    const frag = document.createDocumentFragment();
    sorted.forEach((t, i) => {
      const tkey = `${t.name.toLowerCase()}|${t.artist.toLowerCase()}`;
      if (existingRendered.has(tkey)) return;
      existingRendered.add(tkey);
      if (t._timestamp) {
        const dk = _getDateKey(t._timestamp);
        if (dk !== lastDateKey) {
          lastDateKey = dk;
          const label = _getDateLabel(t._timestamp);
          if (label) {
            const hdr = document.createElement('div'); hdr.className = 'home-date-header'; hdr.dataset.dateKey = dk;
            hdr.textContent = label; frag.appendChild(hdr);
          }
        }
      }
      const tmp = document.createElement('div'); tmp.innerHTML = _renderTrackRowHTML(t, i);
      frag.appendChild(tmp.firstElementChild);
    });
    wrap.appendChild(frag);
  }
  _bindLongPressCopy(wrap);
}

// ══════════════════════════════════════════════════════════════
//  ENTRY ANIMATIONS
// ══════════════════════════════════════════════════════════════
function _triggerEntryAnimations() {
  [
    { id: 'homeHeader',      cls: 'hw-anim-header' },
    { id: 'userProfileCard', cls: 'hw-anim-stats'  },
    { id: 'dailyMixCard',    cls: 'hw-anim-list'   },
  ].forEach(({ id, cls }) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.classList.add(cls);
    el.style.animationName = 'none';
    // Force reflow then re-enable
    requestAnimationFrame(() => { if (el) el.style.animationName = ''; });
  });

  _setupStatsSqueezeAnim();
  _setupInfiniteScroll();

  // Scroll-shadow on the list card header
  const listEl  = document.getElementById('dailyMixList');
  const listCard = document.getElementById('dailyMixCard');
  if (listEl && listCard && !listEl._scrollShadowBound) {
    listEl._scrollShadowBound = true;
    let raf = false;
    listEl.addEventListener('scroll', () => {
      if (raf) return; raf = true;
      requestAnimationFrame(() => { listCard.classList.toggle('scrolled', listEl.scrollTop > 4); raf = false; });
    }, { passive: true });
  }
  _homeShellAnimated = true;   // mark shell as rendered — skip re-animation on tab revisit
}

function _setupStatsSqueezeAnim() {
  const card = document.getElementById('userProfileCard');
  if (!card || card._squeezeBound) return;
  card._squeezeBound = true;
  let startY = 0, dragging = false;
  const FACTOR = 0.0022, MAX_SQ = 0.065, SPRING = 'cubic-bezier(0.34,1.56,0.64,1)';
  card.addEventListener('touchstart', (e) => {
    if (e.target.closest('#dailyMixCard')) return;
    startY = e.touches[0].clientY; dragging = true;
    card.style.transition = 'none'; card.style.willChange = 'transform';
  }, { passive: true });
  card.addEventListener('touchmove', (e) => {
    if (!dragging) return;
    const dy = e.touches[0].clientY - startY, sq = Math.min(Math.abs(dy) * FACTOR, MAX_SQ);
    card.style.transform = dy < 0
      ? `scaleY(${(1 - sq).toFixed(4)}) scaleX(${(1 + sq * 0.28).toFixed(4)})`
      : `scaleY(${(1 + sq * 0.38).toFixed(4)}) scaleX(${(1 - sq * 0.18).toFixed(4)})`;
  }, { passive: true });
  const release = () => {
    if (!dragging) return; dragging = false; card.style.willChange = '';
    card.style.transition = `transform 0.48s ${SPRING}`; card.style.transform = '';
    setTimeout(() => { if (card) card.style.transition = ''; }, 520);
  };
  card.addEventListener('touchend',    release, { passive: true });
  card.addEventListener('touchcancel', release, { passive: true });
}

// ══════════════════════════════════════════════════════════════
//  PULL TO REFRESH
//  Attached to #dailyMixList — the actual scroll container.
//  Guards:
//    listEl.scrollTop === 0  — must be at top
//    delta >= 70px downward  — intentional gesture
//  Never auto-triggers. Never fires on tab switch.
// ══════════════════════════════════════════════════════════════
// _homePtr.refreshing guards against double-trigger across gesture + async fetch
let _homePtr = { startY: 0, active: false, indicator: null, refreshing: false };

function _setupPullToRefresh() {
  const listEl = document.getElementById('dailyMixList');
  if (!listEl || listEl._ptrBound) return;
  listEl._ptrBound = true;

  // Create the indicator once and reuse it
  if (!_homePtr.indicator) {
    const ind = document.createElement('div');
    ind.id = 'homePtrIndicator';
    ind.style.cssText = [
      'position:fixed',
      'top:64px',
      'left:50%',
      'transform:translateX(-50%) translateY(-72px)',
      'opacity:0',
      'width:32px',
      'height:32px',
      'border-radius:50%',
      'background:var(--md-surface-container-high)',
      'box-shadow:0 2px 10px rgba(0,0,0,0.22)',
      'display:flex',
      'align-items:center',
      'justify-content:center',
      'z-index:999',
      'pointer-events:none',
    ].join(';');
    ind.innerHTML = '<div class="ptr-wrapper" style="display:flex;align-items:center;justify-content:center;width:100%;height:100%;"><span class="ptr-spinner material-symbols-rounded" style="font-size:18px;color:var(--md-primary)">refresh</span></div>';
    document.body.appendChild(ind);
    _homePtr.indicator = ind;
  }

  const ind = _homePtr.indicator;
  const ic  = () => ind.querySelector('.ptr-spinner');

  // ptrTrack: follow finger during drag with two-phase resistance.
  // Phase 1 (0–80px raw):  travel = delta * 0.55  (responsive feel)
  // Phase 2 (>80px raw):   travel = 44 + (delta-80) * 0.25  (dampened)
  // Cap at MAX_TRAVEL so indicator never exits the safe zone.
  // Opacity ramps 0 → 1 as travel goes 0 → 44px — no pop-in.
  // Icon spins continuously via .ptr-spinning class (not tied to pull distance).
  const MAX_TRAVEL = 56;
  const ptrTrack = (deltaY) => {
    const travel = deltaY <= 80
      ? Math.min(deltaY * 0.55, MAX_TRAVEL)
      : Math.min(44 + (deltaY - 80) * 0.25, MAX_TRAVEL);
    ind.style.transition = 'none';
    ind.style.transform  = `translateX(-50%) translateY(${travel - 72}px)`;
    ind.style.opacity    = Math.min(travel / 44, 1).toFixed(2);
    // DO NOT touch icon transform here — .ptr-spinning CSS class handles rotation
  };
  // Snap into resting "loading" position — smooth spring, fully opaque.
  // Icon is already spinning via .ptr-spinning — just clear any residual inline styles.
  const ptrSnap = () => {
    ind.style.transition = 'transform 0.35s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.15s ease';
    ind.style.transform  = 'translateX(-50%) translateY(4px)';
    ind.style.opacity    = '1';
    const s = ic();
    if (s) {
      s.style.transition = 'none';
      s.style.transform  = '';   // clear any residual inline transform — CSS class takes over
      s.style.animation  = '';   // do not override .ptr-spinning class
    }
  };
  // Fade out while springing back above viewport — clean disappear.
  // Remove spinning class and reset icon state for the next gesture.
  const ptrHide = () => {
    ind.style.transition = 'transform 0.35s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.25s ease';
    ind.style.transform  = 'translateX(-50%) translateY(-72px)';
    ind.style.opacity    = '0';
    const s = ic();
    if (s) {
      s.classList.remove('ptr-spinning');
      s.style.animation  = '';
      s.style.transform  = '';
    }
  };

  listEl.addEventListener('touchstart', (e) => {
    if (_homePtr.refreshing) return;
    // Only arm when scroll is at absolute top — no tolerance
    if (listEl.scrollTop === 0) {
      _homePtr.startY = e.touches[0].clientY;
      _homePtr.active = true;
      // Start spinning immediately — before threshold, before any movement
      const s = ic();
      if (s) {
        s.style.transform = '';
        s.style.animation = '';
        void s.offsetWidth;              // force reflow so animation restarts cleanly
        s.classList.add('ptr-spinning');
      }
    }
  }, { passive: true });

  listEl.addEventListener('touchmove', (e) => {
    if (!_homePtr.active || _homePtr.refreshing) return;
    const delta = e.touches[0].clientY - _homePtr.startY;
    // Any upward movement or no movement → cancel immediately
    if (delta <= 0) {
      _homePtr.active = false;
      ptrHide();
      return;
    }
    ptrTrack(delta);
  }, { passive: true });

  listEl.addEventListener('touchend', (e) => {
    if (!_homePtr.active) return;
    const delta = e.changedTouches[0].clientY - _homePtr.startY;
    _homePtr.active = false;
    // Trigger only if: pulled down ≥ 70px AND still at top AND not already refreshing
    if (delta >= 70 && listEl.scrollTop === 0 && state.username && !_homePtr.refreshing) {
      // Threshold met — snap indicator into loading position and refresh LIST ONLY
      _homePtr.refreshing = true;
      ptrSnap();
      _refreshAll().finally(() => {
        _homePtr.refreshing = false;
        ptrHide();
      });
    } else {
      ptrHide();
    }
  }, { passive: true });

  listEl.addEventListener('touchcancel', () => {
    _homePtr.active = false;
    if (!_homePtr.refreshing) ptrHide();
  }, { passive: true });
}

// ══════════════════════════════════════════════════════════════
//  HELPERS
// ══════════════════════════════════════════════════════════════

function _applyTopbarAvatar(url) {
  const av = document.getElementById('topbarAvatar'), ic = document.getElementById('topbarAvatarIcon');
  if (!av || !url) return;
  av.src     = url;
  av.onload  = () => { av.classList.remove('hidden'); ic?.classList.add('hidden'); };
  av.onerror = () => { av.classList.add('hidden');    ic?.classList.remove('hidden'); };
}

function _isRealHomeImg(url) {
  if (typeof _isRealImg === 'function') return _isRealImg(url);
  return url && url.trim() !== '' && !url.includes('2a96cbd8b46e442fc41c2b86b821562f');
}

function _showHomeLoading(show) { document.getElementById('homeSkeleton')?.classList.toggle('hidden', !show); }
function _showHomeCards(show) {
  document.getElementById('userProfileCard')?.classList.toggle('hidden', !show);
  document.getElementById('dailyMixCard')?.classList.toggle('hidden', !show);
  if (show && state.username) document.getElementById('homeHeader')?.classList.remove('hidden');
  else if (!show && !_homeDataLoaded) document.getElementById('homeHeader')?.classList.toggle('hidden', !state.username);
}

// ── Save / clear username ─────────────────────────────────────
function saveUsername() {
  const val = document.getElementById('homeUsernameInput').value.trim();
  if (!val) { showToast('Please enter a username', 'error'); return; }
  state.username = val;
  localStorage.setItem('lw_username', val);
  document.getElementById('homeUsernameSection').classList.add('hidden');
  document.getElementById('profileScrobbles').textContent = '—';
  _homeAllTracks = []; _homeDateTracksCache = { 7: null, 30: null }; _homeDateFetching = { 7: false, 30: false };
  _homePeriodFetchToken++; _homeIsFetching = false; _homeDataLoaded = false;
  screen_home();
}
function clearUser() {
  state.username = ''; localStorage.removeItem('lw_username');
  _homeAllTracks = []; _homeDateTracksCache = { 7: null, 30: null }; _homeDateFetching = { 7: false, 30: false };
  _homePeriodFetchToken++; _homeIsFetching = false; _homeDataLoaded = false; _homeShellAnimated = false;
  _destroyListenTimer(); _homeCurrentPage = 1; _homeIsLoadingMore = false; _homeHasMore = true;
  _showHomeCards(false);
  document.getElementById('homeHeader').classList.add('hidden');
  document.getElementById('homeUsernameSection').classList.remove('hidden');
  document.getElementById('homeUsernameInput').value = '';
  const av = document.getElementById('topbarAvatar'), ic = document.getElementById('topbarAvatarIcon');
  if (av) av.classList.add('hidden'); if (ic) ic.classList.remove('hidden');
}
function quickGenerate(mode) { state.selectedMode = mode; state.chipSelections.limit = '25'; generatePlaylist(true); }

// ══════════════════════════════════════════════════════════════
//  TRACK DROPDOWN MENU
// ══════════════════════════════════════════════════════════════
function _openTrackDropdown(btn, trackName, artistName) {
  _closeTrackDropdown();
  const trackEl = btn.closest('.home-track-item');
  const lpName  = trackEl?.dataset?.lpName;
  const pool    = (_homeSortMode === 'last7days' && _homeDateTracksCache[7])
                    ? _homeDateTracksCache[7]
                  : (_homeSortMode === 'last30days' && _homeDateTracksCache[30])
                    ? _homeDateTracksCache[30]
                  : _homeAllTracks;
  let matched   = pool.find(t => t.name === lpName && t.artist === artistName)
               || pool.find(t => t.name.toLowerCase() === (lpName||trackName).toLowerCase() && t.artist.toLowerCase() === artistName.toLowerCase());
  const playedAgo = matched?._timestamp ? _formatRelativeTime(matched._timestamp) : 'Unknown';

  const items = [
    { icon: 'shuffle',       label: 'Start Mix from this', action: () => startMixFromTrack(trackName, artistName) },
    { icon: 'open_in_new',   label: 'Open in Last.fm',     action: () => openTrackOnLastFm(trackName, artistName) },
    { icon: 'smart_display', label: 'Play on YouTube',     action: () => openTrackOnYouTube(trackName, artistName) },
    { icon: 'image_search',  label: 'Refresh Cover Art',   action: async () => {
      showToast('Refreshing cover art\u2026');
      const url = typeof _refreshTrackArtwork === 'function' ? await _refreshTrackArtwork(trackName, artistName) : '';
      if (url) { _patchTrackArt(trackName, artistName, url); _preloadImages([{ image: url }]); showToast('Cover art updated \u2713', 'success'); }
      else showToast('Cover art not available', 'error');
    }},
  ];

  const menu = document.createElement('div');
  menu.id = 'trackDropdownMenu'; menu.className = 'track-dropdown-menu'; menu.setAttribute('role', 'menu');

  const genreRow = document.createElement('div'); genreRow.className = 'track-dropdown-genre';
  genreRow.innerHTML = `<span class="material-symbols-rounded">sell</span><span><span class="track-dropdown-genre-label">Genre:</span><span class="td-genre-val"> \u2026</span></span>`;
  menu.appendChild(genreRow);

  const exploreBtn = document.createElement('button');
  exploreBtn.className = 'track-dropdown-item track-dropdown-explore'; exploreBtn.setAttribute('role', 'menuitem'); exploreBtn.style.display = 'none';
  exploreBtn.innerHTML = `<span class="material-symbols-rounded track-dropdown-icon" style="color:var(--md-primary)">bolt</span><span style="color:var(--md-primary);font-weight:500">Explore this genre</span>`;
  menu.appendChild(exploreBtn);

  const d0 = document.createElement('div'); d0.className = 'track-dropdown-divider'; menu.appendChild(d0);
  const hdr = document.createElement('div'); hdr.className = 'track-dropdown-header'; hdr.innerHTML = `<span class="material-symbols-rounded">history</span>Played: ${playedAgo}`; menu.appendChild(hdr);
  const d1  = document.createElement('div'); d1.className  = 'track-dropdown-divider'; menu.appendChild(d1);

  items.forEach(({ icon, label, action }) => {
    const el = document.createElement('button'); el.className = 'track-dropdown-item ripple-item'; el.setAttribute('role', 'menuitem');
    el.innerHTML = `<span class="material-symbols-rounded track-dropdown-icon">${icon}</span><span>${label}</span>`;
    el.addEventListener('click', e => { e.stopPropagation(); _closeTrackDropdown(); action(); });
    menu.appendChild(el);
  });
  document.body.appendChild(menu);

  const rect = btn.getBoundingClientRect(), mh = items.length * 52 + 52 + 38 + 8, mw = 224;
  let top = rect.bottom + 6, left = rect.right - mw;
  if (top + mh > window.innerHeight - 16) top  = rect.top - mh - 6;
  if (left < 8)                            left = 8;
  if (left + mw > window.innerWidth - 8)  left = window.innerWidth - mw - 8;
  menu.style.top = `${Math.max(top, 8)}px`; menu.style.left = `${left}px`;

  if (typeof _resolveTrackGenre === 'function') {
    _resolveTrackGenre(trackName, artistName).then(genre => {
      const el = menu.querySelector('.td-genre-val'); if (el) el.textContent = genre ? ` ${genre}` : ' Unknown';
      if (genre && genre.toLowerCase() !== 'unknown' && genre !== '—') {
        exploreBtn.style.display = '';
        exploreBtn.addEventListener('click', e => { e.stopPropagation(); _closeTrackDropdown(); _homeExploreGenre(genre); });
      }
    }).catch(() => { const el = menu.querySelector('.td-genre-val'); if (el) el.textContent = ' Unknown'; });
  }
  setTimeout(() => {
    document.addEventListener('click',      _closeTrackDropdown, { once: true });
    document.addEventListener('touchstart', _closeTrackDropdown, { once: true, passive: true });
  }, 0);
}
function _homeExploreGenre(genre) {
  typeof _doExploreGenrePlaylist === 'function' ? _doExploreGenrePlaylist(genre, { source: 'home_recent' }) : navigateTo('genres');
}
function _closeTrackDropdown() {
  const el = document.getElementById('trackDropdownMenu'); if (!el) return;
  el.classList.add('track-dropdown-leaving'); setTimeout(() => el.remove(), 180);
}

// ── Date / time helpers ───────────────────────────────────────
const _MONTHS_SHORT = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Returns true when the UTS timestamp (seconds) belongs to the current calendar day.
// Uses toDateString() comparison — immune to DST roll-over edge cases that trip up
// manual getDate()/getMonth()/getFullYear() checks (Problem 2 fix).
function _isToday(utsSeconds) {
  return new Date(Number(utsSeconds) * 1000).toDateString() === new Date().toDateString();
}
function _formatRelativeTime(tsMs) {
  if (!tsMs) return '';
  const diff = Date.now() - tsMs, s = Math.floor(diff / 1000);
  if (s < 60) return 'Just now';
  const m = Math.floor(s / 60); if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60); if (h < 24) return `${h}h ago`;
  const d = new Date(tsMs); let hr = d.getHours(); const min = String(d.getMinutes()).padStart(2,'0'), ampm = hr >= 12 ? 'PM' : 'AM'; hr = hr % 12 || 12;
  return `${d.getDate()} ${_MONTHS_SHORT[d.getMonth()]} ${d.getFullYear()} \u2022 ${hr}:${min} ${ampm}`;
}
function _getDateKey(tsMs) { const d = new Date(tsMs); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; }
function _getDateLabel(tsMs) {
  const now = new Date(), d = new Date(tsMs);
  const todayKey = `${now.getFullYear()}-${now.getMonth()}-${now.getDate()}`;
  if (_getDateKey(tsMs) === todayKey) return null;
  const yest = new Date(now); yest.setDate(yest.getDate() - 1);
  if (_getDateKey(tsMs) === `${yest.getFullYear()}-${yest.getMonth()}-${yest.getDate()}`) return 'Yesterday';
  return `${d.getDate()} ${_MONTHS_SHORT[d.getMonth()]} ${d.getFullYear()}`;
}
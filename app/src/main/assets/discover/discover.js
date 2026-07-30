/* ════════════════════════════════════════════════════════════
   discover.js — Discover screen
   A dedicated "new music discovery" feed, separate from the
   existing playlist-generation recommendation engine in app.js.

   Reuses (does not duplicate) shared globals from app.js:
     lfmCall, normaliseTracks, shuffleArray, _filterFresh,
     _markAsSeen, _buildUserTasteProfile, _hydrateProfile,
     _scoreTrack, _itunesFetchArtwork, _isRealImg, _preloadImages,
     showToast, showModal, esc, escAttr, openTrackOnYouTube,
     openTrackOnLastFm, startMixFromTrack, _resolveTrackGenre,
     _refreshTrackArtwork
   and from genres.js:
     _doExploreGenrePlaylist

   ENGINE — signals blended per batch (never a single endpoint):
     weight 4 — similar to recent listening (current mood)
     weight 4 — similar to Loved Tracks (strongest explicit signal)
     weight 3 — similar to all-time top tracks (long-term taste)
     weight 2 — top tracks from similar artists (trusted discovery)
     weight 1 — genre / tag discovery        (wide, near-unlimited pages)
     weight 1 — global Last.fm popularity    (wide, near-unlimited pages)

   Only TRACKS are ever recommended (no artists/albums/tags).
   Every candidate is tagged with a short "why" reason and is
   excluded if the user already knows it (top/recent tracks) or
   has already been shown this session / within the last 21 days
   (via app.js's existing _filterFresh / _markAsSeen cache).

   ENDLESSNESS: the tag + global-chart buckets draw from a very
   large random page range, so they alone can supply fresh,
   never-before-seen tracks indefinitely. If a refill still comes
   back empty (rare — e.g. a transient API hiccup), a guaranteed-
   content fallback bucket kicks in so the feed never dead-ends,
   and the per-artist diversity cap gradually relaxes if it's
   ever the bottleneck, so growth never permanently stalls.
   ════════════════════════════════════════════════════════════ */

'use strict';

// ── Module state ────────────────────────────────────────────────
let _discInitialized     = false;  // true once the first load has completed
let _discRefilling       = false;  // guards re-entrant pool refills
let _discLoadingMore     = false;  // guards re-entrant scroll-triggered loads
let _discShuffling       = false;  // guards re-entrant Surprise-Me taps
let _discProfile         = null;   // hydrated UserTasteProfile (Sets)
let _discProfileRaw      = null;   // raw profile (proper-case seed arrays)
let _discSeedPools       = null;   // { topArtists:[name], lovedTracks:[{name,artist}] }
let _discCursors         = {};     // rotating cursor per seed list — keeps refills fresh
let _discQueue           = [];     // { track, weight, reason } waiting to be rendered
let _discQueueKeys       = new Set();  // keys already sitting in the queue
let _discShownKeys       = new Set();  // keys already rendered this session (never repeat)
let _discArtistCounts    = {};         // session-wide artist counts (diversity)
let _discArtistCap       = 2;          // current per-artist cap (relaxes if it stalls growth)
let _discEmptyStreak     = 0;          // consecutive empty/starved refills
let _discActiveDropdown  = null;
const _discEnrichCache   = new Map();  // "name|artist" -> resolved artwork URL

const _DISC_BATCH_SIZE         = 12;
const _DISC_INITIAL_BATCH_SIZE = 20;  // first load shows more, then infinite scroll takes over
const _DISC_LOW_WATER       = 10;   // background-refill trigger point
const _DISC_MAX_PER_ARTIST  = 2;    // starting session-wide diversity cap
const _DISC_ARTIST_CAP_HARD = 6;    // never relax past this, even under pressure
const _DISC_GATHER_ATTEMPTS = 3;    // retries within a single refill before falling back

// ══════════════════════════════════════════════════════════════
//  SCREEN INIT — called by nav.js on every visit to 'discover'
// ══════════════════════════════════════════════════════════════
async function screen_discover() {
  window._lwScreenBackHandlers['discover'] = function () {
    if (_discActiveDropdown) { _closeDiscDropdown(); return true; }
    return false; // nothing to intercept — let nav.js pop the history stack
  };

  _setupDiscScroll();
  _setupDiscPullToRefresh();

  if (_discInitialized) {
    // Already loaded once this app session — this is a continuous, resumable
    // discovery session, not a reset-on-every-visit list. Just re-bind ripples.
    if (typeof _initRipples === 'function') {
      _initRipples(document.querySelector('[data-screen="discover"]'));
    }
    return;
  }

  if (!state.username || !state.apiKey) {
    _discShowState('error', 'Enter your username and API key in Settings first.', 'No credentials found');
    return;
  }

  _discInitialized = true;
  await _discLoadFirstBatch();
}

async function _discLoadFirstBatch() {
  _discFillSkeleton();
  _discShowState('loading');
  try {
    await _discEnsureProfile();
    // Keep refilling until the pool can satisfy the larger initial batch
    // (or attempts run out) — each pass rotates to different seeds, so
    // this widens the pool rather than repeating the same request.
    for (let i = 0; i < 3 && _discQueue.length < _DISC_INITIAL_BATCH_SIZE; i++) {
      await _discRefillQueue();
    }
    _discRenderNextBatch(true);
  } catch (e) {
    _discInitialized = false; // allow retry to actually reload
    _discShowState('error', e?.message || 'Could not load recommendations — try again.', 'Something went wrong');
  }
}

function _discRetry() {
  _discInitialized = false;
  screen_discover();
}

// ══════════════════════════════════════════════════════════════
//  STATE HELPERS
// ══════════════════════════════════════════════════════════════
function _discShowState(s, msg, title) {
  const map = { loading: 'discoverSkeleton', error: 'discoverError', empty: 'discoverEmpty', feed: 'discoverList' };
  Object.entries(map).forEach(([key, id]) => {
    document.getElementById(id)?.classList.toggle('hidden', key !== s);
  });
  if (s === 'error') {
    const t = document.getElementById('discoverErrorTitle');
    const m = document.getElementById('discoverErrorMsg');
    if (t) t.textContent = title || 'Something went wrong';
    if (m) m.textContent = msg || 'Please try again.';
  }
}

/**
 * Fills the loading state with just enough skeleton cards to cover the
 * whole visible viewport (plus a small buffer), instead of a fixed count —
 * so there's never a gap of empty space below the skeletons while the
 * first batch of recommendations is still loading.
 */
function _discSkelCardHTML() {
  return `
    <div class="disc-skel-card">
      <div class="disc-skel-art"></div>
      <div class="disc-skel-lines">
        <div class="disc-skel-line disc-skel-title"></div>
        <div class="disc-skel-line disc-skel-artist"></div>
        <div class="disc-skel-line disc-skel-reason"></div>
      </div>
    </div>`;
}

function _discFillSkeleton() {
  const skel = document.getElementById('discoverSkeleton');
  if (!skel) return;

  const CARD_H  = 82; // approx rendered height of one .disc-skel-card
  const GAP     = 12; // .discover-skeleton flex gap
  const host    = _discGetScrollHost();
  const header  = document.querySelector('.discover-header');
  const headerH = header ? header.offsetHeight + 18 /* header's margin-bottom */ : 88;
  const viewportH = (host?.clientHeight || window.innerHeight) - headerH;

  // +2 buffer cards so the skeleton always slightly overflows the fold
  // rather than under-filling it — matches how YouTube/Spotify-style
  // loading states never leave a visible gap at the bottom.
  const count = Math.max(4, Math.ceil(viewportH / (CARD_H + GAP)) + 2);

  skel.innerHTML = _discSkelCardHTML().repeat(count);
}

// ══════════════════════════════════════════════════════════════
//  TASTE PROFILE + SEED POOLS
// ══════════════════════════════════════════════════════════════
async function _discEnsureProfile() {
  _discProfileRaw = await _buildUserTasteProfile();
  _discProfile    = _hydrateProfile(_discProfileRaw);
}

/**
 * Builds the two seed pools not covered by the shared UserTasteProfile:
 *   - proper-cased top artists (profile only stores lowercased names)
 *   - Loved Tracks (explicit "I love this" signal — strongest of all)
 * Cached for the lifetime of the screen session (reshuffled on refresh).
 */
async function _discBuildSeedPools() {
  if (_discSeedPools) return _discSeedPools;
  const [artRes, lovedRes] = await Promise.allSettled([
    lfmCall({ method: 'user.gettopartists',  user: state.username, period: 'overall', limit: 40 }),
    lfmCall({ method: 'user.getlovedtracks', user: state.username, limit: 100 }),
  ]);
  const topArtists = artRes.status === 'fulfilled'
    ? (artRes.value.topartists?.artist || []).map(a => a.name)
    : [];
  const lovedTracks = lovedRes.status === 'fulfilled'
    ? normaliseTracks(lovedRes.value.lovedtracks?.track)
    : [];
  _discSeedPools = {
    topArtists:  shuffleArray(topArtists),
    lovedTracks: shuffleArray(lovedTracks),
  };
  return _discSeedPools;
}

/** Take N items from a list, rotating through it so repeated refills
 *  use different seeds before anything repeats — keeps the feed fresh. */
function _discTakeRotating(list, n, cursorKey) {
  if (!list || !list.length) return [];
  let cur = _discCursors[cursorKey] || 0;
  const out = [];
  for (let i = 0; i < Math.min(n, list.length); i++) out.push(list[cur++ % list.length]);
  _discCursors[cursorKey] = cur;
  return out;
}

/** Shuffle within small fixed-size windows — keeps the overall familiar/
 *  discovery ratio intact while breaking up the otherwise-deterministic
 *  interleave pattern, so the feed order never feels mechanical. */
function _discChunkShuffle(arr, size) {
  const out = [];
  for (let i = 0; i < arr.length; i += size) out.push(...shuffleArray(arr.slice(i, i + size)));
  return out;
}

// ══════════════════════════════════════════════════════════════
//  RECOMMENDATION ENGINE — candidate gathering
//  Blends 6 weighted signal buckets in parallel, dedupes, and
//  excludes already-known / already-shown tracks. Returns a
//  scored candidate array (NOT yet diversity-capped or queued —
//  shared by both the infinite-scroll refill and the Shuffle
//  "Surprise Me" picker below).
// ══════════════════════════════════════════════════════════════
async function _discGatherCandidates() {
  const profile = _discProfile;
  const raw     = _discProfileRaw;
  const pools   = await _discBuildSeedPools();

  // Each candidate is tagged with a source bucket: 'personal' (rooted in the
  // user's own library — recent/loved/top tracks, their similar artists, or
  // their own top tags) vs 'global' (site-wide popularity, no personalization
  // at all). Shuffle uses this to strongly prefer personal-taste picks.
  const weighted = []; // { track, weight, reason, source }
  const push = (tracks, weight, reason, source) => {
    (tracks || []).forEach(t => { if (t?.name && t?.artist) weighted.push({ track: t, weight, reason, source }); });
  };

  const jobs = [];

  // weight 4 — similar to recent listening (current mood)
  _discTakeRotating(raw.recentTrackSeeds, 4, 'recent').forEach(seed => {
    jobs.push((async () => {
      try {
        const d = await lfmCall({ method: 'track.getsimilar', track: seed.name, artist: seed.artist, limit: 20 });
        push(normaliseTracks(d.similartracks?.track), 4, 'Similar to your recent listening', 'personal');
      } catch {}
    })());
  });

  // weight 4 — similar to Loved Tracks (explicit signal)
  _discTakeRotating(pools.lovedTracks, 3, 'loved').forEach(seed => {
    jobs.push((async () => {
      try {
        const d = await lfmCall({ method: 'track.getsimilar', track: seed.name, artist: seed.artist, limit: 20 });
        push(normaliseTracks(d.similartracks?.track), 4, `Because you loved "${seed.name}"`, 'personal');
      } catch {}
    })());
  });

  // weight 3 — similar to all-time top tracks
  _discTakeRotating(raw.topTrackSeeds, 4, 'top').forEach(seed => {
    jobs.push((async () => {
      try {
        const d = await lfmCall({ method: 'track.getsimilar', track: seed.name, artist: seed.artist, limit: 20 });
        push(normaliseTracks(d.similartracks?.track), 3, `Because you like ${seed.artist}`, 'personal');
      } catch {}
    })());
  });

  // weight 2 — similar artists → their top tracks
  _discTakeRotating(pools.topArtists, 3, 'artist').forEach(rootArtist => {
    jobs.push((async () => {
      try {
        const simD       = await lfmCall({ method: 'artist.getsimilar', artist: rootArtist, limit: 12 });
        const simArtists = shuffleArray(simD.similarartists?.artist || []).slice(0, 3);
        await Promise.allSettled(simArtists.map(async sa => {
          const page = Math.ceil(Math.random() * 3);
          const d    = await lfmCall({ method: 'artist.gettoptracks', artist: sa.name, limit: 8, page });
          push(normaliseTracks(d.toptracks?.track), 2, 'Similar to your favorite artists', 'personal');
        }));
      } catch {}
    })());
  });

  // weight 1 — genre / tag discovery, rooted in the user's OWN top tags
  // (still a personal-taste signal, just a looser one than direct similarity)
  _discTakeRotating(raw.topTags, 4, 'tag').forEach(tag => {
    jobs.push((async () => {
      try {
        const page  = Math.floor(Math.random() * 15) + 1;
        const d     = await lfmCall({ method: 'tag.gettoptracks', tag, limit: 20, page });
        const label = tag.replace(/\b\w/g, c => c.toUpperCase());
        const reason = Math.random() < 0.5 ? `Based on your ${label} taste` : `Recommended from ${label}`;
        push(normaliseTracks(d.tracks?.track), 1, reason, 'personal');
      } catch {}
    })());
  });

  // weight 1 — global Last.fm popularity (wide random page range — near-
  // unlimited pool). NOT personalized — kept in the general feed mix, but
  // Shuffle treats this source as a last resort only (see _discPickSurpriseTrack).
  jobs.push((async () => {
    try {
      const page = Math.floor(Math.random() * 40) + 1;
      const d    = await lfmCall({ method: 'chart.gettoptracks', limit: 20, page });
      push(normaliseTracks(d.tracks?.track), 1, 'Popular on Last.fm right now', 'global');
    } catch {}
  })());

  await Promise.allSettled(jobs);
  if (!weighted.length) return [];

  // ── Dedup this round — keep the highest-weight copy + its reason ──
  const bestOf = new Map();
  for (const { track, weight, reason, source } of weighted) {
    const k  = `${track.name}|${track.artist}`.toLowerCase();
    const ex = bestOf.get(k);
    if (!ex || weight > ex.weight) bestOf.set(k, { track, weight, reason, source });
  }
  let candidates = [...bestOf.values()];

  // ── This is a DISCOVERY feed — exclude tracks the user already knows ──
  candidates = candidates.filter(({ track }) => {
    const k = `${track.name}|${track.artist}`.toLowerCase();
    return !profile.topTrackKeys.has(k) && !profile.recentTrackKeys.has(k);
  });

  // ── Never a duplicate — exclude anything already queued or shown ──
  candidates = candidates.filter(({ track }) => {
    const k = `${track.name}|${track.artist}`.toLowerCase();
    return !_discShownKeys.has(k) && !_discQueueKeys.has(k);
  });

  // ── Cross-session freshness (app.js's 21-day seen-track cache) ──
  const freshKeys = new Set(
    _filterFresh(candidates.map(c => c.track)).map(t => `${t.name}|${t.artist}`.toLowerCase())
  );
  candidates = candidates.filter(({ track }) => freshKeys.has(`${track.name}|${track.artist}`.toLowerCase()));

  // ── Score (reuses app.js's artist-familiarity + bucket-confidence model) ──
  return candidates
    .map(({ track, weight, reason, source }) => ({ track, weight, reason, source, score: _scoreTrack(track, profile, weight) }))
    .filter(({ score }) => score !== -1);
}

/**
 * Guaranteed-content safety net. Only used when normal candidate
 * gathering comes back completely empty (e.g. a transient API hiccup,
 * or every rotating seed briefly exhausted at once) — pulls straight
 * from a random Last.fm global chart page so the feed can never
 * truly dead-end. Still deduped against everything already shown.
 */
async function _discFallbackCandidates() {
  const out = [];
  const seen = new Set();
  try {
    const page = Math.floor(Math.random() * 80) + 1;
    const d = await lfmCall({ method: 'chart.gettoptracks', limit: 30, page });
    normaliseTracks(d.tracks?.track).forEach(track => {
      const k = `${track.name}|${track.artist}`.toLowerCase();
      if (seen.has(k) || _discShownKeys.has(k) || _discQueueKeys.has(k)) return;
      seen.add(k);
      out.push({ track, weight: 1, reason: 'Popular on Last.fm right now', score: 5, source: 'global' });
    });
  } catch {}
  return out;
}

// ══════════════════════════════════════════════════════════════
//  RECOMMENDATION ENGINE — queue refill
//  Mixes ~60/40 familiar-style vs. pure discovery, lightly shuffles
//  for freshness, applies the (dynamically relaxing) artist-diversity
//  cap, then enqueues. Retries internally and falls back to
//  guaranteed content before ever giving up for this call.
// ══════════════════════════════════════════════════════════════
async function _discRefillQueue() {
  if (_discRefilling) return;
  _discRefilling = true;
  try {
    let scored = [];
    for (let attempt = 0; attempt < _DISC_GATHER_ATTEMPTS && scored.length === 0; attempt++) {
      scored = await _discGatherCandidates();
    }
    if (!scored.length) scored = await _discFallbackCandidates();
    if (!scored.length) { _discEmptyStreak++; return; } // truly nothing available right now

    scored.sort((a, b) => b.score - a.score);
    const familiar  = scored.filter(s => s.weight >= 3);
    const discovery = scored.filter(s => s.weight <= 2);

    // Interleave ~60/40 so hidden gems and popular/familiar picks both
    // surface, rather than one pool dominating the whole feed.
    const mixed = [];
    let fi = 0, di = 0;
    while (fi < familiar.length || di < discovery.length) {
      for (let i = 0; i < 3 && fi < familiar.length; i++)  mixed.push(familiar[fi++]);
      for (let i = 0; i < 2 && di < discovery.length; i++) mixed.push(discovery[di++]);
    }
    const shuffledMixed = _discChunkShuffle(mixed, 5);

    // ── Artist diversity cap (session-wide, relaxes if it's the bottleneck) ──
    const accepted = [];
    for (const c of shuffledMixed) {
      const ak   = c.track.artist.toLowerCase();
      const used = (_discArtistCounts[ak] || 0) + accepted.filter(x => x.track.artist.toLowerCase() === ak).length;
      if (used >= _discArtistCap) continue;
      accepted.push(c);
    }

    if (!accepted.length) {
      _discEmptyStreak++;
    } else {
      _discEmptyStreak = 0;
      accepted.forEach(c => {
        _discQueueKeys.add(`${c.track.name}|${c.track.artist}`.toLowerCase());
        _discQueue.push(c);
      });
    }

    // If the diversity cap is choking growth two refills in a row, relax it
    // a notch rather than let the feed stall — still capped, never unlimited.
    if (_discEmptyStreak >= 2 && _discArtistCap < _DISC_ARTIST_CAP_HARD) {
      _discArtistCap++;
      _discEmptyStreak = 0;
    }
  } finally {
    _discRefilling = false;
  }
}

// ══════════════════════════════════════════════════════════════
//  RENDER
// ══════════════════════════════════════════════════════════════
function _discRenderNextBatch(isFirst) {
  const list = document.getElementById('discoverList');
  if (!list) return;

  document.getElementById('discTrailingSkel')?.remove();

  const take = _discQueue.splice(0, isFirst ? _DISC_INITIAL_BATCH_SIZE : _DISC_BATCH_SIZE);

  if (isFirst) {
    list.innerHTML = '';
    if (!take.length) { _discShowState('empty'); return; }
    _discShowState('feed');
  } else if (!take.length) {
    // Nothing ready yet — leave the trailing skeleton up; the in-flight
    // background refill (triggered below / by scroll) will call this again.
    // The feed itself never shows an "end of list" state past first load.
    _discEnsureTrailingSkeleton();
    return;
  }

  const frag = document.createDocumentFragment();
  take.forEach(({ track, reason }, i) => {
    const k = `${track.name}|${track.artist}`.toLowerCase();
    _discShownKeys.add(k);
    _discQueueKeys.delete(k);
    const ak = track.artist.toLowerCase();
    _discArtistCounts[ak] = (_discArtistCounts[ak] || 0) + 1;

    const el = document.createElement('div');
    el.className = 'disc-card ripple-item';
    el.setAttribute('role', 'listitem');
    el.dataset.lpName   = track.name;
    el.dataset.lpArtist = track.artist;
    el.style.animationDelay = `${Math.min(i, 8) * 0.03}s`;
    el.innerHTML = _discCardInnerHTML(track, reason);

    el.addEventListener('click', e => {
      if (e.target.closest('.disc-card-menu')) return;
      openTrackOnYouTube(track.name, track.artist);
    });
    el.querySelector('.disc-card-menu').addEventListener('click', e => {
      e.stopPropagation();
      _showDiscDropdown(track, e.currentTarget);
    });

    frag.appendChild(el);
  });
  list.appendChild(frag);

  _markAsSeen(take.map(c => c.track));
  _preloadImages(take.filter(c => c.track.image).map(c => c.track));
  _discEnrichBatch(take).catch(() => {});

  if (typeof _initRipples === 'function') {
    _initRipples(document.querySelector('[data-screen="discover"]'));
  }
  if (typeof bindLongPressCopy === 'function') {
    // '_lpBound' guard inside bindLongPressCopy makes re-scanning the whole
    // list on every batch cheap — already-bound cards are skipped.
    bindLongPressCopy(list, '[data-lp-name]', '\u2713 Copied to clipboard');
  }

  // Keep the pool topped up well ahead of the user reaching the bottom.
  if (_discQueue.length < _DISC_LOW_WATER && !_discRefilling) {
    _discTopUpAndRender();
  }
}

/**
 * Refills the pool in the background and then actually renders whatever
 * lands — unlike a fire-and-forget refill, this guarantees the trailing
 * skeleton is always replaced by real cards (or removed) once content is
 * ready, even when there's no scroll gesture to trigger a follow-up render
 * (e.g. right after Shuffle, where a single card leaves nothing to scroll).
 */
async function _discTopUpAndRender() {
  _discEnsureTrailingSkeleton();
  for (let i = 0; i < 3 && _discQueue.length === 0; i++) {
    if (!_discRefilling) await _discRefillQueue().catch(() => {});
  }
  _discRenderNextBatch(false);
}

// Card now shows exactly 3 lines: title, artist, reason. No album line.
function _discCardInnerHTML(track, reason) {
  const hasImg = track.image && track.image.trim();
  const k      = escAttr(`${track.name}|${track.artist}`.toLowerCase());
  return `
    <div class="disc-card-art-wrap" data-key="${k}">
      ${hasImg ? `<img src="${esc(track.image)}" alt="" class="disc-card-art" loading="lazy" decoding="async" style="opacity:0;transition:opacity 0.25s ease" onload="_discImgLoad(this)" onerror="_discImgError(this)">` : ''}
      <span class="material-symbols-rounded disc-card-art-fallback"${hasImg ? '' : ' style="display:flex"'}>music_note</span>
    </div>
    <div class="disc-card-info">
      <div class="disc-card-title">${esc(track.name)}</div>
      <div class="disc-card-artist">${esc(track.artist)}</div>
      <div class="disc-card-reason"><span class="material-symbols-rounded">auto_awesome</span><span>${esc(reason)}</span></div>
    </div>
    <button class="disc-card-menu" aria-label="More options" aria-haspopup="true">
      <span class="material-symbols-rounded">more_vert</span>
    </button>`;
}

function _discImgLoad(img) {
  img.style.opacity = '1';
  const fb = img.closest('.disc-card-art-wrap')?.querySelector('.disc-card-art-fallback');
  if (fb) fb.style.display = 'none';
}
function _discImgError(img) {
  if (img._retried) { img.style.display = 'none'; return; }
  img._retried = true;
  const src = img.src;
  img.src = '';
  setTimeout(() => { if (src) img.src = src; else img.style.display = 'none'; }, 400);
}

function _discEnsureTrailingSkeleton() {
  const list = document.getElementById('discoverList');
  if (!list || document.getElementById('discTrailingSkel')) return;
  const row = document.createElement('div');
  row.id = 'discTrailingSkel';
  row.className = 'disc-trailing-skel';
  row.innerHTML = `
    <div class="disc-skel-art"></div>
    <div class="disc-skel-lines">
      <div class="disc-skel-line disc-skel-title"></div>
      <div class="disc-skel-line disc-skel-artist"></div>
    </div>`;
  list.appendChild(row);
}

/**
 * Progressive artwork enrichment — only runs for cards that don't already
 * have a Last.fm image (similar-tracks / tag / chart endpoints sometimes
 * omit it). Cards never block on this; artwork quietly fades in when ready.
 * No album title is fetched or displayed — cards are title/artist/reason only.
 */
async function _discEnrichBatch(take) {
  const BATCH = 5;
  const needing = take.filter(({ track }) => !track.image || !track.image.trim());
  for (let i = 0; i < needing.length; i += BATCH) {
    const slice = needing.slice(i, i + BATCH);
    await Promise.allSettled(slice.map(async ({ track }) => {
      try {
        const url = await _discResolveArt(track);
        if (!url) return;
        track.image = url;
        const key  = `${track.name}|${track.artist}`.toLowerCase();
        const wrap = document.querySelector(`.disc-card-art-wrap[data-key="${CSS.escape(key)}"]`);
        if (!wrap) return;
        let img = wrap.querySelector('.disc-card-art');
        const fb = wrap.querySelector('.disc-card-art-fallback');
        if (!img) {
          img = document.createElement('img');
          img.className = 'disc-card-art';
          img.alt = ''; img.loading = 'lazy'; img.decoding = 'async';
          img.style.cssText = 'opacity:0;transition:opacity 0.25s ease';
          wrap.insertBefore(img, fb);
        }
        img.onload  = () => { img.style.opacity = '1'; if (fb) fb.style.display = 'none'; };
        img.onerror = () => { img.style.display = 'none'; };
        img.src = url;
      } catch {}
    }));
  }
}

async function _discResolveArt(track) {
  const key = `${track.name}|${track.artist}`.toLowerCase();
  if (_discEnrichCache.has(key)) return _discEnrichCache.get(key);

  let url = '';
  try {
    const data  = await lfmCall({ method: 'track.getInfo', track: track.name, artist: track.artist, autocorrect: 1 });
    const image = data?.track?.album?.image;
    if (image) {
      const img = image.find(im => im.size === 'extralarge' && _isRealImg(im['#text']))
               || image.find(im => im.size === 'large'      && _isRealImg(im['#text']))
               || image.find(im => _isRealImg(im['#text']));
      if (img) url = img['#text'];
    }
  } catch { /* fall through to iTunes */ }

  if (!url) {
    try { url = await _itunesFetchArtwork(track.name, track.artist, 'track'); } catch {}
  }

  _discEnrichCache.set(key, url);
  return url;
}

// ══════════════════════════════════════════════════════════════
//  SHUFFLE DISCOVERY — "Surprise Me"
//  Picks one high-uniqueness track (biased toward the discovery
//  pool — tag/global/similar-artist — and toward the lowest
//  familiarity score within it, i.e. the least "obvious" pick),
//  then replaces the current feed with a brand-new session
//  seeded by that pick, still topping the queue back up in the
//  background so scrolling continues normally afterwards.
// ══════════════════════════════════════════════════════════════
async function _discShuffleNow() {
  if (_discShuffling) return;
  _discShuffling = true;
  const btn = document.getElementById('discoverShuffleBtn');
  btn?.classList.add('discover-shuffle-spinning');

  try {
    if (!state.username || !state.apiKey) {
      showToast('Add your username and API key in Settings', 'error');
      return;
    }
    if (!_discProfile) await _discEnsureProfile();

    const pick = await _discPickSurpriseTrack();
    if (!pick) { showToast('Could not find a new track — try again', 'error'); return; }

    // Brand-new discovery session: clear the temporary cache & diversity state.
    _discQueue = []; _discQueueKeys = new Set(); _discShownKeys = new Set();
    _discArtistCounts = {}; _discArtistCap = _DISC_MAX_PER_ARTIST; _discEmptyStreak = 0;

    const list = document.getElementById('discoverList');
    if (list) {
      list.style.opacity = '0';
      await new Promise(r => setTimeout(r, 150));
    }
    document.getElementById('discTrailingSkel')?.remove();
    _discShowState('feed');

    _discQueue.push(pick);
    _discQueueKeys.add(`${pick.track.name}|${pick.track.artist}`.toLowerCase());
    _discRenderNextBatch(true);

    requestAnimationFrame(() => { if (list) list.style.opacity = '1'; });

    // Refill the pool in the background so scrolling continues seamlessly.
    _discRefillQueue().catch(() => {});
    showToast('Surprise pick ready \u2728', 'success');
  } catch (e) {
    showToast(e?.message || 'Could not shuffle — try again', 'error');
  } finally {
    _discShuffling = false;
    setTimeout(() => btn?.classList.remove('discover-shuffle-spinning'), 650);
  }
}

async function _discPickSurpriseTrack() {
  let scored = await _discGatherCandidates();
  if (!scored.length) scored = await _discFallbackCandidates();
  if (!scored.length) return null;

  // Personal-taste signals ONLY (similar tracks/artists/tags all rooted in
  // the user's own library). Global chart popularity is excluded from the
  // primary pool entirely — it's used below only if nothing personal is
  // available at all (e.g. a brand-new Last.fm account with no history).
  const personal = scored.filter(s => s.source !== 'global');
  const pool = personal.length ? personal : scored;

  // Within personal taste, still lean toward the "hidden gem" end: prefer
  // the looser discovery-flavored buckets (similar artists / own top tags,
  // weight ≤ 2) sorted toward lowest familiarity score, falling back to the
  // stronger direct-similarity signals (recent/loved/top, weight ≥ 3) if
  // that pool is empty. A small random window keeps repeated taps varied.
  const gems     = pool.filter(s => s.weight <= 2).sort((a, b) => a.score - b.score);
  const familiar = pool.filter(s => s.weight >= 3).sort((a, b) => a.score - b.score);
  const chosen = gems.length ? gems : familiar;
  const topUnique = chosen.slice(0, Math.min(15, chosen.length));
  return topUnique[Math.floor(Math.random() * topUnique.length)];
}

// ══════════════════════════════════════════════════════════════
//  INFINITE SCROLL
//  The scroll host is the wrapper nav.js creates for this screen
//  ([data-screen="discover"]) — same pattern as search/genres,
//  where .page-scroll fills that wrapper rather than scrolling itself.
//  Loads the next batch well before the bottom is reached, and the
//  background pool refill (triggered from _discRenderNextBatch)
//  keeps content ready ahead of time — the feed has no end.
// ══════════════════════════════════════════════════════════════
function _discGetScrollHost() {
  return document.querySelector('[data-screen="discover"]');
}

function _setupDiscScroll() {
  const host = _discGetScrollHost();
  if (!host || host._discScrollBound) return;
  host._discScrollBound = true;

  host.addEventListener('scroll', () => {
    if (_discLoadingMore || !_discInitialized) return;
    const threshold = 800;
    if (host.scrollTop + host.clientHeight >= host.scrollHeight - threshold) {
      _discLoadingMore = true;
      (async () => {
        // Try a couple of times in a row if the pool happens to be thin —
        // between the wide tag/chart page range and the fallback bucket
        // this should essentially always produce content on the first try.
        for (let i = 0; i < 2 && _discQueue.length < _DISC_BATCH_SIZE; i++) {
          if (!_discRefilling) await _discRefillQueue().catch(() => {});
        }
        _discRenderNextBatch(false);
      })().finally(() => { _discLoadingMore = false; });
    }
  }, { passive: true });
}

// ══════════════════════════════════════════════════════════════
//  PULL TO REFRESH — Material Design 3 gesture + spinner, mirrors
//  home.js's handling, scoped to the Discover scroll host. Rebuilds
//  the feed from scratch: clears the session cache, diversity state,
//  and reshuffles the seed pools so it truly starts over discovering,
//  while still respecting the cross-session 21-day seen-track cache.
// ══════════════════════════════════════════════════════════════
let _discPtr = { startY: 0, active: false, indicator: null, refreshing: false };

function _setupDiscPullToRefresh() {
  const host = _discGetScrollHost();
  if (!host || host._ptrBound) return;
  host._ptrBound = true;

  if (!_discPtr.indicator) {
    const ind = document.createElement('div');
    ind.id = 'discPtrIndicator';
    ind.style.cssText = [
      'position:fixed', 'top:64px', 'left:50%',
      'transform:translateX(-50%) translateY(-72px)', 'opacity:0',
      'width:32px', 'height:32px', 'border-radius:50%',
      'background:var(--md-surface-container-high)',
      'box-shadow:0 2px 10px rgba(0,0,0,0.22)',
      'display:flex', 'align-items:center', 'justify-content:center',
      'z-index:999', 'pointer-events:none',
    ].join(';');
    ind.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;width:100%;height:100%;"><span class="ptr-spinner material-symbols-rounded" style="font-size:18px;color:var(--md-primary)">refresh</span></div>';
    document.body.appendChild(ind);
    _discPtr.indicator = ind;
  }

  const ind = _discPtr.indicator;
  const ic  = () => ind.querySelector('.ptr-spinner');
  const MAX_TRAVEL = 56;

  const ptrTrack = (deltaY) => {
    const travel = deltaY <= 80 ? Math.min(deltaY * 0.55, MAX_TRAVEL) : Math.min(44 + (deltaY - 80) * 0.25, MAX_TRAVEL);
    ind.style.transition = 'none';
    ind.style.transform  = `translateX(-50%) translateY(${travel - 72}px)`;
    ind.style.opacity    = Math.min(travel / 44, 1).toFixed(2);
  };
  const ptrSnap = () => {
    ind.style.transition = 'transform 0.35s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.15s ease';
    ind.style.transform  = 'translateX(-50%) translateY(4px)';
    ind.style.opacity    = '1';
    const s = ic();
    if (s) { s.style.transition = 'none'; s.style.transform = ''; s.style.animation = ''; }
  };
  const ptrHide = () => {
    ind.style.transition = 'transform 0.35s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.25s ease';
    ind.style.transform  = 'translateX(-50%) translateY(-72px)';
    ind.style.opacity    = '0';
    const s = ic();
    if (s) { s.classList.remove('ptr-spinning'); s.style.animation = ''; s.style.transform = ''; }
  };

  host.addEventListener('touchstart', (e) => {
    if (_discPtr.refreshing) return;
    if (host.scrollTop === 0) {
      _discPtr.startY = e.touches[0].clientY;
      _discPtr.active = true;
      const s = ic();
      if (s) { s.style.transform = ''; s.style.animation = ''; void s.offsetWidth; s.classList.add('ptr-spinning'); }
    }
  }, { passive: true });

  host.addEventListener('touchmove', (e) => {
    if (!_discPtr.active || _discPtr.refreshing) return;
    const delta = e.touches[0].clientY - _discPtr.startY;
    if (delta <= 0) { _discPtr.active = false; ptrHide(); return; }
    // Stop the WebView's native rubber-band/overscroll from swallowing the
    // gesture right at the scroll boundary — without this, touchmove can
    // stall partway through the pull and the custom indicator never appears.
    if (host.scrollTop === 0 && e.cancelable) e.preventDefault();
    ptrTrack(delta);
  }, { passive: false });

  host.addEventListener('touchend', (e) => {
    if (!_discPtr.active) return;
    const delta = e.changedTouches[0].clientY - _discPtr.startY;
    _discPtr.active = false;
    if (delta >= 70 && host.scrollTop === 0 && state.username && !_discPtr.refreshing) {
      _discPtr.refreshing = true;
      ptrSnap();
      _discFullRefresh().finally(() => { _discPtr.refreshing = false; ptrHide(); });
    } else {
      ptrHide();
    }
  }, { passive: true });

  host.addEventListener('touchcancel', () => {
    _discPtr.active = false;
    if (!_discPtr.refreshing) ptrHide();
  }, { passive: true });
}

async function _discFullRefresh() {
  // Reset the temporary recommendation cache and diversity state — this is
  // an entirely fresh discovery session, not a continuation of the old one.
  _discQueue = []; _discQueueKeys = new Set(); _discShownKeys = new Set();
  _discArtistCounts = {}; _discArtistCap = _DISC_MAX_PER_ARTIST; _discEmptyStreak = 0;
  _discCursors = {};
  if (_discSeedPools) {
    _discSeedPools.topArtists  = shuffleArray(_discSeedPools.topArtists);
    _discSeedPools.lovedTracks = shuffleArray(_discSeedPools.lovedTracks);
  }
  document.getElementById('discTrailingSkel')?.remove();

  try {
    await _discRefillQueue();
    _discRenderNextBatch(true);
    if (document.querySelectorAll('#discoverList .disc-card').length) {
      showToast('Feed refreshed \u2713', 'success');
    }
  } catch (e) {
    showToast(e?.message || 'Could not refresh — try again', 'error');
  }
}

// ══════════════════════════════════════════════════════════════
//  3-DOT DROPDOWN MENU — same .track-dropdown-menu CSS + pattern
//  used by home/search/genres. Appended to <body>, positioned
//  relative to the tapped button, dismissed on outside tap.
// ══════════════════════════════════════════════════════════════
function _showDiscDropdown(track, anchorBtn) {
  _closeDiscDropdown();

  const menuItems = [
    { icon: 'queue_music',   label: 'Start Mix from this', fn: () => startMixFromTrack(track.name, track.artist) },
    { icon: 'open_in_new',   label: 'Open in Last.fm',     fn: () => openTrackOnLastFm(track.name, track.artist) },
    { icon: 'smart_display', label: 'Play on YouTube',     fn: () => openTrackOnYouTube(track.name, track.artist) },
    { icon: 'image_search',  label: 'Refresh Cover Art',   fn: async () => {
        showToast('Refreshing cover art\u2026');
        const url = typeof _refreshTrackArtwork === 'function' ? await _refreshTrackArtwork(track.name, track.artist) : '';
        if (url) {
          track.image = url;
          const key  = `${track.name}|${track.artist}`.toLowerCase();
          const wrap = document.querySelector(`.disc-card-art-wrap[data-key="${CSS.escape(key)}"]`);
          const img  = wrap?.querySelector('.disc-card-art');
          if (img) img.src = url;
          showToast('Cover art updated \u2713', 'success');
        } else {
          showToast('Cover art not available', 'error');
        }
      }
    },
  ];

  const menuEl = document.createElement('div');
  menuEl.className = 'track-dropdown-menu';
  menuEl.setAttribute('role', 'menu');

  const genreRow = document.createElement('div');
  genreRow.className = 'track-dropdown-genre';
  genreRow.innerHTML = `<span class="material-symbols-rounded">sell</span><span><span class="track-dropdown-genre-label">Genre:</span><span class="td-genre-val"> \u2026</span></span>`;
  menuEl.appendChild(genreRow);

  const exploreBtn = document.createElement('button');
  exploreBtn.className = 'track-dropdown-item track-dropdown-explore';
  exploreBtn.setAttribute('role', 'menuitem');
  exploreBtn.style.display = 'none';
  exploreBtn.innerHTML = `<span class="material-symbols-rounded track-dropdown-icon" style="color:var(--md-primary)">bolt</span><span style="color:var(--md-primary);font-weight:500">Explore this genre</span>`;
  menuEl.appendChild(exploreBtn);

  const divider = document.createElement('div');
  divider.className = 'track-dropdown-divider';
  menuEl.appendChild(divider);

  if (typeof _resolveTrackGenre === 'function') {
    _resolveTrackGenre(track.name, track.artist).then(genre => {
      const el = menuEl.querySelector('.td-genre-val');
      if (el) el.textContent = genre ? ` ${genre}` : ' Unknown';
      if (genre && genre.toLowerCase() !== 'unknown' && genre !== '\u2014') {
        const primaryGenre = genre.split(',')[0].trim();
        exploreBtn.style.display = '';
        exploreBtn.addEventListener('click', e => {
          e.stopPropagation();
          _closeDiscDropdown();
          if (typeof _doExploreGenrePlaylist === 'function') _doExploreGenrePlaylist(primaryGenre, { source: 'discover' });
          else navigateTo('genres');
        });
      }
    }).catch(() => {
      const el = menuEl.querySelector('.td-genre-val');
      if (el) el.textContent = ' Unknown';
    });
  }

  menuItems.forEach(mi => {
    const btn = document.createElement('button');
    btn.className = 'track-dropdown-item';
    btn.setAttribute('role', 'menuitem');
    btn.innerHTML = `<span class="material-symbols-rounded track-dropdown-icon">${mi.icon}</span><span>${esc(mi.label)}</span>`;
    btn.addEventListener('click', e => { e.stopPropagation(); _closeDiscDropdown(); mi.fn(); });
    menuEl.appendChild(btn);
  });

  document.body.appendChild(menuEl);
  _discActiveDropdown = menuEl;
  _discPositionDropdown(menuEl, anchorBtn);

  function _outside(e) {
    if (!menuEl.contains(e.target)) {
      _closeDiscDropdown();
      document.removeEventListener('click', _outside, { capture: true });
      document.removeEventListener('touchstart', _outside, { capture: true });
    }
  }
  setTimeout(() => {
    document.addEventListener('click', _outside, { capture: true });
    document.addEventListener('touchstart', _outside, { capture: true, passive: true });
    menuEl._outsideFn = _outside;
  }, 0);
}

function _discPositionDropdown(menuEl, anchorBtn) {
  const doPos = () => {
    const rect  = anchorBtn.getBoundingClientRect();
    const menuW = 224;
    const menuH = menuEl.offsetHeight || 220;
    const vw = window.innerWidth, vh = window.innerHeight;
    let top = rect.bottom + 6, left = rect.right - menuW;
    if (left < 8) left = 8;
    if (left + menuW > vw - 8) left = vw - menuW - 8;
    if (top + menuH > vh - 8) top = rect.top - menuH - 6;
    menuEl.style.top  = `${Math.max(Math.round(top), 8)}px`;
    menuEl.style.left = `${Math.round(left)}px`;
  };
  doPos();
  if (!menuEl.offsetHeight) requestAnimationFrame(doPos);
}

function _closeDiscDropdown() {
  if (!_discActiveDropdown) return;
  const el = _discActiveDropdown;
  _discActiveDropdown = null;
  if (el._outsideFn) {
    document.removeEventListener('click', el._outsideFn, { capture: true });
    document.removeEventListener('touchstart', el._outsideFn, { capture: true });
  }
  el.classList.add('track-dropdown-leaving');
  el.addEventListener('animationend', () => el.remove(), { once: true });
  setTimeout(() => { if (el.parentNode) el.remove(); }, 250);
}

// ══════════════════════════════════════════════════════════════
//  SAVE DISCOVER — bookmarks all currently visible feed cards as
//  a new playlist, exactly like a playlist created from Create.
//
//  Purely reads the already-rendered DOM (card dataset + artwork
//  already on screen) — never touches the recommendation engine,
//  never re-fetches or regenerates anything, so it's instant.
//  Writes straight to the same 'lw_playlists' localStorage key
//  used by playlist.js, in the same { id, title, subtitle, mode,
//  tracks, date } shape, so it shows up in the Playlist tab
//  immediately (whether or not playlist.js happens to be loaded
//  yet this session).
// ══════════════════════════════════════════════════════════════
const _DISC_PL_STORAGE_KEY = 'lw_playlists';
const _DISC_PL_MAX_SAVED   = 20;

// Reads directly from localStorage — does not assume playlist.js
// (and its in-memory _plCache) has been loaded this session yet.
function _discLoadSavedPlaylists() {
  try { return JSON.parse(localStorage.getItem(_DISC_PL_STORAGE_KEY) || '[]'); }
  catch { return []; }
}

// Naming itself lives in app.js (_generateSmartPlaylistName /
// _generateUniquePlaylistName) so Discover draws from the exact same
// large word banks — and the exact same uniqueness check — as every
// other playlist-creation flow in the app (Create, Recommendations,
// Genre Mix, My Mix, Library, etc.). app.js is always loaded before
// any screen script, so it's safe to call directly here.
function _discUniquePlaylistName() {
  if (typeof _generateSmartPlaylistName === 'function') {
    return _generateSmartPlaylistName();
  }
  // Extremely defensive fallback, should never be hit in practice.
  return `Playlist ${Date.now()}`;
}


// Snapshot of exactly what's on screen right now, in visible (DOM) order.
function _discCurrentVisibleTracks() {
  const cards = document.querySelectorAll('#discoverList .disc-card');
  const tracks = [];
  cards.forEach(card => {
    const name   = card.dataset.lpName;
    const artist = card.dataset.lpArtist;
    if (!name || !artist) return;
    const img   = card.querySelector('.disc-card-art');
    const image = (img && img.src && img.style.display !== 'none') ? img.src : '';
    tracks.push({
      name,
      artist,
      url:       `https://www.last.fm/music/${encodeURIComponent(artist)}/_/${encodeURIComponent(name)}`,
      image,
      listeners: null,
      playcount: null,
      match:     null,
      album:     '',
    });
  });
  return tracks;
}

// Order-preserving identity for a track list — used to detect "this exact
// Discover list is already saved" without caring about title.
function _discTrackSignature(tracks) {
  return tracks.map(t => `${t.name}|${t.artist}`.toLowerCase()).join('\u241F');
}

function _discSaveAsPlaylist() {
  const tracks = _discCurrentVisibleTracks();
  if (!tracks.length) {
    showToast('Nothing to save yet', 'error');
    return;
  }

  const saved     = _discLoadSavedPlaylists();
  const signature = _discTrackSignature(tracks);

  const dupe = saved.find(p => p.mode === 'discover' && p.discoverSignature === signature);
  if (dupe) {
    showToast(`Already saved as "${dupe.title}"`, 'error');
    return;
  }

  const title = _discUniquePlaylistName();
  saved.push({
    id:                Date.now(),
    title,
    subtitle:          'Discover Feed',
    mode:              'discover',
    tracks,
    date:              Date.now(),
    discoverSignature: signature,
  });

  if (saved.length > _DISC_PL_MAX_SAVED) saved.splice(0, saved.length - _DISC_PL_MAX_SAVED);
  localStorage.setItem(_DISC_PL_STORAGE_KEY, JSON.stringify(saved));

  // Invalidate the Playlist screen's in-memory cache if it's already
  // loaded this session, so it re-reads from storage on next visit.
  if (typeof _plCache !== 'undefined') _plCache = null;

  const btn = document.getElementById('discoverSaveBtn');
  btn?.classList.add('discover-save-pop');
  setTimeout(() => btn?.classList.remove('discover-save-pop'), 350);

  showToast(`\u2713 Playlist saved as "${title}"`, 'success');
}

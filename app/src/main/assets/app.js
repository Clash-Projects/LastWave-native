/* ════════════════════════════════════════════════════════════
   app.js — Core
   Contains: state, Last.fm auth, API, helpers, cross-screen logic.
   ════════════════════════════════════════════════════════════

   AUTHENTICATION FLOW
   ───────────────────
   1. User enters API key + API secret in Settings
   2. App calls  auth.getToken  (unsigned, just needs api_key)
   3. App opens  https://www.last.fm/api/auth/?api_key=KEY&token=TOK&cb=lastwave://auth
      via Chrome Custom Tabs / system browser
   4. User approves on Last.fm
   5. Last.fm redirects to  lastwave://auth?token=TOK
   6. Android onNewIntent fires → calls window._lfmDeepLink(token)
      (Fallback: user taps "I've authorized" button → same function)
   7. App calls  auth.getSession  (signed: md5 of sorted params + secret)
   8. Receives { session.name, session.key }
   9. Stores session key + username; loads profile

   SCREEN CONTRACT
   ───────────────
   Screens READ  : state.playlist, state.username, state.apiKey,
                   state.sessionKey, state.selectedMode
   Screens WRITE : state.playlist, state.playlistTitle
   Screens CALL  : navigateTo(), showToast(), showModal(),
                   generatePlaylist(), startLastFmAuth(), signOut()
   ════════════════════════════════════════════════════════════ */

'use strict';

// ── State ────────────────────────────────────────────────────
const state = {
  username:         '',
  apiKey:           '',
  apiSecret:        '',
  sessionKey:       '',          // Last.fm sk — proves authenticated session
  pendingAuthToken: '',          // temporary token awaiting user approval
  authState:        'idle',      // 'idle' | 'pending' | 'authenticated'
  currentPage:      null,
  selectedMode:     null,
  playlist:         [],
  playlistTitle:    '',
  playlistSubtitle: '',
  lastInputs:       {},
  chipSelections:   { period: 'overall', count: '25', limit: '25' },
  accentColor:      '#E03030',
  accentLight:      '#FF6060',
  accentMode:       'manual',
  wallpaperColors:  null,
  visualMode:       null,
};

const LASTFM_BASE   = 'https://ws.audioscrobbler.com/2.0/';
const DEEP_LINK_CB  = 'lastwave://auth';   // must match AndroidManifest intent-filter

// ══════════════════════════════════════════════════════════════
//  PLAYLIST NAMING SYSTEM  v4
//  Every playlist gets a unique, premium-feeling 1–3 word name,
//  assembled from large adjective/noun word banks spanning colors,
//  weather, light, mood, space, nature, mythology, and time — e.g.
//  "Velvet Horizon", "Neon Mirage", "Arctic Dreams", "Silent Orbit".
//  Names are checked against every saved playlist title; if a
//  generated name is already taken, a new one is composed from the
//  same banks until a unique one is found. Numbers are never used.
//  Optional subtitle (unaffected by this system): see
//  _generatePlaylistSubtitle() below — "Genre Mix · House", etc.
// ══════════════════════════════════════════════════════════════

/** Adjective bank — colors, weather/light, mood, space/time. */
const _NAME_ADJECTIVES = [
  // colors
  'Crimson','Violet','Amber','Emerald','Sapphire','Scarlet','Ivory','Obsidian',
  'Cobalt','Copper','Golden','Silver','Jade','Ruby','Indigo','Pearl',
  // weather / light
  'Electric','Radiant','Frozen','Stormy','Misty','Blazing','Frosted','Luminous',
  'Fading','Glowing','Shimmering','Windswept','Sunlit','Moonlit','Hazy','Rainswept',
  // mood
  'Silent','Restless','Wild','Faded','Quiet','Wistful','Feral','Serene',
  'Fractured','Wandering','Distant','Hollow','Tender','Bold','Gentle','Dreamy',
  // space / time
  'Midnight','Lunar','Solar','Arctic','Celestial','Nocturnal','Endless','Ancient',
  'Eternal','Timeless','Drifting','Hidden','Velvet','Neon','Glass','Forgotten',
];

/** Noun bank — places, space, nature, mythology, emotion, time. */
const _NAME_NOUNS = [
  // space
  'Horizon','Orbit','Nebula','Eclipse','Comet','Nova','Zenith','Meridian',
  'Solstice','Constellation','Cosmos','Galaxy','Halo','Aurora','Starlight','Satellite',
  // nature
  'Tides','Storm','Tempest','Cascade','Glacier','Canyon','Meadow','Grove',
  'Summit','Valley','Wildfire','Monsoon','Frost','Ember','Bloom','Thicket',
  'Harbor','Lagoon','Prairie','Oasis',
  // mythology
  'Phoenix','Oracle','Siren','Valkyrie','Elysium','Odyssey','Atlas','Titan',
  'Muse','Chimera',
  // emotion / abstract
  'Echo','Echoes','Mirage','Dreams','Reverie','Pulse','Drift','Wanderlust',
  'Shadows','Sparks','Voyage','Whisper','Serenade','Solitude',
  // places / time
  'Skyline','Twilight','Daybreak','Sanctuary','Labyrinth','Wilderness',
];

/** Pick a random element from a pool array */
function _nameRndPick(pool) { return pool[Math.floor(Math.random() * pool.length)]; }

/**
 * Compose one candidate name from the word banks. Mostly two words
 * ("Adjective Noun") to match the target style; occasionally a single
 * strong noun, or a rarer three-word combination for extra variety.
 */
function _composePlaylistName() {
  const r = Math.random();
  if (r < 0.12) {
    return _nameRndPick(_NAME_NOUNS);
  }
  if (r < 0.90) {
    return `${_nameRndPick(_NAME_ADJECTIVES)} ${_nameRndPick(_NAME_NOUNS)}`;
  }
  const a1 = _nameRndPick(_NAME_ADJECTIVES);
  let   a2 = _nameRndPick(_NAME_ADJECTIVES);
  if (a2 === a1) a2 = _nameRndPick(_NAME_ADJECTIVES);
  return `${a1} ${a2} ${_nameRndPick(_NAME_NOUNS)}`;
}

/** Every saved playlist title, lowercased — read fresh from storage
 *  every time so it's accurate regardless of which screens have
 *  loaded yet or whether their in-memory caches are warm. */
function _loadAllPlaylistTitles() {
  let saved = [];
  try { saved = JSON.parse(localStorage.getItem('lw_playlists') || '[]'); } catch {}
  return new Set(saved.map(p => (p.title || '').toLowerCase().trim()));
}

/**
 * Generate a playlist name guaranteed unique among saved playlists.
 * Never appends numbers — if a composed name collides, another is
 * composed from the same banks (widening to a rarer 3-word shape in
 * the essentially unreachable case of many repeated collisions).
 */
function _generateUniquePlaylistName() {
  const taken = _loadAllPlaylistTitles();
  let name;
  for (let attempt = 0; attempt < 80; attempt++) {
    name = _composePlaylistName();
    if (!taken.has(name.toLowerCase())) return name;
  }
  for (let attempt = 0; attempt < 200; attempt++) {
    const a1 = _nameRndPick(_NAME_ADJECTIVES);
    let   a2 = _nameRndPick(_NAME_ADJECTIVES);
    if (a2 === a1) a2 = _nameRndPick(_NAME_ADJECTIVES);
    name = `${a1} ${a2} ${_nameRndPick(_NAME_NOUNS)}`;
    if (!taken.has(name.toLowerCase())) return name;
  }
  return name; // practically unreachable — banks give tens of thousands of combos
}

/**
 * Generate a subtitle string for a playlist card.
 * Examples: "Genre Mix · House", "Track Mix · Recent", "My Mix"
 */
function _generatePlaylistSubtitle(mode, inputs) {
  switch (mode) {
    case 'tag': {
      const g = inputs?.tagInput || '';
      if (g) {
        const cap = g.split(' ').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
        return `Genre Mix · ${cap}`;
      }
      return 'Genre Mix';
    }
    case 'recent':          return 'Recent Tracks';
    case 'top':
    case 'library':         return 'Top Tracks';
    case 'mix':             return 'My Mix';
    case 'recommendations': return 'Recommendations';
    case 'similar-tracks':
    case 'start-mix':
      return inputs?.seedTrackName
        ? `Track Mix · ${inputs.seedTrackName}`
        : 'Track Mix';
    case 'similar-artists':
      return inputs?.seedArtistInput
        ? `Artist Mix · ${inputs.seedArtistInput}`
        : 'Artist Mix';
    default:                return 'Mix';
  }
}

/**
 * Ensure a specific base name is unique among saved playlists.
 * Kept for backward compatibility with older call sites that pass a
 * pre-derived base name. If taken, blends in a bank word to keep it
 * thematic rather than ever appending a number; falls back to a
 * fresh bank-composed name if that still collides.
 */
function _deduplicateName(baseName) {
  const taken = _loadAllPlaylistTitles();
  const base  = (baseName || '').trim() || _generateUniquePlaylistName();
  if (!taken.has(base.toLowerCase())) return base;

  for (let attempt = 0; attempt < 40; attempt++) {
    const candidate = `${base} ${_nameRndPick(_NAME_NOUNS)}`;
    if (!taken.has(candidate.toLowerCase())) return candidate;
  }
  return _generateUniquePlaylistName();
}

/**
 * Generate a smart, unique playlist name.
 * Called from generatePlaylist(), startMixFromTrack(), genre-explore
 * flows, and the Discover screen's Save feature.
 *
 * Mode/inputs/tracks are accepted for backward compatibility with
 * existing call sites, but naming is now deliberately mode-agnostic:
 * every playlist draws from the same large, high-quality word banks
 * so names stay varied across the whole app rather than repeating
 * within a given mode or genre.
 */
function _generateSmartPlaylistName(mode, inputs, tracks) {
  return _generateUniquePlaylistName();
}

// ══════════════════════════════════════════════════════════════
//  MD5  (needed for Last.fm API signature)
// ══════════════════════════════════════════════════════════════
// Self-contained, no external dependency.
function _md5(str) {
  function safeAdd(x, y) {
    const lsw = (x & 0xffff) + (y & 0xffff);
    return ((x >> 16) + (y >> 16) + (lsw >> 16)) << 16 | (lsw & 0xffff);
  }
  function rol(n, c) { return n << c | n >>> (32 - c); }
  function cmn(q, a, b, x, s, t) { return safeAdd(rol(safeAdd(safeAdd(a, q), safeAdd(x, t)), s), b); }
  function ff(a, b, c, d, x, s, t) { return cmn(b & c | ~b & d, a, b, x, s, t); }
  function gg(a, b, c, d, x, s, t) { return cmn(b & d | c & ~d, a, b, x, s, t); }
  function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
  function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }

  // Encode str as UTF-8 bytes then into 32-bit words
  const utf8 = unescape(encodeURIComponent(str));
  const len8  = utf8.length;
  const n     = len8 + 8 >> 6;
  const x     = new Array((n + 1) * 16).fill(0);
  for (let i = 0; i < len8; i++) x[i >> 2] |= utf8.charCodeAt(i) << (i % 4) * 8;
  x[len8 >> 2] |= 0x80 << (len8 % 4) * 8;
  x[n * 16 - 2] = len8 * 8;

  let a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;

  for (let i = 0; i < x.length; i += 16) {
    const [oa, ob, oc, od] = [a, b, c, d];
    a=ff(a,b,c,d,x[i+ 0], 7,-680876936);d=ff(d,a,b,c,x[i+ 1],12,-389564586);c=ff(c,d,a,b,x[i+ 2],17, 606105819);b=ff(b,c,d,a,x[i+ 3],22,-1044525330);
    a=ff(a,b,c,d,x[i+ 4], 7,-176418897);d=ff(d,a,b,c,x[i+ 5],12,1200080426);c=ff(c,d,a,b,x[i+ 6],17,-1473231341);b=ff(b,c,d,a,x[i+ 7],22,-45705983);
    a=ff(a,b,c,d,x[i+ 8], 7,1770035416);d=ff(d,a,b,c,x[i+ 9],12,-1958414417);c=ff(c,d,a,b,x[i+10],17,-42063);b=ff(b,c,d,a,x[i+11],22,-1990404162);
    a=ff(a,b,c,d,x[i+12], 7,1804603682);d=ff(d,a,b,c,x[i+13],12,-40341101);c=ff(c,d,a,b,x[i+14],17,-1502002290);b=ff(b,c,d,a,x[i+15],22,1236535329);
    a=gg(a,b,c,d,x[i+ 1], 5,-165796510);d=gg(d,a,b,c,x[i+ 6], 9,-1069501632);c=gg(c,d,a,b,x[i+11],14, 643717713);b=gg(b,c,d,a,x[i+ 0],20,-373897302);
    a=gg(a,b,c,d,x[i+ 5], 5,-701558691);d=gg(d,a,b,c,x[i+10], 9, 38016083);c=gg(c,d,a,b,x[i+15],14,-660478335);b=gg(b,c,d,a,x[i+ 4],20,-405537848);
    a=gg(a,b,c,d,x[i+ 9], 5, 568446438);d=gg(d,a,b,c,x[i+14], 9,-1019803690);c=gg(c,d,a,b,x[i+ 3],14,-187363961);b=gg(b,c,d,a,x[i+ 8],20,1163531501);
    a=gg(a,b,c,d,x[i+13], 5,-1444681467);d=gg(d,a,b,c,x[i+ 2], 9,-51403784);c=gg(c,d,a,b,x[i+ 7],14,1735328473);b=gg(b,c,d,a,x[i+12],20,-1926607734);
    a=hh(a,b,c,d,x[i+ 5], 4,-378558);d=hh(d,a,b,c,x[i+ 8],11,-2022574463);c=hh(c,d,a,b,x[i+11],16, 1839030562);b=hh(b,c,d,a,x[i+14],23,-35309556);
    a=hh(a,b,c,d,x[i+ 1], 4,-1530992060);d=hh(d,a,b,c,x[i+ 4],11,1272893353);c=hh(c,d,a,b,x[i+ 7],16,-155497632);b=hh(b,c,d,a,x[i+10],23,-1094730640);
    a=hh(a,b,c,d,x[i+13], 4, 681279174);d=hh(d,a,b,c,x[i+ 0],11,-358537222);c=hh(c,d,a,b,x[i+ 3],16,-722521979);b=hh(b,c,d,a,x[i+ 6],23, 76029189);
    a=hh(a,b,c,d,x[i+ 9], 4,-640364487);d=hh(d,a,b,c,x[i+12],11,-421815835);c=hh(c,d,a,b,x[i+15],16, 530742520);b=hh(b,c,d,a,x[i+ 2],23,-995338651);
    a=ii(a,b,c,d,x[i+ 0], 6,-198630844);d=ii(d,a,b,c,x[i+ 7],10,1126891415);c=ii(c,d,a,b,x[i+14],15,-1416354905);b=ii(b,c,d,a,x[i+ 5],21,-57434055);
    a=ii(a,b,c,d,x[i+12], 6, 1700485571);d=ii(d,a,b,c,x[i+ 3],10,-1894986606);c=ii(c,d,a,b,x[i+10],15,-1051523);b=ii(b,c,d,a,x[i+ 1],21,-2054922799);
    a=ii(a,b,c,d,x[i+ 8], 6, 1873313359);d=ii(d,a,b,c,x[i+15],10,-30611744);c=ii(c,d,a,b,x[i+ 6],15,-1560198380);b=ii(b,c,d,a,x[i+13],21,1309151649);
    a=ii(a,b,c,d,x[i+ 4], 6,-145523070);d=ii(d,a,b,c,x[i+11],10,-1120210379);c=ii(c,d,a,b,x[i+ 2],15, 718787259);b=ii(b,c,d,a,x[i+ 9],21,-343485551);
    a=safeAdd(a,oa); b=safeAdd(b,ob); c=safeAdd(c,oc); d=safeAdd(d,od);
  }

  const hex = (n) => ('0' + (n & 0xff).toString(16)).slice(-2);
  return [a, b, c, d].map(v =>
    [0,8,16,24].map(s => hex(v >> s)).join('')
  ).join('');
}

// ── MD5 self-check (verifies implementation against RFC 1321 vector) ─────────
// MD5("abc") must equal "900150983cd24fb0d6963f7d28e17f72"
let _md5SelfCheckPassed = null;
function _md5SelfCheck() {
  if (_md5SelfCheckPassed !== null) return _md5SelfCheckPassed;
  const expected = '900150983cd24fb0d6963f7d28e17f72';
  const actual   = _md5('abc');
  _md5SelfCheckPassed = (actual === expected);
  if (!_md5SelfCheckPassed) {
    console.error('[Auth] MD5 self-check FAILED — got:', actual, ' expected:', expected);
  }
  return _md5SelfCheckPassed;
}

/**
 * Normalise an API key or secret string.
 * Last.fm keys/secrets are 32-char lowercase hex. Strip EVERYTHING else
 * (spaces, newlines, zero-width chars, Unicode punctuation, etc.)
 */
function _normaliseKey(raw) {
  if (!raw) return '';
  return String(raw)
    .replace(/[\s\u00A0\u200B\u200C\u200D\uFEFF]/g, '')
    .replace(/[^a-fA-F0-9]/g, '')
    .toLowerCase();
}

/**
 * Build a Last.fm API signature — exact spec implementation.
 *
 * Last.fm spec (last.fm/api/authspec):
 *  1. Collect all request params EXCEPT "format" and "callback"
 *  2. Sort alphabetically by key (a-z, case-sensitive lowercase)
 *  3. Concatenate: key1value1key2value2...  (NO separator, NO "=")
 *  4. Append API_SECRET at the end (NO separator)
 *  5. MD5-hash the UTF-8 string to get the lowercase hex signature
 *
 * "api_sig" itself is excluded — it is the OUTPUT of this function.
 */
function _lfmSig(params, secret) {
  const SKIP = new Set(['format', 'callback', 'api_sig']);

  const sortedKeys = Object.keys(params)
    .filter(k => !SKIP.has(k))
    .sort();

  // Signature base: key1value1key2value2...SECRET
  const base = sortedKeys.map(k => k + String(params[k])).join('') + secret;

  const hash = _md5(base);
  return hash;
}

// ══════════════════════════════════════════════════════════════
//  SEEN-TRACKS  (cross-session deduplication)
// ══════════════════════════════════════════════════════════════
const _SEEN_KEY = 'lw_seen_tracks';
const _SEEN_TTL = 21 * 24 * 60 * 60 * 1000;
const _SEEN_MAX = 3000;

function _getSeenMap() {
  try { return JSON.parse(localStorage.getItem(_SEEN_KEY) || '{}'); } catch { return {}; }
}
function _markAsSeen(tracks) {
  const seen = _getSeenMap();
  const now  = Date.now();
  for (const t of (tracks || [])) seen[`${t.name}|${t.artist}`.toLowerCase()] = now;
  const entries = Object.entries(seen).sort((a, b) => b[1] - a[1]).slice(0, _SEEN_MAX);
  localStorage.setItem(_SEEN_KEY, JSON.stringify(Object.fromEntries(entries)));
}
function _filterFresh(tracks) {
  const seen = _getSeenMap(); const now = Date.now();
  return tracks.filter(t => { const ts = seen[`${t.name}|${t.artist}`.toLowerCase()]; return !ts || (now - ts) > _SEEN_TTL; });
}
function clearSeenTracks() {
  localStorage.removeItem(_SEEN_KEY);
  showToast('Discovery history cleared \u2713', 'success');
}
function getSeenTracksCount() { return Object.keys(_getSeenMap()).length; }

// ══════════════════════════════════════════════════════════════
//  USER TASTE PROFILE
//  Builds a structured taste model from the user's Last.fm data.
//  Cached in localStorage with a 1-hour TTL so we don't
//  re-fetch on every playlist generation.
//
//  Serialised shape (arrays for JSON, hydrated to Sets for O(1) lookup):
//    topArtistNames   – lowercased, ordered by playcount
//    recentArtists    – lowercased, from last 50 plays
//    topTags          – lowercased genre names, ordered by weight
//    topTrackKeys     – "name|artist" (lowercased) — long-term favourites
//    recentTrackKeys  – "name|artist" (lowercased) — recently played
//    topTrackSeeds    – [{name,artist}] top 50 tracks (for seeding similar queries)
//    recentTrackSeeds – [{name,artist}] recent 50 tracks (for mood-based seeding)
// ══════════════════════════════════════════════════════════════
const _TASTE_PROFILE_KEY = 'lw_taste_profile_v1';
const _TASTE_PROFILE_TTL = 60 * 60 * 1000; // 1 hour

/**
 * Fetch and build a UserTasteProfile.
 * All 4 Last.fm calls run in parallel for speed.
 * Returns a plain JSON-serialisable object; pass it to _hydrateProfile()
 * before any scoring or Set-based lookups.
 */
async function _buildUserTasteProfile() {
  // Return cached profile if still fresh for this user
  try {
    const raw = JSON.parse(localStorage.getItem(_TASTE_PROFILE_KEY) || 'null');
    if (raw && raw.username === state.username && Date.now() - raw.ts < _TASTE_PROFILE_TTL) {
      return raw.profile;
    }
  } catch {}

  const profile = {
    topArtistNames:   [],  // lowercased
    recentArtists:    [],  // lowercased, no duplicates
    topTags:          [],  // lowercased
    topTrackKeys:     [],  // "name|artist" lowercased
    recentTrackKeys:  [],  // "name|artist" lowercased
    topTrackSeeds:    [],  // [{name, artist}]
    recentTrackSeeds: [],  // [{name, artist}]
  };

  // All 4 profile calls in parallel — total network time = slowest single call
  const [topTrRes, recentRes, topArRes, topTagRes] = await Promise.allSettled([
    lfmCall({ method: 'user.gettoptracks',   user: state.username, period: 'overall', limit: 50 }),
    lfmCall({ method: 'user.getrecenttracks', user: state.username, limit: 50 }),
    lfmCall({ method: 'user.gettopartists',  user: state.username, period: 'overall', limit: 30 }),
    lfmCall({ method: 'user.gettoptags',     user: state.username, limit: 15 }),
  ]);

  if (topTrRes.status === 'fulfilled') {
    normaliseTracks(topTrRes.value.toptracks?.track).forEach(t => {
      const k = `${t.name}|${t.artist}`.toLowerCase();
      profile.topTrackKeys.push(k);
      profile.topTrackSeeds.push({ name: t.name, artist: t.artist });
    });
  }

  if (recentRes.status === 'fulfilled') {
    const rRaw = recentRes.value.recenttracks?.track;
    const arr  = (Array.isArray(rRaw) ? rRaw : [rRaw]).filter(t => !t?.['@attr']?.nowplaying);
    normaliseTracks(arr).forEach(t => {
      const k  = `${t.name}|${t.artist}`.toLowerCase();
      const ak = t.artist.toLowerCase();
      profile.recentTrackKeys.push(k);
      profile.recentTrackSeeds.push({ name: t.name, artist: t.artist });
      if (!profile.recentArtists.includes(ak)) profile.recentArtists.push(ak);
    });
  }

  if (topArRes.status === 'fulfilled') {
    (topArRes.value.topartists?.artist || []).forEach(a => {
      profile.topArtistNames.push(a.name.toLowerCase());
    });
  }

  if (topTagRes.status === 'fulfilled') {
    (topTagRes.value.toptags?.tag || []).forEach(t => {
      profile.topTags.push(t.name.toLowerCase());
    });
  }

  try {
    localStorage.setItem(_TASTE_PROFILE_KEY, JSON.stringify({
      ts: Date.now(), username: state.username, profile
    }));
  } catch {}

  return profile;
}

/**
 * Hydrate a serialised taste profile (arrays → Sets) for O(1) lookup.
 * Always call this before using the profile for scoring.
 */
function _hydrateProfile(raw) {
  return {
    topArtistNames:  new Set(raw.topArtistNames  || []),
    recentArtists:   new Set(raw.recentArtists   || []),
    topTags:         raw.topTags || [],
    topTagSet:       new Set(raw.topTags         || []),
    topTrackKeys:    new Set(raw.topTrackKeys    || []),
    recentTrackKeys: new Set(raw.recentTrackKeys || []),
    topTrackSeeds:   raw.topTrackSeeds    || [],
    recentTrackSeeds:raw.recentTrackSeeds || [],
  };
}

/**
 * Score a track against the user taste profile.
 * Returns -1 as a hard-reject sentinel (track was recently played).
 * Otherwise 0–120 — higher = stronger fit.
 *
 *   bucketWeight bonus (signal confidence):
 *     4  → +40  (similar to recent plays — current mood, highest confidence)
 *     3  → +30  (similar to all-time top tracks)
 *     2  → +20  (similar artist tracks)
 *     1  → +0   (genre/tag discovery — needs artist match to pass strict filter)
 *
 *   Artist match:
 *     +50  artist is in user's top artists (strong long-term signal)
 *     +30  artist is in user's recent artists (current mood signal)
 *
 *   Known track penalty:
 *     −15  track is already in user's top tracks (familiar, less exciting)
 *
 * @param {object} track
 * @param {object} profile — hydrated UserTasteProfile
 * @param {number} [bucketWeight=1] — source bucket priority (1–4)
 */
function _scoreTrack(track, profile, bucketWeight) {
  if (!track?.name || !track?.artist) return 0;
  const artistKey = track.artist.toLowerCase();
  const trackKey  = `${track.name}|${track.artist}`.toLowerCase();

  // Hard reject: user just heard this track recently
  if (profile.recentTrackKeys.has(trackKey)) return -1;

  let score = 0;

  // Bucket confidence bonus — higher weight = more curated source
  const bw = bucketWeight || 1;
  if      (bw >= 4) score += 40;
  else if (bw >= 3) score += 30;
  else if (bw >= 2) score += 20;
  // bw === 1 → no bonus; tag-discovery tracks must prove relevance via artist match

  // Artist familiarity signals
  if (profile.topArtistNames.has(artistKey)) score += 50;
  if (profile.recentArtists.has(artistKey))  score += 30;

  // Already a known favourite — slightly penalise to prefer new discoveries
  if (profile.topTrackKeys.has(trackKey)) score -= 15;

  return Math.max(0, score);
}

/**
 * Filter a scored-track array against the taste profile.
 * Each entry must be { track, weight, score } — call _scoreTrackArray first.
 * score === -1 (recently played) always removed.
 */
function _tasteFilter(scoredTracks, minScore) {
  return scoredTracks.filter(({ score }) => score !== -1 && score >= minScore);
}

/**
 * Score an array of { track, weight } objects, returning { track, weight, score }.
 */
function _scoreTrackArray(weighted, profile) {
  return weighted.map(({ track, weight }) => ({
    track,
    weight,
    score: _scoreTrack(track, profile, weight),
  }));
}

/**
 * Invalidate the cached taste profile (e.g. after sign-out or manual refresh).
 */
function clearTasteProfile() {
  localStorage.removeItem(_TASTE_PROFILE_KEY);
}

// ══════════════════════════════════════════════════════════════
//  TRACK GENRE RESOLUTION
//  Fetches the top 1-2 tags for a track from track.getInfo.
//  Results are memoised in a session-scoped LRU Map (300 entries).
//  Returns a formatted string like "Indie Rock, Alternative" or ''.
// ══════════════════════════════════════════════════════════════
const _genreCache    = new Map();
const _GENRE_CACHE_MAX = 300;

async function _resolveTrackGenre(name, artist) {
  if (!name || !artist) return '';
  const k = `${name}|${artist}`.toLowerCase();

  if (_genreCache.has(k)) return _genreCache.get(k);

  let result = '';

  // ── Step 1: Last.fm track.getInfo → toptags ────────────────
  try {
    const data = await lfmCall({ method: 'track.getInfo', track: name, artist, autocorrect: 1 });
    const tags  = data?.track?.toptags?.tag || [];
    const arr   = Array.isArray(tags) ? tags : [tags];
    const names = arr
      .filter(t => t?.name && t.name.toLowerCase() !== 'seen live')
      .slice(0, 2)
      .map(t => t.name)
      .filter(Boolean);
    if (names.length) result = names.join(', ');
  } catch {}

  // ── Step 2: iTunes primaryGenreName fallback ───────────────
  if (!result) {
    try {
      const term   = encodeURIComponent(`${name} ${artist}`);
      const apiUrl = `https://itunes.apple.com/search?term=${term}&media=music&entity=song&limit=1`;
      const res    = await fetch(apiUrl, { signal: AbortSignal.timeout(6000) });
      if (res.ok) {
        const data = await res.json();
        const genre = data?.results?.[0]?.primaryGenreName;
        if (genre && genre !== 'Music') result = genre;
      }
    } catch {}
  }

  if (_genreCache.size >= _GENRE_CACHE_MAX) _genreCache.delete(_genreCache.keys().next().value);
  _genreCache.set(k, result);
  return result;
}

// ══════════════════════════════════════════════════════════════
//  COVER ART REFRESH
//  Forces a re-fetch of artwork for a specific track, bypassing
//  all caches. Includes a 30-second per-track cooldown.
//  Returns the new URL or '' if nothing was found.
// ══════════════════════════════════════════════════════════════
const _artRefreshCooldown  = new Map();
const _ART_REFRESH_COOLDOWN_MS = 30 * 1000;

async function _refreshTrackArtwork(name, artist) {
  const key = `${name}|${artist}`.toLowerCase();
  const now  = Date.now();

  const lastRefresh = _artRefreshCooldown.get(key) || 0;
  if (now - lastRefresh < _ART_REFRESH_COOLDOWN_MS) {
    showToast('Please wait a moment before refreshing again', 'error');
    return '';
  }
  _artRefreshCooldown.set(key, now);

  // ── Clear from memory cache ────────────────────────────────
  const cacheKey = `t:${key}`;
  _artUrlCache.delete(cacheKey);

  // ── Clear from persistent disk cache ──────────────────────
  try {
    const diskMap = _artDiskLoad();
    if (diskMap[cacheKey]) {
      delete diskMap[cacheKey];
      _artDiskMap = diskMap;
      localStorage.setItem(_ART_DISK_KEY, JSON.stringify(diskMap));
    }
  } catch {}

  // ── Clear from iTunes session cache ───────────────────────
  const itunesKey = `it:track:${name.toLowerCase().slice(0, 60)}:${artist.toLowerCase().slice(0, 40)}`;
  _itunesCache.delete(itunesKey);

  // ── Re-fetch via the full resolution chain ─────────────────
  try {
    return await _resolveTrackArt(name, artist);
  } catch {
    return '';
  }
}

// ══════════════════════════════════════════════════════════════
//  PER-CALL RESPONSE CACHE  (TTL 10 min)
// ══════════════════════════════════════════════════════════════
const _apiCache  = new Map();
const _CACHE_TTL = 10 * 60 * 1000;
const _FETCH_TIMEOUT_MS = 7000; // Hard cap: no API call ever hangs past 7 s

async function _cachedFetch(url) {
  const now = Date.now();
  const hit = _apiCache.get(url);
  if (hit && now - hit.ts < _CACHE_TTL) return hit.data;
  // AbortSignal.timeout ensures the fetch is cancelled after 7 s,
  // preventing any single slow/unreachable endpoint from freezing the UI.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), _FETCH_TIMEOUT_MS);
  let res;
  try {
    res = await fetch(url, { signal: controller.signal });
  } catch (e) {
    clearTimeout(timer);
    if (e.name === 'AbortError') throw new Error('Request timed out — check your connection');
    throw e;
  }
  clearTimeout(timer);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  if (data.error) throw new Error(data.message || `Last.fm error ${data.error}`);
  _apiCache.set(url, { data, ts: now });
  if (_apiCache.size > 200) {
    const oldest = [..._apiCache.entries()].sort((a, b) => a[1].ts - b[1].ts)[0][0];
    _apiCache.delete(oldest);
  }
  return data;
}

// ══════════════════════════════════════════════════════════════
//  ITUNES ARTWORK FALLBACK
//  Uses the Apple iTunes Search API to fill in artwork that
//  Last.fm doesn't provide.
//
//  Supported types:
//    'track'  → searches songs: term = "{track} {artist}"
//    'album'  → searches albums: term = "{album} {artist}"
//    'artist' → searches songs by artist (musicArtist records
//               don't carry artworkUrl100, so we grab a song)
//
//  All results are cached in an LRU Map (300 entries, session).
// ══════════════════════════════════════════════════════════════

/** Returns true if iTunes artwork fallback is enabled (default ON). */
function useItunesArtwork() {
  return localStorage.getItem('lw_use_itunes') !== '0';
}

const _itunesCache = new Map();
const _ITUNES_MAX  = 300;

// ══════════════════════════════════════════════════════════════
//  PERSISTENT ARTWORK DISK CACHE
//  Backed by localStorage so images survive app relaunches.
//  Key format:
//    tracks  → "t:trackname|artistname"
//    artists → "a:artistname"
//    albums  → "al:albumname|artistname"
//  Value: { url: string, ts: number }
//  TTL:  30 days  |  Max: 800 entries (LRU eviction)
// ══════════════════════════════════════════════════════════════
const _ART_DISK_KEY = 'lw_art_v1';
const _ART_DISK_TTL = 30 * 24 * 60 * 60 * 1000; // 30 days in ms
const _ART_DISK_MAX = 800;
let   _artDiskMap   = null; // lazy-loaded

/** Lazy-load the disk cache map from localStorage. */
function _artDiskLoad() {
  if (_artDiskMap) return _artDiskMap;
  try {
    _artDiskMap = JSON.parse(localStorage.getItem(_ART_DISK_KEY) || '{}');
  } catch { _artDiskMap = {}; }
  return _artDiskMap;
}

/**
 * Get a cached artwork URL from localStorage.
 * Returns the URL string (may be '' for confirmed-no-art),
 * or null if the entry is missing or expired.
 */
function _artDiskGet(key) {
  const cache = _artDiskLoad();
  const entry = cache[key];
  if (!entry) return null;
  if (Date.now() - entry.ts > _ART_DISK_TTL) {
    delete cache[key];
    return null;
  }
  return entry.url;
}

/**
 * Save an artwork URL to the persistent localStorage cache.
 * Performs LRU eviction when over _ART_DISK_MAX entries.
 */
function _artDiskSet(key, url) {
  const cache  = _artDiskLoad();
  cache[key]   = { url, ts: Date.now() };
  // LRU eviction: keep only the newest _ART_DISK_MAX entries
  const entries = Object.entries(cache);
  if (entries.length > _ART_DISK_MAX) {
    entries.sort((a, b) => a[1].ts - b[1].ts);
    entries.slice(0, entries.length - _ART_DISK_MAX).forEach(([k]) => delete cache[k]);
  }
  try { localStorage.setItem(_ART_DISK_KEY, JSON.stringify(cache)); } catch (e) {
    // localStorage full → clear old entries and retry once
    try {
      const half = Object.entries(cache)
        .sort((a, b) => a[1].ts - b[1].ts)
        .slice(Math.floor(entries.length / 2));
      const trimmed = Object.fromEntries(half);
      trimmed[key]  = { url, ts: Date.now() };
      _artDiskMap   = trimmed;
      localStorage.setItem(_ART_DISK_KEY, JSON.stringify(trimmed));
    } catch {}
  }
}

/**
 * Convert any iTunes thumbnail URL to its 600×600 variant.
 *   e.g.  …/100x100bb.jpg  →  …/600x600bb.jpg
 */
function _itunesUpscale(raw) {
  if (!raw) return '';
  // Replace any NxNbb.ext pattern at the end of the path
  return raw.replace(/\/\d+x\d+bb\.(jpg|png|webp)$/i, '/600x600bb.jpg');
}

/**
 * Fetch artwork from the iTunes Search API.
 * Returns a 600×600 URL string, or '' on failure / disabled.
 *
 * @param {string} nameOrTrack  – track name, album name, or artist name
 * @param {string} artist       – artist name (empty for artist type)
 * @param {'track'|'album'|'artist'} type
 */
async function _itunesFetchArtwork(nameOrTrack, artist, type) {
  if (!useItunesArtwork()) return '';
  if (!nameOrTrack) return '';

  const ck = `it:${type}:${(nameOrTrack).toLowerCase().slice(0, 60)}:${(artist || '').toLowerCase().slice(0, 40)}`;
  if (_itunesCache.has(ck)) {
    return _itunesCache.get(ck);
  }
  // Check persistent disk cache before hitting the network
  const diskKey = `itunes:${ck}`;
  const diskVal = _artDiskGet(diskKey);
  if (diskVal !== null) {
    _itunesCache.set(ck, diskVal); // warm mem-cache too
    return diskVal;
  }

  let imgUrl = '';
  try {
    let apiUrl;

    if (type === 'artist') {
      // Searching by artistTerm attribute returns song records that do carry
      // artworkUrl100, which we then upscale.  musicArtist entity does NOT
      // return artworkUrl100, so we avoid it.
      apiUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(nameOrTrack)}&media=music&entity=song&attribute=artistTerm&limit=1`;
    } else if (type === 'album') {
      const term = artist ? `${nameOrTrack} ${artist}` : nameOrTrack;
      apiUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&media=music&entity=album&limit=1`;
    } else {
      // track (default)
      const term = artist ? `${nameOrTrack} ${artist}` : nameOrTrack;
      apiUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&media=music&entity=song&limit=1`;
    }

    const res = await fetch(apiUrl, { signal: AbortSignal.timeout(6000) });

    if (!res.ok) {
      console.warn('[iTunes] HTTP error', res.status, 'for', nameOrTrack);
    } else {
      const data = await res.json();
      const results = data?.results;

      if (!Array.isArray(results) || results.length === 0) {
        // no results — imgUrl stays ''
      } else {
        const item = results[0];
        const raw  = item?.artworkUrl100 || item?.artworkUrl60 || '';
        if (raw) {
          imgUrl = _itunesUpscale(raw);
        }
      }
    }
  } catch (e) {
    if (e.name === 'TimeoutError') {
      console.warn('[iTunes] timeout for:', nameOrTrack);
    } else {
      console.warn('[iTunes] error for', nameOrTrack, ':', e.message);
    }
  }

  // LRU eviction: remove oldest entry when over capacity
  if (_itunesCache.size >= _ITUNES_MAX) {
    _itunesCache.delete(_itunesCache.keys().next().value);
  }
  _itunesCache.set(ck, imgUrl);
  _artDiskSet(diskKey, imgUrl); // persist across relaunches
  return imgUrl;
}

// ══════════════════════════════════════════════════════════════
//  INIT
// ══════════════════════════════════════════════════════════════
window.addEventListener('load', () => {
  // ── Step 1: Apply persisted settings synchronously (no network needed) ────
  loadSettings();

  // Register global deep-link callback (called by Android onNewIntent)
  window._lfmDeepLink = _handleDeepLinkToken;

  // ── Step 2: Navigate to home ──────────────────────────────────────────────
  // The app shell is visible immediately; the home screen injects its DOM
  // synchronously and fetches data asynchronously (showing skeleton/cached
  // state while it loads). A screen crash must not break init.
  Promise.resolve()
    .then(() => navigateTo('home'))
    .catch(() => {});

  // ── Step 4: Load user profile silently in the background ─────────────────
  // Fires after a short delay so it does not race with home screen init.
  if (state.username && state.apiKey) {
    setTimeout(() => {
      loadUserProfile().catch(() => {}); // non-critical — ignore errors
    }, 200);
  }
});

// ── Settings loader ───────────────────────────────────────────
function loadSettings() {
  state.username    = (localStorage.getItem('lw_username')   || '').trim();
  state.apiKey      = (localStorage.getItem('lw_apikey')     || '').trim();
  state.apiSecret   = (localStorage.getItem('lw_apisecret')  || '').trim();
  state.sessionKey  = (Platform.getSavedSessionKey() || localStorage.getItem('lw_sessionkey') || '').trim();
  state.accentColor = localStorage.getItem('lw_accent')      || '#E03030';
  state.accentLight = localStorage.getItem('lw_accentLight') || '#FF6060';
  state.accentMode  = localStorage.getItem('lw_accentMode')  || 'manual';

  // Restore AMOLED mode before generating palette so surface tones are correct
  const savedAmoled = localStorage.getItem('lw_amoled') === '1';
  if (savedAmoled) document.body.classList.add('amoled-mode');

  state.authState = state.sessionKey ? 'authenticated' : 'idle';

  // Check if the Android system is providing a Monet/Material You palette
  const _wc = Platform.getWallpaperColors();
  if (_wc) {
    state.wallpaperColors = _wc;
    // wallpaperColors from the bridge represents the *system* Material You
    // seed color (equivalent to what dynamicDarkColorScheme() uses on Android).
    if (state.accentMode === 'dynamic') {
      // Use system-provided seed; full M3 palette is generated from its hue.
      applyAccent(
        state.wallpaperColors.primary,
        state.wallpaperColors.secondary || state.wallpaperColors.primary,
        false,
        'dynamic'
      );
      return; // palette applied — skip manual accent below
    }
  } else if (state.accentMode === 'dynamic') {
    state.accentMode = 'manual'; // dynamic saved but unavailable — fall back
  }

  // Fallback: derive full M3 scheme from the stored manual accent hue
  if (state.accentMode === 'monochrome') {
    _applyMonochromeScheme();
  } else {
    applyAccent(state.accentColor, state.accentLight, false);
  }
}


// ══════════════════════════════════════════════════════════════
//  LAST.FM API
// ══════════════════════════════════════════════════════════════

/** Standard (unsigned) API call — reads only, no session required */
async function lfmCall(params) {
  if (!state.apiKey) throw new Error('No API key set. Go to Settings.');
  const url = new URL(LASTFM_BASE);
  const p   = { ...params, api_key: state.apiKey, format: 'json' };
  Object.entries(p).forEach(([k, v]) => url.searchParams.set(k, v));
  return _cachedFetch(url.toString());
}

/**
 * Map Last.fm API error codes to user-friendly messages.
 * Reference: https://www.last.fm/api/errorcodes
 */
function _lfmFriendlyError(code, rawMsg) {
  const msgs = {
    2:  'Invalid service — please contact support.',
    3:  'Invalid method — please update the app.',
    4:  'Authentication failed — your API key or secret may be wrong. Double-check them in Settings.',
    5:  'Invalid format specified.',
    6:  'Invalid parameters — one or more required fields are missing.',
    7:  'Invalid resource.',
    8:  'Operation failed — try again in a moment.',
    9:  'Invalid session key — please sign in again.',
    10: 'Invalid API key — paste your key from last.fm/api/accounts exactly.',
    11: 'Service temporarily offline — try again later.',
    13: 'Invalid API signature — make sure your API secret is pasted correctly with no extra spaces.',
    14: 'Unauthorized token — please authorize the app in the browser first.',
    15: 'This token has expired — please sign in again.',
    16: 'Service temporarily unavailable — try again in a few seconds.',
    26: 'Suspended API key — contact Last.fm support.',
    29: 'Rate limit exceeded — please wait a moment then try again.',
  };
  if (code && msgs[code]) return msgs[code];
  return rawMsg || `Auth error (code ${code || 'unknown'})`;
}

/**
 * Signed POST — for auth.getSession and any authenticated write method.
 *
 * Flow:
 *  1. Normalise key + secret (strip all non-hex chars, force lowercase)
 *  2. Run MD5 self-check to catch any implementation regression
 *  3. Build params (NO format / callback at this point)
 *  4. Compute api_sig = _lfmSig(params, secret)
 *  5. Add format=json AFTER signing
 *  6. POST as application/x-www-form-urlencoded
 */
async function lfmCallSigned(params) {
  if (!state.apiKey)    throw new Error('No API key set. Go to Settings.');
  if (!state.apiSecret) throw new Error('API secret required. Go to Settings.');

  // ── Step 1: Normalise — strip ALL whitespace & non-hex chars ─────────────
  // Last.fm API keys and secrets are always 32-char lowercase hex strings.
  // Any stray space, newline, invisible Unicode char, or punctuation will
  // corrupt the signature. We remove them unconditionally.
  const keyNorm = _normaliseKey(state.apiKey);
  const secNorm = _normaliseKey(state.apiSecret);

  if (keyNorm.length < 16) throw new Error('API key is too short after normalisation — please check it in Settings.');
  if (secNorm.length < 16) throw new Error('API secret is too short after normalisation — please check it in Settings.');
  if (keyNorm.length !== 32) console.warn('[Auth] API key is', keyNorm.length, 'chars — expected 32');
  if (secNorm.length !== 32) console.warn('[Auth] API secret is', secNorm.length, 'chars — expected 32');

  // ── Step 2: MD5 self-check ─────────────────────────────────────────────────
  if (!_md5SelfCheck()) {
    throw new Error('Internal MD5 error — authentication cannot proceed. Please report this bug.');
  }

  // ── Step 3: Build params (signing scope) ──────────────────────────────────
  // Do NOT include format, callback, or api_sig at this stage.
  const p = { ...params, api_key: keyNorm };

  // ── Step 4: Compute signature ─────────────────────────────────────────────
  p.api_sig = _lfmSig(p, secNorm);

  // ── Step 5: Add format AFTER signing ─────────────────────────────────────
  p.format = 'json';

  // ── Step 6: POST ─────────────────────────────────────────────────────────
  const body = new URLSearchParams(p).toString();

  const res = await fetch(LASTFM_BASE, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });

  let data = null;
  try { data = await res.json(); } catch { /* non-JSON body */ }

  if (!res.ok || data?.error) {
    const code     = data?.error;
    const raw      = data?.message || `HTTP ${res.status}`;
    const friendly = _lfmFriendlyError(code, raw);
    console.error('[Auth] Last.fm error code', code, ':', raw);
    throw new Error(friendly);
  }
  return data;
}

// ══════════════════════════════════════════════════════════════
//  AUTHENTICATION FLOW
// ══════════════════════════════════════════════════════════════

/**
 * Step 1+2 — Request a token then open the Last.fm auth page in
 * Chrome Custom Tabs / system browser.
 */
async function startLastFmAuth() {
  if (!state.apiKey) {
    showToast('Enter your API key first', 'error'); return;
  }
  if (!state.apiSecret) {
    showToast('Enter your API secret first', 'error'); return;
  }

  try {
    _setAuthStatus('pending', 'Requesting token\u2026');

    // Clear any stale pending token from a previous aborted auth attempt
    state.pendingAuthToken = '';
    localStorage.removeItem('lw_pending_token');

    // Step 1: get token (unsigned GET — no signature required for auth.getToken)
    const url  = new URL(LASTFM_BASE);
    url.searchParams.set('method',  'auth.getToken');
    url.searchParams.set('api_key', state.apiKey);
    url.searchParams.set('format',  'json');

    const res  = await fetch(url.toString());
    // Check HTTP status before attempting JSON parse
    if (!res.ok) throw new Error(`HTTP ${res.status} \u2014 could not request token`);
    const data = await res.json();
    if (data.error) throw new Error(data.message || `Last.fm error ${data.error}`);

    const token = data.token;
    if (!token) throw new Error('No token returned by Last.fm');
    state.pendingAuthToken = token;
    localStorage.setItem('lw_pending_token', token);

    // Step 2: open browser — do NOT include cb= with a custom deep-link scheme;
    // Last.fm may reject or mishandle non-HTTPS callback URLs causing 400 errors.
    // The "I've authorized" button below handles the manual fallback.
    const authUrl = `https://www.last.fm/api/auth/?api_key=${encodeURIComponent(state.apiKey)}&token=${encodeURIComponent(token)}`;

    Platform.openAuthBrowser(authUrl);

    _setAuthStatus('pending', 'Waiting for authorization\u2026');
    _notifySettingsAuthState();   // tell settings.js to update its UI

  } catch (e) {
    _setAuthStatus('idle');
    showToast(e.message, 'error');
    _notifySettingsAuthState();
  }
}

/**
 * Step 5+6 — Called from:
 *   a) Android onNewIntent via window._lfmDeepLink(token)
 *   b) Manual "I've authorized" button in settings
 */
async function _handleDeepLinkToken(token) {
  const useToken = token || state.pendingAuthToken || localStorage.getItem('lw_pending_token');
  if (!useToken) {
    showToast('No pending authorization found', 'error');
    return;
  }

  _setAuthStatus('pending', 'Exchanging token\u2026');
  _notifySettingsAuthState();

  let attempts = 0;
  const MAX_ATTEMPTS = 3;

  while (attempts < MAX_ATTEMPTS) {
    attempts++;
    try {
      // Step 5: exchange token for session (signed POST)
      // Params for signature: api_key + method + token  (format excluded per spec)
      const data = await lfmCallSigned({
        method: 'auth.getSession',
        token:  useToken
      });

      const session = data.session;
      if (!session?.key) throw new Error('Invalid session response from Last.fm');

      // Step 6: persist session
      state.sessionKey  = session.key;
      state.username    = session.name;
      state.authState   = 'authenticated';
      state.pendingAuthToken = '';

      localStorage.setItem('lw_sessionkey', session.key);
      localStorage.setItem('lw_username',   session.name);
      localStorage.removeItem('lw_pending_token');

      Platform.saveSessionKey(session.key);

      showToast(`Signed in as ${session.name} \u2713`, 'success');
      _notifySettingsAuthState();
      loadUserProfile();
      return;

    } catch (e) {
      if (attempts >= MAX_ATTEMPTS) {
        _setAuthStatus('idle');
        showToast(`Auth failed: ${e.message}`, 'error');
        _notifySettingsAuthState();
        return;
      }
      // Brief back-off before retry
      await new Promise(r => setTimeout(r, 1500 * attempts));
    }
  }
}

/**
 * Manual fallback — user taps "I've authorized, continue" button
 * Uses the pending token stored from step 1.
 */
function continueAfterAuth() {
  const token = state.pendingAuthToken || localStorage.getItem('lw_pending_token');
  _handleDeepLinkToken(token);
}

/** Sign out — clears session key and resets auth state */
function signOut() {
  showModal(
    'Sign out?',
    `You'll need to sign in again to use personalized features.`,
    () => {
      state.sessionKey       = '';
      state.authState        = 'idle';
      state.pendingAuthToken = '';
      localStorage.removeItem('lw_sessionkey');
      localStorage.removeItem('lw_pending_token');
      Platform.clearSession();
      showToast('Signed out');
      _notifySettingsAuthState();
      // Reset topbar avatar
      const topbarAvatar = document.getElementById('topbarAvatar');
      const topbarIcon   = document.getElementById('topbarAvatarIcon');
      if (topbarAvatar) { topbarAvatar.src = ''; topbarAvatar.classList.add('hidden'); }
      if (topbarIcon)   topbarIcon.classList.remove('hidden');
    }
  );
}

function _setAuthStatus(status, msg) {
  state.authState = status;
}

/** Fire the settings screen's refresh function if it's currently loaded */
function _notifySettingsAuthState() {
  try {
    if (typeof window.screen_settings_refreshAuth === 'function') {
      window.screen_settings_refreshAuth();
    }
  } catch {}
}

// ── User profile ──────────────────────────────────────────────
async function loadUserProfile() {
  if (!state.username || !state.apiKey) return;
  try {
    const data = await lfmCall({ method: 'user.getinfo', user: state.username });
    const u    = data.user;
    const nameEl  = document.getElementById('profileName');
    const scrobEl = document.getElementById('profileScrobbles');
    if (nameEl)  nameEl.textContent  = u.realname || u.name;
    if (scrobEl) scrobEl.textContent = parseInt(u.playcount || 0).toLocaleString();

    const img    = u.image && (u.image.find(i => i.size === 'large') || u.image.find(i => i.size === 'medium') || u.image[0]);
    const imgUrl = img?.['#text'] || '';
    if (imgUrl) {
      const topbarAvatar = document.getElementById('topbarAvatar');
      const topbarIcon   = document.getElementById('topbarAvatarIcon');
      if (topbarAvatar) {
        topbarAvatar.src    = imgUrl;
        topbarAvatar.onload = () => { topbarAvatar.classList.remove('hidden'); topbarIcon?.classList.add('hidden'); };
        topbarAvatar.onerror= () => { topbarAvatar.classList.add('hidden');    topbarIcon?.classList.remove('hidden'); };
      }
      const profileAvatar = document.getElementById('profileAvatar');
      if (profileAvatar) profileAvatar.src = imgUrl;
    }
    // Update settings auth card if visible
    _notifySettingsAuthState();
  } catch {}
}

function showUserInfo() {
  navigateTo('settings');
}

// ══════════════════════════════════════════════════════════════
//  LOADING MESSAGE ROTATION
// ══════════════════════════════════════════════════════════════
const _LOADING_MSGS = [
  'Shuffling your seeds\u2026', 'Exploring similar artists\u2026',
  'Digging into your taste\u2026', 'Finding hidden gems\u2026',
  'Mixing genres\u2026', 'Picking fresh tracks\u2026',
  'Scanning recent plays\u2026', 'Building your unique mix\u2026',
  'Curating discoveries\u2026', 'Filtering repeats\u2026', 'Almost ready\u2026',
];
let _loadingMsgTimer = null;
function _startLoadingCycle(firstMsg) {
  _stopLoadingCycle();
  setLoadingText(firstMsg || _LOADING_MSGS[0]);
  let i = 1;
  _loadingMsgTimer = setInterval(() => {
    const el = document.getElementById('plLoadingText');
    if (!el) return;
    el.style.transition = 'opacity 0.25s'; el.style.opacity = '0';
    setTimeout(() => { if (el) { el.textContent = _LOADING_MSGS[i % _LOADING_MSGS.length]; el.style.opacity = '1'; } }, 260);
    i++;
  }, 2800);
}
function _stopLoadingCycle() {
  if (_loadingMsgTimer) { clearInterval(_loadingMsgTimer); _loadingMsgTimer = null; }
}

// ══════════════════════════════════════════════════════════════
//  PLAYLIST GENERATION
// ══════════════════════════════════════════════════════════════
async function generatePlaylist(skipNav) {
  if (!state.username) { showToast('Enter a Last.fm username first', 'error'); navigateTo('home'); return; }
  if (!state.apiKey)   { showToast('Add your API key in Settings', 'error'); navigateTo('settings'); return; }
  if (!state.selectedMode) { showToast('Select a playlist mode first', 'error'); return; }

  const inputs = {
    seedTrackName:   document.getElementById('seedTrackName')?.value.trim()   || state.lastInputs?.seedTrackName   || '',
    seedArtistName:  document.getElementById('seedArtistName')?.value.trim()  || state.lastInputs?.seedArtistName  || '',
    seedArtistInput: document.getElementById('seedArtistInput')?.value.trim() || state.lastInputs?.seedArtistInput || '',
    tagInput:        document.getElementById('tagInput')?.value.trim()        || state.lastInputs?.tagInput         || '',
  };
  state.lastInputs = { ...inputs };

  await navigateTo('results');
  _startLoadingCycle('Generating your unique playlist\u2026');
  showLoading(true);

  try {
    let tracks = [];
    let skipSharedPrecheck = false; // set true only for My Recommendations — see below
    const limit = parseInt(state.chipSelections.limit) || 25;

    switch (state.selectedMode) {
      case 'top':
        tracks = await fetchTopTracks(limit);
        state.playlistTitle    = _generateSmartPlaylistName(
          state.visualMode === 'library' ? 'library' : 'top', inputs
        );
        state.playlistSubtitle = _generatePlaylistSubtitle(
          state.visualMode === 'library' ? 'library' : 'top', inputs
        );
        break;
      case 'recent':
        tracks = await fetchRecentTracks(parseInt(state.chipSelections.count) || 25);
        state.playlistTitle    = _generateSmartPlaylistName('recent', inputs);
        state.playlistSubtitle = _generatePlaylistSubtitle('recent', inputs);
        break;
      case 'similar-tracks':
        if (!inputs.seedTrackName || !inputs.seedArtistName) { showToast('Enter a seed track and artist', 'error'); _stopLoadingCycle(); showLoading(false); return; }
        tracks = await fetchSimilarTracks(inputs.seedTrackName, inputs.seedArtistName, limit);
        state.playlistTitle    = _generateSmartPlaylistName('similar-tracks', inputs);
        state.playlistSubtitle = _generatePlaylistSubtitle('similar-tracks', inputs);
        break;
      case 'similar-artists':
        if (!inputs.seedArtistInput) { showToast('Enter a seed artist', 'error'); _stopLoadingCycle(); showLoading(false); return; }
        tracks = await fetchSimilarArtistTracks(inputs.seedArtistInput, limit);
        state.playlistTitle    = _generateSmartPlaylistName('similar-artists', inputs);
        state.playlistSubtitle = _generatePlaylistSubtitle('similar-artists', inputs);
        break;
      case 'tag':
        if (!inputs.tagInput) { showToast('Enter a genre/tag', 'error'); _stopLoadingCycle(); showLoading(false); return; }
        tracks = await fetchTagTracks(inputs.tagInput, limit);
        state.playlistTitle    = _generateSmartPlaylistName('tag', inputs);
        state.playlistSubtitle = _generatePlaylistSubtitle('tag', inputs);
        break;
      case 'mix':
        if (state.visualMode === 'recommendations') {
          // My Recommendations — smart personalised engine.
          // Uses `limit` (the strict slider value) directly, and the engine
          // itself already guarantees the exact count + diversity, so the
          // shared _precheckTracks() re-trim below is skipped for this mode
          // only (it would otherwise re-cap artist repeats and can shave
          // tracks off an already-exact, already-diverse playlist).
          tracks = await fetchRecommendations(limit);
          skipSharedPrecheck = true;
          state.playlistTitle    = _generateSmartPlaylistName('recommendations', inputs);
          state.playlistSubtitle = _generatePlaylistSubtitle('recommendations', inputs);
        } else {
          tracks = await fetchMix(parseInt(state.chipSelections.count) || 30);
          state.playlistTitle    = _generateSmartPlaylistName('mix', inputs);
          state.playlistSubtitle = _generatePlaylistSubtitle('mix', inputs);
        }
        break;
    }

    state.playlist = skipSharedPrecheck
      ? deduplicateTracks(tracks).slice(0, limit)
      : _precheckTracks(tracks).slice(0, limit || 200);
    _markAsSeen(state.playlist);
    setLoadingText('Loading album artwork\u2026');
    await enrichTracksWithArt(state.playlist);
    _preloadImages(state.playlist);   // fire-and-forget: warm the browser image cache
    _stopLoadingCycle();
    showLoading(false);
    renderResults();
  } catch (e) {
    _stopLoadingCycle(); showLoading(false);
    showToast(e.message, 'error'); showResultsEmpty();
  }
}

async function regeneratePlaylist() {
  if (!state.selectedMode) { showToast('Generate a playlist first', 'error'); navigateTo('generator'); return; }
  return generatePlaylist(false);
}

// ══════════════════════════════════════════════════════════════
//  FETCH HELPERS  (all randomised for freshness)
// ══════════════════════════════════════════════════════════════

async function fetchTopTracks(limit) {
  setLoadingText('Fetching your top tracks\u2026');
  const page = Math.ceil(Math.random() * 3);
  const data = await lfmCall({ method: 'user.gettoptracks', user: state.username, period: state.chipSelections.period || 'overall', limit, page });
  return shuffleArray(normaliseTracks(data.toptracks.track));
}

async function fetchRecentTracks(limit) {
  setLoadingText('Fetching recent plays\u2026');
  const data = await lfmCall({ method: 'user.getrecenttracks', user: state.username, limit });
  const raw  = data.recenttracks.track;
  const arr  = (Array.isArray(raw) ? raw : [raw]).filter(t => !t['@attr']?.nowplaying);
  return shuffleArray(normaliseTracks(arr));
}

async function fetchSimilarTracks(track, artist, limit) {
  setLoadingText(`Finding tracks similar to \u201c${track}\u201d\u2026`);
  const data  = await lfmCall({ method: 'track.getsimilar', track, artist, limit: Math.min(limit * 4, 200) });
  const all   = normaliseTracks(data.similartracks.track);
  const fresh = _filterFresh(all);
  return shuffleArray(fresh.length >= Math.min(limit, 8) ? fresh : all).slice(0, limit);
}

async function fetchSimilarArtistTracks(artist, limit) {
  setLoadingText(`Finding artists similar to ${artist}\u2026`);
  const data    = await lfmCall({ method: 'artist.getsimilar', artist, limit: 20 });
  const artists = shuffleArray(data.similarartists.artist || []).slice(0, 8);
  let tracks    = [];
  for (const a of artists) {
    try {
      const page = Math.ceil(Math.random() * 4);
      const d    = await lfmCall({ method: 'artist.gettoptracks', artist: a.name, limit: Math.ceil(limit / 5), page });
      tracks = tracks.concat(normaliseTracks(d.toptracks.track));
    } catch {}
  }
  const fresh = _filterFresh(tracks);
  return shuffleArray(fresh.length >= Math.min(limit, 8) ? fresh : tracks).slice(0, limit);
}

async function fetchTagTracks(tag, limit) {
  setLoadingText(`Loading ${tag} tracks\u2026`);
  const page  = Math.floor(Math.random() * 8) + 1;
  const data  = await lfmCall({ method: 'tag.gettoptracks', tag, limit: Math.min(limit * 3, 100), page });
  const all   = normaliseTracks(data.tracks.track);
  const fresh = _filterFresh(all);
  return shuffleArray(fresh.length >= Math.min(limit, 8) ? fresh : all).slice(0, limit);
}

// ══════════════════════════════════════════════════════════════

// ══════════════════════════════════════════════════════════════
//  SMART RECOMMENDATIONS ENGINE — "My Recommendations"
//  (Create screen → My Recommendations → Generate Playlist.
//   This is one generation mode among Top/Recent/Similar/Tag/Mix —
//   not a separate screen or module. Lives here in app.js next to
//   fetchTopTracks / fetchRecentTracks / fetchMix, same as always.)
//
//  Guarantees:
//    1. Exact requested track count, always — a fixed-order,
//       7-source pipeline (Similar Tracks → Similar Artists →
//       Artist Top Tracks → Genre Matches → Tag Matches →
//       Related Artists → Discovery Pool) runs in widen-and-retry
//       cycles: while (pool too small) { run pipeline; widen; retry }
//       up to _RECO_MAX_CYCLES times, then an own-library last
//       resort guarantees the count regardless.
//    2. Nothing already heard — recent + all-time top + loved
//       tracks + everything in the current or any saved playlist.
//    3. Artist cap (2), best-effort album cap (2, where Last.fm
//       supplies album data), genre-bucket cap (~35%).
//    4. Multi-signal scoring: taste match, similarity confidence,
//       hidden-gem/popularity signal, genre-primary bonus, and a
//       small exploration factor so regenerations feel fresh.
//    5. Post-selection genre interleave so one tag doesn't run
//       back-to-back.
//
//  Every cycle logs via _rLog — check Chrome remote debugging
//  (chrome://inspect) against the device to see pool/fresh counts
//  per cycle if the exact-count guarantee ever needs auditing.
// ══════════════════════════════════════════════════════════════

const RECO_DEBUG = true;
function _rLog(...args) { if (RECO_DEBUG) console.log('%c[Recommendations]', 'color:#8b5cf6', ...args); }

// ── Tunables ────────────────────────────────────────────────────
const _RECO_MAX_CYCLES      = 5;     // hard ceiling on widen-and-retry cycles
const _RECO_ARTIST_CAP      = 2;     // max tracks per artist
const _RECO_ALBUM_CAP       = 2;     // max tracks per album (best-effort — see note above)
const _RECO_GENRE_CAP_RATIO = 0.35;  // no single genre/tag bucket > 35% of the playlist

/** Compact map of closely-related genres — widens genre discovery
 *  without drifting away from the user's taste. */
const _RECO_GENRE_NEIGHBORS = {
  'indie rock': 'alternative rock', 'alternative rock': 'indie rock',
  'house': 'deep house', 'deep house': 'tech house', 'tech house': 'techno', 'techno': 'trance',
  'hip hop': 'rap', 'hip-hop': 'boom bap', 'rap': 'hip hop',
  'metal': 'heavy metal', 'heavy metal': 'metal', 'black metal': 'doom metal', 'death metal': 'doom metal',
  'pop': 'indie pop', 'indie pop': 'dream pop', 'dream pop': 'shoegaze', 'shoegaze': 'dream pop',
  'jazz': 'soul', 'soul': 'r&b', 'r&b': 'neo-soul', 'funk': 'soul',
  'electronic': 'experimental', 'ambient': 'chillwave', 'lo-fi': 'chillwave', 'chillwave': 'synthwave',
  'punk': 'post-punk', 'post-punk': 'new wave', 'new wave': 'synth-pop',
  'folk': 'indie folk', 'indie folk': 'folk', 'country': 'folk',
  'trap': 'drill', 'drill': 'trap', 'drum and bass': 'jungle', 'jungle': 'drum and bass',
};

/** Last.fm returns a bare object instead of a 1-item array for some
 *  endpoints when there's exactly one match. Normalise defensively. */
function _toArr(x) { return Array.isArray(x) ? x : (x ? [x] : []); }

function _rKey(t) { return `${t.name}|${t.artist}`.toLowerCase(); }

// ══════════════════════════════════════════════════════════════
//  SCORING — every candidate gets one score from many signals
// ══════════════════════════════════════════════════════════════

/** Hidden-gem / popularity-penalty signal from Last.fm's global listener count.
 *  Widened curve — quality goal is "hidden masterpieces", not average songs,
 *  so true deep cuts are rewarded much more strongly than before, and
 *  mega-hits are pushed down harder rather than just slightly. */
function _recoGemScore(track) {
  const n = parseInt(track.listeners, 10);
  if (!n || isNaN(n))   return 10;    // popularity unknown — treat as a plausible gem
  if (n < 3000)         return 36;
  if (n < 10000)        return 30;
  if (n < 30000)        return 22;
  if (n < 100000)       return 12;
  if (n < 400000)       return 0;
  if (n < 1500000)      return -14;
  return -28;
}

/** Community appreciation — playcount-per-listener ratio. A track that gets
 *  replayed a lot relative to how many people even found it signals "the
 *  people who discovered this loved it", independent of raw popularity. */
function _recoCommunityScore(track) {
  const listeners = parseInt(track.listeners, 10);
  const playcount = parseInt(track.playcount, 10);
  if (!listeners || !playcount || isNaN(listeners) || isNaN(playcount)) return 0;
  const ratio = playcount / listeners;
  if (ratio >= 6)  return 14;  // heavily replayed by the people who found it
  if (ratio >= 3)  return 8;
  if (ratio >= 1.5) return 3;
  return 0;
}

/** track.getsimilar's 0–1 "match" score — the real "audio/community similarity" signal Last.fm exposes. */
function _recoMatchBonus(track) {
  const m = parseFloat(track.match);
  return isNaN(m) ? 0 : Math.round(m * 26);
}

/**
 * Combine every available signal into one score.
 * bucketWeight: 4 mood(recent-similar) / 3 taste(top-similar) /
 *               2 artist-discovery / 1 genre-discovery
 * isPrimaryGenre: candidate came from one of the user's own top tags
 *                 (vs. a "neighbouring" genre reached for variety)
 * sourceCount:    how many independent sources surfaced this same
 *                 candidate (e.g. a similar-artist AND a favourite-tag
 *                 both pointed at it) — strong convergent evidence
 * isFresh:        hasn't been recommended in a recent session
 */
function _recoScore(track, profile, bucketWeight, isPrimaryGenre, sourceCount, isFresh) {
  let score = 0;
  const bw = bucketWeight || 1;

  // Discovery-bucket confidence
  if      (bw >= 4) score += 34;
  else if (bw >= 3) score += 26;
  else if (bw >= 2) score += 18;

  // Taste match vs. novelty — an intentional tension: known-artist trust
  // is rewarded, but a completely new artist gets a novelty nod too, so
  // the engine doesn't just churn out deep cuts from artists already known.
  const artistKey = (track.artist || '').toLowerCase();
  const knownArtist = profile.topArtistNames.has(artistKey) || profile.recentArtists.has(artistKey);
  if (profile.topArtistNames.has(artistKey)) score += 22;
  if (profile.recentArtists.has(artistKey))  score += 10;
  if (!knownArtist) score += 9; // novelty bonus — a genuinely new artist for this user
  if (isPrimaryGenre) score += 6;

  // Multi-source confirmation — the same track being surfaced by more than
  // one independent signal (similar-artist AND a favourite tag, say) is
  // much stronger evidence of fit than any single source alone.
  if (sourceCount >= 3) score += 18;
  else if (sourceCount === 2) score += 9;

  // Community/similarity signal + hidden-gem / popularity-penalty signal
  score += _recoMatchBonus(track);
  score += _recoGemScore(track);
  score += _recoCommunityScore(track);

  // Replay-avoidance — continuous, on top of the hard staged filter in
  // selection, so ranking itself already favours truly-unseen tracks.
  score += isFresh ? 6 : -20;

  // Small random exploration factor — keeps regenerations feeling
  // fresh even when scores would otherwise tie every time.
  score += Math.random() * 6;

  return score;
}

// ══════════════════════════════════════════════════════════════
//  CANDIDATE SOURCES
//  Each source is a small async function that ADDS candidates via
//  `addAll(tracks, weight, tag)`. The engine calls them in a fixed
//  pipeline order, then widens (deeper pages / new seeds / more
//  artists+tags) and repeats the whole pipeline if still short.
// ══════════════════════════════════════════════════════════════

async function _srcSimilarTracks(ctx, cycle) {
  const pool = cycle === 1 ? ctx.rawProfile.recentTrackSeeds : ctx.rawProfile.topTrackSeeds;
  const seeds = shuffleArray(pool || []).slice((cycle - 1) * 6, (cycle - 1) * 6 + 6);
  await Promise.allSettled(seeds.map(async s => {
    try {
      const d = await lfmCall({ method: 'track.getsimilar', track: s.name, artist: s.artist, limit: 30 });
      ctx.addAll(normaliseTracks(d.similartracks?.track), cycle === 1 ? 4 : 3, cycle === 1 ? 'mood' : 'taste');
    } catch {}
  }));
}

async function _srcSimilarArtists(ctx, cycle) {
  const artists = shuffleArray([...ctx.profile.topArtistNames]).slice((cycle - 1) * 6, (cycle - 1) * 6 + 6);
  await Promise.allSettled(artists.map(async artistName => {
    try {
      const d = await lfmCall({ method: 'artist.getsimilar', artist: artistName, limit: 20 });
      const sims = shuffleArray(_toArr(d.similarartists?.artist));
      for (const sa of sims.slice(0, 5)) {
        const ak = sa.name.toLowerCase();
        if (ctx.exploredArtists.has(ak)) continue;
        ctx.exploredArtists.add(ak);
        ctx.pendingArtists.push(sa.name);
      }
    } catch {}
  }));
}

async function _srcArtistTopTracks(ctx) {
  const batch = ctx.pendingArtists.splice(0, 12); // consume artists discovered by _srcSimilarArtists
  await Promise.allSettled(batch.map(async artistName => {
    try {
      const page = Math.ceil(Math.random() * 6); // skip page 1 megahits sometimes — hidden-gem bias
      const d    = await lfmCall({ method: 'artist.gettoptracks', artist: artistName, limit: 10, page });
      ctx.addAll(normaliseTracks(d.toptracks?.track), 2, `artist:${artistName.toLowerCase()}`);
    } catch {}
  }));
}

async function _srcGenreMatches(ctx, cycle) {
  const tags = shuffleArray(ctx.profile.topTags).slice((cycle - 1) * 4, (cycle - 1) * 4 + 4);
  await Promise.allSettled(tags.map(async tag => {
    ctx.exploredTags.add(tag);
    try {
      const page = Math.floor(Math.random() * 10) + 2; // pages 2–11: past the obvious chart-toppers
      const d    = await lfmCall({ method: 'tag.gettoptracks', tag, limit: Math.ceil(ctx.total * 0.4), page });
      ctx.addAll(normaliseTracks(d.tracks?.track), 1, `tag:${tag}`, true);
    } catch {}
  }));
}

async function _srcTagMatches(ctx) {
  // Neighbouring/related genres — cohesive variety beyond the user's exact top tags
  const tags = [...ctx.exploredTags].map(t => _RECO_GENRE_NEIGHBORS[t]).filter(Boolean);
  const unique = [...new Set(tags)].filter(t => !ctx.exploredTags.has(t)).slice(0, 4);
  await Promise.allSettled(unique.map(async tag => {
    ctx.exploredTags.add(tag);
    try {
      const page = Math.floor(Math.random() * 6) + 1;
      const d    = await lfmCall({ method: 'tag.gettoptracks', tag, limit: Math.ceil(ctx.total * 0.3), page });
      ctx.addAll(normaliseTracks(d.tracks?.track), 1, `tag:${tag}`, false);
    } catch {}
  }));
}

async function _srcRelatedArtists(ctx, cycle) {
  // 2-hop discovery: similar artists of artists already explored this session
  const seedArtists = shuffleArray([...ctx.exploredArtists]).slice(0, 4);
  await Promise.allSettled(seedArtists.map(async artistName => {
    try {
      const d    = await lfmCall({ method: 'artist.getsimilar', artist: artistName, limit: 15 });
      const sims = shuffleArray(_toArr(d.similarartists?.artist));
      for (const sa of sims) {
        const ak = sa.name.toLowerCase();
        if (ctx.exploredArtists.has(ak)) continue;
        ctx.exploredArtists.add(ak);
        ctx.pendingArtists.push(sa.name);
      }
    } catch {}
  }));
  await _srcArtistTopTracks(ctx);
}

async function _srcDiscoveryPool(ctx) {
  // Brute-force supply net: the user's own wider top-artist catalogue,
  // deep pages only, so it still favours lesser-known songs over hits.
  const wide = shuffleArray([...ctx.profile.topArtistNames]);
  await Promise.allSettled(wide.map(async artistName => {
    try {
      const page = Math.ceil(Math.random() * 8);
      const d    = await lfmCall({ method: 'artist.gettoptracks', artist: artistName, limit: 15, page });
      ctx.addAll(normaliseTracks(d.toptracks?.track), 2, `artist:${artistName.toLowerCase()}`);
    } catch {}
  }));
}

// Fixed pipeline order, run every cycle. Widening happens by re-running
// the same sources with fresh seeds/pages/artists (cycle-dependent slices).
const _RECO_PIPELINE = [
  _srcSimilarTracks,
  _srcSimilarArtists,
  _srcArtistTopTracks,
  _srcGenreMatches,
  _srcTagMatches,
  _srcRelatedArtists,
  _srcDiscoveryPool,
];

// ══════════════════════════════════════════════════════════════
//  SELECTION — staged relaxation, guarantees the exact count
// ══════════════════════════════════════════════════════════════

function _recoSelectFinal(scored, total) {
  const genreCap = Math.max(2, Math.ceil(total * _RECO_GENRE_CAP_RATIO));

  function attempt({ artistCap, albumCapOn, genreCapOn, freshOnly }) {
    const artistCount = {};
    const albumCount  = {};
    const genreCount  = {};
    const pickedKeys  = new Set();
    const picked      = [];
    for (const c of scored) {
      const key = _rKey(c.track);
      if (pickedKeys.has(key)) continue;
      if (freshOnly && !c.fresh) continue;
      const ak = (c.track.artist || '').toLowerCase();
      if ((artistCount[ak] || 0) >= artistCap) continue;
      if (albumCapOn && c.track.album) {
        const alK = c.track.album.toLowerCase();
        if ((albumCount[alK] || 0) >= _RECO_ALBUM_CAP) continue;
      }
      if (genreCapOn) {
        const genreTag = [...c.tags].find(t => t.startsWith('tag:'));
        if (genreTag) {
          if ((genreCount[genreTag] || 0) >= genreCap) continue;
          genreCount[genreTag] = (genreCount[genreTag] || 0) + 1;
        }
      }
      artistCount[ak] = (artistCount[ak] || 0) + 1;
      if (c.track.album) {
        const alK = c.track.album.toLowerCase();
        albumCount[alK] = (albumCount[alK] || 0) + 1;
      }
      pickedKeys.add(key);
      picked.push(c.track);
      if (picked.length >= total) break;
    }
    return picked;
  }

  // Stage 1 — full diversity + freshness (ideal case)
  let result = attempt({ artistCap: _RECO_ARTIST_CAP, albumCapOn: true, genreCapOn: true, freshOnly: true });
  // Stage 2 — allow tracks recommended in a past session
  if (result.length < total) result = attempt({ artistCap: _RECO_ARTIST_CAP, albumCapOn: true, genreCapOn: true, freshOnly: false });
  // Stage 3 — relax artist cap slightly
  if (result.length < total) result = attempt({ artistCap: 3, albumCapOn: true, genreCapOn: true, freshOnly: false });
  // Stage 4 — relax genre balance
  if (result.length < total) result = attempt({ artistCap: 3, albumCapOn: true, genreCapOn: false, freshOnly: false });
  // Stage 5 — relax album balance
  if (result.length < total) result = attempt({ artistCap: 3, albumCapOn: false, genreCapOn: false, freshOnly: false });
  // Stage 6 — take whatever unique candidates remain
  if (result.length < total) result = attempt({ artistCap: 99, albumCapOn: false, genreCapOn: false, freshOnly: false });

  return result;
}

/** Round-robin re-order by genre bucket so the same tag never plays
 *  twice in a row when it can be avoided (post-selection, doesn't
 *  change *which* tracks were picked — only their play order). */
function _recoInterleave(tracks, tagsByKey) {
  const buckets = new Map(); // bucket label -> queue of tracks
  for (const t of tracks) {
    const tags = tagsByKey.get(_rKey(t));
    const genreTag = tags ? [...tags].find(x => x.startsWith('tag:')) : null;
    const label = genreTag || 'other';
    if (!buckets.has(label)) buckets.set(label, []);
    buckets.get(label).push(t);
  }
  const queues = [...buckets.values()];
  const out = [];
  let lastLabel = null;
  while (out.length < tracks.length) {
    // pick the queue that isn't the same as the last-picked label, if possible
    let pickedIdx = queues.findIndex((q, i) => q.length && buckets_label(buckets, i) !== lastLabel);
    if (pickedIdx === -1) pickedIdx = queues.findIndex(q => q.length);
    if (pickedIdx === -1) break;
    const track = queues[pickedIdx].shift();
    out.push(track);
    lastLabel = buckets_label(buckets, pickedIdx);
  }
  return out;
  function buckets_label(map, idx) { return [...map.keys()][idx]; }
}

// ══════════════════════════════════════════════════════════════
//  MAIN ENTRY POINT
// ══════════════════════════════════════════════════════════════

async function fetchRecommendations(total) {
  setLoadingText('Building your taste profile\u2026');
  const rawProfile = await _buildUserTasteProfile();
  const profile     = _hydrateProfile(rawProfile);

  // ── Hard blacklist — anything the user has actually heard, plus
  // everything already in a saved/generated playlist ─────────────
  const blacklist = new Set([...profile.recentTrackKeys, ...profile.topTrackKeys]);
  try {
    const lovedRes = await lfmCall({ method: 'user.getlovedtracks', user: state.username, limit: 200 });
    normaliseTracks(lovedRes?.lovedtracks?.track).forEach(t => blacklist.add(_rKey(t)));
  } catch {}
  try {
    (typeof _plLoad === 'function' ? _plLoad() : []).forEach(pl => {
      (pl.tracks || []).forEach(t => { if (t?.name && t?.artist) blacklist.add(_rKey(t)); });
    });
  } catch {}
  (state.playlist || []).forEach(t => { if (t?.name && t?.artist) blacklist.add(_rKey(t)); });

  _rLog(`target=${total}, blacklist size=${blacklist.size}`);

  // Session freshness — avoid repeating what was recommended recently
  const seenMap = _getSeenMap();
  const now     = Date.now();
  const isFresh = (key) => { const ts = seenMap[key]; return !ts || (now - ts) > _SEEN_TTL; };

  // Candidate pool — key "name|artist" → { track, weight, tags:Set }
  const pool = new Map();
  const addAll = (tracks, weight, tag, isPrimaryGenre) => {
    for (const t of (tracks || [])) {
      if (!t?.name || !t?.artist) continue;
      const key = _rKey(t);
      if (blacklist.has(key)) continue;
      const cur = pool.get(key);
      if (cur) { if (weight > cur.weight) cur.weight = weight; cur.tags.add(tag); }
      else pool.set(key, { track: t, weight, tags: new Set([tag]), isPrimaryGenre: !!isPrimaryGenre });
    }
  };

  const ctx = {
    total, rawProfile, profile, addAll,
    exploredArtists: new Set(),
    exploredTags:    new Set(),
    pendingArtists:  [],
  };

  // ── Run the full pipeline; widen and repeat while short ────────
  let cycle = 1;
  while (cycle <= _RECO_MAX_CYCLES) {
    setLoadingText(cycle === 1
      ? 'Reading your listening mood\u2026'
      : `Digging deeper for more discoveries (round ${cycle})\u2026`);

    for (const source of _RECO_PIPELINE) {
      await source(ctx, cycle);
    }

    const freshCount = [...pool.keys()].filter(isFresh).length;
    _rLog(`cycle ${cycle}: pool=${pool.size} unique, fresh=${freshCount}, need~${Math.ceil(total * 1.6)}`);

    if (pool.size >= total * 1.6 || freshCount >= total * 1.4) break;
    cycle++;
  }

  setLoadingText('Curating your personal recommendations\u2026');

  // ── Score every candidate ──────────────────────────────────────
  const scored = [...pool.values()].map(({ track, weight, tags, isPrimaryGenre }) => {
    const fresh = isFresh(_rKey(track));
    return {
      track, weight, tags, fresh,
      score: _recoScore(track, profile, weight, isPrimaryGenre, tags.size, fresh),
    };
  });
  scored.sort((a, b) => (b.fresh - a.fresh) || (b.score - a.score));

  // ── Select with staged relaxation until `total` is reached ─────
  let final = _recoSelectFinal(scored, total);
  _rLog(`selected ${final.length}/${total} from candidate pool`);

  // ── Absolute last resort ────────────────────────────────────────
  // Only triggers when Last.fm's candidate pool itself is smaller
  // than `total` unique unheard tracks (a very sparse/new account).
  // The exact-count rule outranks discovery-purity here.
  if (final.length < total) {
    _rLog(`WARNING: pool too small, filling ${total - final.length} slot(s) from the user's own library as a last resort`);
    const usedKeys = new Set(final.map(_rKey));
    const backup = [...rawProfile.topTrackSeeds, ...rawProfile.recentTrackSeeds]
      .map(s => ({ name: s.name, artist: s.artist, url: '', image: '' }));
    for (const t of backup) {
      if (final.length >= total) break;
      const k = _rKey(t);
      if (usedKeys.has(k)) continue;
      usedKeys.add(k);
      final.push(t);
    }
  }

  final = deduplicateTracks(final).slice(0, total);

  // ── Interleave so the same genre bucket doesn't repeat back-to-back
  const tagsByKey = new Map(scored.map(c => [_rKey(c.track), c.tags]));
  final = _recoInterleave(final, tagsByKey);

  // ── Playlist flow — open and close on the strongest, most-confident
  // tracks so the playlist feels intentional rather than shuffled.
  // The middle keeps the genre-interleaved discovery variety as-is.
  if (final.length >= 3) {
    const scoreByKey = new Map(scored.map(c => [_rKey(c.track), c.score]));
    const order = [...final].sort((a, b) => (scoreByKey.get(_rKey(b)) || 0) - (scoreByKey.get(_rKey(a)) || 0));
    const opener = order[0];
    const closer = order.find(t => _rKey(t) !== _rKey(opener)) || order[1];
    final = final.filter(t => _rKey(t) !== _rKey(opener) && _rKey(t) !== _rKey(closer));
    final = [opener, ...final, closer];
  }

  _rLog(`FINAL: ${final.length}/${total} tracks returned`);
  if (final.length !== total) {
    console.warn(`[Recommendations] Could not reach the exact requested count. Returned ${final.length} of ${total}. This only happens when the Last.fm account has fewer unique unheard tracks available than requested.`);
  }

  return final;
}

async function fetchMix(total) {
  setLoadingText('Discovering tracks for you\u2026');

  // Priority buckets — each item is { track, weight }.
  // Weight determines sort order: 3 (recent) > 2 (top) > 1 (discovery).
  const weighted = [];

  // ── BUCKET A  weight:3  Recent plays + similar ────────────────
  // Most personal signal: what the user just listened to.
  let topArtists = [];
  try {
    setLoadingText('Personalising from recent plays\u2026');
    const rd     = await lfmCall({ method: 'user.getrecenttracks', user: state.username, limit: 50 });
    const rRaw   = rd.recenttracks.track;
    const recent = normaliseTracks(
      (Array.isArray(rRaw) ? rRaw : [rRaw]).filter(t => !t['@attr']?.nowplaying)
    );
    const recentSeeds = shuffleArray(recent).slice(0, 6);
    recentSeeds.forEach(t => weighted.push({ track: t, weight: 3 }));

    for (const t of recentSeeds.slice(0, 4)) {
      if (!t.name || !t.artist) continue;
      try {
        const d = await lfmCall({ method: 'track.getsimilar', track: t.name, artist: t.artist, limit: Math.ceil(total / 6) });
        normaliseTracks(d.similartracks.track).forEach(st => weighted.push({ track: st, weight: 3 }));
      } catch {}
    }
  } catch {}

  // ── BUCKET B  weight:2  Confirmed top tracks ──────────────────
  try {
    setLoadingText('Pulling in your top tracks\u2026');
    const r      = Math.random();
    const period = r < 0.4 ? '1month' : r < 0.7 ? '3month' : r < 0.9 ? '6month' : '12month';
    const topD   = await lfmCall({ method: 'user.gettoptracks', user: state.username, period, limit: 30 });
    normaliseTracks(topD.toptracks.track).forEach(t => weighted.push({ track: t, weight: 2 }));
  } catch {}

  // ── BUCKET B2  weight:2  Top artists → similar artist tracks ──
  try {
    const r      = Math.random();
    const period = r < 0.5 ? 'overall' : r < 0.75 ? '12month' : '6month';
    const d      = await lfmCall({ method: 'user.gettopartists', user: state.username, period, limit: 30 });
    topArtists   = d.topartists.artist || [];
  } catch {}

  for (const artist of shuffleArray(topArtists).slice(0, 3)) {
    try {
      setLoadingText(`Exploring artists like ${artist.name}\u2026`);
      const sim     = await lfmCall({ method: 'artist.getsimilar', artist: artist.name, limit: 12 });
      const simPool = shuffleArray(sim.similarartists.artist || []).slice(0, 3);
      for (const sa of simPool) {
        try {
          const page = Math.ceil(Math.random() * 4);
          const d    = await lfmCall({ method: 'artist.gettoptracks', artist: sa.name, limit: Math.max(4, Math.ceil(total / 12)), page });
          normaliseTracks(d.toptracks.track).forEach(t => weighted.push({ track: t, weight: 2 }));
        } catch {}
      }
    } catch {}
  }

  // ── BUCKET C  weight:1  Genre/tag discovery pad ───────────────
  const sofar = weighted.length;
  if (sofar < total * 2) {
    try {
      setLoadingText('Adding genre discoveries\u2026');
      const td  = await lfmCall({ method: 'user.gettoptags', user: state.username, limit: 8 });
      const tag = shuffleArray(td.toptags?.tag || [])[Math.floor(Math.random() * Math.min(5, (td.toptags?.tag || []).length))]?.name;
      if (tag) {
        const page = Math.floor(Math.random() * 8) + 1;
        const td2  = await lfmCall({ method: 'tag.gettoptracks', tag, limit: Math.ceil(total * 0.4), page });
        normaliseTracks(td2.tracks.track).forEach(t => weighted.push({ track: t, weight: 1 }));
      }
    } catch {}
  }

  setLoadingText('Curating your personalised mix\u2026');

  // ── Deduplicate — keep highest-weight copy of each track ──────
  const seen    = new Map();
  const trackOf = new Map();
  for (const { track, weight } of weighted) {
    if (!track?.name || !track?.artist) continue;
    const k = `${track.name}|${track.artist}`.toLowerCase();
    if (!seen.has(k) || weight > seen.get(k)) {
      seen.set(k, weight);
      trackOf.set(k, track);
    }
  }

  // Sort by weight tier descending, shuffle within each tier for variety
  const merged = [3, 2, 1].flatMap(w =>
    shuffleArray([...seen.entries()]
      .filter(([, wt]) => wt === w)
      .map(([k]) => trackOf.get(k)))
  );

  // ── Artist diversity: max 3 tracks per artist ─────────────────
  const artistCount = {};
  const diverse = merged.filter(t => {
    const key = (t.artist || '').toLowerCase();
    artistCount[key] = (artistCount[key] || 0) + 1;
    return artistCount[key] <= 3;
  });

  // ── Prefer freshness (unseen tracks) ─────────────────────────
  const fresh = _filterFresh(diverse);
  let pool    = fresh.length >= Math.min(total, 10) ? fresh : diverse;

  // ── Fallback: similar artists if pool is thin ─────────────────
  if (pool.length < total && topArtists.length > 0) {
    try {
      setLoadingText('Finding more recommendations\u2026');
      const fa = shuffleArray(topArtists)[0];
      const fd = await lfmCall({ method: 'artist.getsimilar', artist: fa.name, limit: 10 });
      for (const sa of shuffleArray(fd.similarartists.artist || []).slice(0, 3)) {
        try {
          const d = await lfmCall({ method: 'artist.gettoptracks', artist: sa.name, limit: 6 });
          pool = pool.concat(normaliseTracks(d.toptracks.track));
        } catch {}
      }
    } catch {}
  }

  return deduplicateTracks(pool);
}

// ── Image / Track helpers ─────────────────────────────────────
const _LFM_NO_ART = '2a96cbd8b46e442fc41c2b86b821562f';
function _isRealImg(url) { return url && url.trim() !== '' && !url.includes(_LFM_NO_ART); }

function normaliseTracks(tracks) {
  if (!tracks) return [];
  if (!Array.isArray(tracks)) tracks = [tracks];
  return tracks.filter(t => t && t.name).map(t => {
    // Fix 6: prefer extralarge → large → medium → any for best album art quality
    const imgEntry = t.image && (
      t.image.find(i => i.size === 'extralarge' && _isRealImg(i['#text'])) ||
      t.image.find(i => i.size === 'large'      && _isRealImg(i['#text'])) ||
      t.image.find(i => i.size === 'medium'     && _isRealImg(i['#text'])) ||
      t.image.find(i => _isRealImg(i['#text']))
    );
    return {
      name:      t.name,
      artist:    t.artist ? (typeof t.artist === 'string' ? t.artist : (t.artist.name || t.artist['#text'] || '')) : '',
      url:       t.url || '',
      image:     imgEntry?.['#text'] || '',
      // Extra signals (may be absent depending on endpoint) — used by the
      // recommendations engine for hidden-gem / similarity / album-diversity
      // scoring only. Last.fm does not include album on every endpoint —
      // this is best-effort, present when the source endpoint supplies it.
      listeners: t.listeners ?? null,
      playcount: t.playcount ?? null,
      match:     t.match ?? null,
      album:     t.album ? (typeof t.album === 'string' ? t.album : (t.album['#text'] || t.album.title || '')) : '',
    };
  });
}

function deduplicateTracks(tracks) {
  const seen = new Set();
  return tracks.filter(t => { const k = `${t.name}|${t.artist}`.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; });
}

/**
 * Pre-save quality pass:
 *  1. Remove null / missing name+artist
 *  2. Deduplicate
 *  3. Cap artist repetition (max 3 tracks per artist) for balanced playlists
 */
function _precheckTracks(tracks) {
  if (!Array.isArray(tracks)) return [];

  // Step 1: remove invalid entries
  const valid = tracks.filter(t =>
    t &&
    typeof t.name   === 'string' && t.name.trim()   !== '' &&
    typeof t.artist === 'string' && t.artist.trim() !== ''
  );

  // Step 2: deduplicate by name+artist key
  const deduped = deduplicateTracks(valid);

  // Step 3: artist balance — max 3 per artist
  const artistCount = {};
  return deduped.filter(t => {
    const key = t.artist.toLowerCase().trim();
    artistCount[key] = (artistCount[key] || 0) + 1;
    return artistCount[key] <= 3;
  });
}

// ── Global artwork URL cache (persists across sort changes / re-renders) ──
// Key: "name|artist" (lowercased). Value: resolved URL string ('' = confirmed no art).
const _artUrlCache = new Map();
const _ART_CACHE_MAX = 500;

/**
 * Resolve artwork for a single track.
 * Priority: Last.fm album art → Last.fm track image → iTunes (only if no Last.fm art).
 * Results are memoised in _artUrlCache to avoid redundant network calls.
 */
async function _resolveTrackArt(name, artist) {
  const cacheKey = `t:${name}|${artist}`.toLowerCase();

  // 1. Memory cache — instant, no I/O
  if (_artUrlCache.has(cacheKey)) return _artUrlCache.get(cacheKey);

  // 2. Disk cache — persists across app relaunches
  const diskVal = _artDiskGet(cacheKey);
  if (diskVal !== null) {
    _artUrlCache.set(cacheKey, diskVal); // warm mem-cache
    return diskVal;
  }

  let imgUrl = '';
  try {
    // Step 1: track.getInfo → album art (best quality)
    const data  = await lfmCall({ method: 'track.getInfo', track: name, artist, autocorrect: 1 });
    const album = data?.track?.album;
    if (album?.image) {
      const img =
        album.image.find(i => i.size === 'extralarge' && _isRealImg(i['#text'])) ||
        album.image.find(i => i.size === 'large'      && _isRealImg(i['#text'])) ||
        album.image.find(i => i.size === 'medium'     && _isRealImg(i['#text'])) ||
        album.image.find(i => _isRealImg(i['#text']));
      if (img) imgUrl = img['#text'];
    }
    // Step 2: track-level image fallback (still from Last.fm)
    if (!imgUrl && data?.track?.image) {
      const img = data.track.image.find(i => _isRealImg(i['#text']));
      if (img) imgUrl = img['#text'];
    }
  } catch { /* network / API error — fall through to iTunes */ }

  // Step 3: iTunes ONLY when Last.fm returned nothing
  if (!imgUrl) {
    try { imgUrl = await _itunesFetchArtwork(name, artist, 'track'); } catch {}
  }

  // Save to both caches ('' = confirmed no art, prevents future network calls)
  if (_artUrlCache.size >= _ART_CACHE_MAX) {
    _artUrlCache.delete(_artUrlCache.keys().next().value);
  }
  _artUrlCache.set(cacheKey, imgUrl);
  _artDiskSet(cacheKey, imgUrl);
  return imgUrl;
}

async function enrichTracksWithArt(tracks) {
  if (!tracks?.length) return;
  // Only enrich tracks that genuinely have no Last.fm image yet
  const toEnrich = tracks.filter(t => !t.image || !t.image.trim()).slice(0, 40);
  if (!toEnrich.length) return;
  const BATCH = 5;
  for (let i = 0; i < toEnrich.length; i += BATCH) {
    await Promise.allSettled(toEnrich.slice(i, i + BATCH).map(async t => {
      const imgUrl = await _resolveTrackArt(t.name, t.artist);
      if (imgUrl) t.image = imgUrl;
    }));
  }
}

/**
 * Pre-warm the browser image cache for all tracks that have art.
 * Called fire-and-forget after enrichTracksWithArt() resolves.
 * On subsequent renders the images load instantly from the browser cache.
 */
function _preloadImages(tracks) {
  if (!tracks?.length) return;
  tracks.forEach(t => {
    if (t.image && t.image.trim()) {
      const img = new Image();
      img.src   = t.image;
    }
  });
}

function shuffleArray(arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}

// ── Export helpers ────────────────────────────────────────────
function triggerSave(filename, content, mimeType) {
  Platform.saveFile(filename, content, mimeType);
}
// ── YouTube / Last.fm openers ─────────────────────────────────

/**
 * Open a YouTube search for a track.
 * Works on Android (via Platform.openBrowser) and in browser.
 */
function openTrackOnYouTube(trackName, artistName) {
  const q   = encodeURIComponent(`${trackName} ${artistName}`);
  const url = `https://www.youtube.com/results?search_query=${q}`;
  Platform.openBrowser(url);
}


/** Open the artist page on Last.fm */
function viewArtistOnLastFm(artistName) {
  const url = `https://www.last.fm/music/${encodeURIComponent(artistName)}`;
  openUrl(url);
}

/** Open the track page on Last.fm */
function openTrackOnLastFm(trackName, artistName) {
  const url = `https://www.last.fm/music/${encodeURIComponent(artistName)}/_/${encodeURIComponent(trackName)}`;
  openUrl(url);
}

// ── Start Mix from Track ──────────────────────────────────────

/**
 * Generate a 20-30 track mix seeded from the given track.
 * Uses similar tracks + artist top tracks, deduplicates, sorts for variety.
 */
async function startMixFromTrack(trackName, artistName) {
  if (!state.apiKey)   { showToast('Add your API key in Settings', 'error'); return; }
  if (!trackName)      { showToast('No track selected', 'error'); return; }

  const MIX_SIZE = 25;

  // Navigate to results screen and show loading
  await navigateTo('results');
  state.playlistTitle    = _generateSmartPlaylistName('start-mix', { seedTrackName: trackName, seedArtistName: artistName });
  state.playlistSubtitle = _generatePlaylistSubtitle('start-mix', { seedTrackName: trackName });
  state.selectedMode     = 'start-mix';
  _startLoadingCycle(`Building mix from "${trackName}"…`);
  showLoading(true);

  try {
    const pool = [];

    // 1. Similar tracks (highest relevance)
    try {
      setLoadingText(`Finding tracks similar to "${trackName}"…`);
      const d = await lfmCall({ method: 'track.getsimilar', track: trackName, artist: artistName, limit: 80 });
      normaliseTracks(d.similartracks?.track).forEach(t => pool.push({ track: t, weight: 3 }));
    } catch {}

    // 2. Artist's own top tracks (same artist, medium priority)
    try {
      setLoadingText(`Loading top tracks by ${artistName}…`);
      const d = await lfmCall({ method: 'artist.gettoptracks', artist: artistName, limit: 30 });
      normaliseTracks(d.toptracks?.track).forEach(t => pool.push({ track: t, weight: 2 }));
    } catch {}

    // 3. Similar artists' top tracks (discovery layer)
    try {
      setLoadingText(`Exploring artists like ${artistName}…`);
      const d       = await lfmCall({ method: 'artist.getsimilar', artist: artistName, limit: 12 });
      const simPool = shuffleArray(d.similarartists?.artist || []).slice(0, 4);
      for (const sa of simPool) {
        try {
          const d2 = await lfmCall({ method: 'artist.gettoptracks', artist: sa.name, limit: 8 });
          normaliseTracks(d2.toptracks?.track).forEach(t => pool.push({ track: t, weight: 1 }));
        } catch {}
      }
    } catch {}

    // Deduplicate — keep highest-weight copy; exclude the seed track itself
    const seedKey  = `${trackName}|${artistName}`.toLowerCase();
    const seenMap  = new Map();
    const trackMap = new Map();
    for (const { track, weight } of pool) {
      if (!track?.name || !track?.artist) continue;
      const k = `${track.name}|${track.artist}`.toLowerCase();
      if (k === seedKey) continue;
      if (!seenMap.has(k) || weight > seenMap.get(k)) {
        seenMap.set(k, weight);
        trackMap.set(k, track);
      }
    }

    // Sort: weight 3 → 2 → 1, shuffle within each tier
    const sorted = [3, 2, 1].flatMap(w =>
      shuffleArray([...seenMap.entries()]
        .filter(([, wt]) => wt === w)
        .map(([k]) => trackMap.get(k)))
    );

    // Artist cap: max 3 per artist for variety
    const artistCount = {};
    const diverse = sorted.filter(t => {
      const k = (t.artist || '').toLowerCase();
      artistCount[k] = (artistCount[k] || 0) + 1;
      return artistCount[k] <= 3;
    });

    // Relax artist cap progressively so we always reach MIX_SIZE
    let usePool = diverse;
    if (usePool.length < MIX_SIZE) {
      const ac2 = {};
      usePool = sorted.filter(t => {
        const k = (t.artist || '').toLowerCase();
        ac2[k] = (ac2[k] || 0) + 1;
        return ac2[k] <= 6;
      });
    }
    if (usePool.length < MIX_SIZE) usePool = sorted;

    // Prefer unseen tracks
    const fresh = _filterFresh(usePool);
    let result  = (fresh.length >= Math.min(MIX_SIZE, 8) ? fresh : usePool).slice(0, MIX_SIZE);

    // Pad to exactly MIX_SIZE if the pool was small
    if (result.length < MIX_SIZE) {
      const usedKeys = new Set(result.map(t => `${t.name}|${t.artist}`.toLowerCase()));
      const padding  = usePool.filter(t => !usedKeys.has(`${t.name}|${t.artist}`.toLowerCase()));
      result = [...result, ...padding].slice(0, MIX_SIZE);
    }

    // Enrich with artwork
    setLoadingText('Loading album artwork…');
    await enrichTracksWithArt(result);
    _preloadImages(result);   // warm browser image cache

    state.playlist = result;
    _markAsSeen(result);
    _stopLoadingCycle();
    showLoading(false);
    renderResults();
    showToast(`Mix ready — ${result.length} tracks`, 'success');

  } catch (e) {
    _stopLoadingCycle();
    showLoading(false);
    showToast(e.message || 'Failed to generate mix', 'error');
    showResultsEmpty();
  }
}

// ══════════════════════════════════════════════════════════════
//  DELETE SCROBBLE
//  Uses track.unlove (authenticated signed call).
//  Requires sessionKey obtained via startLastFmAuth().
//
//  Signed POST — params sorted alphabetically for api_sig:
//    api_key{KEY}artist{ARTIST}methodtrack.scrobble.deletesk{SK}timestamp{TS}track{TRACK}{SECRET}
// ══════════════════════════════════════════════════════════════
async function _lfmDeleteScrobble(trackName, artistName, tsMs) {
  if (!state.apiKey || !state.apiSecret) {
    showToast('API credentials required — go to Settings', 'error');
    return false;
  }

  if (!state.sessionKey) {
    // No OAuth session — prompt user to authorize; do not spam error toasts
    showModal(
      'Authorization Required',
      'Deleting scrobbles requires LastWave to be authorized with your Last.fm account. Tap Authorize to open the Last.fm login page, then return to the app and try again.',
      () => startLastFmAuth()
    );
    // Relabel the confirm button so the action is unambiguous
    const btn = document.getElementById('modalConfirm');
    if (btn) btn.textContent = 'Authorize';
    return false;
  }

  if (!tsMs) {
    showToast('Cannot delete — scrobble has no timestamp', 'error');
    return false;
  }

  // Last.fm requires timestamp in whole seconds (not milliseconds)
  const tsSec = Math.floor(tsMs / 1000);

  try {
    // lfmCallSigned builds and signs the POST automatically.
    // Params passed here (plus api_key added inside lfmCallSigned):
    //   api_key, artist, method, sk, timestamp, track  — sorted a-z
    // api_sig = md5(
    //   "api_key"   + API_KEY   +
    //   "artist"    + ARTIST    +
    //   "method"    + "track.scrobble.delete" +
    //   "sk"        + SK        +
    //   "timestamp" + TIMESTAMP +
    //   "track"     + TRACK     +
    //   API_SECRET
    // )
    await lfmCallSigned({
      method:    'track.scrobble.delete',
      artist:    artistName,
      track:     trackName,
      timestamp: tsSec,
      sk:        state.sessionKey,
    });
    showToast('Scrobble deleted \u2713', 'success');
    return true;
  } catch (e) {
    showToast(e.message || 'Could not delete scrobble', 'error');
    return false;
  }
}

// ── Accent colour + Material You ──────────────────────────────

/**
 * Apply an accent and rebuild the full M3 dark color scheme from it.
 * `mode` is optional ('manual' | 'dynamic' | 'monochrome').
 */
function applyAccent(color, light, save, mode) {
  state.accentColor = color;
  state.accentLight = light || color;
  if (mode) state.accentMode = mode;

  if (state.accentMode === 'monochrome') {
    _applyMonochromeScheme();
  } else {
    _applyMaterialYouScheme(color);
  }

  if (save) {
    localStorage.setItem('lw_accent', color);
    localStorage.setItem('lw_accentLight', light || color);
    localStorage.setItem('lw_accentMode', state.accentMode);
  }
}

/**
 * Convert hex color to HSL components.
 */
function _hexToHsl(hex) {
  if (!hex || hex.length < 7) return { h: 0, s: 0, l: 0 };
  const r = parseInt(hex.slice(1,3),16)/255;
  const g = parseInt(hex.slice(3,5),16)/255;
  const b = parseInt(hex.slice(5,7),16)/255;
  const max = Math.max(r,g,b), min = Math.min(r,g,b);
  const l = (max + min) / 2;
  let h = 0, s = 0;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
      case g: h = ((b - r) / d + 2) / 6; break;
      case b: h = ((r - g) / d + 4) / 6; break;
    }
  }
  return { h: Math.round(h * 360), s: Math.round(s * 100), l: Math.round(l * 100) };
}

/**
 * Build the complete Material You dark scheme from a source hue.
 *
 * This mirrors what Android's dynamicDarkColorScheme() does:
 *   - Primary palette:   hue = source hue,      chroma ~36
 *   - Secondary palette: hue = source hue,      chroma ~16  (desaturated)
 *   - Tertiary palette:  hue = source hue + 60°, chroma ~24 (rotated)
 *   - Neutral palette:   hue = source hue,      chroma ~6   (near-neutral surfaces)
 *   - Neutral variant:   hue = source hue,       chroma ~8
 *
 * Tones are chosen per M3 dark scheme spec:
 *   primary = tone 80, primaryContainer = tone 30, onPrimary = tone 20, etc.
 *
 * The chroma (saturation) is clamped to ~16% in CSS HSL space to keep
 * colors soft and Pixel-like — even when the source is a vivid hue.
 */
function _applyMaterialYouScheme(hex) {
  try {
    const { h } = _hexToHsl(hex);
    const R = document.documentElement;
    const amoled = document.body.classList.contains('amoled-mode');

    // Palette chroma levels (CSS HSL %, intentionally muted for M3 feel)
    const cP  = 30;   // primary chroma
    const cS  = 14;   // secondary chroma (desaturated)
    const cT  = 22;   // tertiary chroma
    const cN  =  5;   // neutral (surfaces)
    const cNV =  8;   // neutral variant

    // Tertiary hue offset: +60° following M3 hue rotation rule
    const hT = (h + 60) % 360;

    // ── Primary roles ──────────────────────────────────────────
    R.style.setProperty('--md-primary',               `hsl(${h},${cP}%,82%)`);      // tone 80
    R.style.setProperty('--md-on-primary',            `hsl(${h},${cP}%,16%)`);      // tone 20
    R.style.setProperty('--md-primary-container',     `hsl(${h},${cP}%,30%)`);      // tone 30
    R.style.setProperty('--md-on-primary-container',  `hsl(${h},${cP - 5}%,90%)`); // tone 90

    // ── Secondary roles ────────────────────────────────────────
    R.style.setProperty('--md-secondary',               `hsl(${h},${cS}%,80%)`);
    R.style.setProperty('--md-on-secondary',            `hsl(${h},${cS}%,16%)`);
    R.style.setProperty('--md-secondary-container',     `hsl(${h},${cS}%,28%)`);
    R.style.setProperty('--md-on-secondary-container',  `hsl(${h},${cS - 4}%,90%)`);

    // ── Tertiary roles ─────────────────────────────────────────
    R.style.setProperty('--md-tertiary',               `hsl(${hT},${cT}%,80%)`);
    R.style.setProperty('--md-on-tertiary',            `hsl(${hT},${cT}%,16%)`);
    R.style.setProperty('--md-tertiary-container',     `hsl(${hT},${cT}%,28%)`);
    R.style.setProperty('--md-on-tertiary-container',  `hsl(${hT},${cT - 6}%,90%)`);

    // ── Error roles (always red-family, fixed) ─────────────────
    R.style.setProperty('--md-error',               `hsl(0,45%,80%)`);
    R.style.setProperty('--md-error-container',     `hsl(0,30%,25%)`);
    R.style.setProperty('--md-on-error-container',  `hsl(0,25%,88%)`);

    // ── Background / Surface hierarchy ────────────────────────
    // AMOLED mode: surfaces drop to pitch black; subtle tonal tint preserved
    const bgL   = amoled ? 0  : 6;
    const sc1L  = amoled ? 4  : 11;
    const sc2L  = amoled ? 8  : 16;
    const sc3L  = amoled ? 12 : 20;

    R.style.setProperty('--md-background',                   `hsl(${h},${cN}%,${bgL}%)`);
    R.style.setProperty('--md-on-background',                `hsl(${h},${cNV}%,90%)`);
    R.style.setProperty('--md-surface',                      `hsl(${h},${cN}%,${bgL}%)`);
    R.style.setProperty('--md-on-surface',                   `hsl(${h},${cNV}%,90%)`);
    R.style.setProperty('--md-surface-container',            `hsl(${h},${cN}%,${sc1L}%)`);
    R.style.setProperty('--md-surface-container-high',       `hsl(${h},${cN}%,${sc2L}%)`);
    R.style.setProperty('--md-surface-container-highest',    `hsl(${h},${cN}%,${sc3L}%)`);
    R.style.setProperty('--md-surface-variant',              `hsl(${h},${cNV}%,${sc2L + 4}%)`);
    R.style.setProperty('--md-on-surface-variant',           `hsl(${h},${cNV}%,80%)`);

    // ── Outline roles ──────────────────────────────────────────
    R.style.setProperty('--md-outline',         `hsl(${h},${cNV}%,60%)`);
    R.style.setProperty('--md-outline-variant', `hsl(${h},${cNV}%,28%)`);

    // ── Legacy bridge tokens update ────────────────────────────
    // These are used in legacy selectors that haven't been migrated yet.
    // They read from the M3 custom properties above via var() in CSS,
    // so most don't need explicit JS assignment — but we keep these for
    // any third-party references or dynamic inline styles.
    R.style.setProperty('--accent',       `hsl(${h},${cP}%,82%)`);
    R.style.setProperty('--accent-light', `hsl(${h},${cP}%,90%)`);
    R.style.setProperty('--accent-dim',   `hsla(${h},${cP}%,50%,0.18)`);
    R.style.setProperty('--accent-tonal', `hsla(${h},${cP}%,50%,0.28)`);

  } catch (e) {
    console.warn('[MaterialYou] scheme generation failed:', e);
  }
}

// Keep old name as alias so any external calls still work
function _applyTintedPalette(hex) { _applyMaterialYouScheme(hex); }

/**
 * Apply a pure grayscale (monochrome) Material You scheme.
 * All chroma is set to 0 — no hue tint anywhere.
 * Called when accentMode === 'monochrome'.
 */
function _applyMonochromeScheme() {
  try {
    const R      = document.documentElement;
    const amoled = document.body.classList.contains('amoled-mode');

    const bgL  = amoled ? 0  : 6;
    const sc1L = amoled ? 4  : 11;
    const sc2L = amoled ? 8  : 16;
    const sc3L = amoled ? 12 : 20;

    // ── Primary roles — mid gray ──────────────────────────────
    R.style.setProperty('--md-primary',               `hsl(0,0%,82%)`);
    R.style.setProperty('--md-on-primary',            `hsl(0,0%,16%)`);
    R.style.setProperty('--md-primary-container',     `hsl(0,0%,30%)`);
    R.style.setProperty('--md-on-primary-container',  `hsl(0,0%,90%)`);

    // ── Secondary roles ───────────────────────────────────────
    R.style.setProperty('--md-secondary',               `hsl(0,0%,80%)`);
    R.style.setProperty('--md-on-secondary',            `hsl(0,0%,16%)`);
    R.style.setProperty('--md-secondary-container',     `hsl(0,0%,28%)`);
    R.style.setProperty('--md-on-secondary-container',  `hsl(0,0%,90%)`);

    // ── Tertiary roles ────────────────────────────────────────
    R.style.setProperty('--md-tertiary',               `hsl(0,0%,75%)`);
    R.style.setProperty('--md-on-tertiary',            `hsl(0,0%,16%)`);
    R.style.setProperty('--md-tertiary-container',     `hsl(0,0%,26%)`);
    R.style.setProperty('--md-on-tertiary-container',  `hsl(0,0%,90%)`);

    // ── Error roles — always red, fixed ───────────────────────
    R.style.setProperty('--md-error',               `hsl(0,45%,80%)`);
    R.style.setProperty('--md-error-container',     `hsl(0,30%,25%)`);
    R.style.setProperty('--md-on-error-container',  `hsl(0,25%,88%)`);

    // ── Background / Surface hierarchy — pure gray ────────────
    R.style.setProperty('--md-background',                   `hsl(0,0%,${bgL}%)`);
    R.style.setProperty('--md-on-background',                `hsl(0,0%,90%)`);
    R.style.setProperty('--md-surface',                      `hsl(0,0%,${bgL}%)`);
    R.style.setProperty('--md-on-surface',                   `hsl(0,0%,90%)`);
    R.style.setProperty('--md-surface-container',            `hsl(0,0%,${sc1L}%)`);
    R.style.setProperty('--md-surface-container-high',       `hsl(0,0%,${sc2L}%)`);
    R.style.setProperty('--md-surface-container-highest',    `hsl(0,0%,${sc3L}%)`);
    R.style.setProperty('--md-surface-variant',              `hsl(0,0%,${sc2L + 4}%)`);
    R.style.setProperty('--md-on-surface-variant',           `hsl(0,0%,80%)`);

    // ── Outline roles — pure gray ─────────────────────────────
    R.style.setProperty('--md-outline',         `hsl(0,0%,60%)`);
    R.style.setProperty('--md-outline-variant', `hsl(0,0%,28%)`);

    // ── Legacy bridge tokens ──────────────────────────────────
    R.style.setProperty('--accent',       `hsl(0,0%,82%)`);
    R.style.setProperty('--accent-light', `hsl(0,0%,90%)`);
    R.style.setProperty('--accent-dim',   `hsla(0,0%,50%,0.18)`);
    R.style.setProperty('--accent-tonal', `hsla(0,0%,50%,0.28)`);

  } catch (e) {
    console.warn('[Monochrome] scheme generation failed:', e);
  }
}

function applyDynamicAccent(save) {
  if (!state.wallpaperColors) {
    showToast('Dynamic color not available on this device', 'error');
    return false;
  }
  // wallpaperColors.primary is the Monet/system seed color from Android.
  // We pass it as both primary and light; the full palette is derived from hue.
  applyAccent(state.wallpaperColors.primary, state.wallpaperColors.secondary || state.wallpaperColors.primary, save, 'dynamic');
  return true;
}

// ── Open URL ──────────────────────────────────────────────────
function openUrl(url) { Platform.openBrowser(url); }

// ── Modal ─────────────────────────────────────────────────────
let _modalCallback = null;
function showModal(title, body, onConfirm) {
  document.getElementById('modalTitle').textContent = title;
  document.getElementById('modalBody').textContent  = body;
  _modalCallback = onConfirm;
  document.getElementById('modal').classList.remove('hidden');
  document.getElementById('modalConfirm').onclick = () => { if (_modalCallback) _modalCallback(); closeModal(); };
}
function closeModal() { document.getElementById('modal').classList.add('hidden'); _modalCallback = null; }

// ── Toast ─────────────────────────────────────────────────────
let _toastTimer = null;
function showToast(msg, type) {
  const el = document.getElementById('toast');
  el.textContent = msg; el.className = 'toast show' + (type ? ' ' + type : '');
  if (_toastTimer) clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => el.classList.remove('show'), 3000);
}

// ── Helpers ───────────────────────────────────────────────────
function esc(str) { if (!str) return ''; return String(str).replace(/&/g,'&').replace(/</g,'<').replace(/>/g,'>').replace(/"/g,'&quot;'); }
function escAttr(str) { if (!str) return ''; return String(str).replace(/'/g,"\\'").replace(/"/g,'\\"'); }
// ══════════════════════════════════════════════════════════════
//  GLOBAL LONG-PRESS COPY UTILITY
//  Shared by Home and Playlist screens.
//  Binds a 500ms hold gesture on any element with
//  data-lp-name / data-lp-artist attributes.
//  Copies "Song — Artist" to clipboard; shows "Copied" toast.
//  Normal tap is NOT affected.
// ══════════════════════════════════════════════════════════════

/**
 * Bind long-press copy to all matching items inside `container`.
 * @param {Element} container      — the wrapper element to query inside
 * @param {string}  selector       — CSS selector for track items (default: '[data-lp-name]')
 * @param {string}  copiedMessage  — toast text on success (default: 'Copied')
 */
function bindLongPressCopy(container, selector, copiedMessage) {
  if (!container) return;
  const SEL      = selector || '[data-lp-name]';
  const LONG_MS  = 500;
  const MSG      = copiedMessage || 'Copied';

  container.querySelectorAll(SEL).forEach(item => {
    if (item._lpBound) return;
    item._lpBound = true;

    let _timer     = null;
    let _startX    = 0;
    let _startY    = 0;
    let _triggered = false;

    const cancel = () => { clearTimeout(_timer); _timer = null; };

    item.addEventListener('touchstart', (e) => {
      _triggered = false;
      _startX = e.touches[0].clientX;
      _startY = e.touches[0].clientY;
      cancel();
      _timer = setTimeout(() => {
        _triggered = true;
        const name   = item.dataset.lpName   || '';
        const artist = item.dataset.lpArtist || '';
        const text   = artist ? `${name} — ${artist}` : name;
        if (navigator.clipboard?.writeText) {
          navigator.clipboard.writeText(text)
            .then(() => showToast(MSG, 'success'))
            .catch(() => _lpFallbackCopy(text, MSG));
        } else {
          _lpFallbackCopy(text, MSG);
        }
        try { navigator.vibrate?.(30); } catch {}
      }, LONG_MS);
    }, { passive: true });

    item.addEventListener('touchmove', (e) => {
      const dx = e.touches[0].clientX - _startX;
      const dy = e.touches[0].clientY - _startY;
      if (Math.hypot(dx, dy) > 8) cancel();
    }, { passive: true });

    item.addEventListener('touchend',    cancel, { passive: true });
    item.addEventListener('touchcancel', cancel, { passive: true });

    // Block tap-click from firing after a successful long-press
    item.addEventListener('click', (e) => {
      if (_triggered) { e.stopPropagation(); e.preventDefault(); _triggered = false; }
    });
  });
}

/** Clipboard fallback for older Android WebViews without navigator.clipboard */
function _lpFallbackCopy(text, copiedMessage) {
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;pointer-events:none';
    document.body.appendChild(ta);
    ta.focus(); ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    showToast(copiedMessage || 'Copied', 'success');
  } catch { showToast('Could not copy', 'error'); }
}
function sanitizeFilename(name) { return name.replace(/[^a-z0-9\-_\s]/gi,'').replace(/\s+/g,'_').substring(0,60)||'playlist'; }
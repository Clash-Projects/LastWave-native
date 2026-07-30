/* ════════════════════════════════════════════════════════════
   nav.js — Navigation
   Loads each screen once, then shows/hides — never re-renders.
   Results are transparently redirected to the Playlist screen.
   ════════════════════════════════════════════════════════════ */

'use strict';

// Bump this whenever ANY screen asset changes — CSS, HTML, or JS — so the
// WebView's disk cache (which persists across app reinstalls) never serves
// a stale file. Previously only applied to CSS; HTML/JS had no cache-busting
// at all, which could silently keep old generation logic running after a
// rebuild even though the source file on disk was already fixed.
const BUILD_VERSION = '9';

const PAGE_TITLES = {
  home:      'LastWave',
  generator: 'Create',
  playlist:  'Playlist',
  settings:  'Settings',
  genres:    'Your Genres',
  search:    'Search',
  discover:  'Discover',
};

/*
 * Per-screen enter animation classes.
 * 'screen-in'          → default fade + translateY (home, playlist, settings)
 * 'screen-slide-right' → slideInHorizontally from right (generator, genres)
 * Each class has a matching @keyframes in app.css.
 */
const PAGE_TRANSITIONS = {
  home:      'screen-in',
  generator: 'screen-slide-right',
  playlist:  'screen-in',
  settings:  'screen-in',
  genres:    'screen-slide-right',
  search:    'screen-slide-right',
  discover:  'screen-slide-right',
};

// 'results' is an internal alias — always loads the playlist screen
// so renderResults() / showLoading() / showResultsEmpty() live there.
const PAGE_ALIAS = {
  results: 'playlist'
};

const _loadedScreens = new Set();

// ── Navigation history stack ──────────────────────────────────────
// Tracks screen visits so the hardware back button can unwind correctly.
// 'home' is the implicit bottom — going back from an empty stack exits.
let _navHistory = [];

// Fetch an asset with a hard timeout so a slow/unreachable server never
// blocks navigation. Resolves with the Response or rejects on timeout.
async function _fetchWithTimeout(url, timeoutMs = 8000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timer);
    return res;
  } catch (e) {
    clearTimeout(timer);
    if (e.name === 'AbortError') throw new Error(`Asset fetch timed out: ${url}`);
    throw e;
  }
}

async function navigateTo(page, opts) {
  // Resolve alias: 'results' → 'playlist'
  page = PAGE_ALIAS[page] || page;
  
  const container = document.getElementById('screen-container');
  
  // 1. Load screen assets once (with timeouts — never hangs)
  if (!_loadedScreens.has(page)) {
    
    if (!document.querySelector(`link[data-screen="${page}"]`)) {
      await new Promise(resolve => {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = `${page}/${page}.css?v=${BUILD_VERSION}`;
        link.dataset.screen = page;
        link.onload  = resolve;
        link.onerror = resolve; // CSS failure is non-fatal
        document.head.appendChild(link);
        // CSS load timeout — don't block nav if stylesheet stalls
        setTimeout(resolve, 5000);
      });
    }
    
    let htmlText = '';
    try {
      const res = await _fetchWithTimeout(`${page}/${page}.html?v=${BUILD_VERSION}`, 8000);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      htmlText = await res.text();
    } catch (e) {
      // If the HTML can't load, insert a minimal error placeholder so the
      // app shell still shows a usable screen rather than an empty container.
      console.error(`[nav] Failed to load ${page}.html:`, e.message);
      htmlText = `<div style="padding:40px 24px;text-align:center;color:var(--text2,#888)">
        <p style="font-size:16px;font-weight:600">Could not load this screen</p>
        <p style="font-size:13px;margin-top:8px">Check your connection and try again</p>
        <button onclick="navigateTo('${page}')"
          style="margin-top:16px;padding:10px 20px;border-radius:100px;border:none;
                 background:var(--accent,#E03030);color:#fff;font-size:14px;font-weight:700;cursor:pointer">
          Retry
        </button>
      </div>`;
    }

    const wrapper = document.createElement('div');
    wrapper.dataset.screen = page;
    wrapper.style.cssText = 'position:absolute;inset:0;display:none;overflow-x:hidden;overflow-y:auto;-webkit-overflow-scrolling:touch;overscroll-behavior-y:contain;scroll-behavior:auto;';
    wrapper.innerHTML = htmlText;
    container.appendChild(wrapper);
    
    if (!document.querySelector(`script[data-screen="${page}"]`)) {
      await new Promise(resolve => {
        const script = document.createElement('script');
        script.src = `${page}/${page}.js?v=${BUILD_VERSION}`;
        script.dataset.screen = page;
        script.onload  = resolve;
        script.onerror = () => { console.error(`[nav] Failed to load ${page}.js`); resolve(); };
        document.body.appendChild(script);
        // JS load timeout — don't block nav if script stalls
        setTimeout(resolve, 8000);
      });
    }
    
    _loadedScreens.add(page);
  }
  
  // 2. Skip if already on this page
  if (state.currentPage === page) return;

  // 3. Record history — skipped for back-navigation so we don't re-push
  //    the page we just popped. Also skip on the very first load (no currentPage).
  //    Root tabs (home / generator / playlist) don't push other root tabs onto
  //    the back stack — switching between them always exits on back press.
  const _ROOT_TABS = new Set(['home', 'generator', 'playlist']);
  if (state.currentPage && !(opts && opts.isBack)) {
    if (_ROOT_TABS.has(page) && _ROOT_TABS.has(state.currentPage)) {
      // Tab → tab transition: clear the stack so back exits the app
      _navHistory.length = 0;
    } else {
      _navHistory.push(state.currentPage);
    }
  }
  
  // 3. Hide current screen
  if (state.currentPage) {
    const current = container.querySelector(`[data-screen="${state.currentPage}"]`);
    if (current) current.style.display = 'none';
  }
  
  // 4. Show new screen with per-screen slide/fade animation
  const next = container.querySelector(`[data-screen="${page}"]`);
  const transClass = PAGE_TRANSITIONS[page] || 'screen-in';
  next.style.display = 'block';
  next.classList.remove('screen-in', 'screen-slide-right');
  next.offsetHeight; // force reflow so animation restarts cleanly
  next.classList.add(transClass);
  
  // 5. Update topbar title
  document.getElementById('pageTitle').textContent = PAGE_TITLES[page] || 'LastWave';
  
  // 6. Update nav active state
  document.querySelectorAll('.nav-item').forEach(n => {
    n.classList.toggle('active', n.dataset.page === page);
  });

  // Toggle settings-open — controls bottom nav / search / avatar visibility
  document.body.classList.toggle('settings-open', page === 'settings');

  // Toggle discover-open — controls bottom nav visibility/animation on Discover
  // (CSS lives in discover/discover.css, next to the rest of the screen's styles)
  document.body.classList.toggle('discover-open', page === 'discover');

  state.currentPage = page;
  
  // 7. Call screen init — fire-and-forget so a crashing/slow screen init
  //    never prevents navigateTo from resolving.
  if (typeof window['screen_' + page] === 'function') {
    try {
      const result = window['screen_' + page]();
      // If the init returns a Promise, swallow any rejection — a data-load
      // error must never crash the navigation layer.
      if (result && typeof result.catch === 'function') result.catch(() => {});
    } catch (e) {
      console.error(`[nav] screen_${page} threw:`, e);
    }
  }
}

// ── Material You nav press highlight ─────────────────────────
/**
 * Places press feedback inside .nav-indicator — the pill element that
 * wraps the icon. Because .nav-indicator has overflow:hidden, the
 * highlight is automatically clipped to the pill shape. No size math,
 * no position offsets needed. The 3-state machine keeps it visible
 * for the full duration of a hold, then fades on release.
 *
 *   touchstart/mousedown  → .entering (120ms fade-in)
 *   after 120ms           → .holding  (stays visible)
 *   touchend/mouseup/etc  → .leaving  (280ms fade-out, then removed)
 */
function _initNavRipples() {
  document.querySelectorAll('.nav-item').forEach(btn => {
    if (btn._rippleBound) return;
    btn._rippleBound = true;

    // Ripple lives inside the pill, not the full button
    const indicator = btn.querySelector('.nav-indicator');
    if (!indicator) return;

    let _ripple    = null;
    let _holdTimer = null;

    function _startPress() {
      _endPress(true); // clean up any in-flight highlight

      const el = document.createElement('span');
      el.className = 'nav-ripple entering';
      indicator.appendChild(el);
      _ripple = el;

      // Switch to holding state after enter animation completes
      _holdTimer = setTimeout(() => {
        if (_ripple === el) el.className = 'nav-ripple holding';
      }, 120);
    }

    function _endPress(immediate) {
      clearTimeout(_holdTimer);
      _holdTimer = null;
      if (!_ripple) return;

      const el = _ripple;
      _ripple = null;

      if (immediate) {
        el.remove();
        return;
      }

      el.className = 'nav-ripple leaving';
      el.addEventListener('animationend', () => el.remove(), { once: true });
      // Safety timeout in case animationend doesn't fire (hidden tab, etc.)
      setTimeout(() => { if (el.parentNode) el.remove(); }, 400);
    }

    btn.addEventListener('touchstart',  () => _startPress(),   { passive: true });
    btn.addEventListener('touchend',    () => _endPress(false), { passive: true });
    btn.addEventListener('touchcancel', () => _endPress(false), { passive: true });
    btn.addEventListener('mousedown',   () => _startPress());
    btn.addEventListener('mouseup',     () => _endPress(false));
    btn.addEventListener('mouseleave',  () => _endPress(false));
  });
}

// Initialise ripples as soon as the DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', _initNavRipples);
} else {
  // DOM already ready — defer one tick so nav buttons are in the DOM
  setTimeout(_initNavRipples, 0);
}

/* ══════════════════════════════════════════════════════════════
   _initRipples(root) — reusable Material ripple for any screen.

   Usage (call from any screen's init function):
     _initRipples(document.querySelector('[data-screen="generator"]'));

   Mark each tappable element with class "ripple-item".
   CSS lives in app.css (.ripple-item, .m-ripple keyframes).
   ══════════════════════════════════════════════════════════════ */
function _initRipples(root) {
  if (!root) return;
  root.querySelectorAll('.ripple-item').forEach(el => {
    if (el._rippleBound) return;
    el._rippleBound = true;

    let _r = null, _t = null;

    function _start() {
      _end(true);
      const span = document.createElement('span');
      span.className = 'm-ripple entering';
      el.appendChild(span);
      _r = span;
      _t = setTimeout(() => { if (_r === span) span.className = 'm-ripple holding'; }, 120);
    }

    function _end(immediate) {
      clearTimeout(_t); _t = null;
      if (!_r) return;
      const span = _r; _r = null;
      if (immediate) { span.remove(); return; }
      span.className = 'm-ripple leaving';
      span.addEventListener('animationend', () => span.remove(), { once: true });
      setTimeout(() => { if (span.parentNode) span.remove(); }, 400);
    }

    el.addEventListener('touchstart',  _start,        { passive: true });
    el.addEventListener('touchend',    () => _end(false), { passive: true });
    el.addEventListener('touchcancel', () => _end(false), { passive: true });
    el.addEventListener('mousedown',   _start);
    el.addEventListener('mouseup',     () => _end(false));
    el.addEventListener('mouseleave',  () => _end(false));
  });
}
// ══════════════════════════════════════════════════════════════
//  Hardware back-button handler — called by MainActivity.java
//  via evaluateJavascript("window._lwHandleBack()").
//
//  Returns true  → JS handled it (native does nothing).
//  Returns false → nothing to go back to (native should exit).
// ══════════════════════════════════════════════════════════════
window._lwHandleBack = function() {
  // 1. Let the current screen intercept first.
  //    Screens register via window._lwScreenBackHandlers[pageName] = fn.
  //    Example: genres.js closes its detail view before letting nav pop.
  const screenHandlers = window._lwScreenBackHandlers;
  if (screenHandlers && typeof screenHandlers[state.currentPage] === 'function') {
    if (screenHandlers[state.currentPage]()) return true;
  }

  // 2. Pop the history stack and navigate back.
  if (_navHistory.length > 0) {
    const prev = _navHistory.pop();
    navigateTo(prev, { isBack: true });
    return true;
  }

  // 3. Stack is empty — signal native to exit.
  return false;
};

// Initialise the screen back-handler registry so screens can register
// without having to guard for its existence themselves.
window._lwScreenBackHandlers = window._lwScreenBackHandlers || {};

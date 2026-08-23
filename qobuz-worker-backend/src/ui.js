/**
 * Production Developer API Playground & Lossless Audio Console
 * Optimized for Mobile, Tablet, and Desktop with 100% professional Vercel / Linear aesthetic.
 */

export function renderDashboardHtml(workerUrl = "") {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Qobuz Engine • API Console</title>
  <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 180 180' fill='%23000000'%3E%3Cmask height='180' id='mask0' maskUnits='userSpaceOnUse' width='180' x='0' y='0'%3E%3Ccircle cx='90' cy='90' fill='%23000000' r='90'/%3E%3C/mask%3E%3Cg mask='url(%23mask0)'%3E%3Ccircle cx='90' cy='90' fill='%23000000' r='90' stroke='%23ffffff' stroke-width='6'/%3E%3Cpath d='M149.508 157.52L69.142 54H54V125.97H66.1136V69.3836L139.999 164.845C143.333 162.614 146.509 160.165 149.508 157.52Z' fill='%23ffffff'/%3E%3Crect fill='%23ffffff' height='72' width='12' x='115' y='54'/%3E%3C/g%3E%3C/svg%3E">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #000000;
      --card: #0a0a0a;
      --card-hover: #111111;
      --border: #1f1f1f;
      --border-subtle: #141414;
      --text: #f5f5f5;
      --text-muted: #888888;
      --text-dim: #444444;
      --accent: #ffffff;
      --cyan: #00e5ff;
      --emerald: #00e676;
      --gold: #f5a623;
      --radius: 6px;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif; -webkit-tap-highlight-color: transparent; }

    body {
      background: var(--bg);
      color: var(--text);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      -webkit-font-smoothing: antialiased;
      overflow-x: hidden;
    }

    /* Ambient Subtle Grid */
    .bg-grid {
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background-image: 
        linear-gradient(to right, rgba(255,255,255,0.015) 1px, transparent 1px),
        linear-gradient(to bottom, rgba(255,255,255,0.015) 1px, transparent 1px);
      background-size: 32px 32px;
      pointer-events: none;
      z-index: 0;
    }

    .container {
      position: relative;
      z-index: 1;
      width: 100%;
      max-width: 1180px;
      margin: 0 auto;
      padding: 0 16px 64px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    /* SVG Icon Helpers */
    .icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 14px;
      height: 14px;
      stroke-width: 2;
      stroke: currentColor;
      fill: none;
      stroke-linecap: round;
      stroke-linejoin: round;
      flex-shrink: 0;
    }

    /* Top Navbar */
    nav {
      position: sticky;
      top: 0;
      z-index: 50;
      background: rgba(0, 0, 0, 0.88);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--border);
      width: 100%;
    }
    .nav-inner {
      max-width: 1180px;
      margin: 0 auto;
      padding: 12px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
      text-decoration: none;
      color: inherit;
    }
    .brand-svg {
      width: 20px;
      height: 20px;
      fill: #fff;
    }
    .brand-title {
      font-size: 14px;
      font-weight: 700;
      letter-spacing: -0.3px;
      color: #fff;
    }
    .brand-pill {
      font-size: 10px;
      font-family: 'JetBrains Mono', monospace;
      padding: 2px 6px;
      background: #111;
      border: 1px solid var(--border);
      border-radius: 4px;
      color: var(--cyan);
      font-weight: 500;
    }
    .nav-links {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .nav-link {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-muted);
      text-decoration: none;
      padding: 6px 12px;
      border-radius: var(--radius);
      transition: all 0.15s;
    }
    .nav-link:hover { color: #fff; background: rgba(255,255,255,0.05); }
    .nav-link.active { color: #fff; background: #141414; border: 1px solid var(--border); }

    /* Main Console Grid */
    .console-layout {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }

    .card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .card-head {
      padding: 12px 16px;
      border-bottom: 1px solid var(--border);
      background: #050505;
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 12px;
      font-weight: 600;
    }
    .card-body {
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    /* Form Fields */
    .field {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .field-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .input-bar {
      display: flex;
      gap: 6px;
    }
    .method-badge {
      padding: 0 10px;
      height: 38px;
      background: rgba(0, 112, 243, 0.1);
      border: 1px solid rgba(0, 112, 243, 0.3);
      color: #3894ff;
      border-radius: var(--radius);
      font-size: 11px;
      font-family: 'JetBrains Mono', monospace;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .input-code {
      flex: 1;
      background: #000;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 0 12px;
      height: 38px;
      color: var(--text);
      font-size: 13px;
      outline: none;
      font-family: 'JetBrains Mono', monospace;
      min-width: 0;
      transition: border-color 0.15s;
    }
    .input-code:focus { border-color: #555; }

    .quality-selector {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 6px;
    }
    .q-btn {
      padding: 8px 6px;
      background: #000;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      color: var(--text-muted);
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      text-align: center;
      transition: all 0.15s;
    }
    .q-btn.active {
      background: #141414;
      border-color: #fff;
      color: #fff;
    }
    .q-btn span {
      display: block;
      font-size: 9px;
      color: var(--text-dim);
      font-family: 'JetBrains Mono', monospace;
      margin-top: 2px;
    }
    .q-btn.active span { color: var(--cyan); }

    .btn-exec {
      height: 38px;
      background: #fff;
      color: #000;
      border: none;
      border-radius: var(--radius);
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      transition: background 0.15s;
    }
    .btn-exec:hover { background: #e0e0e0; }

    /* Tabs & Code Display */
    .tab-row {
      display: flex;
      gap: 12px;
    }
    .tab-btn {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-muted);
      cursor: pointer;
      padding-bottom: 2px;
      border-bottom: 2px solid transparent;
      transition: color 0.15s;
    }
    .tab-btn.active { color: #fff; border-bottom-color: #fff; }

    .code-display {
      background: #000;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 12px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      color: #d4d4d4;
      max-height: 340px;
      overflow-y: auto;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-all;
    }

    /* Lossless Audio Player Component */
    .player-box {
      background: #000;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .player-meta-row {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .player-art {
      width: 48px;
      height: 48px;
      border-radius: 4px;
      object-fit: cover;
      border: 1px solid var(--border);
      background: #111;
      flex-shrink: 0;
    }
    .player-info {
      flex: 1;
      min-width: 0;
    }
    .player-song-title {
      font-size: 13px;
      font-weight: 700;
      color: #fff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .player-artist {
      font-size: 12px;
      color: var(--text-muted);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .player-badge {
      font-size: 10px;
      font-family: 'JetBrains Mono', monospace;
      color: var(--cyan);
      display: inline-block;
      margin-top: 2px;
    }

    audio {
      width: 100%;
      height: 32px;
      outline: none;
      filter: invert(100%) hue-rotate(180deg);
    }

    .btn-row {
      display: flex;
      gap: 6px;
    }
    .btn-outline {
      flex: 1;
      height: 32px;
      background: transparent;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      color: var(--text-muted);
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      text-decoration: none;
      transition: all 0.15s;
    }
    .btn-outline:hover { color: #fff; border-color: #666; background: #111; }

    /* Results Table / List */
    .feed-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .feed-row {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 10px 14px;
      display: flex;
      align-items: center;
      gap: 12px;
      transition: border-color 0.15s;
    }
    .feed-row:hover { border-color: #333; }
    .feed-art {
      width: 42px;
      height: 42px;
      border-radius: 4px;
      object-fit: cover;
      border: 1px solid var(--border);
      flex-shrink: 0;
    }
    .feed-meta {
      flex: 1;
      min-width: 0;
    }
    .feed-title {
      font-size: 13px;
      font-weight: 600;
      color: #fff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .feed-sub {
      font-size: 11px;
      color: var(--text-muted);
      display: flex;
      align-items: center;
      gap: 6px;
      margin-top: 2px;
    }
    .badge-quality {
      font-size: 9px;
      font-family: 'JetBrains Mono', monospace;
      padding: 1px 4px;
      border-radius: 3px;
      background: rgba(0, 229, 255, 0.1);
      border: 1px solid rgba(0, 229, 255, 0.25);
      color: var(--cyan);
      font-weight: 600;
    }
    .badge-hires {
      background: rgba(245, 166, 35, 0.1);
      border: 1px solid rgba(245, 166, 35, 0.3);
      color: var(--gold);
    }
    .btn-play-trigger {
      padding: 6px 12px;
      border-radius: var(--radius);
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid var(--border);
      color: #fff;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 4px;
      transition: background 0.15s;
      flex-shrink: 0;
    }
    .btn-play-trigger:hover { background: #fff; color: #000; }

    /* Modal */
    .modal-backdrop {
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0,0,0,0.8);
      backdrop-filter: blur(8px);
      display: none;
      align-items: center;
      justify-content: center;
      z-index: 100;
      padding: 16px;
    }
    .modal-backdrop.open { display: flex; }
    .modal-window {
      background: #0a0a0a;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      max-width: 680px;
      width: 100%;
      max-height: 80vh;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .modal-top {
      padding: 14px 18px;
      border-bottom: 1px solid var(--border);
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 13px;
      font-weight: 700;
    }
    .modal-body {
      padding: 18px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    /* Toast */
    .toast {
      position: fixed;
      bottom: 20px;
      right: 20px;
      background: #141414;
      border: 1px solid var(--border);
      color: #fff;
      padding: 10px 16px;
      border-radius: var(--radius);
      font-size: 12px;
      font-weight: 500;
      transform: translateY(40px);
      opacity: 0;
      pointer-events: none;
      transition: all 0.2s;
      z-index: 1000;
    }
    .toast.active { transform: translateY(0); opacity: 1; }

    /* Responsive Queries */
    @media (max-width: 768px) {
      .console-layout { grid-template-columns: 1fr; }
      .quality-selector { grid-template-columns: repeat(2, 1fr); }
      .container { padding: 0 12px 48px; gap: 14px; }
      .nav-inner { padding: 10px 12px; }
    }
  </style>
</head>
<body>
  <div class="bg-grid"></div>

  <!-- Navbar -->
  <nav>
    <div class="nav-inner">
      <a href="/" class="brand">
        <svg class="brand-svg" viewBox="0 0 180 180" xmlns="http://www.w3.org/2000/svg">
          <circle cx="90" cy="90" fill="#000000" r="90" stroke="#ffffff" stroke-width="6"/>
          <path d="M149.508 157.52L69.142 54H54V125.97H66.1136V69.3836L139.999 164.845C143.333 162.614 146.509 160.165 149.508 157.52Z" fill="#ffffff"/>
          <rect fill="#ffffff" height="72" width="12" x="115" y="54"/>
        </svg>
        <span class="brand-title">QOBUZ ENGINE</span>
        <span class="brand-pill">EDGE</span>
      </a>
      <div class="nav-links">
        <a href="/" class="nav-link active">Console</a>
        <a href="/docs" class="nav-link">Documentation</a>
      </div>
    </div>
  </nav>

  <div class="container">
    <!-- Top Console Grid -->
    <div class="console-layout">
      <!-- Request Configurator -->
      <div class="card">
        <div class="card-head">
          <span>Request Builder</span>
          <span style="font-family:'JetBrains Mono',monospace;color:var(--text-dim);font-size:11px;">HTTP GET</span>
        </div>
        <div class="card-body">
          <div class="field">
            <label class="field-label">Target Endpoint</label>
            <div class="input-bar">
              <div class="method-badge">GET</div>
              <input type="text" id="reqInput" class="input-code" value="/api/search?q=Daft+Punk+Giorgio&type=track&limit=5" />
            </div>
          </div>

          <div class="field">
            <label class="field-label">Target Audio Format</label>
            <div class="quality-selector">
              <button class="q-btn active" data-q="6">FLAC 16B<span>44.1k CD</span></button>
              <button class="q-btn" data-q="7">FLAC 24B<span>≤ 96k Hi-Res</span></button>
              <button class="q-btn" data-q="27">FLAC 24B+<span>192k Master</span></button>
              <button class="q-btn" data-q="5">MP3<span>320 kbps</span></button>
            </div>
          </div>

          <button id="btnExecute" class="btn-exec">
            <span>Execute Request</span>
            <svg class="icon" viewBox="0 0 24 24"><line x1="5" y1="12" x2="19" y2="12"></line><polyline points="12 5 19 12 12 19"></polyline></svg>
          </button>
        </div>
      </div>

      <!-- Live Inspector & Studio Player -->
      <div class="card">
        <div class="card-head">
          <div class="tab-row">
            <span class="tab-btn active" id="tabHeadJson" onclick="selectTab('json')">Response</span>
            <span class="tab-btn" id="tabHeadPlayer" onclick="selectTab('player')">Audio Player</span>
            <span class="tab-btn" id="tabHeadSdk" onclick="selectTab('sdk')">Code</span>
          </div>
          <span id="badgeLatency" style="font-family:'JetBrains Mono',monospace;color:var(--emerald);font-size:11px;">READY</span>
        </div>
        <div class="card-body">
          <!-- JSON View -->
          <div id="viewJson" class="code-display">{ "status": "Ready to execute..." }</div>

          <!-- Studio Player (Zero Autoplay) -->
          <div id="viewPlayer" style="display:none;" class="player-box">
            <div class="player-meta-row">
              <img id="artThumb" class="player-art" src="https://via.placeholder.com/100x100?text=Audio" alt="Cover" />
              <div class="player-info">
                <div id="songTitle" class="player-song-title">No Track Selected</div>
                <div id="artistName" class="player-artist">Select a track below to stream</div>
                <span id="formatStatus" class="player-badge">Akamai Edge Direct</span>
              </div>
            </div>
            <audio id="audioStream" controls></audio>
            <div class="btn-row">
              <a id="btnDl" href="#" target="_blank" class="btn-outline">
                <svg class="icon" viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
                Download
              </a>
              <button id="btnCopyCdn" class="btn-outline">
                <svg class="icon" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                Copy Akamai URL
              </button>
            </div>
          </div>

          <!-- Code Snippets View -->
          <div id="viewSdk" style="display:none;flex-direction:column;gap:8px;">
            <div style="display:flex;gap:6px;">
              <button class="btn-outline" style="flex:initial;padding:2px 8px;font-size:10px;" onclick="renderSnippet('curl')">cURL</button>
              <button class="btn-outline" style="flex:initial;padding:2px 8px;font-size:10px;" onclick="renderSnippet('js')">JS</button>
              <button class="btn-outline" style="flex:initial;padding:2px 8px;font-size:10px;" onclick="renderSnippet('kotlin')">Kotlin</button>
            </div>
            <div id="sdkCodeBox" class="code-display">curl -X GET "..."</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Catalog Feed -->
    <div class="card">
      <div class="card-head">
        <span>Catalog Results</span>
        <span id="feedCounter" style="font-family:'JetBrains Mono',monospace;color:var(--text-dim);font-size:11px;">0 items</span>
      </div>
      <div class="card-body" style="padding:10px;">
        <div id="catalogFeed" class="feed-container"></div>
      </div>
    </div>
  </div>

  <!-- Album Modal -->
  <div class="modal-backdrop" id="modalBackdrop">
    <div class="modal-window">
      <div class="modal-top">
        <span id="modalAlbumTitle">Album Tracks</span>
        <button class="btn-outline" style="flex:initial;width:28px;height:28px;padding:0;" onclick="closeModal()">✕</button>
      </div>
      <div class="modal-body" id="modalBody"></div>
    </div>
  </div>

  <div class="toast" id="toast">Notification</div>

  <script>
    let activeQuality = 6;
    let activeSignedUrl = '';
    const reqInput = document.getElementById('reqInput');
    const btnExecute = document.getElementById('btnExecute');
    const viewJson = document.getElementById('viewJson');
    const viewPlayer = document.getElementById('viewPlayer');
    const viewSdk = document.getElementById('viewSdk');
    const sdkCodeBox = document.getElementById('sdkCodeBox');
    const badgeLatency = document.getElementById('badgeLatency');
    const catalogFeed = document.getElementById('catalogFeed');
    const feedCounter = document.getElementById('feedCounter');

    const audioStream = document.getElementById('audioStream');
    const artThumb = document.getElementById('artThumb');
    const songTitle = document.getElementById('songTitle');
    const artistName = document.getElementById('artistName');
    const formatStatus = document.getElementById('formatStatus');
    const btnDl = document.getElementById('btnDl');
    const btnCopyCdn = document.getElementById('btnCopyCdn');
    const toast = document.getElementById('toast');

    document.querySelectorAll('.q-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.q-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        activeQuality = parseInt(btn.dataset.q, 10);
        renderSnippet('curl');
      });
    });

    btnExecute.addEventListener('click', runQuery);
    reqInput.addEventListener('keydown', e => { if (e.key === 'Enter') runQuery(); });

    async function runQuery() {
      let path = reqInput.value.trim();
      if (!path.startsWith('/')) path = '/' + path;

      btnExecute.disabled = true;
      badgeLatency.textContent = '...';
      badgeLatency.style.color = 'var(--gold)';

      const startTime = performance.now();
      try {
        const res = await fetch(path);
        const elapsed = Math.round(performance.now() - startTime);
        badgeLatency.textContent = \`\${res.status} OK • \${elapsed}ms\`;
        badgeLatency.style.color = res.ok ? 'var(--emerald)' : '#ff3366';

        const contentType = res.headers.get('Content-Type') || '';
        if (contentType.includes('json')) {
          const json = await res.json();
          viewJson.textContent = JSON.stringify(json, null, 2);
          renderResults(json);
          renderSnippet('curl');
        } else {
          viewJson.textContent = await res.text();
        }
      } catch (err) {
        viewJson.textContent = 'Error: ' + err.message;
        badgeLatency.textContent = 'ERROR';
        badgeLatency.style.color = '#ff3366';
      } finally {
        btnExecute.disabled = false;
      }
    }

    function renderResults(data) {
      catalogFeed.innerHTML = '';
      const items = data.results?.tracks?.items || data.results?.albums?.items || data.results?.items || [];
      feedCounter.textContent = \`\${items.length} items\`;

      if (!items.length) {
        catalogFeed.innerHTML = '<div style="text-align:center;padding:24px;color:var(--text-dim);font-size:12px;">No items returned</div>';
        return;
      }

      items.forEach(item => {
        const title = item.title || item.name || 'Untitled';
        const artist = item.performer?.name || item.artist?.name || 'Unknown';
        const art = item.album?.image?.small || item.image?.small || 'https://via.placeholder.com/80x80?text=Art';
        const isHiRes = item.hires_streamable || (item.maximum_bit_depth && item.maximum_bit_depth > 16);
        const bitDepth = item.maximum_bit_depth || 16;
        const sampleRate = item.maximum_sampling_rate || 44.1;
        const isAlbum = Boolean(item.tracks_count || item.media_count);

        const row = document.createElement('div');
        row.className = 'feed-row';
        row.innerHTML = \`
          <img src="\${art}" class="feed-art" alt="Art" loading="lazy" />
          <div class="feed-meta">
            <div class="feed-title">\${title}</div>
            <div class="feed-sub">
              <span>\${artist}</span>
              <span class="badge-quality \${isHiRes ? 'badge-hires' : ''}">\${bitDepth}B/\${sampleRate}k</span>
            </div>
          </div>
          \${!isAlbum ? \`
            <button class="btn-play-trigger btn-play-track">▶ Play</button>
            <a href="/api/download/track/\${item.id}?quality=\${activeQuality}" class="btn-outline" style="flex:initial;width:32px;height:32px;padding:0;" target="_blank">⬇</a>
          \` : \`
            <button class="btn-play-trigger btn-view-album">View</button>
          \`}
        \`;

        if (!isAlbum) {
          row.querySelector('.btn-play-track').addEventListener('click', () => {
            streamTrack(item.id, title, artist, art);
          });
        } else {
          row.querySelector('.btn-view-album').addEventListener('click', () => {
            openAlbum(item.id);
          });
        }

        catalogFeed.appendChild(row);
      });
    }

    async function streamTrack(trackId, title, artist, art) {
      selectTab('player');
      songTitle.textContent = title;
      artistName.textContent = artist;
      artThumb.src = art;
      formatStatus.textContent = 'Resolving stream...';

      try {
        const res = await fetch(\`/api/track/\${trackId}/url?quality=\${activeQuality}\`);
        const json = await res.json();
        if (json.success && json.data?.url) {
          activeSignedUrl = json.data.url;
          audioStream.src = activeSignedUrl;
          audioStream.play();
          const bitDepth = json.data.bit_depth || 16;
          const sampleRate = json.data.sampling_rate || 44.1;
          const mime = json.data.mime_type?.includes('flac') ? 'FLAC' : 'MP3';
          formatStatus.textContent = \`\${mime} \${bitDepth}B / \${sampleRate}kHz • Akamai CDN\`;
          btnDl.href = \`/api/download/track/\${trackId}?quality=\${activeQuality}\`;
          showToast(\`Streaming "\${title}"\`);
        }
      } catch (err) {
        formatStatus.textContent = 'Failed: ' + err.message;
      }
    }

    async function openAlbum(albumId) {
      const modal = document.getElementById('modalBackdrop');
      const body = document.getElementById('modalBody');
      modal.classList.add('open');
      body.innerHTML = '<div style="text-align:center;padding:24px;color:var(--text-muted);font-size:12px;">Loading album...</div>';

      try {
        const res = await fetch(\`/api/download/album/\${albumId}?quality=\${activeQuality}\`);
        const data = await res.json();
        const album = data.album;

        document.getElementById('modalAlbumTitle').textContent = album.albumTitle;
        body.innerHTML = \`
          <div style="display:flex;gap:14px;align-items:center;padding-bottom:12px;border-bottom:1px solid var(--border);">
            <img src="\${album.coverUrl}" style="width:64px;height:64px;border-radius:4px;object-fit:cover;" />
            <div style="flex:1;min-width:0;">
              <div style="font-weight:700;font-size:14px;color:#fff;">\${album.albumTitle}</div>
              <div style="font-size:12px;color:var(--text-muted);">\${album.artist} • \${album.totalTracks} Tracks</div>
              <div style="display:flex;gap:6px;margin-top:6px;">
                <a href="/api/download/m3u?type=album&id=\${albumId}" target="_blank" class="btn-outline" style="font-size:10px;padding:2px 8px;height:24px;">M3U</a>
                <a href="\${album.coverUrl}" target="_blank" class="btn-outline" style="font-size:10px;padding:2px 8px;height:24px;">Cover</a>
              </div>
            </div>
          </div>
          <div style="display:flex;flex-direction:column;gap:4px;max-height:300px;overflow-y:auto;">
            \${album.tracks.map(t => \`
              <div style="display:flex;align-items:center;justify-content:space-between;padding:8px 10px;background:#050505;border-radius:4px;font-size:12px;">
                <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:8px;">\${t.trackNumber}. \${t.title}</span>
                <button onclick="streamTrack('\${t.trackId}', '\${t.title.replace(/'/g, "")}', '\${album.artist.replace(/'/g, "")}', '\${album.coverUrl}')" class="btn-play-trigger" style="padding:3px 8px;font-size:10px;">▶</button>
              </div>
            \`).join('')}
          </div>
        \`;
      } catch (err) {
        body.innerHTML = '<div style="color:#ff3366;font-size:12px;">Failed to load album</div>';
      }
    }

    function closeModal() {
      document.getElementById('modalBackdrop').classList.remove('open');
    }

    function renderSnippet(lang) {
      const path = reqInput.value.trim();
      const fullUrl = \`\${window.location.origin}\${path.startsWith('/') ? path : '/' + path}\`;
      if (lang === 'curl') sdkCodeBox.textContent = \`curl -X GET "\${fullUrl}"\`;
      else if (lang === 'js') sdkCodeBox.textContent = \`const res = await fetch("\${fullUrl}");\nconst data = await res.json();\`;
      else if (lang === 'kotlin') sdkCodeBox.textContent = \`val req = Request.Builder().url("\${fullUrl}").build()\nclient.newCall(req).execute()\`;
    }

    btnCopyCdn.addEventListener('click', () => {
      if (!activeSignedUrl) return;
      navigator.clipboard.writeText(activeSignedUrl);
      showToast('Akamai CDN Stream URL copied');
    });

    function showToast(msg) {
      toast.textContent = msg;
      toast.classList.add('active');
      setTimeout(() => toast.classList.remove('active'), 2500);
    }

    function selectTab(t) {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      viewJson.style.display = t === 'json' ? 'block' : 'none';
      viewPlayer.style.display = t === 'player' ? 'flex' : 'none';
      viewSdk.style.display = t === 'sdk' ? 'flex' : 'none';
      if (t === 'json') document.getElementById('tabHeadJson').classList.add('active');
      if (t === 'player') document.getElementById('tabHeadPlayer').classList.add('active');
      if (t === 'sdk') {
        document.getElementById('tabHeadSdk').classList.add('active');
        renderSnippet('curl');
      }
    }

    // Initial fetch without auto-playing audio
    runQuery();
  </script>
</body>
</html>`;
}

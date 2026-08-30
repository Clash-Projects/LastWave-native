/**
 * Production In-House Private API Documentation
 * Served exclusively on GET /docs
 * Clean, responsive, developer-first documentation.
 */

export function renderDocsHtml(workerUrl = "") {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Qobuz Engine • API Documentation</title>
  <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 180 180' fill='%23000000'%3E%3Cmask height='180' id='mask0' maskUnits='userSpaceOnUse' width='180' x='0' y='0'%3E%3Ccircle cx='90' cy='90' fill='%23000000' r='90'/%3E%3C/mask%3E%3Cg mask='url(%23mask0)'%3E%3Ccircle cx='90' cy='90' fill='%23000000' r='90' stroke='%23ffffff' stroke-width='6'/%3E%3Cpath d='M149.508 157.52L69.142 54H54V125.97H66.1136V69.3836L139.999 164.845C143.333 162.614 146.509 160.165 149.508 157.52Z' fill='%23ffffff'/%3E%3Crect fill='%23ffffff' height='72' width='12' x='115' y='54'/%3E%3C/g%3E%3C/svg%3E">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #000000;
      --card: #0a0a0a;
      --border: #1f1f1f;
      --text: #f5f5f5;
      --text-muted: #888888;
      --text-dim: #555555;
      --cyan: #00e5ff;
      --emerald: #00e676;
      --gold: #f5a623;
      --radius: 6px;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif; }

    body {
      background: var(--bg);
      color: var(--text);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      -webkit-font-smoothing: antialiased;
      overflow-x: hidden;
      padding-bottom: 64px;
    }

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
      max-width: 960px;
      margin: 0 auto;
      padding: 0 16px;
      display: flex;
      flex-direction: column;
      gap: 24px;
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
      max-width: 960px;
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

    /* Content Cards */
    .doc-section {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .doc-title {
      font-size: 16px;
      font-weight: 700;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .doc-text {
      color: var(--text-muted);
      font-size: 13.5px;
      line-height: 1.6;
    }

    .code-block {
      background: #000;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 14px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      color: #e0e0e0;
      line-height: 1.5;
      overflow-x: auto;
      white-space: pre;
    }
    .code-inline {
      font-family: 'JetBrains Mono', monospace;
      font-size: 11.5px;
      background: #111;
      padding: 2px 5px;
      border: 1px solid var(--border);
      border-radius: 3px;
      color: var(--cyan);
    }

    /* Responsive Tables */
    .table-wrap {
      width: 100%;
      overflow-x: auto;
      border: 1px solid var(--border);
      border-radius: var(--radius);
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12.5px;
      text-align: left;
      min-width: 540px;
    }
    th {
      background: #050505;
      color: #fff;
      padding: 10px 14px;
      font-weight: 600;
      border-bottom: 1px solid var(--border);
    }
    td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border);
      color: var(--text-muted);
    }
    tr:last-child td { border-bottom: none; }
    tr:hover td { background: rgba(255,255,255,0.02); color: #fff; }

    @media (max-width: 768px) {
      .container { padding: 0 12px 48px; gap: 16px; }
      .doc-section { padding: 18px 14px; }
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
        <span class="brand-pill">DOCS</span>
      </a>
      <div class="nav-links">
        <a href="/" class="nav-link">Console</a>
        <a href="/docs" class="nav-link active">Documentation</a>
      </div>
    </div>
  </nav>

  <div class="container" style="margin-top: 16px;">
    <!-- Architecture -->
    <div class="doc-section">
      <h2 class="doc-title">1. Edge Architecture & Dynamic Request Signing</h2>
      <p class="doc-text">
        The Cloudflare Worker acts as a serverless gateway to Qobuz's Akamai CDN. Secured track endpoints (<span class="code-inline">track/getFileUrl</span>) require dynamic MD5 request signatures computed over the intent, format tier, track ID, timestamp, and app secret:
      </p>
      <div class="code-block">r_sig = "trackgetFileUrlformat_id" + format_id + "intentstreamtrack_id" + track_id + timestamp + app_secret
request_sig = md5(r_sig).hexdigest()</div>
      <p class="doc-text">
        The worker handles this signing automatically on the edge. Authenticated callers can pass custom tokens via <span class="code-inline">X-User-Auth-Token</span> header or the default environment variables configured in Cloudflare.
      </p>
    </div>

    <!-- Quality Formats -->
    <div class="doc-section">
      <h2 class="doc-title">2. Audio Quality Formats & Fallback Matrix</h2>
      <p class="doc-text">
        If a studio master is requested (<span class="code-inline">quality=27</span> or <span class="code-inline">quality=7</span>) but not released for a specific album, the engine automatically cascades down to CD Lossless (<span class="code-inline">6</span>) without throwing restriction errors:
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Codec</th>
              <th>Bit Depth</th>
              <th>Sampling Rate</th>
              <th>Catalog Coverage</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><span class="code-inline">5</span></td>
              <td>MP3</td>
              <td>16-bit</td>
              <td>44.1 kHz (320 kbps)</td>
              <td>100% of Catalog</td>
            </tr>
            <tr>
              <td><span class="code-inline">6</span></td>
              <td>FLAC</td>
              <td>16-bit</td>
              <td>44.1 kHz (CD Lossless)</td>
              <td>100% of Catalog</td>
            </tr>
            <tr>
              <td><span class="code-inline">7</span></td>
              <td>FLAC</td>
              <td>24-bit</td>
              <td>Up to 96.0 kHz (Hi-Res)</td>
              <td>Studio Hi-Res Releases</td>
            </tr>
            <tr>
              <td><span class="code-inline">27</span></td>
              <td>FLAC</td>
              <td>24-bit</td>
              <td>Up to 192.0 kHz (Hi-Res+)</td>
              <td>Studio Master 192k Releases</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Endpoints Specification -->
    <div class="doc-section">
      <h2 class="doc-title">3. REST API Endpoints Specification</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Method</th>
              <th>Endpoint</th>
              <th>Params</th>
              <th>Description</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/search</span></td>
              <td><span class="code-inline">q, type, limit</span></td>
              <td>Searches catalog by tracks, albums, artists, or playlists.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/track/:id</span></td>
              <td><span class="code-inline">:id</span></td>
              <td>Returns track metadata, Vorbis tags, and formatted filename.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/track/:id/url</span></td>
              <td><span class="code-inline">quality</span></td>
              <td>Generates signed Akamai CDN stream URL.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/stream/:id</span></td>
              <td><span class="code-inline">quality</span></td>
              <td>HTTP 302 direct redirect to signed audio stream.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/download/track/:id</span></td>
              <td><span class="code-inline">quality, proxy</span></td>
              <td>Pipes binary FLAC stream with HTTP 206 Range seeking.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/download/album/:id</span></td>
              <td><span class="code-inline">quality</span></td>
              <td>Complete album manifest with booklet PDF & cover art.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/download/m3u</span></td>
              <td><span class="code-inline">type, id</span></td>
              <td>Generates and downloads <span class="code-inline">.m3u8</span> playlist.</td>
            </tr>
            <tr>
              <td><span style="color:#3894ff;font-weight:700;">GET</span></td>
              <td><span class="code-inline">/api/download/cover/:id</span></td>
              <td><span class="code-inline">size</span></td>
              <td>Redirects to original lossless cover artwork.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Android Integration -->
    <div class="doc-section">
      <h2 class="doc-title">4. Android ExoPlayer / Media3 Integration (LastWave-native)</h2>
      <p class="doc-text">
        In Android Kotlin, play directly via the 302 stream redirect with zero worker bandwidth overhead:
      </p>
      <div class="code-block">// 1. Direct stream redirect URL
val streamUrl = "https://your-backend-url.com/api/stream/$trackId?quality=6"

// 2. Play in ExoPlayer
val player = ExoPlayer.Builder(context).build()
player.setMediaItem(MediaItem.fromUri(streamUrl))
player.prepare()
player.play()</div>
    </div>
  </div>
</body>
</html>`;
}

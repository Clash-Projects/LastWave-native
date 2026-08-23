# Qobuz Cloudflare Worker Backend — Setup Guide

A 100% hand-crafted, zero-dependency serverless JavaScript music API and downloader backend for **Cloudflare Workers**, porting the core architecture of `vitiko98/qobuz-dl`.

---

## 🛠️ Requirements

- **Node.js** (v18 or newer)
- **Cloudflare Account** & **Wrangler CLI** (optional for local testing, required for deployment)
- **Qobuz Account** (optional for public bundle scraping, recommended for Hi-Res streaming)

---

## 🚀 Quick Setup & Deployment

### 1. Installation

Navigate to the worker directory:

```bash
cd qobuz-worker-backend
npm install
```

### 2. Environment Configuration

Copy the sample environment file:

```bash
cp .env.example .env
```

Edit `.env` (or configure secrets in `wrangler.toml` / Cloudflare Dashboard):

```ini
# Production App ID (automatically scraped if omitted)
QOBUZ_APP_ID=798273057

# User Authentication Token (from an active Qobuz account)
QOBUZ_USER_AUTH_TOKEN=

# Or account credentials for auto-authentication
QOBUZ_EMAIL=
QOBUZ_PASSWORD=

# Default Audio Quality (5=MP3 320, 6=16-bit FLAC, 7=24-bit/96kHz, 27=24-bit/192kHz)
DEFAULT_QUALITY=6

# Optional Security Key (Leave blank for open public access)
API_AUTH_KEY=

# CORS Allowed Origin
ALLOWED_ORIGIN=*
```

### 3. Run Local Engine Verification Tests

Verify pure JS MD5 signatures, URL parsing, metadata tagging, and M3U generation without external dependencies:

```bash
npm run test
```

### 4. Start Local Development Server

```bash
npm run dev
```
The server will be available at `http://localhost:8787`.

### 5. Deploy to Cloudflare Workers

```bash
npm run deploy
```

---

## 📦 Project Architecture (100% Hand-Crafted Pure JavaScript)

```
qobuz-worker-backend/
├── SETUP.md                  # Setup & configuration guide
├── .env.example              # Environment variables template
├── .env                      # Local environment configuration
├── package.json              # Worker metadata and scripts
├── wrangler.toml             # Cloudflare Worker configuration & vars
├── test/
│   └── local-test.js         # Standalone engine test suite
└── src/
    ├── index.js              # REST API Router & Request Dispatcher
    └── qobuz/
        ├── bundle.js         # Web player bundle scraper & secret extractor
        ├── client.js         # API client with signature hashing & fallback ladder
        ├── downloader.js     # Download orchestrator & audio streaming proxy
        ├── metadata.js       # ID3/Vorbis comment tagger & filename formatter
        ├── m3u.js            # Dynamic M3U/M3U8 playlist file generator
        ├── signature.js      # Zero-dependency RFC 1321 pure JS MD5 & request signer
        └── utils.js          # Helpers, discography filters, and URL resolver
```

---

## 📡 API Endpoint Reference & Examples

### 1. Catalog & Search

#### Search Music
```bash
curl "http://localhost:8787/api/search?q=Daft+Punk&type=album&limit=5"
```

#### Track Metadata
```bash
curl "http://localhost:8787/api/track/5966783"
```

#### Album Metadata & Tracklist
```bash
curl "http://localhost:8787/api/album/0060253786966"
```

#### Artist Discography (Smart Filtered)
```bash
curl "http://localhost:8787/api/artist/194488?smart=true"
```

#### Universal Qobuz URL Resolver
```bash
curl "http://localhost:8787/api/resolve?url=https://play.qobuz.com/album/0060253786966"
```

---

### 2. Audio Streaming & Downloads

#### Direct Audio Playback Redirect
```bash
# Returns HTTP 302 Redirect to signed CDN stream
curl -L "http://localhost:8787/api/stream/5966783?quality=6"
```

#### Direct Audio Download (File Stream)
```bash
# Streams audio binary directly with Content-Disposition attachment header
curl -O -J "http://localhost:8787/api/download/track/5966783?quality=6"
```

#### Full Album Download Manifest Package
```bash
# Returns all track download URLs, booklet link, cover art, and M3U playlist
curl "http://localhost:8787/api/download/album/0060253786966?quality=6"
```

#### Download M3U Playlist File
```bash
# Generates and serves a .m3u8 playlist file directly
curl -O -J "http://localhost:8787/api/download/m3u?type=album&id=0060253786966"
```

#### High-Res Album Cover Art
```bash
curl -L "http://localhost:8787/api/download/cover/0060253786966?size=org"
```

#### Batch Download Resolver
```bash
curl -X POST "http://localhost:8787/api/download/batch" \
     -H "Content-Type: application/json" \
     -d '{"items": ["https://play.qobuz.com/album/0060253786966", "5966783"]}'
```

---

### 3. Authentication & Tokens

#### Dynamic Scraper Status
```bash
curl "http://localhost:8787/api/tokens"
```

#### Account Login
```bash
curl -X POST "http://localhost:8787/api/auth/login" \
     -H "Content-Type: application/json" \
     -d '{"email": "your_email@example.com", "password": "your_password"}'
```

#### User Library Favorites
```bash
curl "http://localhost:8787/api/user/favorites?type=albums&token=YOUR_USER_AUTH_TOKEN"
```

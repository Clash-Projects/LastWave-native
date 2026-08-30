/**
 * Local Standalone HTTP Server for Qobuz Worker
 * Runs worker.fetch on http://localhost:8787 without needing Wrangler installed.
 */

import http from "node:http";
import fs from "node:fs";
import worker from "./src/index.js";

// Load .env variables
const env = {};
if (fs.existsSync(".env")) {
  const envContent = fs.readFileSync(".env", "utf8");
  for (const line of envContent.split("\n")) {
    const trimmed = line.trim();
    if (trimmed && !trimmed.startsWith("#") && trimmed.includes("=")) {
      const idx = trimmed.indexOf("=");
      env[trimmed.slice(0, idx).trim()] = trimmed.slice(idx + 1).trim();
    }
  }
}

const PORT = process.env.PORT || 8787;

// In-Memory LRU/TTL Response Cache
const memoryCache = new Map();
const MAX_CACHE_SIZE = 3000;

function getCacheTTL(urlPath) {
  if (urlPath === "/api/pool/status" || urlPath === "/api/pool" || urlPath.startsWith("/api/auth")) {
    return 0; // Never cache real-time health or auth
  }
  if (urlPath.includes("/url") || urlPath.startsWith("/api/stream")) {
    return 30 * 60 * 1000; // 30 minutes for signed audio URLs
  }
  if (urlPath.startsWith("/api/search") || urlPath.startsWith("/api/track") || urlPath.startsWith("/api/album") || urlPath.startsWith("/api/artist") || urlPath.startsWith("/api/playlist")) {
    return 24 * 60 * 60 * 1000; // 24 hours for catalog metadata
  }
  if (urlPath === "/docs" || urlPath === "/" || urlPath === "/ui" || urlPath === "/playground") {
    return 7 * 24 * 60 * 60 * 1000; // 7 days for UI/documentation
  }
  return 60 * 60 * 1000; // 1 hour default
}

const server = http.createServer(async (req, res) => {
  try {
    const fullUrl = `http://${req.headers.host || `localhost:${PORT}`}${req.url}`;
    const parsedUrl = new URL(fullUrl);
    const urlPath = parsedUrl.pathname;
    const isGet = ["GET", "HEAD"].includes(req.method);
    const ttl = getCacheTTL(urlPath);
    const cacheKey = `${req.method}:${req.url}`;

    // 1. Check in-memory cache
    if (isGet && ttl > 0 && memoryCache.has(cacheKey)) {
      const entry = memoryCache.get(cacheKey);
      if (Date.now() < entry.expiresAt) {
        res.statusCode = entry.status;
        for (const [k, v] of Object.entries(entry.headers)) {
          res.setHeader(k, v);
        }
        res.setHeader("X-Cache", "HIT");
        res.setHeader("X-Cache-Age", `${Math.floor((Date.now() - entry.createdAt) / 1000)}s`);
        res.end(entry.body);
        return;
      } else {
        memoryCache.delete(cacheKey);
      }
    }
    
    // Read request body for POST/PUT
    const chunks = [];
    for await (const chunk of req) {
      chunks.push(chunk);
    }
    const bodyBuffer = chunks.length > 0 ? Buffer.concat(chunks) : null;
    const body = isGet ? undefined : bodyBuffer;

    const standardHeaders = new Headers();
    for (const [key, value] of Object.entries(req.headers)) {
      if (Array.isArray(value)) {
        value.forEach(v => standardHeaders.append(key, v));
      } else if (value !== undefined) {
        standardHeaders.set(key, value);
      }
    }

    const webRequest = new Request(fullUrl, {
      method: req.method,
      headers: standardHeaders,
      body: body
    });

    const webResponse = await worker.fetch(webRequest, env, {});

    res.statusCode = webResponse.status;
    const responseHeaders = {};

    for (const [key, value] of webResponse.headers.entries()) {
      responseHeaders[key] = value;
      res.setHeader(key, value);
    }

    // Apply CDN & Edge Cache-Control headers if 200 OK
    if (webResponse.status === 200 && isGet && ttl > 0) {
      const maxAgeSec = Math.floor(ttl / 1000);
      const cdnHeader = `public, max-age=${maxAgeSec}, s-maxage=${maxAgeSec * 2}, stale-while-revalidate=86400`;
      res.setHeader("Cache-Control", cdnHeader);
      res.setHeader("CDN-Cache-Control", `max-age=${maxAgeSec * 2}`);
      res.setHeader("X-Cache", "MISS");
      responseHeaders["Cache-Control"] = cdnHeader;
      responseHeaders["CDN-Cache-Control"] = `max-age=${maxAgeSec * 2}`;
    }

    let responseBuffer = null;
    if (webResponse.body) {
      const responseChunks = [];
      const reader = webResponse.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        responseChunks.push(Buffer.from(value));
      }
      responseBuffer = Buffer.concat(responseChunks);
      res.write(responseBuffer);
    }

    // Save to cache if eligible
    if (webResponse.status === 200 && isGet && ttl > 0 && responseBuffer) {
      if (memoryCache.size >= MAX_CACHE_SIZE) {
        const firstKey = memoryCache.keys().next().value;
        memoryCache.delete(firstKey);
      }
      memoryCache.set(cacheKey, {
        status: webResponse.status,
        headers: responseHeaders,
        body: responseBuffer,
        createdAt: Date.now(),
        expiresAt: Date.now() + ttl
      });
    }

    res.end();
  } catch (err) {
    console.error("Server Error:", err);
    res.statusCode = 500;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ success: false, error: err.message }));
  }
});

server.listen(PORT, () => {
  console.log(`\n======================================================`);
  console.log(`  Qobuz Backend Server running at http://localhost:${PORT}`);
  console.log(`======================================================\n`);
});

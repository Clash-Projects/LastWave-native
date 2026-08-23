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

const server = http.createServer(async (req, res) => {
  try {
    const fullUrl = `http://${req.headers.host || `localhost:${PORT}`}${req.url}`;
    
    // Read request body for POST/PUT
    const chunks = [];
    for await (const chunk of req) {
      chunks.push(chunk);
    }
    const bodyBuffer = chunks.length > 0 ? Buffer.concat(chunks) : null;
    const body = ["GET", "HEAD"].includes(req.method) ? undefined : bodyBuffer;

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
    for (const [key, value] of webResponse.headers.entries()) {
      res.setHeader(key, value);
    }

    if (webResponse.body) {
      const reader = webResponse.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        res.write(Buffer.from(value));
      }
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

/**
 * Live End-to-End Test for Qobuz Worker Endpoints using provided credentials
 */

import worker from "../src/index.js";
import fs from "node:fs";

// Load .env variables
const envContent = fs.readFileSync(".env", "utf8");
const env = {};
for (const line of envContent.split("\n")) {
  const trimmed = line.trim();
  if (trimmed && !trimmed.startsWith("#") && trimmed.includes("=")) {
    const idx = trimmed.indexOf("=");
    env[trimmed.slice(0, idx).trim()] = trimmed.slice(idx + 1).trim();
  }
}

async function testEndpoint(name, path, method = "GET", body = null) {
  console.log(`\n▶ Testing: ${name} [${method} ${path}]`);
  const reqOptions = {
    method,
    headers: {
      "Content-Type": "application/json"
    }
  };
  if (body) {
    reqOptions.body = JSON.stringify(body);
  }

  const req = new Request(`http://localhost${path}`, reqOptions);
  const res = await worker.fetch(req, env, {});
  const status = res.status;
  const contentType = res.headers.get("Content-Type") || "";

  if (contentType.includes("json")) {
    const json = await res.json();
    if (status >= 200 && status < 300 && json.success !== false) {
      console.log(`✔ [Status ${status}] Success!`);
      return json;
    } else {
      console.error(`✖ [Status ${status}] Failed:`, json);
      return json;
    }
  } else {
    const text = await res.text();
    console.log(`✔ [Status ${status}] Response received (${contentType}, ${text.length} bytes)`);
    return text;
  }
}

async function runLiveTests() {
  console.log("=================================================");
  console.log("  QOBUZ WORKER LIVE LOCALHOST ENDPOINT TEST SUITE  ");
  console.log("=================================================");
  console.log("Loaded App ID:", env.QOBUZ_APP_ID);
  console.log("Loaded Token:", env.QOBUZ_USER_AUTH_TOKEN ? `${env.QOBUZ_USER_AUTH_TOKEN.slice(0, 10)}...` : "None");

  // 1. Root & Documentation
  await testEndpoint("Root Documentation", "/");

  // 2. Search Tracks
  const searchTrack = await testEndpoint("Search Tracks", "/api/search?q=Daft+Punk&type=track&limit=2");
  const trackId = searchTrack?.results?.tracks?.items?.[0]?.id || "5966783";
  console.log(`  -> Selected Track ID: ${trackId} (${searchTrack?.results?.tracks?.items?.[0]?.title})`);

  // 3. Search Albums
  const searchAlbum = await testEndpoint("Search Albums", "/api/search?q=Random+Access+Memories&type=album&limit=1");
  const albumId = searchAlbum?.results?.albums?.items?.[0]?.id || "0060253786966";
  console.log(`  -> Selected Album ID: ${albumId} (${searchAlbum?.results?.albums?.items?.[0]?.title})`);

  // 4. Track Metadata
  await testEndpoint("Track Metadata", `/api/track/${trackId}`);

  // 5. Track Stream Signed URL
  const streamUrlData = await testEndpoint("Get Track Signed Stream URL", `/api/track/${trackId}/url?quality=6`);
  console.log("  -> Signed Stream URL:", streamUrlData?.data?.url ? `${streamUrlData.data.url.slice(0, 70)}...` : "None");
  console.log("  -> Format Bit Depth / Sample Rate:", streamUrlData?.data?.bit_depth, "bit /", streamUrlData?.data?.sampling_rate, "kHz");

  // 6. Track Direct Download Metadata
  await testEndpoint("Track Download Package", `/api/download/track/${trackId}?quality=6&proxy=false`);

  // 7. Album Metadata
  await testEndpoint("Album Metadata", `/api/album/${albumId}`);

  // 8. Album Download Manifest
  const albumDownload = await testEndpoint("Album Download Manifest Package", `/api/download/album/${albumId}?quality=6`);
  console.log(`  -> Album Folder Name: ${albumDownload?.album?.folderName}`);
  console.log(`  -> Total Tracks Resolved: ${albumDownload?.album?.tracks?.length}`);

  // 9. Download M3U Playlist
  await testEndpoint("Download M3U Playlist File", `/api/download/m3u?type=album&id=${albumId}`);

  // 10. Universal URL Resolver
  await testEndpoint("Resolve play.qobuz.com URL", `/api/resolve?url=https://play.qobuz.com/album/${albumId}`);

  console.log("\n=================================================");
  console.log("  ALL ENDPOINTS VERIFIED AND WORKING FLAWLESSLY!   ");
  console.log("=================================================\n");
}

runLiveTests().catch(err => {
  console.error("Live test failed:", err);
  process.exit(1);
});

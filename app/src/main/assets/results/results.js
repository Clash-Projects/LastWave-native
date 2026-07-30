/* ════════════════════════════════════════════════════════════
   results.js — Results screen logic
   ════════════════════════════════════════════════════════════ */

'use strict';

// ── Screen init (called by nav.js every time results loads) ───
function screen_results() {
  if (state.playlist && state.playlist.length) {
    renderResults();
  } else {
    showResultsEmpty();
  }
}

// ── Loading state (called from app.js generatePlaylist) ───────
function showLoading(show) {
  const loading = document.getElementById('resultsLoading');
  const content = document.getElementById('resultsContent');
  const empty   = document.getElementById('resultsEmpty');
  if (show) {
    loading.classList.remove('hidden');
    content.classList.add('hidden');
    empty.classList.add('hidden');
  } else {
    loading.classList.add('hidden');
  }
}

function setLoadingText(text) {
  const el = document.getElementById('loadingText');
  if (el) el.textContent = text;
}

function showResultsEmpty() {
  document.getElementById('resultsEmpty').classList.remove('hidden');
  document.getElementById('resultsContent').classList.add('hidden');
  document.getElementById('resultsLoading').classList.add('hidden');
}

// ── Render track list ─────────────────────────────────────────
function renderResults() {
  document.getElementById('resultsEmpty').classList.add('hidden');
  document.getElementById('resultsLoading').classList.add('hidden');
  document.getElementById('resultsContent').classList.remove('hidden');

  document.getElementById('resultsTitle').textContent = state.playlistTitle;
  document.getElementById('resultsCount').textContent = state.playlist.length + ' tracks';

  const list = document.getElementById('trackList');
  list.innerHTML = '';

  state.playlist.forEach((track, i) => {
    const el = document.createElement('div');
    el.className = 'track-item';
    el.innerHTML = `
      <div class="track-num">${i + 1}</div>
      <div class="track-info">
        <div class="track-name">${esc(track.name)}</div>
        <div class="track-artist">${esc(track.artist)}</div>
      </div>
      <button class="track-play-btn" onclick="openTrackOnYouTube('${escAttr(track.name)}','${escAttr(track.artist)}')" title="Open in streaming app">
        <span class="material-symbols-rounded">play_circle</span>
      </button>
    `;
    list.appendChild(el);
  });
}

// ── Clear results ─────────────────────────────────────────────
function clearResults() {
  state.playlist = [];
  state.playlistTitle = '';
  showResultsEmpty();
}

// ── Export: CSV ───────────────────────────────────────────────
function exportCSV() {
  if (!state.playlist.length) { showToast('No playlist to export', 'error'); return; }
  const lines = ['#,Track,Artist'];
  state.playlist.forEach((t, i) => {
    lines.push(`${i + 1},"${t.name.replace(/"/g, '""')}","${t.artist.replace(/"/g, '""')}"`);
  });
  triggerSave(sanitizeFilename(state.playlistTitle) + '.csv', lines.join('\n'), 'text/csv');
}

// ── Export: M3U ───────────────────────────────────────────────
function exportM3U() {
  if (!state.playlist.length) { showToast('No playlist to export', 'error'); return; }
  const lines = ['#EXTM3U', `#PLAYLIST:${state.playlistTitle}`];
  state.playlist.forEach(t => {
    lines.push(`#EXTINF:-1,${t.artist} - ${t.name}`);
    lines.push(t.url || `# ${t.artist} - ${t.name}`);
  });
  triggerSave(sanitizeFilename(state.playlistTitle) + '.m3u', lines.join('\n'), 'audio/x-mpegurl');
}

// ── Share as text ─────────────────────────────────────────────
function shareText() {
  if (!state.playlist.length) { showToast('No playlist to share', 'error'); return; }
  const lines = [state.playlistTitle, ''];
  state.playlist.forEach((t, i) => lines.push(`${i + 1}. ${t.name} – ${t.artist}`));
  Platform.shareText(lines.join('\n'), state.playlistTitle);
}

// ── Open first track in streaming app (currently YouTube) ────
function openInStreamingApp() {
  if (!state.playlist.length) { showToast('No playlist to open', 'error'); return; }
  const first = state.playlist[0];
  openTrackOnYouTube(first.name, first.artist);
}

/* SESI MINI extension — dashboard (popup and in-page overlay). Talks to the Dola tab's content script,
 * the background download service, and the Apps Script license/update server. */
(() => {
  'use strict';
  const S = chrome.storage.local;
  const DEFAULT_SERVER = 'https://script.google.com/macros/s/AKfycbz71kYSLnl3Gwxnm5S3g1dPN5u8m19RRjniHrxlWkTUlF_NV8sF43nKbnYAtFA1NOU3oQ/exec';
  const ALPHA = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  const BUNDLED = { skill_md: ['System MD', 'v5', 'Introvert-Dreams-SKILL-v5.md'], auto_prompt: ['Auto prompt', 'v1'], enforcer: ['Paksa 30 detik', 'v1'], collector: ['Pendeteksi video', 'v1'] };
  const embed = new URLSearchParams(location.search).get('embed') === '1';
  const $ = id => document.getElementById(id);
  const licensed = lic => !!(lic && lic.until && lic.until > Date.now());
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const toast = t => { $('hint').textContent = t; setTimeout(() => { if ($('hint').textContent === t) $('hint').textContent = ''; }, 3500); };

  // ---- server ----
  async function serverUrl() { return (await S.get('serverUrl')).serverUrl || DEFAULT_SERVER; }
  async function post(body) {
    const r = await fetch(await serverUrl(), { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify(body), redirect: 'follow' });
    const text = await r.text();
    if (text.trim().startsWith('<')) throw new Error('server mengembalikan halaman error (Code.gs rusak/tidak lengkap)');
    return JSON.parse(text);
  }
  async function deviceId() {
    let { deviceId } = await S.get('deviceId');
    if (!/^[2-9A-HJ-NP-Z]{6}$/.test(deviceId || '')) { deviceId = ''; const b = crypto.getRandomValues(new Uint8Array(6)); for (const x of b) deviceId += ALPHA[x % ALPHA.length]; await S.set({ deviceId }); }
    return deviceId;
  }
  const REASON = { not_found: 'Key tidak ditemukan.', revoked: 'Key sudah dicabut.', expired: 'Masa berlaku key habis.', device_limit: 'Key sudah dipakai di perangkat lain (batas tercapai).', device_mismatch: 'Key tidak terdaftar untuk perangkat ini.', server_setup: 'Server belum di-setup.', code_incomplete: 'Code.gs server tidak lengkap.' };
  async function license(action, key) {
    const id = await deviceId(), nonce = String(Date.now()) + Math.random().toString(36).slice(2);
    const r = await post({ action, key, deviceId: id, nonce, note: 'SesiMini ext ' + chrome.runtime.getManifest().version + ' · ' + navigator.userAgent.slice(0, 60) });
    if (r.nonce !== nonce) throw new Error('respons tidak valid');
    if (r.ok) {
      if (r.deviceId !== id) throw new Error('respons tidak valid');
      await S.set({ lic: { key, deviceId: id, until: r.graceUntil, name: r.name || '', expiresAt: r.expiresAt || 0, checkedAt: Date.now() } });
      return { ok: true };
    }
    return { ok: false, definitive: true, message: REASON[r.reason] || ('Ditolak (' + r.reason + ')') };
  }

  // ---- tab messaging ----
  async function dolaTab() {
    const tabs = await chrome.tabs.query(embed ? { active: true, currentWindow: true } : { url: ['https://*.dola.com/*', 'https://dola.com/*'] });
    return tabs.find(t => /(^|\.)dola\.com$/i.test(new URL(t.url || 'https://x').hostname)) || null;
  }
  async function tell(msg) {
    const t = await dolaTab(); if (!t) return { ok: false, error: 'Buka tab Dola terlebih dahulu.' };
    try { return await chrome.tabs.sendMessage(t.id, msg); } catch (e) { return { ok: false, error: 'Muat ulang tab Dola lalu coba lagi.' }; }
  }

  // ---- render ----
  async function render() {
    const st = await S.get(['lic', 'autoPrompt', 'imageNote', 'mdArmed', 'videos', 'dl', 'ota_skill_md', 'ota_versions', 'seen']);
    const lic = licensed(st.lic);
    $('gate').hidden = lic; $('app').hidden = !lic; $('accounts').hidden = true;
    $('pill').textContent = lic ? '● Active' : '○ Belum aktif'; $('pill').classList.toggle('off', !lic);
    $('close').hidden = !embed; $('ver').textContent = 'SESI MINI extension v' + chrome.runtime.getManifest().version;
    const id = await deviceId(); $('deviceId').textContent = id; $('licId').textContent = id;
    $('wa').href = 'https://wa.me/628889841098?text=' + encodeURIComponent('Halo, saya mau key SESI MINI. Device ID: ' + id);
    if (st.lic && st.lic.lost && !lic) $('gateMsg').textContent = REASON[st.lic.lost] || 'Lisensi tidak valid lagi.';
    if (!lic) return;
    const exp = st.lic.expiresAt; $('licStatus').textContent = 'Aktif' + (st.lic.name ? ' · ' + st.lic.name : '') + (exp ? ' · s/d ' + new Date(exp).toLocaleDateString('id-ID') : ' · lifetime');
    $('ap').checked = !!st.autoPrompt; $('img').checked = !!st.imageNote;
    $('md').textContent = st.mdArmed ? 'Nonaktifkan' : 'Aktifkan'; $('md').classList.toggle('primary', !!st.mdArmed);
    $('mdName').textContent = '· ' + ((st.ota_skill_md && st.ota_skill_md.name) || BUNDLED.skill_md[2]);
    renderVideos(st); renderUpdates(st);
  }
  function stateFor(url, dl) { let best = ''; for (const id in dl || {}) if (dl[id].url === url) { if (dl[id].state === 'complete') return 'complete'; best = dl[id].state; } return best; }
  function fmt(sec) { sec = Math.round(sec || 0); const m = Math.floor(sec % 3600 / 60), s = String(sec % 60).padStart(2, '0'); return sec >= 3600 ? Math.floor(sec / 3600) + ':' + String(m).padStart(2, '0') + ':' + s : m + ':' + s; }
  function renderVideos(st) {
    const list = Object.values(st.videos || {}).sort((a, b) => (b.seq || 0) - (a.seq || 0)), seen = new Set(st.seen || []);
    const box = $('videos'); box.innerHTML = '';
    let saved = 0, busy = false;
    for (const v of list) { const s = stateFor(v.url, st.dl); if (s === 'complete') saved++; if (s === 'in_progress') busy = true; }
    $('nFound').textContent = list.length; $('nSaved').textContent = saved;
    if (!list.length) { box.innerHTML = '<div class="item muted">Belum ada video. Buka chat yang berisi video lalu ketuk Scan chat.</div>'; return; }
    for (const v of list) {
      const s = stateFor(v.url, st.dl), dim = v.width ? v.width + '×' + v.height : (v.definition || 'mp4');
      const row = document.createElement('div'); row.className = 'vid';
      const th = document.createElement('div'); th.className = 'thumb';
      if (v.poster) th.style.backgroundImage = 'url("' + v.poster + '")';
      else { const vd = document.createElement('video'); vd.muted = true; vd.preload = 'metadata'; vd.src = v.url + '#t=1'; th.appendChild(vd); }
      th.addEventListener('click', () => player(v));
      const meta = document.createElement('div'); meta.className = 'meta';
      const status = s === 'complete' ? 'Saved · ' + dim : s === 'in_progress' ? 'Saving…' : s === 'interrupted' ? 'Gagal · ketuk untuk ulangi' : dim;
      meta.innerHTML = '<b>' + (seen.has(v.vid || v.url) ? '' : '<span class="badge">BARU</span>') + esc(v.name || 'video') + '</b><small class="st-' + s + '">#' + (v.seq || '') + ' · ' + status + (v.duration ? ' · ' + fmt(v.duration) : '') + '</small>';
      const btn = document.createElement('button'); btn.className = 'btn sm'; btn.textContent = s === 'complete' ? '✓ Ulangi' : s === 'in_progress' ? '…' : '⬇ Download'; btn.disabled = s === 'in_progress';
      btn.addEventListener('click', () => download(v));
      row.append(th, meta, btn); box.appendChild(row);
    }
    if (busy) setTimeout(render, 1500);
  }
  async function download(v) { const r = await chrome.runtime.sendMessage({ type: 'download', url: v.url, name: v.name }); toast(r && r.ok ? 'Download dimulai' : 'Gagal: ' + (r && r.error)); render(); }
  function player(v) {
    const p = document.createElement('div'); p.id = 'player';
    p.innerHTML = '<div class="bar"><b class="grow">' + esc(v.name || 'Pratinjau') + '</b><button class="btn sm" id="pc">✕</button></div><video controls autoplay playsinline loop src="' + esc(v.url) + '"></video><div class="bar"><button class="btn primary grow" id="pd">⬇ Download video ini</button></div>';
    document.body.appendChild(p);
    p.querySelector('#pc').onclick = () => p.remove();
    p.querySelector('#pd').onclick = () => { download(v); p.remove(); };
  }

  // ---- updates (server reads the Drive folder; MD is applied OTA, scripts need a new extension build) ----
  function renderUpdates(st) {
    const box = $('updates'); box.innerHTML = ''; const ota = st.ota_versions || {};
    for (const id in BUNDLED) {
      const cur = id === 'skill_md' && st.ota_skill_md ? st.ota_skill_md.version : BUNDLED[id][1];
      const row = document.createElement('div'); row.className = 'item';
      row.innerHTML = '<span>' + BUNDLED[id][0] + '</span><span class="muted">' + esc(cur) + (id === 'skill_md' && st.ota_skill_md ? ' · OTA' : '') + (ota[id] && cmpVer(ota[id], cur) > 0 ? ' <small class="st-interrupted">(tersedia ' + esc(ota[id]) + ')</small>' : '') + '</span>';
      if (id === 'skill_md' && st.ota_skill_md) { row.title = 'Klik dua kali: kembali ke bawaan'; row.addEventListener('dblclick', async () => { await S.remove('ota_skill_md'); render(); }); }
      box.appendChild(row);
    }
  }
  const cmpVer = (a, b) => { const x = String(a).replace(/[^0-9.]/g, '').split('.').map(Number), y = String(b).replace(/[^0-9.]/g, '').split('.').map(Number); for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d; } return 0; };
  async function checkUpdates() {
    $('checkUpdates').disabled = true; $('updHint').textContent = 'Memeriksa versi terbaru…';
    try {
      const m = await post({ action: 'updates' });
      if (!m.ok) throw new Error(m.reason === 'no_folder' ? 'folder update belum diatur di server' : m.reason === 'code_incomplete' ? 'Code.gs server tidak lengkap' : 'server belum diberi izin Drive (Run setup() di Apps Script)');
      const st = await S.get('ota_skill_md'); const versions = {}; let updated = 0; const notes = [];
      for (const a of m.assets || []) {
        const local = a.id === 'skill_md' && st.ota_skill_md ? st.ota_skill_md.version : (BUNDLED[a.id] || [])[1];
        if (!local || cmpVer(a.version, local) <= 0) continue;
        if (a.id === 'skill_md') {
          const f = await post({ action: 'update_file', fileId: a.fileId });
          if (!f.ok) throw new Error('server menolak file (' + f.reason + ')');
          await S.set({ ota_skill_md: { base64: f.content, name: f.name || a.filename, version: a.version } }); updated++;
        } else { versions[a.id] = a.version; notes.push(BUNDLED[a.id][0] + ' ' + a.version); }
      }
      await S.set({ ota_versions: versions });
      $('updHint').textContent = (updated ? 'System MD diperbarui. ' : '') + (notes.length ? 'Skrip baru tersedia (' + notes.join(', ') + ') — pasang extension versi terbaru.' : updated ? '' : 'Semua sudah versi terbaru ✓');
    } catch (e) { $('updHint').textContent = 'Gagal: ' + e.message; }
    $('checkUpdates').disabled = false; render();
  }

  // ---- accounts (cookies + localStorage) ----
  async function allCookies() { return chrome.cookies.getAll({ domain: 'dola.com' }); }
  async function clearCookies() { for (const c of await allCookies()) { try { await chrome.cookies.remove({ url: 'https://' + c.domain.replace(/^\./, '') + c.path, name: c.name }); } catch (_) {} } }
  async function restoreCookies(list) {
    for (const c of list) {
      const d = { url: 'https://' + c.domain.replace(/^\./, '') + c.path, name: c.name, value: c.value, path: c.path, secure: true, httpOnly: !!c.httpOnly, expirationDate: Math.floor(Date.now() / 1000) + 365 * 86400 };
      if (c.sameSite && c.sameSite !== 'unspecified') d.sameSite = c.sameSite;
      if (!c.hostOnly) d.domain = c.domain;
      try { await chrome.cookies.set(d); } catch (_) {}
    }
  }
  async function snapshot() { const local = await tell({ type: 'localStorageGet' }); return { cookies: await allCookies(), local: local && !local.error ? local : {} }; }
  async function renderAccounts() {
    const { accounts = [], activeAccount = '' } = await S.get(['accounts', 'activeAccount']);
    const box = $('accList'); box.innerHTML = '';
    if (!accounts.length) { box.innerHTML = '<div class="card item muted center">Belum ada sesi tersimpan.<br>Login di Dola, lalu ketuk “Simpan sesi ini”.</div>'; return; }
    for (const a of accounts) {
      const active = a.id === activeAccount; const row = document.createElement('div'); row.className = 'acc';
      row.innerHTML = '<span class="led' + (active ? ' on' : '') + '"></span><div class="meta"><b>' + esc(a.name) + '</b><small>' + (active ? 'Aktif · ' : '') + 'disimpan ' + new Date(a.savedAt).toLocaleString('id-ID') + '</small></div>';
      const use = document.createElement('button'); use.className = 'btn sm' + (active ? '' : ' primary'); use.textContent = active ? 'Segarkan' : 'Pakai';
      use.onclick = () => active ? saveSession(a.id) : switchTo(a.id);
      const more = document.createElement('button'); more.className = 'btn sm'; more.textContent = '⋯';
      more.onclick = async () => {
        const c = prompt('Ganti nama (kosongkan + OK untuk HAPUS):', a.name); if (c === null) return;
        const { accounts = [] } = await S.get('accounts');
        const next = c.trim() ? accounts.map(x => x.id === a.id ? Object.assign(x, { name: c.trim() }) : x) : accounts.filter(x => x.id !== a.id);
        await S.set({ accounts: next }); if (!c.trim() && active) await S.remove('activeAccount'); renderAccounts();
      };
      row.append(use, more); box.appendChild(row);
    }
  }
  async function saveCurrentInto(accounts, id) { const snap = await snapshot(); const cur = accounts.find(a => a.id === id); if (cur) Object.assign(cur, { cookies: snap.cookies, local: snap.local, savedAt: Date.now() }); }
  async function saveSession(id) {
    if (!(await allCookies()).length) return alert('Belum ada sesi Dola. Login dulu di tab Dola.');
    let { accounts = [], activeAccount = '' } = await S.get(['accounts', 'activeAccount']);
    id = id || activeAccount; let acc = accounts.find(a => a.id === id);
    if (!acc) { const name = prompt('Nama akun:', 'Akun ' + (accounts.length + 1)); if (name === null) return; acc = { id: Date.now().toString(36), name: name.trim() || 'Akun ' + (accounts.length + 1) }; accounts.push(acc); }
    await saveCurrentInto(accounts, acc.id);
    await S.set({ accounts, activeAccount: acc.id }); renderAccounts(); toast('Sesi “' + acc.name + '” tersimpan');
  }
  async function leave() { await tell({ type: 'navigate', url: 'https://www.dola.com/' }); if (embed) tell({ type: 'closeOverlay' }); else window.close(); }
  async function switchTo(id) {
    const { accounts = [], activeAccount = '' } = await S.get(['accounts', 'activeAccount']); const acc = accounts.find(a => a.id === id); if (!acc) return;
    if (activeAccount && activeAccount !== id && (await allCookies()).length) await saveCurrentInto(accounts, activeAccount);
    await clearCookies(); await restoreCookies(acc.cookies || []);
    await tell({ type: 'localStorageSet', data: acc.local || {} });
    await S.set({ accounts, activeAccount: id }); leave();
  }
  async function freshSession() {
    if (!confirm('Sesi Dola yang aktif akan dikeluarkan (logout) supaya kamu bisa login akun lain. Sesi saat ini disimpan dulu bila sudah terdaftar. Lanjut?')) return;
    const { accounts = [], activeAccount = '' } = await S.get(['accounts', 'activeAccount']);
    if (activeAccount) { await saveCurrentInto(accounts, activeAccount); await S.set({ accounts }); }
    await clearCookies(); await tell({ type: 'localStorageSet', data: {} }); await S.remove('activeAccount'); leave();
  }

  // ---- wire up ----
  $('close').onclick = () => tell({ type: 'closeOverlay' });
  $('copyId').onclick = async () => { await navigator.clipboard.writeText(await deviceId()); $('gateMsg').textContent = 'Device ID disalin'; };
  $('setServer').onclick = async e => { e.preventDefault(); const u = prompt('URL server lisensi (…/exec). Kosongkan untuk bawaan:', (await S.get('serverUrl')).serverUrl || ''); if (u === null) return; await S.set({ serverUrl: u.trim() }); $('gateMsg').textContent = 'Server diatur.'; };
  $('activate').onclick = async () => {
    let k = $('keyInput').value.toUpperCase().replace(/[^A-Z0-9]/g, '').replace(/^SESI/, '');
    if (k.length !== 12) { $('gateMsg').textContent = 'Format key: SESI-XXXX-XXXX-XXXX'; return; }
    k = 'SESI-' + k.slice(0, 4) + '-' + k.slice(4, 8) + '-' + k.slice(8);
    $('activate').disabled = true; $('gateMsg').className = 'msg'; $('gateMsg').textContent = 'Memverifikasi…';
    try {
      const r = await license('activate', k);
      $('gateMsg').className = r.ok ? 'msg ok' : 'msg'; $('gateMsg').textContent = r.ok ? 'Aktivasi berhasil… selamat menikmati ☕' : r.message;
      if (r.ok) { setTimeout(render, 600); tell({ type: 'reload' }); }
    } catch (e) { $('gateMsg').textContent = 'Tidak bisa menghubungi server lisensi: ' + e.message; }
    $('activate').disabled = false;
  };
  $('verify').onclick = async () => {
    const { lic } = await S.get('lic');
    try { const r = await license('verify', lic.key); toast(r.ok ? 'Lisensi valid ✓' : r.message); if (!r.ok && r.definitive) { await S.set({ lic: Object.assign(lic, { until: 0, lost: 'revoked' }) }); render(); } }
    catch (e) { toast('Server tidak terjangkau: ' + e.message); }
  };
  $('scan').onclick = async () => { toast('Memindai chat…'); const r = await tell({ type: 'scan' }); if (!r || !r.ok) toast((r && r.error) || 'Gagal'); else { const n = Object.keys(r.videos || {}).length; toast(n ? n + ' video ditemukan' : 'Tidak ada video di halaman ini.'); } render(); };
  $('md').onclick = async () => { const r = await tell({ type: 'attach' }); if (r && r.error) toast(r.error); if (embed) tell({ type: 'closeOverlay' }); render(); };
  $('ap').onchange = async e => { const r = await tell({ type: 'setAutoPrompt', enabled: e.target.checked }); if (!r || !r.ok) { toast((r && r.error) || 'Skrip auto prompt belum siap. Muat ulang Dola.'); render(); } else toast(e.target.checked ? 'Auto prompt aktif · paksa 1 video × 30 detik ☕' : 'Auto prompt nonaktif'); };
  $('img').onchange = async e => { const r = await tell({ type: 'setImageNote', enabled: e.target.checked }); if (!r || !r.ok) { toast((r && r.error) || 'Skrip auto prompt belum siap. Muat ulang Dola.'); render(); } };
  $('checkUpdates').onclick = checkUpdates;
  $('tabAccounts').onclick = () => { $('app').hidden = true; $('accounts').hidden = false; renderAccounts(); };
  $('backMain').onclick = render;
  $('saveSession').onclick = () => saveSession(null); $('freshSession').onclick = freshSession;
  chrome.storage.onChanged.addListener(ch => { if (ch.videos || ch.dl || ch.lic || ch.mdArmed || ch.autoPrompt || ch.imageNote) render(); });
  window.addEventListener('pagehide', async () => { const { videos = {} } = await S.get('videos'); S.set({ seen: Object.keys(videos) }); });
  render();
})();

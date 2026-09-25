/* SESI MINI extension — isolated-world content script: dock UI, storage, MAIN-world RPC, dashboard relay. */
(() => {
  'use strict';
  if (window.top !== window) return;
  const S = chrome.storage.local;
  const pending = new Map(); let rid = 0;
  const rpc = (cmd, args = {}, timeout = 4000) => new Promise(resolve => {
    const id = String(++rid); pending.set(id, resolve);
    window.postMessage({ __sesi: true, type: 'cmd', id, cmd, args }, location.origin);
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); resolve({ ok: false, error: 'timeout' }); } }, timeout);
  });
  window.addEventListener('message', ev => {
    if (ev.source !== window || !ev.data || ev.data.__sesi !== true) return;
    const m = ev.data;
    if (m.type === 'reply') { const r = pending.get(m.id); if (r) { pending.delete(m.id); r(m.result); } }
    else if (m.type === 'video') addVideos([m.video]);
    else if (m.type === 'attached') { S.set({ mdArmed: false }); paint(); toast('File MD terlampir ke composer.'); }
  });

  async function addVideos(list) {
    const { videos = {} } = await S.get('videos');
    let changed = false;
    for (const v of list) {
      if (!v || !v.url) continue;
      const key = v.vid || v.url; if (videos[key]) continue;
      v.seq = Object.keys(videos).length + 1; v.foundAt = Date.now(); v.page = location.href; videos[key] = v; changed = true;
    }
    if (changed) await S.set({ videos });
    return videos;
  }
  async function scan() {
    for (let i = 0; i < 10; i++) {
      const r = await rpc('scan');
      if (r && r.videos) { await addVideos(r.videos); if (!r.pending) break; }
      await new Promise(res => setTimeout(res, 500));
    }
    return (await S.get('videos')).videos || {};
  }

  // ---- settings → page ----
  async function ready() { for (let i = 0; i < 40; i++) { const r = await rpc('ping', {}, 800); if (r && r.ok && r.autoPrompt) return true; await new Promise(res => setTimeout(res, 250)); } return false; }
  async function applySettings() {
    const st = await S.get(['lic', 'autoPrompt', 'imageNote']);
    if (!licensed(st.lic)) return;
    if (st.autoPrompt) await rpc('autoPrompt', { enabled: true });
    if (st.imageNote) await rpc('imageNote', { enabled: true });
  }
  const licensed = lic => !!(lic && lic.until && lic.until > Date.now());

  async function mdBytes() {
    const { ota_skill_md } = await S.get('ota_skill_md');
    if (ota_skill_md && ota_skill_md.base64) return ota_skill_md;
    const res = await fetch(chrome.runtime.getURL('Introvert-Dreams-SKILL-v5.md'));
    const buf = new Uint8Array(await res.arrayBuffer()); let bin = ''; for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
    return { base64: btoa(bin), name: 'Introvert-Dreams-SKILL-v5.md' };
  }

  // ---- commands shared by dock and dashboard ----
  async function requireLicense() { const { lic } = await S.get('lic'); if (licensed(lic)) return true; toast('Aktivasi lisensi dulu di dashboard SESI.'); openDashboard(); return false; }
  const actions = {
    async toggleMd() {
      if (!await requireLicense()) return;
      const { mdArmed } = await S.get('mdArmed');
      if (mdArmed) { await rpc('attach', { arm: false }); await S.set({ mdArmed: false }); toast('Lampiran MD nonaktif.'); }
      else { const md = await mdBytes(); const r = await rpc('attach', { base64: md.base64, arm: true, name: md.name }); await S.set({ mdArmed: !!(r && r.ok) }); toast(r && r.message ? r.message : 'Gagal menyiapkan lampiran.'); }
      paint();
    },
    async toggleAutoPrompt() {
      const st = await S.get(['autoPrompt', 'lic']); const next = !st.autoPrompt;
      if (next && !licensed(st.lic)) return requireLicense();
      const r = await rpc('autoPrompt', { enabled: next });
      if (r && r.ok) { await S.set({ autoPrompt: next }); toast(next ? 'Auto prompt aktif · paksa 1 video × 30 detik ☕' : 'Auto prompt nonaktif'); } else toast('Skrip auto prompt belum siap. Muat ulang Dola.');
      paint();
    },
    async toggleImageNote() {
      const st = await S.get(['imageNote', 'lic']); const next = !st.imageNote;
      if (next && !licensed(st.lic)) return requireLicense();
      const r = await rpc('imageNote', { enabled: next });
      if (r && r.ok) { await S.set({ imageNote: next }); toast(next ? 'Image referensi aktif' : 'Image referensi nonaktif'); } else toast('Skrip auto prompt belum siap. Muat ulang Dola.');
      paint();
    }
  };

  chrome.runtime.onMessage.addListener((m, sender, send) => {
    (async () => {
      switch (m && m.type) {
        case 'scan': return send({ ok: true, videos: await scan() });
        case 'attach': return send(await actions.toggleMd(), true);
        case 'setAutoPrompt': { const r = await rpc('autoPrompt', { enabled: !!m.enabled }); if (r && r.ok) await S.set({ autoPrompt: !!m.enabled }); paint(); return send(r); }
        case 'setImageNote': { const r = await rpc('imageNote', { enabled: !!m.enabled }); if (r && r.ok) await S.set({ imageNote: !!m.enabled }); paint(); return send(r); }
        case 'localStorageGet': { const o = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); o[k] = localStorage.getItem(k); } return send(o); }
        case 'localStorageSet': { try { localStorage.clear(); for (const k in m.data || {}) localStorage.setItem(k, m.data[k]); } catch (_) {} return send({ ok: true }); }
        case 'closeOverlay': closeDashboard(); return send({ ok: true });
        case 'reload': location.reload(); return send({ ok: true });
        case 'navigate': location.href = m.url || 'https://www.dola.com/'; return send({ ok: true });
        case 'toast': toast(m.text); return send({ ok: true });
        default: return send({ ok: false });
      }
    })();
    return true;
  });

  // ---- dock ----
  let dock, dashBtn, overlay, toastEl, toastTimer;
  function mount() {
    if (dock || !document.documentElement) return;
    dock = document.createElement('div'); dock.id = 'sesi-dock';
    dock.innerHTML = '<button data-k="md" title="Lampirkan skill MD">💉</button><button data-k="ap" title="Auto prompt (Seedance 2.5, 30 detik)">⚡</button><button data-k="img" title="Catatan image referensi">🖼</button>';
    dock.addEventListener('click', e => { const b = e.target.closest('button'); if (!b) return; ({ md: actions.toggleMd, ap: actions.toggleAutoPrompt, img: actions.toggleImageNote })[b.dataset.k](); });
    dashBtn = document.createElement('button'); dashBtn.id = 'sesi-dash-btn'; dashBtn.title = 'Dashboard SESI';
    dashBtn.style.backgroundImage = 'url(' + chrome.runtime.getURL('icons/48.png') + ')';
    dashBtn.addEventListener('click', () => overlay ? closeDashboard() : openDashboard());
    document.documentElement.append(dock, dashBtn); paint();
  }
  async function paint() {
    if (!dock) return;
    const st = await S.get(['lic', 'autoPrompt', 'imageNote', 'mdArmed']); const lic = licensed(st.lic);
    const set = (k, on) => { const b = dock.querySelector('[data-k="' + k + '"]'); b.dataset.on = String(!!on && lic); b.dataset.lock = String(!lic); };
    set('md', st.mdArmed); set('ap', st.autoPrompt); set('img', st.imageNote);
  }
  function openDashboard() {
    if (overlay) return;
    overlay = document.createElement('div'); overlay.id = 'sesi-overlay';
    const f = document.createElement('iframe'); f.src = chrome.runtime.getURL('dashboard.html?embed=1'); overlay.appendChild(f);
    overlay.addEventListener('click', e => { if (e.target === overlay) closeDashboard(); });
    document.documentElement.appendChild(overlay);
  }
  function closeDashboard() { if (overlay) { overlay.remove(); overlay = null; } paint(); }
  function toast(text) {
    if (!toastEl) { toastEl = document.createElement('div'); toastEl.id = 'sesi-toast'; document.documentElement.appendChild(toastEl); }
    toastEl.textContent = text; toastEl.style.display = 'block'; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toastEl.style.display = 'none'; }, 2600);
  }
  chrome.storage.onChanged.addListener(ch => { if (ch.lic || ch.autoPrompt || ch.imageNote || ch.mdArmed) paint(); if (ch.lic && !licensed(ch.lic.newValue)) { rpc('autoPrompt', { enabled: false }); rpc('imageNote', { enabled: false }); } });

  if (document.documentElement) mount(); else document.addEventListener('DOMContentLoaded', mount, { once: true });
  new MutationObserver(mount).observe(document, { childList: true, subtree: false });
  ready().then(applySettings);
})();

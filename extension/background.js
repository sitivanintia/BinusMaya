/* SESI MINI extension — service worker: downloads + periodic license re-verification. */
const S = chrome.storage.local;
chrome.runtime.onMessage.addListener((m, sender, send) => {
  if (!m || m.type !== 'download') return false;
  (async () => {
    try {
      const { lic } = await S.get('lic');
      if (!(lic && lic.until > Date.now())) return send({ ok: false, error: 'Aktivasi lisensi terlebih dahulu' });
      const safe = String(m.name || 'dola_video').replace(/[<>:"/\\|?*\x00-\x1F]/g, '').trim() || 'dola_video';
      const id = await chrome.downloads.download({ url: m.url.replace(/^http:\/\//i, 'https://'), filename: 'SesiMini/' + (safe.endsWith('.mp4') ? safe : safe + '.mp4'), conflictAction: 'uniquify', saveAs: false });
      const { dl = {} } = await S.get('dl'); dl[id] = { url: m.url, state: 'in_progress', at: Date.now() }; await S.set({ dl });
      send({ ok: true, id });
    } catch (e) { send({ ok: false, error: String((e && e.message) || e) }); }
  })();
  return true;
});
chrome.downloads.onChanged.addListener(async d => {
  if (!d.state) return;
  const { dl = {} } = await S.get('dl'); if (!dl[d.id]) return;
  dl[d.id].state = d.state.current === 'complete' ? 'complete' : d.state.current === 'interrupted' ? 'interrupted' : 'in_progress';
  if (d.error) dl[d.id].error = d.error.current;
  await S.set({ dl });
});
// Re-verify the license every 6 hours; a definitive server rejection locks the extension.
chrome.alarms.create('lic', { periodInMinutes: 360 });
chrome.alarms.onAlarm.addListener(async a => {
  if (a.name !== 'lic') return;
  const { lic, serverUrl } = await S.get(['lic', 'serverUrl']); if (!lic || !lic.key) return;
  try {
    const r = await (await fetch(serverUrl || DEFAULT_SERVER, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify({ action: 'verify', key: lic.key, deviceId: lic.deviceId, nonce: String(Date.now()), note: 'SesiMini ext ' + chrome.runtime.getManifest().version }) })).json();
    if (r.ok) await S.set({ lic: Object.assign(lic, { until: r.graceUntil, name: r.name, expiresAt: r.expiresAt, checkedAt: Date.now() }) });
    else if (['revoked', 'expired', 'not_found', 'device_mismatch'].includes(r.reason)) await S.set({ lic: Object.assign(lic, { until: 0, lost: r.reason }) });
  } catch (_) {}
});
const DEFAULT_SERVER = 'https://script.google.com/macros/s/AKfycbz71kYSLnl3Gwxnm5S3g1dPN5u8m19RRjniHrxlWkTUlF_NV8sF43nKbnYAtFA1NOU3oQ/exec';

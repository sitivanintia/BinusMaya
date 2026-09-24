/**
 * SESI MINI — server lisensi online (Google Apps Script + Google Sheet).
 *
 * Sheet "Licenses" kolom:
 *   A Key | B Nama | C Status (active/revoked) | D Max Perangkat | E Perangkat (dipisah koma)
 *   F Kedaluwarsa (tanggal, kosong = lifetime) | G Diaktifkan | H Terakhir Dilihat | I Catatan
 *
 * Deploy: Deploy → New deployment → Web app → Execute as: Me · Who has access: Anyone.
 * Jalankan setupSheet() sekali dari editor, lalu generateKeys(...) untuk membuat key.
 */
const SHEET = 'Licenses';
const GRACE_MS = 3 * 24 * 60 * 60 * 1000; // offline grace: cached activation stays valid this long
const ALPHA = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

function doGet(e) { return handle((e && e.parameter) || {}); }
function doPost(e) {
  let body = {};
  try { body = JSON.parse(e.postData.contents || '{}'); } catch (_) { body = (e && e.parameter) || {}; }
  return handle(body);
}

function handle(req) {
  const out = ContentService.createTextOutput().setMimeType(ContentService.MimeType.JSON);
  const action = String(req.action || '');
  if (!action) return out.setContent(JSON.stringify({ ok: true, service: 'sesi-license', time: Date.now() }));
  if (action === 'updates') return out.setContent(JSON.stringify(updatesManifest()));
  if (action === 'update_file') return out.setContent(JSON.stringify(updateFile(req)));
  if (action.startsWith('admin_')) return out.setContent(JSON.stringify(admin(action, req)));
  const key = normalizeKey(req.key), deviceId = String(req.deviceId || '').trim().toUpperCase(), nonce = String(req.nonce || '');
  if (!/^SESI-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$/.test(key) || !/^[2-9A-HJ-NP-Z]{6}$/.test(deviceId)) {
    return out.setContent(JSON.stringify({ ok: false, reason: 'bad_request', nonce }));
  }
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const sheet = SpreadsheetApp.getActive().getSheetByName(SHEET);
    if (!sheet) return out.setContent(JSON.stringify({ ok: false, reason: 'server_setup', nonce }));
    const rows = sheet.getDataRange().getValues();
    let rowIndex = -1;
    for (let i = 1; i < rows.length; i++) if (normalizeKey(rows[i][0]) === key) { rowIndex = i; break; }
    if (rowIndex < 0) return out.setContent(JSON.stringify({ ok: false, reason: 'not_found', nonce }));
    const row = rows[rowIndex];
    const status = String(row[2] || 'active').toLowerCase();
    const maxDevices = Math.max(1, Number(row[3]) || 1);
    const devices = String(row[4] || '').split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
    const expiresAt = row[5] instanceof Date ? row[5].getTime() : (row[5] ? new Date(row[5]).getTime() : 0);
    const now = Date.now();
    if (status !== 'active') return out.setContent(JSON.stringify({ ok: false, reason: 'revoked', nonce }));
    if (expiresAt && now > expiresAt) return out.setContent(JSON.stringify({ ok: false, reason: 'expired', expiresAt, nonce }));

    const r = rowIndex + 1;
    if (action === 'deactivate') {
      const rest = devices.filter(d => d !== deviceId);
      sheet.getRange(r, 5).setValue(rest.join(', '));
      return out.setContent(JSON.stringify({ ok: true, released: rest.length !== devices.length, nonce }));
    }
    if (action !== 'activate' && action !== 'verify') return out.setContent(JSON.stringify({ ok: false, reason: 'bad_request', nonce }));
    if (!devices.includes(deviceId)) {
      if (action === 'verify') return out.setContent(JSON.stringify({ ok: false, reason: 'device_mismatch', nonce }));
      if (devices.length >= maxDevices) return out.setContent(JSON.stringify({ ok: false, reason: 'device_limit', maxDevices, nonce }));
      devices.push(deviceId);
      sheet.getRange(r, 5).setValue(devices.join(', '));
      if (!row[6]) sheet.getRange(r, 7).setValue(new Date());
    }
    sheet.getRange(r, 8).setValue(new Date());
    if (req.note) sheet.getRange(r, 9).setValue(String(req.note).slice(0, 200));
    const graceUntil = expiresAt ? Math.min(expiresAt, now + GRACE_MS) : now + GRACE_MS;
    return out.setContent(JSON.stringify({ ok: true, status: 'active', name: String(row[1] || ''), expiresAt, graceUntil, devices: devices.length, maxDevices, serverTime: now, deviceId, nonce }));
  } finally { lock.releaseLock(); }
}

function normalizeKey(v) { return String(v || '').trim().toUpperCase().replace(/[^A-Z0-9]/g, '').replace(/^SESI/, '').replace(/(.{4})(?=.)/g, '$1-').replace(/^/, 'SESI-'); }

/** Jalankan sekali: membuat sheet Licenses dengan header. */
function setupSheet() {
  const ss = SpreadsheetApp.getActive();
  let sheet = ss.getSheetByName(SHEET);
  if (!sheet) sheet = ss.insertSheet(SHEET);
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(['Key', 'Nama', 'Status', 'Max Perangkat', 'Perangkat', 'Kedaluwarsa', 'Diaktifkan', 'Terakhir Dilihat', 'Catatan']);
    sheet.setFrozenRows(1);
    sheet.getRange('A:A').setNumberFormat('@'); sheet.getRange('F:H').setNumberFormat('yyyy-mm-dd hh:mm');
  }
}

/** Buat key baru. Contoh dari editor: generateKeys(5, 1, 0, 'Batch September') → 5 key lifetime, 1 perangkat. days=30 → berlaku 30 hari. */
function generateKeys(count, maxDevices, days, name) {
  setupSheet();
  const sheet = SpreadsheetApp.getActive().getSheetByName(SHEET);
  const existing = new Set(sheet.getDataRange().getValues().slice(1).map(r => normalizeKey(r[0])));
  const made = [];
  for (let i = 0; i < (count || 1); i++) {
    let key;
    do { key = 'SESI-' + [0, 1, 2].map(() => randomBlock(4)).join('-'); } while (existing.has(key));
    existing.add(key);
    const exp = days > 0 ? new Date(Date.now() + days * 86400000) : '';
    sheet.appendRow([key, name || '', 'active', maxDevices || 1, '', exp, '', '', '']);
    made.push(key);
  }
  Logger.log(made.join('\n'));
  return made;
}

function randomBlock(n) { let s = ''; for (let i = 0; i < n; i++) s += ALPHA.charAt(Math.floor(Math.random() * ALPHA.length)); return s; }

// ---------------------------------------------------------------------------------------------
// Update system: satu folder Google Drive. Drop file ke folder; versi dibaca dari NAMA file.
//   Introvert-Dreams-SKILL-v6.md  → skill_md v6      auto-prompt-v2.js → auto_prompt v2
//   single-clip-enforcer-v2.js    → enforcer v2      inject-v2.js / collector-v2.js → collector v2
// Server membaca folder sebagai pemilik, jadi file tidak perlu di-share publik.
// Isi UPDATES_FOLDER dengan link/ID folder, atau Run setUpdatesFolder() setelah menempel link di dalamnya.
// ---------------------------------------------------------------------------------------------
const UPDATES_FOLDER = '';
function setUpdatesFolder() { const link = 'TEMPEL_LINK_FOLDER_DI_SINI'; PropertiesService.getScriptProperties().setProperty('UPDATES_FOLDER', link); Logger.log('Folder update disimpan: ' + folderId()); }
function folderId() {
  const raw = PropertiesService.getScriptProperties().getProperty('UPDATES_FOLDER') || UPDATES_FOLDER;
  const m = String(raw || '').match(/folders\/([\w-]+)/) || String(raw || '').match(/[?&]id=([\w-]+)/) || String(raw || '').match(/^([\w-]{20,})$/);
  return m ? m[1] : '';
}
const ASSET_RULES = [
  { id: 'skill_md',    test: n => /\.md$/i.test(n) && /skill|introvert|system/i.test(n) },
  { id: 'auto_prompt', test: n => /auto[-_ ]?prompt/i.test(n) },
  { id: 'enforcer',    test: n => /enforcer|single[-_ ]?clip|30s|paksa/i.test(n) },
  { id: 'collector',   test: n => /inject|collector|detect/i.test(n) },
];
function versionOf(name) {
  const base = name.replace(/\.[a-z0-9]+$/i, '');
  const m = base.match(/[-_ ]v?(\d+(?:\.\d+)*)$/i) || base.match(/v(\d+(?:\.\d+)*)/i);
  return m ? m[1] : '';
}
function cmpVer(a, b) { const x = a.split('.').map(Number), y = b.split('.').map(Number); for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d; } return 0; }
function updatesManifest() {
  const id = folderId();
  if (!id) return { ok: false, reason: 'no_folder', assets: [] };
  let folder; try { folder = DriveApp.getFolderById(id); } catch (e) { return { ok: false, reason: 'folder_access', assets: [] }; }
  const best = {};
  const files = folder.getFiles();
  while (files.hasNext()) {
    const f = files.next(); const name = f.getName(); const ver = versionOf(name); if (!ver) continue;
    const rule = ASSET_RULES.find(r => r.test(name)); if (!rule) continue;
    if (!best[rule.id] || cmpVer(ver, best[rule.id].version) > 0) best[rule.id] = { id: rule.id, version: 'v' + ver, filename: name, fileId: f.getId(), size: f.getSize(), updated: f.getLastUpdated().getTime() };
  }
  return { ok: true, assets: Object.values(best), folder: folder.getName(), serverTime: Date.now() };
}
/** Isi file dikirim langsung oleh server (base64), sehingga folder boleh privat. Hanya file dari folder update. */
function updateFile(req) {
  const id = folderId(), fileId = String(req.fileId || '');
  if (!id || !fileId) return { ok: false, reason: 'bad_request' };
  try {
    const f = DriveApp.getFileById(fileId);
    let inFolder = false; const parents = f.getParents(); while (parents.hasNext()) if (parents.next().getId() === id) inFolder = true;
    if (!inFolder) return { ok: false, reason: 'not_in_folder' };
    if (f.getSize() > 2 * 1024 * 1024) return { ok: false, reason: 'too_large' };
    return { ok: true, name: f.getName(), size: f.getSize(), content: Utilities.base64Encode(f.getBlob().getBytes()) };
  } catch (e) { return { ok: false, reason: 'file_access' }; }
}

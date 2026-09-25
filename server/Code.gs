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
const CODE_VERSION = 8; // dikembalikan oleh action=version untuk memastikan kode yang ter-deploy lengkap

/** ► JALANKAN INI SEKALI setelah menempel: pilih fungsi "setup" → Run. Menyetujui izin Sheet + Drive,
 *  membuat sheet Licenses, membuat/menampilkan token admin, dan memastikan seluruh file tertempel utuh. */
function setup() {
  const missing = ['handle', 'admin', 'listLicenses', 'setupAdmin', 'updatesManifest', 'updateFile', 'generateKeys', 'agentAdmin', 'agentChat', 'storeReport'].filter(n => typeof globalThis[n] !== 'function');
  if (missing.length) throw new Error('KODE TIDAK LENGKAP — fungsi hilang: ' + missing.join(', ') + '. Tempel ulang seluruh isi Code.gs (±200 baris).');
  setupSheet();
  const token = setupAdmin();
  let folder = 'BELUM DIATUR'; try { folder = DriveApp.getFolderById(folderId()).getName(); } catch (e) { folder = 'TIDAK BISA DIAKSES: ' + e.message; }
  Logger.log('\n=== SESI MINI SERVER v' + CODE_VERSION + ' ===\nSheet Licenses : OK\nToken admin    : ' + token + '\nFolder update  : ' + folder + '\n\nSelanjutnya: Deploy → Manage deployments → ✏ → New version → Deploy');
}

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
  const has = n => typeof globalThis[n] === 'function';
  if (action === 'version') return out.setContent(JSON.stringify({ ok: true, version: CODE_VERSION, complete: ['admin', 'listLicenses', 'updatesManifest', 'updateFile', 'agentAdmin', 'agentChat', 'storeReport'].every(has), agent: has('agentAdmin'), adminReady: !!PropertiesService.getScriptProperties().getProperty('ADMIN_TOKEN'), folder: folderId() }));
  // Defensive dispatch: a truncated paste yields a clear JSON error instead of an HTML crash page.
  if (action === 'updates') return out.setContent(JSON.stringify(has('updatesManifest') ? updatesManifest() : { ok: false, reason: 'code_incomplete' }));
  if (action === 'update_file') return out.setContent(JSON.stringify(has('updateFile') ? updateFile(req) : { ok: false, reason: 'code_incomplete' }));
  if (action === 'report') return out.setContent(JSON.stringify(has('storeReport') ? storeReport(req) : { ok: false, reason: 'code_incomplete' }));
  if (action.startsWith('admin_')) return out.setContent(JSON.stringify(has('admin') ? admin(action, req) : { ok: false, reason: 'code_incomplete', nonce: String(req.nonce || '') }));
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
// Admin API (dipakai APK SESI Admin). Token disimpan di Script Properties, bukan di kode.
// Jalankan setupAdmin() sekali dari editor: token dicetak di Execution log → masukkan ke APK Admin.
// ---------------------------------------------------------------------------------------------
function setupAdmin() {
  setupSheet();
  const props = PropertiesService.getScriptProperties();
  let token = props.getProperty('ADMIN_TOKEN');
  if (!token) { token = 'ADM-' + randomBlock(6) + '-' + randomBlock(6) + '-' + randomBlock(6); props.setProperty('ADMIN_TOKEN', token); }
  Logger.log('ADMIN TOKEN (masukkan ke APK SESI Admin):\n' + token);
  return token;
}
/** Ganti token bila bocor. */
function resetAdminToken() { PropertiesService.getScriptProperties().deleteProperty('ADMIN_TOKEN'); return setupAdmin(); }

function admin(action, req) {
  const nonce = String(req.nonce || '');
  const expected = PropertiesService.getScriptProperties().getProperty('ADMIN_TOKEN');
  if (!expected) return { ok: false, reason: 'admin_setup', nonce };
  if (String(req.adminToken || '') !== expected) return { ok: false, reason: 'unauthorized', nonce };
  const lock = LockService.getScriptLock(); lock.waitLock(10000);
  try {
    setupSheet();
    const sheet = SpreadsheetApp.getActive().getSheetByName(SHEET);
    if (action === 'admin_ping') return { ok: true, nonce };
    if (action === 'admin_create') {
      const keys = generateKeys(Math.min(50, Math.max(1, Number(req.count) || 1)), Math.max(1, Number(req.maxDevices) || 1), Math.max(0, Number(req.days) || 0), String(req.name || '').slice(0, 60));
      return { ok: true, keys, nonce, licenses: listLicenses(sheet) };
    }
    if (action === 'admin_list') return { ok: true, nonce, licenses: listLicenses(sheet), serverTime: Date.now() };
    if (action.startsWith('admin_ai_') || action === 'admin_agent' || action === 'admin_reports' || action.startsWith('admin_draft')) return Object.assign(agentAdmin(action, req), { nonce });
    const key = normalizeKey(req.key);
    const rows = sheet.getDataRange().getValues();
    let r = -1; for (let i = 1; i < rows.length; i++) if (normalizeKey(rows[i][0]) === key) { r = i + 1; break; }
    if (r < 0) return { ok: false, reason: 'not_found', nonce };
    if (action === 'admin_set_status') sheet.getRange(r, 3).setValue(String(req.status) === 'revoked' ? 'revoked' : 'active');
    else if (action === 'admin_rename') sheet.getRange(r, 2).setValue(String(req.name || '').slice(0, 60));
    else if (action === 'admin_set_max') sheet.getRange(r, 4).setValue(Math.max(1, Number(req.maxDevices) || 1));
    else if (action === 'admin_set_expiry') sheet.getRange(r, 6).setValue(Number(req.days) > 0 ? new Date(Date.now() + Number(req.days) * 86400000) : '');
    else if (action === 'admin_release') {
      const dev = String(req.deviceId || '').trim().toUpperCase();
      const devices = String(rows[r - 1][4] || '').split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
      sheet.getRange(r, 5).setValue((dev ? devices.filter(d => d !== dev) : []).join(', '));
    }
    else if (action === 'admin_delete') sheet.deleteRow(r);
    else return { ok: false, reason: 'bad_request', nonce };
    return { ok: true, nonce, licenses: listLicenses(sheet) };
  } finally { lock.releaseLock(); }
}

function listLicenses(sheet) {
  const rows = sheet.getDataRange().getValues();
  const toMs = v => v instanceof Date ? v.getTime() : (v ? new Date(v).getTime() || 0 : 0);
  const list = [];
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i]; if (!row[0]) continue;
    list.push({ key: normalizeKey(row[0]), name: String(row[1] || ''), status: String(row[2] || 'active').toLowerCase(), maxDevices: Number(row[3]) || 1,
      devices: String(row[4] || '').split(',').map(s => s.trim().toUpperCase()).filter(Boolean), expiresAt: toMs(row[5]), activatedAt: toMs(row[6]), lastSeen: toMs(row[7]), note: String(row[8] || '') });
  }
  return list;
}

// ---------------------------------------------------------------------------------------------
// Update system: satu folder Google Drive. Drop file ke folder; versi dibaca dari NAMA file.
//   Introvert-Dreams-SKILL-v6.md  → skill_md v6      auto-prompt-v2.js → auto_prompt v2
//   single-clip-enforcer-v2.js    → enforcer v2      inject-v2.js / collector-v2.js → collector v2
// Server membaca folder sebagai pemilik, jadi file tidak perlu di-share publik.
// Isi UPDATES_FOLDER dengan link/ID folder, atau Run setUpdatesFolder() setelah menempel link di dalamnya.
// ---------------------------------------------------------------------------------------------
const UPDATES_FOLDER = 'https://drive.google.com/drive/folders/1Ykim3c6gKPAf1SqOHRqCr239jYr1N0JT';
function setUpdatesFolder() { const link = 'https://drive.google.com/drive/folders/1Ykim3c6gKPAf1SqOHRqCr239jYr1N0JT?usp=drive_link'; PropertiesService.getScriptProperties().setProperty('UPDATES_FOLDER', link); Logger.log('Folder update disimpan: ' + folderId()); }
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

// ---------------------------------------------------------------------------------------------
// Diagnostik: APK/extension mengirim laporan anonim (komponen, event, detail) → sheet "Reports".
// Agent AI membacanya untuk melihat perubahan perilaku Dola (deteksi gagal, download 403, dsb.).
// ---------------------------------------------------------------------------------------------
const REPORTS_SHEET = 'Reports', REPORTS_MAX = 2000;
function reportsSheet() {
  const ss = SpreadsheetApp.getActive(); let s = ss.getSheetByName(REPORTS_SHEET);
  if (!s) { s = ss.insertSheet(REPORTS_SHEET); s.appendRow(['Waktu', 'Device', 'App', 'Komponen', 'Event', 'Detail']); s.setFrozenRows(1); }
  return s;
}
function storeReport(req) {
  const dev = String(req.deviceId || '').toUpperCase().slice(0, 12), comp = String(req.component || '').slice(0, 40), ev = String(req.event || '').slice(0, 80);
  if (!comp || !ev) return { ok: false, reason: 'bad_request' };
  const s = reportsSheet();
  const vers = req.versions && typeof req.versions === 'object' ? ' [' + Object.keys(req.versions).map(k => k + '=' + req.versions[k]).join(' ') + ']' : '';
  s.appendRow([new Date(), dev, (String(req.app || '') + vers).slice(0, 160), comp, ev, String(req.detail || '').slice(0, 4000)]);
  if (s.getLastRow() > REPORTS_MAX + 1) s.deleteRows(2, s.getLastRow() - REPORTS_MAX - 1);
  return { ok: true };
}
function listReports(limit) {
  const s = reportsSheet(); const n = s.getLastRow() - 1; if (n <= 0) return [];
  const take = Math.min(n, Math.max(1, limit || 50));
  return s.getRange(n - take + 2, 1, take, 6).getValues().reverse().map(r => ({ time: r[0] instanceof Date ? r[0].toISOString() : String(r[0]), device: r[1], app: r[2], component: r[3], event: r[4], detail: r[5] }));
}

// ---------------------------------------------------------------------------------------------
// AI Agent (provider OpenAI-compatible). Konfigurasi di Script Properties: AI_BASE_URL, AI_API_KEY, AI_MODEL.
// Tools: read_asset, write_draft, publish_draft, list_drafts, syntax_check, get_reports, list_licenses.
// Draft disimpan di sub-folder "drafts" dan hanya aktif setelah admin menekan Terapkan (publish_draft).
// ---------------------------------------------------------------------------------------------
const ASSET_IDS = ['skill_md', 'auto_prompt', 'enforcer', 'collector'];
const ASSET_FILES = { skill_md: ['Introvert-Dreams-SKILL', 'md'], auto_prompt: ['auto-prompt', 'js'], enforcer: ['single-clip-enforcer', 'js'], collector: ['inject', 'js'] };
const BUNDLED_RAW = 'https://raw.githubusercontent.com/sitivanintia/BinusMaya/hoplite/andros-901d108b/app/src/main/assets/';
const BUNDLED_NAMES = { skill_md: 'Introvert-Dreams-SKILL-v5.md', auto_prompt: 'auto-prompt.js', enforcer: 'single-clip-enforcer.js', collector: 'inject.js' };
const BUNDLED_VER = { skill_md: 'v5', auto_prompt: 'v1', enforcer: 'v1', collector: 'v1' };

function aiProps() { const p = PropertiesService.getScriptProperties(); return { baseUrl: (p.getProperty('AI_BASE_URL') || '').replace(/\/+$/, ''), apiKey: p.getProperty('AI_API_KEY') || '', model: p.getProperty('AI_MODEL') || '' }; }
function aiFetch(path, payload) {
  const c = aiProps(); if (!c.baseUrl || !c.apiKey) throw new Error('ai_not_configured');
  const opt = { method: payload ? 'post' : 'get', headers: { Authorization: 'Bearer ' + c.apiKey, 'HTTP-Referer': 'https://sesi-mini', 'X-Title': 'SESI MINI Agent' }, muteHttpExceptions: true, followRedirects: true };
  if (payload) { opt.contentType = 'application/json'; opt.payload = JSON.stringify(payload); }
  const r = UrlFetchApp.fetch(c.baseUrl + path, opt); const code = r.getResponseCode(); const text = r.getContentText();
  if (code < 200 || code >= 300) throw new Error('Provider HTTP ' + code + ': ' + text.slice(0, 300));
  return JSON.parse(text);
}
function listModels(refresh) {
  const cache = CacheService.getScriptCache(); const key = 'ai_models';
  if (!refresh) { const hit = cache.get(key); if (hit) return JSON.parse(hit); }
  const r = aiFetch('/models'); const ids = (r.data || r.models || []).map(m => m.id || m.name).filter(Boolean).sort();
  try { cache.put(key, JSON.stringify(ids), 21600); } catch (_) {}
  return ids;
}
function draftsFolder() { const root = DriveApp.getFolderById(folderId()); const it = root.getFoldersByName('drafts'); return it.hasNext() ? it.next() : root.createFolder('drafts'); }
function latestAsset(id) {
  const m = updatesManifest(); const a = (m.assets || []).find(x => x.id === id);
  if (a) { const f = DriveApp.getFileById(a.fileId); return { id, version: a.version, filename: a.filename, source: 'drive', content: f.getBlob().getDataAsString('UTF-8') }; }
  const r = UrlFetchApp.fetch(BUNDLED_RAW + BUNDLED_NAMES[id], { muteHttpExceptions: true });
  if (r.getResponseCode() !== 200) throw new Error('aset bawaan tidak bisa diambil (' + r.getResponseCode() + ')');
  return { id, version: BUNDLED_VER[id], filename: BUNDLED_NAMES[id], source: 'bundled', content: r.getContentText() };
}
function nextVersion(cur) { const m = String(cur || '').match(/(\d+)(?:\.(\d+))?/); return 'v' + ((m ? Number(m[1]) : 0) + 1); }
function listDrafts() { const out = []; const it = draftsFolder().getFiles(); while (it.hasNext()) { const f = it.next(); out.push({ fileId: f.getId(), name: f.getName(), size: f.getSize(), updated: f.getLastUpdated().getTime(), note: f.getDescription() || '' }); } return out.sort((a, b) => b.updated - a.updated); }
function writeDraft(id, content, note) {
  if (!ASSET_IDS.includes(id)) throw new Error('asset id tidak dikenal');
  if (typeof content !== 'string' || content.length < 20) throw new Error('isi kosong');
  if (ASSET_FILES[id][1] === 'js') { const chk = syntaxCheck(content); if (!chk.ok) throw new Error('syntax error: ' + chk.error); }
  const cur = latestAsset(id); const ver = nextVersion(cur.version);
  const name = ASSET_FILES[id][0] + '-' + ver + '.' + ASSET_FILES[id][1];
  const folder = draftsFolder(); const old = folder.getFilesByName(name); while (old.hasNext()) old.next().setTrashed(true);
  const f = folder.createFile(name, content, ASSET_FILES[id][1] === 'md' ? 'text/markdown' : 'application/javascript'); f.setDescription(String(note || '').slice(0, 500));
  return { ok: true, fileId: f.getId(), name, version: ver, from: cur.version, bytes: content.length };
}
function publishDraft(fileId) {
  const f = DriveApp.getFileById(fileId); const drafts = draftsFolder(); let inDrafts = false; const ps = f.getParents(); while (ps.hasNext()) if (ps.next().getId() === drafts.getId()) inDrafts = true;
  if (!inDrafts) throw new Error('bukan draft');
  const root = DriveApp.getFolderById(folderId()); f.moveTo(root);
  return { ok: true, name: f.getName(), published: true };
}
function syntaxCheck(code) { try { new Function(code); return { ok: true }; } catch (e) { return { ok: false, error: String(e && e.message || e) }; } }

const AGENT_TOOLS = [
  { type: 'function', function: { name: 'read_asset', description: 'Baca isi terbaru sebuah komponen (skill_md = System MD; auto_prompt; enforcer = paksa 1 video 30 detik; collector = pendeteksi video inject.js). Mengembalikan versi, nama file dan isi lengkap.', parameters: { type: 'object', properties: { id: { type: 'string', enum: ASSET_IDS } }, required: ['id'] } } },
  { type: 'function', function: { name: 'write_draft', description: 'Simpan versi baru sebuah komponen sebagai DRAFT (belum aktif untuk user). Isi harus FILE LENGKAP, bukan potongan. JS diperiksa sintaksnya. Versi otomatis naik (v5→v6).', parameters: { type: 'object', properties: { id: { type: 'string', enum: ASSET_IDS }, content: { type: 'string' }, note: { type: 'string', description: 'ringkasan perubahan' } }, required: ['id', 'content', 'note'] } } },
  { type: 'function', function: { name: 'list_drafts', description: 'Daftar draft yang menunggu persetujuan admin.', parameters: { type: 'object', properties: {} } } },
  { type: 'function', function: { name: 'syntax_check', description: 'Periksa sintaks JavaScript sebelum menyimpan.', parameters: { type: 'object', properties: { code: { type: 'string' } }, required: ['code'] } } },
  { type: 'function', function: { name: 'get_reports', description: 'Laporan diagnostik terbaru dari aplikasi pengguna (deteksi gagal, download gagal, skrip tidak siap, struktur respons Dola). Gunakan untuk memahami perubahan perilaku Dola.', parameters: { type: 'object', properties: { limit: { type: 'integer' }, component: { type: 'string' } } } } },
  { type: 'function', function: { name: 'list_licenses', description: 'Ringkasan lisensi (jumlah aktif, dicabut, perangkat, online 24 jam) dan 20 entri terbaru.', parameters: { type: 'object', properties: {} } } }
];
const AGENT_SYSTEM = 'Kamu adalah AI Agent developer untuk SESI MINI (aplikasi Android + extension pendamping Dola/Doubao: pendeteksi video HD tanpa watermark, auto prompt Seedance 2.5 / 30 detik, System MD yang dilampirkan ke chat). '
  + 'Tugasmu: menyesuaikan System MD dan skrip JS saat perilaku Dola berubah, berdasarkan permintaan admin dan laporan diagnostik. Alur kerja: (1) baca laporan (get_reports) dan aset terkait (read_asset), (2) analisis akar masalah, (3) buat perubahan MINIMAL dan aman, (4) untuk JS jalankan syntax_check, (5) simpan dengan write_draft berisi FILE LENGKAP, (6) jelaskan singkat apa yang berubah dan risiko. '
  + 'Jangan pernah mengubah perilaku yang tidak diminta, jangan menghapus fitur, jangan menaruh secret. Skrip berjalan di document_start pada halaman dola.com (MAIN world), boleh memakai window.IDBridge?.onVideo(json), window.IDBridge?.onReport(json). Format balasan SELALU: **Masalah** (1 kalimat) · **Penyebab** · **Yang harus dilakukan** (langkah konkret untuk developer) · **Tindakan agent** (draft yang dibuat / tidak ada). Bila konteks real-time menunjukkan semuanya normal, katakan normal dan sebutkan hal yang perlu dipantau. Balas dalam Bahasa Indonesia, ringkas.';

function runTool(name, args) {
  switch (name) {
    case 'read_asset': return latestAsset(String(args.id));
    case 'write_draft': return writeDraft(String(args.id), String(args.content), args.note);
    case 'list_drafts': return { drafts: listDrafts() };
    case 'syntax_check': return syntaxCheck(String(args.code || ''));
    case 'get_reports': { let r = listReports(Math.min(200, Number(args.limit) || 50)); if (args.component) r = r.filter(x => String(x.component).toLowerCase().includes(String(args.component).toLowerCase())); return { count: r.length, reports: r }; }
    case 'list_licenses': { const l = listLicenses(SpreadsheetApp.getActive().getSheetByName(SHEET)); const now = Date.now(); return { total: l.length, active: l.filter(x => x.status === 'active').length, revoked: l.filter(x => x.status === 'revoked').length, devices: l.reduce((s, x) => s + x.devices.length, 0), online24h: l.filter(x => now - x.lastSeen < 86400000).length, latest: l.slice(-20) }; }
    default: throw new Error('tool tidak dikenal: ' + name);
  }
}
function agentChat(messages, context) {
  const c = aiProps(); if (!c.model) throw new Error('model belum dipilih');
  const msgs = [{ role: 'system', content: AGENT_SYSTEM }];
  // Real-time snapshot from the developer app (URL, component versions, collector stats, recent events).
  if (context) msgs.push({ role: 'system', content: 'KONTEKS REAL-TIME dari aplikasi developer (JSON). Gunakan ini sebagai sumber utama diagnosis; laporan historis ada di get_reports.\n' + String(context).slice(0, 30000) });
  for (const m of messages) msgs.push(m);
  const events = []; const started = Date.now();
  for (let step = 0; step < 8; step++) {
    if (Date.now() - started > 270000) { events.push({ type: 'note', text: 'Batas waktu server; kirim "lanjutkan" untuk meneruskan.' }); break; }
    const r = aiFetch('/chat/completions', { model: c.model, messages: msgs, tools: AGENT_TOOLS, tool_choice: 'auto', temperature: 0.2 });
    const m = r.choices && r.choices[0] && r.choices[0].message; if (!m) throw new Error('respons provider kosong');
    msgs.push(m);
    if (!m.tool_calls || !m.tool_calls.length) { events.push({ type: 'assistant', text: m.content || '' }); break; }
    for (const tc of m.tool_calls) {
      let args = {}; try { args = JSON.parse(tc.function.arguments || '{}'); } catch (_) {}
      let result; try { result = runTool(tc.function.name, args); } catch (e) { result = { error: String(e && e.message || e) }; }
      const summary = tc.function.name === 'write_draft' ? (result.error ? 'gagal: ' + result.error : 'draft ' + result.name + ' (' + result.from + ' → ' + result.version + ')') : tc.function.name === 'read_asset' ? (result.error || (result.filename + ' ' + result.version + ' · ' + result.content.length + ' char')) : JSON.stringify(result).slice(0, 160);
      events.push({ type: 'tool', name: tc.function.name, args: tc.function.name === 'write_draft' ? { id: args.id, note: args.note } : args, summary, draft: tc.function.name === 'write_draft' && result.ok ? result : undefined });
      msgs.push({ role: 'tool', tool_call_id: tc.id, content: JSON.stringify(result).slice(0, 200000) });
    }
  }
  // Return the transcript without the system prompt so the client can continue the conversation.
  return { events, messages: msgs.slice(1) };
}

function agentAdmin(action, req) {
  try {
    const p = PropertiesService.getScriptProperties();
    if (action === 'admin_ai_get') { const c = aiProps(); return { ok: true, baseUrl: c.baseUrl, model: c.model, hasKey: !!c.apiKey }; }
    if (action === 'admin_ai_set') {
      if (req.baseUrl !== undefined) { p.setProperty('AI_BASE_URL', String(req.baseUrl).trim()); CacheService.getScriptCache().remove('ai_models'); }
      if (req.apiKey) { p.setProperty('AI_API_KEY', String(req.apiKey).trim()); CacheService.getScriptCache().remove('ai_models'); }
      if (req.model !== undefined) p.setProperty('AI_MODEL', String(req.model).trim());
      const c = aiProps(); const out = { ok: true, baseUrl: c.baseUrl, model: c.model, hasKey: !!c.apiKey };
      // One round-trip instead of two: the APK asks for the model list right after saving credentials.
      if (req.withModels) { try { out.models = listModels(!!req.refresh); } catch (e) { out.modelsError = String(e && e.message || e); } }
      return out;
    }
    if (action === 'admin_ai_models') return { ok: true, models: listModels(!!req.refresh) };
    if (action === 'admin_agent') { const r = agentChat(Array.isArray(req.messages) ? req.messages : [], req.context ? (typeof req.context === 'string' ? req.context : JSON.stringify(req.context)) : ''); return Object.assign({ ok: true }, r); }
    if (action === 'admin_reports') return { ok: true, reports: listReports(Number(req.limit) || 100) };
    if (action === 'admin_drafts') return { ok: true, drafts: listDrafts() };
    if (action === 'admin_draft_get') { const f = DriveApp.getFileById(String(req.fileId)); const id = ASSET_IDS.find(k => f.getName().startsWith(ASSET_FILES[k][0])); const cur = id ? latestAsset(id) : null; return { ok: true, name: f.getName(), content: f.getBlob().getDataAsString('UTF-8'), note: f.getDescription() || '', current: cur ? { version: cur.version, filename: cur.filename, content: cur.content } : null }; }
    if (action === 'admin_draft_publish') return publishDraft(String(req.fileId));
    if (action === 'admin_draft_upload') { const d = writeDraft(String(req.id), String(req.content || ''), req.note); if (req.publish) return Object.assign(publishDraft(d.fileId), { version: d.version }); return d; }
    if (action === 'admin_draft_delete') { DriveApp.getFileById(String(req.fileId)).setTrashed(true); return { ok: true }; }
    return { ok: false, reason: 'bad_request' };
  } catch (e) { const msg = String(e && e.message || e); return { ok: false, reason: msg === 'ai_not_configured' ? 'ai_not_configured' : 'agent_error', error: msg }; }
}

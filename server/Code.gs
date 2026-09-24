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

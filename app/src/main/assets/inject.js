// Introvert Dreams — Dola video collector (SESI MAX MODE extractor pipeline).
// Sniffs `fallback_api` from Dola API responses, resolves it with logo_type=unwatermarked,
// decodes the main_url token (plain / base64 / qAAB AES-CBC) and reports the real CDN URL.
// Requests are never modified; responses are only cloned and read.
(() => {
  if (window.__idreamsCollector) return; window.__idreamsCollector = true;
  const QAAB_SALT_HEX = '4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58';
  const found = new Map(); const processed = new Set(); let pending = 0;
  // Diagnostics for the developer agent: never includes chat text, only structure/error summaries.
  const reported = new Set();
  const report = (event, detail) => { const k = event + '|' + String(detail).slice(0, 80); if (reported.has(k) || reported.size > 20) return; reported.add(k); try { window.IDBridge?.onReport(JSON.stringify({ component: 'collector', event, detail: String(detail).slice(0, 1500) })); } catch {} };
  const shape = (o, d = 0) => { if (d > 3 || o === null || typeof o !== 'object') return typeof o; if (Array.isArray(o)) return [o.length ? shape(o[0], d + 1) : 'empty']; const r = {}; for (const k of Object.keys(o).slice(0, 25)) r[k] = shape(o[k], d + 1); return r; };
  const isHttp = u => typeof u === 'string' && /^https?:\/\//i.test(u);
  const isDola = u => { try { return /(^|\.)(dola\.com|doubao\.com)$/i.test(new URL(u, location.href).hostname); } catch { return false; } };
  const emit = v => {
    if (!isHttp(v.url)) return;
    // Dola CDN tokens often decode to http://; the CDN serves https and the WebView blocks mixed content.
    v.url = v.url.replace(/^http:\/\//i, 'https://');
    const key = v.vid || v.url;
    if (found.has(key)) return;
    v.name = v.name || ('dola_' + String(found.size + 1).padStart(2, '0') + (v.vid ? '_' + String(v.vid).slice(-6) : ''));
    found.set(key, v);
    try { window.IDBridge?.onVideo(JSON.stringify(v)); } catch {}
  };

  // ---- JSON walking (strings that hold JSON are parsed too, Dola nests payloads several levels deep) ----
  const parseJsonString = t => { const s = t.trim(); if (!s || (s[0] !== '{' && s[0] !== '[')) return null; try { return JSON.parse(s); } catch { return null; } };
  const walk = (v, visit, seen = new Set()) => {
    if (v == null) return;
    if (typeof v === 'string') { const p = parseJsonString(v); if (p !== null) walk(p, visit, seen); return; }
    if (typeof v !== 'object' || seen.has(v)) return;
    seen.add(v); visit(v);
    if (Array.isArray(v)) { for (const i of v) walk(i, visit, seen); return; }
    for (const k of Object.keys(v)) walk(v[k], visit, seen);
  };
  const valuesByKey = (v, key) => { const out = []; walk(v, n => { if (n && typeof n === 'object' && !Array.isArray(n) && Object.prototype.hasOwnProperty.call(n, key)) out.push(n[key]); }); return out; };
  const unescapeFragment = value => {
    let text = value;
    for (let i = 0; i < 3; i++) { try { const d = JSON.parse('"' + text.replace(/"/g, '\\"') + '"'); if (d === text) break; text = d; } catch { break; } }
    return text.replace(/\\u0026/g, '&').replace(/\\\//g, '/');
  };
  const findFallbackApis = (json, raw) => {
    const apis = new Set();
    const add = v => { if (typeof v !== 'string' || !v) return; const u = unescapeFragment(v); if (isHttp(u)) apis.add(u); };
    for (const v of valuesByKey(json, 'fallback_api')) add(v);
    for (const re of [/fallback_api\\":\\"(.*?)\\"/g, /"fallback_api"\s*:\s*"([^"]+)"/g]) { let m; while ((m = re.exec(raw || ''))) add(m[1]); }
    return [...apis];
  };

  // ---- main_url token decoding ----
  const padB64 = t => t + '='.repeat((4 - t.length % 4) % 4);
  const b64Loose = text => {
    const input = String(text || '').trim();
    const variants = [input, input.replace(/[$@#]/g, c => ({ '$': '_', '@': '/', '#': '.' }[c])), input.replace(/[$@#]/g, c => ({ '$': '+', '@': '/', '#': '=' }[c]))];
    const seen = new Set();
    for (const c of variants) {
      if (!c || seen.has(c)) continue; seen.add(c);
      try { const bin = atob(padB64(c).replace(/-/g, '+').replace(/_/g, '/')); const b = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i); return b; } catch {}
    }
    return null;
  };
  const asciiUrl = bytes => { if (!bytes || !bytes.length) return ''; for (const b of bytes) if (b !== 9 && b !== 10 && b !== 13 && (b < 32 || b > 126)) return ''; return new TextDecoder().decode(bytes); };
  const hexToBytes = hex => { const b = new Uint8Array(hex.length / 2); for (let i = 0; i < b.length; i++) b[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16); return b; };
  const concat = (a, b) => { const o = new Uint8Array(a.length + b.length); o.set(a, 0); o.set(b, a.length); return o; };
  const stripPkcs7 = b => { if (!b || !b.length) return new Uint8Array(); const p = b[b.length - 1]; if (p < 1 || p > 16 || p > b.length) return b; for (let i = b.length - p; i < b.length; i++) if (b[i] !== p) return b; return b.slice(0, b.length - p); };
  const aesCbcUrl = async (payload, keyBytes, ivBytes) => {
    if (!payload.length || payload.length % 16 !== 0) return '';
    try {
      const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-CBC', false, ['decrypt']);
      const plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-CBC', iv: ivBytes }, key, payload));
      const direct = asciiUrl(plain); if (isHttp(direct)) return direct;
      const u = asciiUrl(stripPkcs7(plain)); return isHttp(u) ? u : '';
    } catch { return ''; }
  };
  const decodeQaab = async (token, keySeed) => {
    const data = b64Loose(token), seed = b64Loose(keySeed);
    if (!data || !seed) return '';
    const d1 = await crypto.subtle.digest('SHA-512', seed.slice(0, 32));
    const d2 = new Uint8Array(await crypto.subtle.digest('SHA-512', concat(new Uint8Array(d1), hexToBytes(QAAB_SALT_HEX))));
    const key = d2.slice(0, 16), iv = d2.slice(16, 32); const attempts = [];
    if (data.length >= 4 && data[0] === 0xa8 && data[1] === 0x00 && data[2] === 0x01 && data[3] === 0x00) {
      attempts.push({ payload: data.slice(4), key, iv }, { payload: data.slice(4), key: iv, iv: key });
      if (data.length > 36) attempts.push({ payload: data.slice(36), key, iv: data.slice(20, 36) }, { payload: data.slice(36), key, iv });
    } else attempts.push({ payload: data, key, iv });
    for (const a of attempts) { const u = await aesCbcUrl(a.payload, a.key, a.iv); if (u) return u; }
    return '';
  };
  const decodeMainUrl = async (token, keySeed) => {
    if (isHttp(token)) return token;
    const plain = asciiUrl(b64Loose(token)); if (isHttp(plain)) return plain;
    if (token.startsWith('qAAB') && keySeed) return decodeQaab(token, keySeed);
    return '';
  };
  const findKeySeed = (v, depth = 0) => {
    if (depth > 10 || v == null) return '';
    if (typeof v === 'string') {
      let m = v.match(/(?:^|[?&])key_seed=([^&"'<>\\\s]+)/i); if (m) return decodeURIComponent(m[1]);
      m = v.match(/["']key_seed["']\s*:\s*["']([^"']+)/i); return m ? decodeURIComponent(m[1]) : '';
    }
    if (typeof v !== 'object') return '';
    if (typeof v.key_seed === 'string' && v.key_seed.trim()) return v.key_seed.trim();
    for (const item of Object.values(v)) { const hit = findKeySeed(item, depth + 1); if (hit) return hit; }
    return '';
  };

  // ---- fallback_api resolution ----
  const num = (...c) => { for (const v of c) { const n = Number(v); if (Number.isFinite(n) && n > 0) return n; } return 0; };
  const videoData = payload => { const info = payload?.video_info || payload?.data?.video_info || payload; const d = info?.data || info; return d && typeof d === 'object' ? d : {}; };
  const expectedBytes = (e, d) => Math.max(0, ...[e?.file_size, e?.fileSize, e?.filesize, e?.content_length, e?.video_size, d?.file_size, d?.fileSize, d?.content_length].map(Number).filter(Number.isFinite));
  const pickBest = data => {
    const list = data?.video_list && typeof data.video_list === 'object' && Object.keys(data.video_list).length ? Object.values(data.video_list) : [data];
    let best = null;
    for (const e of list) {
      if (!e || typeof e !== 'object') continue;
      const token = e.main_url || e.play_url || ''; if (typeof token !== 'string' || !token.trim()) continue;
      const px = num(e.vwidth, e.width) * num(e.vheight, e.height), br = num(e.bitrate, e.real_bitrate), fs = expectedBytes(e, {});
      if (!best || px > best.px || (px === best.px && br > best.br) || (px === best.px && br === best.br && fs > best.fs)) best = { token: token.trim(), px, br, fs, entry: e };
    }
    return best;
  };
  const ofetch = window.fetch;
  const requestJson = async url => {
    const r = await ofetch.call(window, url, { method: 'GET', credentials: 'omit', headers: { accept: 'application/json,text/plain,*/*' } });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  };
  const resolveFallback = async api => {
    const u = new URL(api); u.searchParams.set('channel', 'no'); u.searchParams.set('codec_type', '8'); u.searchParams.set('logo_type', 'unwatermarked');
    let payload; try { payload = await requestJson(u.toString()); } catch (e) { report('fallback_api_http_error', String(e && e.message || e) + ' ' + u.host + u.pathname); throw e; }
    const data = videoData(payload); const picked = pickBest(data);
    if (!picked) { report('no_main_url_in_payload', JSON.stringify(shape(payload))); return null; }
    const url = await decodeMainUrl(picked.token, findKeySeed(payload));
    if (!url) { report('main_url_decode_failed', 'token prefix=' + picked.token.slice(0, 8) + ' len=' + picked.token.length + ' key_seed=' + (findKeySeed(payload) ? 'yes' : 'no') + ' shape=' + JSON.stringify(shape(data))); return null; }
    const m = picked.entry || {};
    return { vid: data.vid || data.video_id || m.vid || m.video_id || api, url, width: num(m.vwidth, m.width, data.vwidth, data.width), height: num(m.vheight, m.height, data.vheight, data.height),
      definition: m.definition || data.definition || '', duration: num(m.duration, data.duration), poster: data.poster_url || data.poster || '', expectedBytes: expectedBytes(m, data), source: 'fallback_api' };
  };
  const processBody = (json, raw) => {
    const apis = findFallbackApis(json, raw).filter(a => !processed.has(a));
    for (const api of apis) {
      processed.add(api); pending++;
      resolveFallback(api).then(info => { if (info) emit(info); }).catch(() => {}).finally(() => { pending = Math.max(0, pending - 1); });
    }
  };
  const sniffText = text => {
    if (!text || text.length > 12e6) return; sniffed++;
    if (!text.includes('fallback_api')) return; withApi++;
    let data = null; try { data = JSON.parse(text); } catch {}
    if (data === null) { for (const line of text.split(/\r?\n/)) { const m = line.match(/^(?:data:\s*)?([\[{].*)$/); if (m) { try { processBody(JSON.parse(m[1]), m[1]); } catch {} } } }
    processBody(data, text);
  };
  const shouldSniff = (url, res) => {
    try {
      if (!url || !res || !res.ok || /\.(?:mp4|webm|m3u8|ts|jpg|jpeg|png|gif|webp|avif|woff2?|css|js|map)(?:\?|$)/i.test(url)) return false;
      const ct = String(res.headers?.get?.('content-type') || '').toLowerCase();
      return ct.includes('json') || ct.includes('text/plain') || ct.includes('event-stream');
    } catch { return false; }
  };

  window.__idreamsVideos = () => [...found.values()];
  window.__idreamsPending = () => pending;
  let sniffed = 0, withApi = 0;
  window.__idreamsStats = () => ({ sniffed, withApi, found: found.size, pending });
  window.__idreamsScanDom = () => {
    if (sniffed > 0 && withApi === 0 && found.size === 0 && !document.querySelector('video')) report('scan_no_fallback_api', 'sniffed=' + sniffed + ' responses on ' + location.pathname + ' contained no fallback_api');
    if (withApi > 0 && found.size === 0 && pending === 0) report('fallback_api_seen_but_no_video', 'withApi=' + withApi);
    for (const el of document.querySelectorAll('video, video source')) { const u = el.currentSrc || el.src || ''; if (isHttp(u) && !/blob:/.test(u)) emit({ url: u, source: 'dom' }); }
    // Re-parse cached chat payloads embedded in the document (SSR state) for fallback_api entries.
    for (const s of document.querySelectorAll('script:not([src])')) { const t = s.textContent || ''; if (t.includes('fallback_api')) sniffText(t); }
    return found.size;
  };

  // ---- read-only taps ----
  window.fetch = async function (input, init) {
    const res = await ofetch.call(this, input, init);
    try { const u = typeof input === 'string' ? input : input?.url || ''; if (isDola(u) && shouldSniff(u, res)) res.clone().text().then(sniffText).catch(() => {}); } catch {}
    return res;
  };
  const oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u, ...r) { this.__idu = String(u); return oo.call(this, m, u, ...r); };
  XMLHttpRequest.prototype.send = function (b) {
    try { this.addEventListener('load', () => { try { const u = this.__idu || ''; if (isDola(u) && !u.includes('/chat/completion') && typeof this.responseText === 'string') sniffText(this.responseText); } catch {} }); } catch {}
    return os.call(this, b);
  };
})();

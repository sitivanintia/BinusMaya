// Introvert Dreams — HD video collector only. Observes Dola API responses for source-quality
// video URLs (fallback_api / video_list, highest pixels+bitrate). Never modifies any request.
(() => {
  if (window.__idreamsCollector) return; window.__idreamsCollector = true;
  const parse = JSON.parse;
  const isDola = u => { try { return /(^|\.)dola\.com$/i.test(new URL(u, location.href).hostname); } catch { return false; } };
  const found = new Map();
  const emit = v => { if (v.url && !found.has(v.url)) { found.set(v.url, v); try { window.IDBridge?.onVideo(JSON.stringify(v)); } catch {} } };
  const dec = s => s.replace(/\\u0026/g, '&').replace(/\\\//g, '/');
  const bestOf = data => {
    const list = data?.video_list && typeof data.video_list === 'object' ? Object.values(data.video_list) : [data];
    let best = null;
    for (const e of list) {
      if (!e || typeof e !== 'object') continue;
      const url = e.main_url || e.play_url || e.fallback_api || '';
      if (typeof url !== 'string' || !/^https?:/i.test(url)) continue;
      const px = Number(e.vwidth || e.width || 0) * Number(e.vheight || e.height || 0), br = Number(e.bitrate || e.real_bitrate || 0);
      if (!best || px > best.px || (px === best.px && br > best.br)) best = { url, px, br, w: e.vwidth || e.width || 0, h: e.vheight || e.height || 0, def: e.definition || '' };
    }
    return best;
  };
  const scanJson = (node, depth = 0) => {
    if (!node || typeof node !== 'object' || depth > 12) return;
    if (Array.isArray(node)) { node.forEach(n => scanJson(n, depth + 1)); return; }
    if (node.video_list || node.fallback_api || node.main_url) { const b = bestOf(node); if (b) emit({ url: dec(b.url), width: b.w, height: b.h, definition: b.def, name: `dola_${found.size + 1}` }); }
    for (const k of Object.keys(node)) {
      const v = node[k];
      if (typeof v === 'string' && v.length > 40 && /^\s*[\[{]/.test(v) && /(fallback_api|video_list|main_url)/.test(v)) { try { scanJson(parse(v), depth + 1); } catch {} }
      else if (v && typeof v === 'object') scanJson(v, depth + 1);
    }
  };
  const scanText = text => {
    if (!text || text.length > 12e6 || !/(fallback_api|video_list|main_url)/.test(text)) return;
    try { scanJson(parse(text)); return; } catch {}
    for (const line of text.split(/\r?\n/)) { const m = line.match(/^(?:data:\s*)?([\[{].*)$/); if (m) { try { scanJson(parse(m[1])); } catch {} } }
    for (const m of text.matchAll(/(?:fallback_api|main_url)\\?":\\?"(https?:[^"\\]+)/g)) emit({ url: dec(m[1]), name: `dola_${found.size + 1}` });
  };
  window.__idreamsVideos = () => [...found.values()];
  window.__idreamsScanDom = () => {
    for (const el of document.querySelectorAll('video, video source, [src*=".mp4"]')) { const u = el.currentSrc || el.src || ''; if (/^https?:/i.test(u)) emit({ url: u, name: `dola_${found.size + 1}` }); }
    return found.size;
  };
  // Read-only taps: responses are cloned, requests pass through untouched.
  const of = window.fetch;
  window.fetch = async function (input, init) {
    const res = await of.call(this, input, init);
    try { const u = typeof input === 'string' ? input : input?.url || ''; const ct = res.headers.get('content-type') || '';
      if (isDola(u) && /json|text|event-stream/i.test(ct)) res.clone().text().then(scanText).catch(() => {}); } catch {}
    return res;
  };
  const oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u, ...r) { this.__idu = String(u); return oo.call(this, m, u, ...r); };
  XMLHttpRequest.prototype.send = function (b) { try { this.addEventListener('load', () => { try { if (isDola(this.__idu || '') && typeof this.responseText === 'string') scanText(this.responseText); } catch {} }); } catch {} return os.call(this, b); };
})();

/* SESI MINI extension — MAIN-world bridge. Receives commands from content.js via postMessage and
 * drives the page scripts (auto-prompt, single-clip enforcer, video collector, MD attach). */
(() => {
  if (window.__sesiExtBridge) return; window.__sesiExtBridge = true;
  const post = m => { try { window.postMessage(Object.assign({ __sesi: true }, m), location.origin); } catch (_) {} };
  // inject.js / attach-md.js talk to the Android bridge; emulate it so the same files run unchanged.
  window.IDBridge = window.IDBridge || {
    onVideo: json => { try { post({ type: 'video', video: JSON.parse(json) }); } catch (_) {} },
    onAttached: () => post({ type: 'attached' })
  };
  window.addEventListener('message', ev => {
    if (ev.source !== window || !ev.data || ev.data.__sesi !== true || ev.data.type !== 'cmd') return;
    const { id, cmd, args = {} } = ev.data; let result;
    try {
      const ap = window.__sesiAutoPrompt, en = window.__whempySingleClip;
      switch (cmd) {
        case 'ping': result = { ok: true, autoPrompt: !!ap, enforcer: !!en, collector: !!window.__idreamsVideos }; break;
        case 'autoPrompt': {
          if (!ap) throw new Error('auto-prompt belum siap');
          const s = args.enabled ? ap.activate() : ap.deactivate();
          if (en) { en.cfg.enabled = en.cfg.aggressive = en.cfg.forceModel25 = !!args.enabled; en.cfg.duration = 30; }
          result = { ok: !!s.ready && s.enabled === !!args.enabled }; break;
        }
        case 'imageNote': { if (!ap) throw new Error('auto-prompt belum siap'); const s = ap.setImageNote(!!args.enabled); result = { ok: !!s.ready && s.imageNoteEnabled === !!args.enabled }; break; }
        case 'status': result = { autoPrompt: ap ? ap.status() : null, enforcer: en ? en.cfg.enabled : null }; break;
        case 'scan': { try { window.__idreamsScanDom?.(); } catch (_) {} result = { videos: window.__idreamsVideos?.() || [], pending: window.__idreamsPending?.() || 0 }; break; }
        case 'attach': result = window.__idreamsAttach ? window.__idreamsAttach(args.base64 || '', !!args.arm, args.name || '') : { ok: false, message: 'helper lampiran tidak ada' }; break;
        default: result = { ok: false, error: 'unknown cmd' };
      }
    } catch (e) { result = { ok: false, error: String((e && e.message) || e) }; }
    post({ type: 'reply', id, result });
  });
})();
window.__idreamsAttach = __ATTACH_MD__;

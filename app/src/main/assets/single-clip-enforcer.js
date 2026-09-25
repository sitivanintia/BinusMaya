// ============================================================================
// SINGLE-CLIP ENFORCER v2.0 — "PAKSA 1 VIDEO × 30 DETIK" (MAX AGGRESSIVE)
// ----------------------------------------------------------------------------
// v2.1 — L0 Dola-native chat hook (reverse-engineered payload: skill_type 17, content.text)
// Runs in the MAIN world AFTER inject.js so its hooks wrap the existing ones.
// Goal: Dola must return ONE continuous 30s clip — never 2 × 15s / 3 × 10s.
//
// Layers (all ON by default, toggles in popup → Settings):
//   L1  Payload rewrite   : every count/batch/split field → 1 / false, every
//                           duration-like field → 30 (also string "10s", ms, URL
//                           query params, form bodies, Request objects, sendBeacon).
//   L2  Prompt injection  : appends an explicit "one single continuous 30s video,
//                           do not split" directive to prompt / chat text that
//                           asks for a video.
//   L3  DOM auto-answer   : when Dola asks "generate 2 videos?" / "split?" it
//                           clicks the single-video option, or replies in chat
//                           "1 video only, 30 seconds, single clip".
//   L4  DOM selector guard: if a quantity selector shows "2"/"x2" as selected,
//                           it clicks "1"/"x1".
//   L5  Response guard    : logs when the server still returns >1 task so the
//                           user knows the server ignored the request.
// ============================================================================
(function () {
  'use strict';
  if (typeof window === 'undefined' || window.__whempySingleClipActive) return;
  window.__whempySingleClipActive = true;

  const TAG = '[Whempy 1×30s]';
  const cfg = {
    enabled: false,         // master switch (singleClip) — SesiMini arms it from the Auto prompt toggle
    aggressive: true,       // L2 + L3 + L4 (aggressiveMode)
    duration: 30,
    promptInject: false,   // SESI uses auto-prompt.js at the network boundary instead.
    autoReply: true,
    autoAcceptSplit: false, // when Dola can only do 2×15s: auto-answer "Ya" instead of looping
    forceModel25: true      // rewrite variables.model → Seedance 2.5 id (learned), decline server downgrade cards
  };

  const log = (m) => { try { console.log(TAG, m); } catch (e) {} };
  const toast = (m) => {
    log(m);
    try { window.postMessage({ type: 'PURZA_LOG', msg: TAG + ' ' + m }, '*'); } catch (e) {}
  };

  // -------------------------------------------------------------- settings
  function applySettings(obj) {
    if (!obj) return;
    if (obj.singleClip !== undefined) cfg.enabled = obj.singleClip !== false;
    if (obj.aggressiveMode !== undefined) cfg.aggressive = obj.aggressiveMode !== false;
    if (obj.promptInject !== undefined) cfg.promptInject = obj.promptInject !== false;
    if (obj.autoReply !== undefined) cfg.autoReply = obj.autoReply !== false;
    if (obj.autoAcceptSplit !== undefined) cfg.autoAcceptSplit = obj.autoAcceptSplit === true;
    if (obj.forceModel25 !== undefined) cfg.forceModel25 = obj.forceModel25 !== false;
    if (obj.durationOverride || obj.duration) {
      const d = parseInt(obj.durationOverride || obj.duration, 10);
      if (d > 0) cfg.duration = d;
    }
  }
  try {
    const st = globalThis.chrome && chrome.storage && chrome.storage.local;
    if (st) {
      st.get(['singleClip', 'aggressiveMode', 'promptInject', 'autoReply', 'durationOverride', 'autoAcceptSplit', 'forceModel25'], (res) => {
        try { void chrome.runtime.lastError; } catch (e) {}
        applySettings(res || {});
        log('settings loaded: ' + JSON.stringify(cfg));
      });
      chrome.storage.onChanged.addListener((changes, area) => {
        if (area && area !== 'local') return;
        const flat = {};
        Object.keys(changes || {}).forEach(k => { flat[k] = changes[k] && changes[k].newValue; });
        applySettings(flat);
        toast('settings updated → ' + (cfg.enabled ? '1 video × ' + cfg.duration + 's' : 'OFF') + (cfg.aggressive ? ' (AGGRESSIVE)' : ''));
      });
    }
  } catch (e) {}
  window.addEventListener('message', (ev) => {
    const d = ev && ev.data;
    if (!d || typeof d !== 'object') return;
    if (d.type === 'PURZA_UPDATE_SETTINGS' || d.type === 'WHEMPY_SINGLE_CLIP') applySettings(d);
  });

  // -------------------------------------------------------------- helpers
  const origStringify = JSON.stringify;
  const bypass = (url, body, opts) => {
    try { return typeof window.__isStudioRelayBypassRequest === 'function' && window.__isStudioRelayBypassRequest(url, body, opts); }
    catch (e) { return false; }
  };

  // Fields that mean "how many videos" → force 1
  const COUNT_KEYS = [
    'n', 'num', 'nums', 'count', 'video_count', 'videos_count', 'video_num', 'video_nums', 'num_videos', 'n_videos',
    'generate_count', 'gen_count', 'generate_num', 'gen_num', 'batch', 'batch_size', 'batch_count', 'num_outputs',
    'output_count', 'sample_count', 'num_samples', 'clip_count', 'num_clips', 'n_clips',
    'segment_count', 'num_segments', 'n_segments', 'shot_count', 'num_shots', 'scene_count',
    'num_scenes', 'variants', 'variant_count', 'num_variants', 'candidate_count',
    'num_candidates', 'quantity', 'qty', 'repeat_count', 'parallel_count',
    'gen_video_count', 'video_number', 'result_count', 'task_count', 'num_tasks'
  ];
  // Boolean flags that mean "split into several clips" → false
  const SPLIT_FALSE_KEYS = [
    'split', 'auto_split', 'split_video', 'split_clips', 'multi_clip', 'multi_clips', 'multiclip', 'multi_shot',
    'multishot', 'multi_scene', 'multiscene', 'segmented', 'chunked', 'storyboard', 'is_storyboard',
    'multiple_videos', 'batch_mode', 'is_batch', 'auto_extend', 'need_split',
    'enable_split', 'split_enabled', 'multi_video', 'multi_videos'
  ];
  // Boolean flags that mean "single continuous clip" → true
  const SINGLE_TRUE_KEYS = [
    'single_clip', 'single_video', 'single_shot', 'one_shot', 'continuous', 'is_continuous', 'one_take',
    'merge_clips', 'no_split', 'disable_split', 'long_video', 'is_long_video', 'seamless'
  ];
  // Duration-like fields (seconds) → cfg.duration
  const DURATION_KEYS = [
    'duration', 'video_duration', 'seconds', 'motion_seconds', 'video_length', 'video_len',
    'duration_s', 'duration_sec', 'duration_seconds', 'clip_duration', 'segment_duration', 'shot_duration',
    'total_duration', 'target_duration', 'max_duration', 'min_duration', 'video_seconds', 'gen_duration',
    'generate_duration', 'output_duration', 'time_length', 'video_time', 'secs', 'dur'
  ];
  const DURATION_MS_KEYS = ['duration_ms', 'video_duration_ms', 'length_ms', 'duration_millis', 'durationMs', 'time_ms'];
  const DURATION_STR_RE = /^\s*(\d+(?:\.\d+)?)\s*(s|sec|secs|second|seconds|detik)?\s*$/i;

  const isVideoish = (s) => /video|clip|animate|animation|motion|film|movie|scene|shot|cinematic|generate|render|buat|animasi|gerak|bikin|render/i.test(String(s || ''));

  const DIRECTIVE = () => ' [IMPORTANT: use Dreamina Seedance 2.5 (supports a 30-second single take) and output exactly ONE video, a single continuous ' + cfg.duration + '-second clip (' + cfg.duration + 's total). Do NOT split into 2 videos, do NOT generate multiple videos, clips, parts, segments or scenes. One take, one file, ' + cfg.duration + ' seconds.]';
  const DIRECTIVE_RE = /output exactly ONE video, a single continuous/i;

  function injectPrompt(text) {
    if (!cfg.promptInject || !cfg.aggressive || typeof text !== 'string') return text;
    if (DIRECTIVE_RE.test(text)) return text;
    // Strip user-side "2 videos"/"two clips" so the model isn't pulled both ways
    let t = text
      .replace(/\b(2|two|dua|3|three|tiga|4|four|empat)\s*(x\s*)?(videos?|clips?|parts?|segments?|scenes?|shots?)\b/gi, 'one single video')
      .replace(/\b(videos?|clips?)\s*x\s*[2-9]\b/gi, 'one single video')
      .replace(/\b(split|dibagi|dipecah|bagi|pecah)\s+(into|menjadi|jadi)\s+\w+/gi, 'as one continuous clip')
      .replace(/\b(\d+)\s*(?:s|sec(?:ond)?s?|detik)\s*(each|per\s*(clip|video|part|segment)|masing-masing|per\s*video)\b/gi, cfg.duration + 's total');
    return t + DIRECTIVE();
  }


  // Gate: only touch payloads that are clearly video-generation related
  const VIDEO_KEY_RE = /"(?:duration|video_duration|motion_seconds|video_length|seconds|clip_duration|aspect_ratio|ratio|resolution|generate_type|task_type|video_count|num_videos|prompt|negative_prompt|image_url|first_frame|last_frame|reference_image)"\s*:/i;
  const VIDEO_URL_RE = /video|generat|motion|animate|task|create|submit|render|i2v|t2v|img2video|image2video|clip/i;
  function isVideoPayload(str, url) {
    if (url && VIDEO_URL_RE.test(String(url))) return true;
    if (typeof str === 'string') return VIDEO_KEY_RE.test(str) || /"generate_type"\s*:\s*"video"|"task_type"\s*:\s*"video"|\bvideo\b/i.test(str);
    return false;
  }
  function isVideoObject(obj) {
    if (!obj || typeof obj !== 'object') return false;
    try { return isVideoPayload(origStringify(obj).slice(0, 20000), null); } catch (e) { return false; }
  }

  // --------------------------------------------------------- L1: object walk
  function forceObject(obj, depth, ctx) {
    if (!obj || typeof obj !== 'object' || depth > 8) return false;
    let changed = false;
    if (Array.isArray(obj)) {
      for (const it of obj) if (forceObject(it, depth + 1, ctx)) changed = true;
      return changed;
    }
    for (const key of Object.keys(obj)) {
      const v = obj[key];
      const lk = key.toLowerCase();
      if (typeof v === 'number' || (typeof v === 'string' && /^\d+(\.\d+)?$/.test(v))) {
        if (COUNT_KEYS.includes(lk) && Number(v) !== 1) { obj[key] = typeof v === 'string' ? '1' : 1; changed = true; ctx.count = true; continue; }
        if (DURATION_KEYS.includes(lk) && Number(v) !== cfg.duration) { obj[key] = typeof v === 'string' ? String(cfg.duration) : cfg.duration; changed = true; ctx.dur = true; continue; }
        if (DURATION_MS_KEYS.includes(lk) && Number(v) !== cfg.duration * 1000) { obj[key] = cfg.duration * 1000; changed = true; ctx.dur = true; continue; }
      } else if (typeof v === 'string') {
        if (DURATION_KEYS.includes(lk)) {
          const m = DURATION_STR_RE.exec(v);
          if (m && Number(m[1]) !== cfg.duration) { obj[key] = cfg.duration + (m[2] ? 's' : ''); changed = true; ctx.dur = true; continue; }
        }
        if (cfg.aggressive && (lk === 'prompt' || lk === 'text' || lk === 'content' || lk === 'query' || lk === 'input' || lk === 'message' || lk === 'user_prompt') && v.length > 3 && v.length < 4000) {
          if (lk === 'prompt' || isVideoish(v)) {
            const nv = injectPrompt(v);
            if (nv !== v) { obj[key] = nv; changed = true; ctx.prompt = true; }
          }
          continue;
        }
      } else if (typeof v === 'boolean') {
        if (SPLIT_FALSE_KEYS.includes(lk) && v) { obj[key] = false; changed = true; ctx.split = true; continue; }
        if (SINGLE_TRUE_KEYS.includes(lk) && !v) { obj[key] = true; changed = true; ctx.split = true; continue; }
      }
      if (v && typeof v === 'object') if (forceObject(v, depth + 1, ctx)) changed = true;
    }
    return changed;
  }

  // Add explicit single-clip hints to a top-level video-generation payload
  function decorateTopLevel(obj, ctx) {
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return false;
    const looksVideo = ('duration' in obj) || ('video_duration' in obj) || ('motion_seconds' in obj) || ('prompt' in obj) ||
      obj.generate_type === 'video' || obj.task_type === 'video' || obj.type === 'video' || ('aspect_ratio' in obj) || ('ratio' in obj);
    if (!looksVideo) return false;
    let changed = false;
    const set = (k, val) => { if (obj[k] !== val) { obj[k] = val; changed = true; } };
    set('video_count', 1); set('num', 1); set('count', 1); set('n', 1); set('clip_count', 1); set('segment_count', 1); set('batch_size', 1);
    set('split', false); set('auto_split', false); set('multi_clip', false); set('single_clip', true); set('single_video', true); set('continuous', true);
    set('duration', cfg.duration); set('max_duration', cfg.duration);
    if (changed) ctx.decorated = true;
    return changed;
  }

  // --------------------------------------------------------- L1: string body
  function forceJsonString(str, url) {
    if (!cfg.enabled || typeof str !== 'string' || str.length < 2) return str;
    if (/"(?:conversation_id|local_message_id|content_type|skill_type|uplink_entity|completion_option)"\s*:/.test(str.slice(0, 5000))) return dolaRewriteChatBody(str, url); // Dola chat shape → text-only
    if (!isVideoPayload(str, url)) return str;
    const trimmed = str.trim();
    if (trimmed[0] === '{' || trimmed[0] === '[') {
      try {
        const obj = JSON.parse(trimmed);
        const ctx = {};
        let changed = forceObject(obj, 0, ctx);
        if (decorateTopLevel(obj, ctx)) changed = true;
        if (changed) { report(ctx); return JSON.stringify(obj); }
        return str;
      } catch (e) { /* not strict JSON → regex fallback below */ }
    }
    return forceLooseString(str);
  }

  function forceLooseString(str, url) {
    if (!cfg.enabled || typeof str !== 'string') return str;
    if (!isVideoPayload(str, url)) return str;
    const ctx = {}; let out = str;
    const keyAlt = (arr) => arr.map(k => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
    // JSON-ish (also escaped JSON inside strings)
    out = out.replace(new RegExp('(\\\\?"(?:' + keyAlt(COUNT_KEYS) + ')\\\\?"\\s*:\\s*)(\\d+)', 'gi'), (m, p, v) => { if (v !== '1') ctx.count = true; return p + '1'; });
    out = out.replace(new RegExp('(\\\\?"(?:' + keyAlt(DURATION_KEYS) + ')\\\\?"\\s*:\\s*)(\\d+(?:\\.\\d+)?)', 'gi'), (m, p, v) => { if (Number(v) !== cfg.duration) ctx.dur = true; return p + cfg.duration; });
    out = out.replace(new RegExp('(\\\\?"(?:' + keyAlt(DURATION_KEYS) + ')\\\\?"\\s*:\\s*\\\\?")(\\d+(?:\\.\\d+)?)(s|sec|seconds?)?(\\\\?")', 'gi'), (m, p, v, u, q) => { if (Number(v) !== cfg.duration) ctx.dur = true; return p + cfg.duration + (u || '') + q; });
    out = out.replace(new RegExp('(\\\\?"(?:' + keyAlt(DURATION_MS_KEYS) + ')\\\\?"\\s*:\\s*)(\\d+)', 'gi'), (m, p) => { ctx.dur = true; return p + (cfg.duration * 1000); });
    out = out.replace(new RegExp('(\\\\?"(?:' + keyAlt(SPLIT_FALSE_KEYS) + ')\\\\?"\\s*:\\s*)true', 'gi'), (m, p) => { ctx.split = true; return p + 'false'; });
    out = out.replace(new RegExp('(\\\\?"(?:' + keyAlt(SINGLE_TRUE_KEYS) + ')\\\\?"\\s*:\\s*)false', 'gi'), (m, p) => { ctx.split = true; return p + 'true'; });
    // URL-encoded / query style: duration=10&num=2
    out = out.replace(new RegExp('([?&]|^)(' + keyAlt(COUNT_KEYS) + ')=(\\d+)', 'gi'), (m, a, k, v) => { if (v !== '1') ctx.count = true; return a + k + '=1'; });
    out = out.replace(new RegExp('([?&]|^)(' + keyAlt(DURATION_KEYS) + ')=(\\d+)', 'gi'), (m, a, k, v) => { if (Number(v) !== cfg.duration) ctx.dur = true; return a + k + '=' + cfg.duration; });
    if (Object.keys(ctx).length) report(ctx);
    return out;
  }

  function forceUrl(url) {
    if (!cfg.enabled || typeof url !== 'string' || url.indexOf('?') < 0) return url;
    if (!/dola\.com|seaart\.ai|byteintl|ibytedtos|volces/i.test(url) && !url.startsWith('/')) return url;
    if (!VIDEO_URL_RE.test(url)) return url;
    return forceLooseString(url, url);
  }

  let lastReport = 0;
  function report(ctx) {
    const now = Date.now();
    if (now - lastReport < 1500) return;
    lastReport = now;
    const parts = [];
    if (ctx.count) parts.push('count→1');
    if (ctx.split) parts.push('split→off');
    if (ctx.dur) parts.push('duration→' + cfg.duration + 's');
    if (ctx.prompt) parts.push('prompt directive');
    if (ctx.decorated) parts.push('single-clip flags');
    if (parts.length) toast('🎯 Enforced 1 video × ' + cfg.duration + 's: ' + parts.join(', '));
  }



  // --------------------------------------------------------- L0b: MODEL MAP LEARNER + FORCER
  // Dola sends the video model in variables.model / ability_param.model. The *ids* come from server config
  // (not in the JS bundle), so we LEARN them: sniff any JSON/SSE response that lists "Seedance x.y" items and
  // record {version -> id}. Also learn from outgoing requests while the UI shows 2.5 checked.
  const MODEL_STORE_KEY = 'whempy_model_map_v1';
  const modelMap = (() => { try { return JSON.parse(localStorage.getItem(MODEL_STORE_KEY) || '{}'); } catch (e) { return {}; } })();
  const saveModelMap = () => { try { localStorage.setItem(MODEL_STORE_KEY, origStringify(modelMap)); } catch (e) {} };
  const model25 = () => (cfg.forceModel25 && modelMap['2.5'] !== undefined) ? modelMap['2.5'] : undefined;
  const VALUE_KEYS = ['model', 'model_key', 'model_id', 'model_name', 'value', 'key', 'item_id', 'id', 'model_item_key', 'menu_type', 'option_value', 'select_value'];
  const NAME_KEYS = ['name', 'title', 'label', 'show_name', 'selected_name', 'display_name', 'text', 'model_name', 'desc'];
  const SEEDANCE_RE = /seedance\s*(\d(?:\.\d)?)/i;

  function learnFromObject(o, depth) {
    if (!o || typeof o !== 'object' || depth > 10) return;
    if (Array.isArray(o)) { o.forEach(x => learnFromObject(x, depth + 1)); return; }
    let ver = null, nameKey = null;
    for (const k of NAME_KEYS) { const v = o[k]; if (typeof v === 'string') { const m = v.match(SEEDANCE_RE); if (m) { ver = m[1].length === 1 ? m[1] + '.0' : m[1]; nameKey = k; break; } } }
    if (ver) {
      for (const k of VALUE_KEYS) {
        if (k === nameKey) continue;
        const v = o[k];
        const ok = typeof v === 'number' || (typeof v === 'string' && v.length > 0 && v.length < 80 && !/\s/.test(v.trim()) === true) || (typeof v === 'string' && /^[\w.\-:]+$/.test(v));
        if (!ok) continue;
        if (modelMap[ver] !== v) { modelMap[ver] = v; saveModelMap(); toast('🧠 Learned model id: Seedance ' + ver + ' → ' + origStringify(v)); }
        break;
      }
    }
    for (const k of Object.keys(o)) { const v = o[k]; if (v && typeof v === 'object') learnFromObject(v, depth + 1); else if (typeof v === 'string' && v.length > 30 && v.length < 300000 && /^\s*[\[{]/.test(v) && /seedance/i.test(v)) { try { learnFromObject(JSON.parse(v), depth + 1); } catch (e) {} } }
  }
  function learnFromText(txt) {
    if (!cfg.enabled || typeof txt !== 'string' || txt.length < 20 || txt.length > 3000000 || !/seedance/i.test(txt)) return;
    const chunks = [];
    const t = txt.trim();
    if (/^[\[{]/.test(t)) chunks.push(t);
    else for (const line of t.split(/\r?\n/)) { const m = line.match(/^(?:data:\s*)?([\[{].*)$/); if (m && /seedance/i.test(m[1])) chunks.push(m[1]); }
    for (const c of chunks) { try { learnFromObject(JSON.parse(c), 0); } catch (e) {} }
  }
  // outgoing: learn from a video message when UI shows 2.5 checked, and FORCE otherwise
  function forceModelIn(obj, where) {
    if (!obj || typeof obj !== 'object' || !('model' in obj)) return false;
    const cur = obj.model; const want = model25();
    if (cur !== undefined && cur !== null && cur !== '' && modelIs25 === true && modelMap['2.5'] !== cur) {
      // UI says 2.5 is selected → this is the id
      modelMap['2.5'] = cur; saveModelMap(); toast('🧠 Learned Seedance 2.5 id from request: ' + origStringify(cur));
      return false;
    }
    if (want === undefined || cur === want) return false;
    // remember what the old one was (for the badge) then force
    const oldVer = Object.keys(modelMap).find(k => modelMap[k] === cur);
    obj.model = want;
    toast('🎬 ' + where + '.model ' + (oldVer ? '(Seedance ' + oldVer + ') ' : '') + '→ Seedance 2.5 (forced)');
    return true;
  }
  // heuristic fallback when the id is unknown but the value itself encodes the version
  function forceModelHeuristic(obj, where) {
    if (!obj || typeof obj !== 'object' || typeof obj.model !== 'string' || model25() !== undefined) return false;
    const m = obj.model.match(/^(.*?seedance[_\-\s]?)([12])([._\-]?)0(.*)$/i);
    if (!m) return false;
    const nv = m[1] + '2' + (m[3] || '.') + '5' + m[4].replace(/[_\-\s]?fast$/i, '');
    if (nv === obj.model) return false;
    obj.model = nv; toast('🎬 ' + where + '.model "' + m[0] + '" → "' + nv + '" (heuristic, id unknown)');
    return true;
  }
  function dolaForceModel(msg) {
    if (!cfg.forceModel25) return false;
    let ch = false;
    const targets = [[msg.variables, 'variables'], [msg.ability_param, 'ability_param'], [msg.skill_info && msg.skill_info.variables, 'skill_info.variables'], [msg.ext && msg.ext.variables, 'ext.variables']];
    for (const [o, w] of targets) { if (o && typeof o === 'object') { if (forceModelIn(o, w) || forceModelHeuristic(o, w)) ch = true; } }
    return ch;
  }

  // --------------------------------------------------------- L0: DOLA NATIVE CHAT HOOK
  // Reverse-engineered from dola.com (Cici/ByteDance) bundle, 2026-09-07:
  //  * Video generation is a CHAT message → POST /samantha/chat/completion (+ /alice/message/pre_handle_v2*)
  //  * Message carries skill_type 17 (SkillVideoGeneration) / skill_id "17", variables:{ratio, model, style,
  //    camera_movement, template_type} and `content` = JSON string {"text": "<prompt>, <ratio label>"}.
  //  * There is NO duration / clip-count field in the client payload — Dola's agent (Seedance) parses the
  //    natural-language prompt (even the aspect ratio is appended to the prompt text!). So the ONLY real
  //    lever for "1 video × 30s" is the prompt text itself. This hook injects the directive there.
  //  * The legacy inject.js bypass() whitelists every /samantha|/chat|/message URL, so the old payload
  //    rewriter never touched the real request. This hook runs BEFORE that bypass.
  const DOLA_CHAT_RE = /\/samantha\/chat\/completion|\/alice\/message\/pre_handle|\/chat\/completion|\/chat\/async\/chunk_stream|\/samantha\/chat\//i;
  const DOLA_VIDEO_SKILL = new Set(['17', '2021']);            // SkillVideoGeneration=17, AliceVideoGeneration=2021
  const VIDEO_INTENT_RE = /\b(video|vidio|clip|klip|animate|animasi|animation|motion|gerak(kan)?|buat(kan)? video|make .{0,20}video|generate .{0,20}video|seedance|text[- ]to[- ]video|image[- ]to[- ]video|i2v|t2v|film|footage|cinematic|camera (movement|pan|zoom)|slow[- ]motion|timelapse|time-lapse)\b/i;

  function dolaMsgIsVideo(msg) {
    try {
      const st = msg.skill_type ?? (msg.skill_info && msg.skill_info.skill_type) ?? (msg.ext && msg.ext.skill_type);
      const sid = msg.skill_id ?? (msg.skill_info && msg.skill_info.skill_id) ?? (msg.ext && msg.ext.skill_id);
      if (st !== undefined && DOLA_VIDEO_SKILL.has(String(st))) return true;
      if (sid !== undefined && DOLA_VIDEO_SKILL.has(String(sid))) return true;
      const v = msg.variables || (msg.skill_info && msg.skill_info.variables) || (msg.ext && msg.ext.variables);
      if (v && typeof v === 'object') {
        if ('ratio' in v || 'camera_movement' in v) return true;
        if ('model' in v) { const mv = String(v.model); if (/seedance|video/i.test(mv) || Object.values(modelMap).some(id => String(id) === mv)) return true; }
      }
      if (msg.ext && typeof msg.ext === 'object') {
        const es = origStringify(msg.ext).slice(0, 4000);
        if (/"skill_type"\s*:\s*"?(17|2021)"?/.test(es) || /video_generation/i.test(es)) return true;
      }
    } catch (e) {}
    return false;
  }

  // Rewrites a Dola message object in place. Returns true if changed.
  function dolaRewriteMessage(msg, forceVideo) {
    if (!msg || typeof msg !== 'object') return false;
    let changed = false;
    const isVideo = forceVideo || dolaMsgIsVideo(msg);
    if (isVideo && dolaForceModel(msg)) changed = true;
    const fixText = (txt) => {
      if (typeof txt !== 'string' || txt.length < 3) return txt;
      if (!isVideo && !VIDEO_INTENT_RE.test(txt)) return txt;
      const nt = injectPrompt(txt);
      if (nt !== txt) changed = true;
      return nt;
    };
    // content: JSON string {"text": "..."} (content_type 2001) or plain string
    if (typeof msg.content === 'string') {
      const c = msg.content.trim();
      if (c.startsWith('{')) {
        try {
          const inner = JSON.parse(c);
          if (inner && typeof inner.text === 'string') {
            const nt = fixText(inner.text);
            if (nt !== inner.text) { inner.text = nt; msg.content = origStringify(inner); }
          }
        } catch (e) {}
      } else if (c) {
        const nt = fixText(msg.content);
        if (nt !== msg.content) msg.content = nt;
      }
    } else if (msg.content && typeof msg.content === 'object' && typeof msg.content.text === 'string') {
      const nt = fixText(msg.content.text); if (nt !== msg.content.text) msg.content.text = nt;
    }
    if (typeof msg.text === 'string') { const nt = fixText(msg.text); if (nt !== msg.text) msg.text = nt; }
    if (msg.content_obj && typeof msg.content_obj.text === 'string') { const nt = fixText(msg.content_obj.text); if (nt !== msg.content_obj.text) msg.content_obj.text = nt; }
    // variables: keep Dola's own keys; only touch what we *know* exists. Never add unknown keys here
    // (server-side validation risk) — duration lives in the prompt text.
    return changed;
  }

  function dolaRewriteChatBody(str, url) {
    if (!cfg.enabled || typeof str !== 'string' || str.length < 2 || !str.trim().startsWith('{')) return str;
    let j; try { j = JSON.parse(str); } catch (e) { return str; }
    let changed = false;
    const seen = new Set();
    const walk = (node, depth) => {
      if (!node || typeof node !== 'object' || depth > 8 || seen.has(node)) return;
      seen.add(node);
      if (Array.isArray(node)) { node.forEach(n => walk(n, depth + 1)); return; }
      // message-like object?
      if ('content' in node || 'text' in node || 'content_obj' in node) {
        if (dolaRewriteMessage(node, false)) changed = true;
      }
      // uplink_entity may wrap a message as a JSON string
      for (const k of Object.keys(node)) {
        const v = node[k];
        if (typeof v === 'string' && v.length > 20 && v.length < 200000 && /^\s*\{/.test(v) && /"(?:text|content|skill_type|variables)"/.test(v)) {
          try { const inner = JSON.parse(v); const before = origStringify(inner); walk(inner, depth + 1); const after = origStringify(inner); if (after !== before) { node[k] = after; changed = true; } } catch (e) {}
        } else if (v && typeof v === 'object') walk(v, depth + 1);
      }
    };
    walk(j, 0);
    if (!changed) return str;
    toast('🎯 Dola chat payload: prompt forced to 1 video × ' + cfg.duration + 's (single clip)');
    try { window.dispatchEvent(new Event('whempy:video-sent')); } catch (e) {}
    return origStringify(j);
  }
  const isDolaChatUrl = (u) => DOLA_CHAT_RE.test(String(u || ''));

  // --------------------------------------------------------- hooks (outermost)
  // JSON.stringify — wraps inject.js's wrapper → runs first
  const DOLA_MSG_KEYS = ['conversation_id', 'local_message_id', 'content_type', 'skill_type', 'skill_info', 'uplink_entity', 'completion_option', 'messages', 'content_blocks_v2', 'bot_id', 'section_id'];
  const isDolaMsgTree = (v) => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
    for (const k of DOLA_MSG_KEYS) if (k in v) return true;
    return false;
  };
  function dolaWalkObject(root) {
    let changed = false; const seen = new Set();
    const walk = (node, d) => {
      if (!node || typeof node !== 'object' || d > 8 || seen.has(node)) return; seen.add(node);
      if (Array.isArray(node)) { node.forEach(n => walk(n, d + 1)); return; }
      if ('content' in node || 'text' in node || 'content_obj' in node) { if (dolaRewriteMessage(node, false)) changed = true; }
      for (const k of Object.keys(node)) { const v = node[k]; if (v && typeof v === 'object') walk(v, d + 1); }
    };
    walk(root, 0); return changed;
  }
  JSON.stringify = function (value) {
    // Dola message objects: text-only injection, NEVER add unknown keys (server validates variables)
    try {
      if (cfg.enabled && isDolaMsgTree(value)) {
        if (dolaWalkObject(value)) toast('🎯 Dola message: prompt forced to 1 video × ' + cfg.duration + 's');
        return origStringify.apply(this, arguments);
      }
    } catch (e) {}
    try {
      if (cfg.enabled && value && typeof value === 'object' && !bypass(null, value) && isVideoObject(value)) {
        const ctx = {};
        let ch = forceObject(value, 0, ctx);
        if (decorateTopLevel(value, ctx)) ch = true;
        if (ch) report(ctx);
      }
    } catch (e) {}
    return origStringify.apply(this, arguments);
  };

  const origFetch = window.fetch;
  window.fetch = async function (input, init) {
    try {
      if (cfg.enabled) {
        // L0: Dola native chat endpoint — bypass-proof (runs before legacy bypass())
        const u0 = typeof input === 'string' ? input : (input && (input.url || input.href)) || '';
        if (isDolaChatUrl(u0)) {
          if (init && typeof init.body === 'string') init.body = dolaRewriteChatBody(init.body, u0);
          else if (input && typeof input === 'object' && typeof input.clone === 'function' && String(input.method || 'GET').toUpperCase() === 'POST') {
            const txt = await input.clone().text();
            const nt = dolaRewriteChatBody(txt, u0);
            if (nt !== txt) input = new Request(input.url, { method: 'POST', headers: input.headers, body: nt, mode: input.mode, credentials: input.credentials, cache: input.cache, redirect: input.redirect, referrer: input.referrer, referrerPolicy: input.referrerPolicy, keepalive: input.keepalive, signal: input.signal });
          }
        }
        // Request object with body
        if (input && typeof input === 'object' && typeof input.url === 'string' && typeof input.clone === 'function') {
          const method = String(input.method || 'GET').toUpperCase();
          if (method !== 'GET' && method !== 'HEAD' && !bypass(input.url, null, input)) {
            const ct = (input.headers && input.headers.get && input.headers.get('content-type')) || '';
            if (!/multipart|octet-stream|image|video/i.test(ct)) {
              const txt = await input.clone().text();
              if (txt && !bypass(input.url, txt, input)) {
                const nt = forceJsonString(txt, input.url);
                const nu = forceUrl(input.url);
                if (nt !== txt || nu !== input.url) {
                  input = new Request(nu, { method, headers: input.headers, body: nt, mode: input.mode, credentials: input.credentials, cache: input.cache, redirect: input.redirect, referrer: input.referrer, referrerPolicy: input.referrerPolicy, integrity: input.integrity, keepalive: input.keepalive, signal: input.signal });
                }
              }
            }
          }
        } else if (typeof input === 'string') {
          input = forceUrl(input);
          if (init && init.body && !bypass(input, init.body, init)) {
            if (typeof init.body === 'string') init.body = forceJsonString(init.body, input);
            else if (typeof URLSearchParams !== 'undefined' && init.body instanceof URLSearchParams) init.body = new URLSearchParams(forceLooseString(init.body.toString(), input));
          }
        } else if (input && typeof input.href === 'string') { // URL object
          input = forceUrl(input.href);
          if (init && typeof init.body === 'string' && !bypass(input, init.body, init)) init.body = forceJsonString(init.body, input);
        }
      }
    } catch (e) {}
    const res = await origFetch.call(this, input, init);
    // L0b: learn model ids from any Dola JSON/SSE response
    try {
      const u = typeof input === 'string' ? input : (input && input.url) || '';
      const ct = (res && res.headers && res.headers.get && res.headers.get('content-type')) || '';
      if (cfg.enabled && res && /dola\.com|ciciai|samantha|alice/i.test(u) && /json|text|event-stream/i.test(ct) && !/\.(mp4|webm|m3u8|jpg|png|gif|js|css)(\?|$)/i.test(u)) {
        res.clone().text().then(learnFromText).catch(() => {});
      }
    } catch (e) {}
    // L5 response guard (non-blocking)
    try {
      if (cfg.enabled && res && res.ok) {
        const u = typeof input === 'string' ? input : (input && input.url) || '';
        if (/video|generate|task|create|submit/i.test(u) && !/\.(mp4|webm|m3u8|ts|jpg|png|gif)(\?|$)/i.test(u)) {
          res.clone().text().then(t => {
            if (!t || t.length > 300000) return;
            const m = t.match(/"(?:task_ids|video_ids|videos|tasks|clips|results|urls|video_urls)"\s*:\s*\[/i);
            if (m) {
              try {
                const j = JSON.parse(t); const arr = findFirstArray(j, ['task_ids', 'video_ids', 'videos', 'tasks', 'clips', 'results', 'video_urls']);
                if (arr && arr.length > 1) toast('⚠️ Server still returned ' + arr.length + ' items for one request (server-side split). Payload was forced to 1×' + cfg.duration + 's.');
              } catch (e) {}
            }
          }).catch(() => {});
        }
      }
    } catch (e) {}
    return res;
  };
  function findFirstArray(o, keys, d) {
    d = d || 0; if (!o || typeof o !== 'object' || d > 5) return null;
    for (const k of keys) if (Array.isArray(o[k])) return o[k];
    for (const k of Object.keys(o)) { const r = findFirstArray(o[k], keys, d + 1); if (r) return r; }
    return null;
  }

  // XHR — capture URL in open(), rewrite body in send()
  const xo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    try { this.__wUrl = String(url || ''); this.__wMethod = String(method || 'GET').toUpperCase(); if (cfg.enabled && typeof url === 'string') { const nu = forceUrl(url); if (nu !== url) { arguments[1] = nu; this.__wUrl = nu; } } } catch (e) {}
    return xo.apply(this, arguments);
  };
  const xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (body) {
    try {
    try { if (cfg.enabled && typeof body === 'string' && isDolaChatUrl(this.__wUrl)) body = dolaRewriteChatBody(body, this.__wUrl); } catch (e) {}
    try { if (cfg.enabled) this.addEventListener('loadend', function () { try { if ((this.responseType === '' || this.responseType === 'text') && typeof this.responseText === 'string') learnFromText(this.responseText); else if (this.responseType === 'json' && this.response) learnFromObject(this.response, 0); } catch (e) {} }); } catch (e) {}
      if (cfg.enabled && body && !bypass(this.__wUrl || this._url, body, { method: this.__wMethod })) {
        if (typeof body === 'string') body = forceJsonString(body, this.__wUrl);
        else if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) body = new URLSearchParams(forceLooseString(body.toString(), this.__wUrl));
      }
    } catch (e) {}
    return xs.call(this, body);
  };

  if (window.WebSocket) {
    const ws = WebSocket.prototype.send;
    WebSocket.prototype.send = function (data) {
      try { if (cfg.enabled && typeof data === 'string') data = forceJsonString(data); } catch (e) {}
      return ws.call(this, data);
    };
  }
  if (navigator && navigator.sendBeacon) {
    const sb = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function (url, data) {
      try { if (cfg.enabled && typeof data === 'string' && isDolaChatUrl(url)) data = dolaRewriteChatBody(data, url); } catch (e) {}
      try { if (cfg.enabled && typeof data === 'string' && !bypass(url, data)) data = forceJsonString(data, url); } catch (e) {}
      return sb(url, data);
    };
  }
  // new Request(url, {body}) built manually by the site
  try {
    const OrigRequest = window.Request;
    if (OrigRequest) {
      const PatchedRequest = function (input, init) {
        try {
          if (cfg.enabled && init && typeof init.body === 'string' && !bypass(typeof input === 'string' ? input : (input && input.url), init.body, init)) {
            init = Object.assign({}, init, { body: forceJsonString(init.body, typeof input === 'string' ? input : (input && input.url)) });
          }
          if (cfg.enabled && typeof input === 'string') input = forceUrl(input);
        } catch (e) {}
        return new OrigRequest(input, init);
      };
      PatchedRequest.prototype = OrigRequest.prototype;
      Object.setPrototypeOf(PatchedRequest, OrigRequest);
      window.Request = PatchedRequest;
    }
  } catch (e) {}

  // --------------------------------------------------------- L3 / L4: DOM
  // v2.2 findings (screenshots 2026-09-07):
  //  * Legacy inject.js injected FAKE 20/25/30/60s options (data-channa-opt) whose onclick only flips an
  //    extension variable, then relabels the pill "30s (MAX MODE)". Dola's real duration state stayed 15s.
  //  * Dola's model menu offers "Dreamina Seedance 2.5" (4–30 s single take, official). With 2.5 + a REAL
  //    30s option selected, one 30-second clip is possible. Seedance 2.0 / 2.0 Fast / 1.0 cap at 15 s.
  //  => L4 now selects the REAL options (model 2.5, max real duration) and removes the fake ones.
  const handled = new WeakSet();
  let lastReply = 0, insistCount = 0, realDuration = null, modelIs25 = null, warnedNo25 = false, lastMenuAct = 0, tunedOnce = false, tuning = false;
  const ASK_SPLIT_RE = /(2|two|dua|3|three|tiga|4|four|empat)\s*(x\s*)?(videos?|clips?|parts?|segments?|scenes?|shots?)|split (it|the video|into)|dibagi|dipecah|membagi(nya)?|(15|10|5)\s*(s|sec(onds)?|detik)\s*(each|per|×|x|masing)|masing-masing\s*(15|10|5)|consume\s*[2-9]\s*video|generate\s*[2-9]|multiple videos|several (videos|clips)|two separate|in two|in 2|batch of/i;
  const REFUSE_30_RE = /(tidak|belum|nggak|gak)\s*(dapat|bisa|mampu)[^.]{0,80}(30|tiga puluh)\s*(detik|s\b|sec)|(cannot|can't|unable to|not able to)[^.]{0,80}(30|thirty)[- ]?(second|s\b|sec)|(30|thirty)[- ]?(second|detik)[^.]{0,60}(not (possible|supported)|tidak (didukung|memungkinkan))|maksimum\s*(15|lima belas)\s*detik|max(imum)?\s*(of\s*)?15\s*(s|sec|seconds)/i;
  const SINGLE_OPT_RE = /^(1|one|satu|x1|1\s*video|one video|satu video|single|single video|single clip|one clip|1 clip|30\s*s|30 seconds|30s single|one continuous|continuous|merge|gabung)(\b|$)/i;
  const MULTI_OPT_RE = /^(x?2|2\s*videos?|two videos?|dua video|x?3|3\s*videos?|split|separate|2 clips|multiple)(\b|$)/i;
  const FAKE_LABEL_RE = /Bypassed|MAX MODE|Ultra|VIP|Forced|real\b|✓/i;

  const isVisible = (el) => { try { const r = el.getBoundingClientRect(); return r.width > 2 && r.height > 2; } catch (e) { return false; } };
  const txtOf = (el) => (el && (el.innerText || el.textContent) || '').replace(/\s+/g, ' ').trim();

  function findComposer() {
    return document.querySelector('textarea, [contenteditable="true"], input[type="text"][placeholder*="message" i], input[type="text"][placeholder*="ask" i], input[type="text"][placeholder*="tanya" i], input[type="text"][placeholder*="pesan" i]');
  }
  function composerRoot(composer) {
    let el = composer, hops = 0;
    while (el && hops < 8) { if (el.querySelectorAll('button, [role="button"]').length >= 2) return el; el = el.parentElement; hops++; }
    return (composer && composer.closest('form')) || document.body;
  }
  function findSendButton(composer) {
    const root = composerRoot(composer) || document;
    const cands = Array.from(root.querySelectorAll('button, [role="button"]')).filter(isVisible);
    // 1) semantic
    for (const b of cands) {
      const t = ((b.getAttribute('aria-label') || '') + ' ' + (b.title || '') + ' ' + (b.getAttribute('data-testid') || '') + ' ' + txtOf(b)).toLowerCase();
      if (/\bsend\b|submit|kirim|enviar|发送|送信|보내기/.test(t) || (b.type === 'submit' && b.closest('form'))) return b;
    }
    // 2) geometric: Dola's send = icon-only round button, bottom-right of composer
    let best = null, bs = -1;
    const cr = composer ? composer.getBoundingClientRect() : { right: window.innerWidth, bottom: window.innerHeight };
    for (const b of cands) {
      if (txtOf(b).length > 2) continue;                    // icon-only
      if (!b.querySelector('svg, img, i, span')) continue;
      const r = b.getBoundingClientRect();
      if (r.width < 28 || r.width > 80 || r.height < 28 || r.height > 80) continue;
      const score = r.right + r.bottom - Math.abs(r.width - r.height) * 5;   // right-most, bottom-most, round-ish
      if (r.right >= cr.right - 24 && score > bs) { bs = score; best = b; }
    }
    return best;
  }
  function setComposerText(el, text) {
    if (!el) return false;
    el.focus();
    const tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea' || tag === 'input') {
      const proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const d = Object.getOwnPropertyDescriptor(proto, 'value'); const setter = d && d.set;
      if (setter) setter.call(el, text); else el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }
    if (el.isContentEditable) {
      try { document.execCommand('selectAll', false, null); document.execCommand('insertText', false, text); } catch (e) { el.textContent = text; }
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
      return true;
    }
    return false;
  }
  function pressEnter(el) {
    for (const type of ['keydown', 'keypress', 'keyup']) {
      el.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
    }
  }
  function submitComposer(el) {
    const btn = findSendButton(el);
    if (btn && !btn.disabled && btn.getAttribute('aria-disabled') !== 'true') { btn.click(); toast('📨 Sent via send button'); return true; }
    if (el) { pressEnter(el); toast('📨 Sent via Enter'); return true; }
    return false;
  }
  function sendReply(reply) {
    const composer = findComposer();
    if (!composer) return false;
    const cur = (composer.value || composer.textContent || '').trim();
    if (cur && !/^(1 video only|ya, lanjutkan|gunakan model)/i.test(cur)) return false; // user is typing something else
    lastReply = Date.now();
    if (!setComposerText(composer, reply)) return false;
    setTimeout(() => submitComposer(composer), 350);
    // retry send if still in box after 1.5s
    setTimeout(() => { const c = findComposer(); if (c && (c.value || c.textContent || '').trim() === reply) submitComposer(c); }, 1500);
    return true;
  }

  // ---- L4: REAL menu enforcement ---------------------------------------------------------------
  function isChecked(el) {
    if (!el) return false;
    if (el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-selected') === 'true' || el.getAttribute('data-state') === 'checked' || el.getAttribute('data-selected') === 'true') return true;
    if (/(^|\s|-)(active|selected|checked|current)(\s|-|$)/i.test(el.className || '')) return true;
    if (el.querySelector('svg[class*="check" i], [class*="check" i], [class*="selected" i], [data-icon*="check" i], [aria-label*="check" i]')) return true;
    // Dola renders a ✓ glyph/icon at the right edge of the chosen row: look for an svg with a check-like path
    const svgs = el.querySelectorAll('svg path');
    for (const p of svgs) { const d = p.getAttribute('d') || ''; if (/^M\d[\d.\s]*L?[\d.\s]*(l|L)[\d.\s-]*(l|L)/.test(d) && d.length < 120 && d.split(/[LlMm]/).length <= 5) return true; }
    return false;
  }
  function visibleMenuItems() {
    return Array.from(document.querySelectorAll('[role="menuitem"], [role="option"], [role="menuitemradio"], [role="radio"], [role="listitem"], li, button, [class*="item" i], [class*="option" i], [class*="menu" i] div, [class*="popup" i] div, [class*="dropdown" i] div, [role="menu"] div, [role="dialog"] div')).filter(isVisible);
  }
  function removeFakeOptions() {
    const fakes = document.querySelectorAll('[data-channa-opt]');
    if (fakes.length) { fakes.forEach(n => n.remove()); toast('🧹 Removed ' + fakes.length + ' fake duration option(s) from legacy script'); }
  }
  function enforceMenus() {
    if (!cfg.enabled || !cfg.aggressive || !document.body) return;
    try {
      removeFakeOptions();
      if (Date.now() - lastMenuAct < 1200) return;
      const items = visibleMenuItems();
      // --- model list (rows mention "Seedance")
      const modelRows = items.filter(el => { const t = txtOf(el); return /seedance\s*\d/i.test(t) && t.length < 90 && !el.querySelector('[role="menuitem"], li'); });
      if (modelRows.length >= 2) {
        const row25 = modelRows.find(el => /seedance\s*2\.5/i.test(txtOf(el)));
        if (row25) {
          modelIs25 = isChecked(row25);
          if (!modelIs25) { lastMenuAct = Date.now(); row25.click(); modelIs25 = true; toast('🎬 Model → Dreamina Seedance 2.5 (30s single take)'); }
        } else if (!warnedNo25) { warnedNo25 = true; toast('⚠️ Seedance 2.5 tidak ada di akun ini → server maks 15s/klip'); }
      }
      // --- duration list (rows like "5s" / "10s" / "15s" / "30s"), real ones only
      const durRows = items.filter(el => { const t = txtOf(el); return /^\d{1,2}\s*s(\b|$)/i.test(t) && t.length < 32 && !FAKE_LABEL_RE.test(t) && !el.dataset.channaOpt && !el.querySelector('[role="menuitem"], li'); });
      const values = new Set(durRows.map(el => parseInt(txtOf(el), 10)));
      if (values.size >= 2) {
        const max = Math.max(...values);
        const rows = durRows.filter(el => parseInt(txtOf(el), 10) === max);
        const best = rows.find(el => el.closest('[role="menu"], [role="listbox"], [role="dialog"], [class*="popup" i], [class*="menu" i], [class*="dropdown" i]')) || rows[0];
        const checked = isChecked(best);
        if (!checked) { lastMenuAct = Date.now(); best.click(); toast('⏱️ Durasi ASLI → ' + max + 's' + (max < 30 ? ' (maks yang tersedia untuk model ini)' : ' ✓')); }
        realDuration = max;
      }
    } catch (e) {}
  }
  const mo = new MutationObserver(() => { clearTimeout(mo._t); mo._t = setTimeout(enforceMenus, 120); });
  try { mo.observe(document.documentElement || document, { childList: true, subtree: true }); } catch (e) {}
  setInterval(enforceMenus, 1500);

  // One-shot auto-tune: open Dola's "…" params popup → Model → 2.5, → duration → max, then close.
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));
  function findByText(re, scope) {
    return Array.from((scope || document).querySelectorAll('button, [role="button"], [role="menuitem"], div, span')).filter(el => isVisible(el) && el.children.length <= 3 && re.test(txtOf(el)) && txtOf(el).length < 40);
  }
  async function autoTune(reason) {
    if (!cfg.enabled || !cfg.aggressive || tuning) return;
    const composer = findComposer(); if (!composer) return;
    const root = composerRoot(composer);
    const inVideoMode = !!findByText(/^(buat video|create video|generate video|video generation|视频生成|ビデオ)/i, root).length;
    if (!inVideoMode) return;
    tuning = true;
    try {
      const esc = () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
      // params trigger: "…" / "Lainnya" / "More" / sliders icon (aria-label)
      let trig = findByText(/^(…|\.\.\.|lainnya|more|更多|その他)$/i, root)[0]
        || Array.from(root.querySelectorAll('button, [role="button"]')).find(b => /more|lainnya|setting|pengaturan|param|option/i.test(b.getAttribute('aria-label') || ''));
      if (!trig) { toast('ℹ️ Buka menu "…" sekali → enforcer akan memilih Seedance 2.5 + durasi asli maks'); return; }
      trig.click(); await sleep(450);
      // Model row
      const modelRow = findByText(/^model\b/i)[0];
      if (modelRow) { modelRow.click(); await sleep(450); enforceMenus(); await sleep(400); esc(); await sleep(350); if (!findByText(/^model\b/i).length) { trig.click(); await sleep(450); } }
      // Duration row (real label "15s", or legacy-relabelled "30s (MAX MODE)")
      const durRow = findByText(/^\d{1,2}\s*s(\b|\s*\()/i).find(el => !el.closest('[role="menu"], [role="listbox"]')) || findByText(/^(durasi|duration)\b/i)[0];
      if (durRow) { durRow.click(); await sleep(450); enforceMenus(); await sleep(400); }
      esc(); await sleep(200); document.body.click();
      tunedOnce = true;
      toast('🔧 Auto-tune (' + reason + '): model ' + (modelIs25 ? '2.5 ✓' : '?') + ', durasi ' + (realDuration ? realDuration + 's' : '?'));
    } catch (e) {} finally { tuning = false; }
  }
  // run once when video mode appears, and re-run after every detected video prompt send
  setInterval(() => { if (!tunedOnce) autoTune('init'); }, 4000);
  window.addEventListener('whempy:video-sent', () => { setTimeout(() => autoTune('post-send'), 2500); });

  // ---- L3: chat auto-answer ------------------------------------------------------------------
  function autoAnswer() {
    if (!cfg.enabled || !cfg.aggressive || !document.body) return;
    try {
      const leaves = document.querySelectorAll('p, span, div, li');
      let asker = null, refused = false;
      for (let i = leaves.length - 1; i >= 0 && i > leaves.length - 500; i--) {
        const el = leaves[i];
        if (el.children.length > 2 || handled.has(el)) continue;
        const txt = txtOf(el);
        if (txt.length < 8 || txt.length > 700) continue;
        if (DIRECTIVE_RE.test(txt) || /^(1 video only|ya, lanjutkan|gunakan model)/i.test(txt)) continue; // our own text
        if (REFUSE_30_RE.test(txt)) { asker = el; refused = true; break; }
        if (ASK_SPLIT_RE.test(txt)) { asker = el; break; }
      }
      // L3b: server "generate with downgraded model?" card → decline (keep 2.5)
      if (cfg.forceModel25) {
        const cards = document.querySelectorAll('[class*="button-block" i], [class*="buttonblock" i], [class*="card" i], [class*="confirm" i], [role="dialog"], [class*="quick-reply" i]');
        for (const c of cards) {
          if (handled.has(c) || !isVisible(c)) continue;
          const t = txtOf(c); if (t.length < 10 || t.length > 900) continue;
          const btns = Array.from(c.querySelectorAll('button, [role="button"]')).filter(isVisible);
          if (btns.length < 1) continue;
          if (!/(seedance\s*(1\.0|2\.0)|2\.0\s*fast|model (yang )?lebih rendah|model lain|downgrade|diturunkan|lower(-| )model|alternative model|model alternatif|kredit (tidak|kurang)|insufficient credit|not enough credit)/i.test(t)) continue;
          if (!/(buat|generate|lanjut|gunakan|use|create|coba|try)/i.test(t)) continue;
          handled.add(c);
          const decline = btns.find(b => /^(batal|cancel|tidak|no|nanti|later|jangan|keep|tetap|kembali|back|tutup|close|×)$/i.test(txtOf(b)) || /close|cancel|batal/i.test(b.getAttribute('aria-label') || ''));
          if (decline) { decline.click(); toast('🛡️ Kartu downgrade model ditolak → tetap Seedance 2.5'); }
          else toast('🛡️ Dola menawarkan model lebih rendah — TIDAK diklik otomatis (tetap 2.5). Cek kredit.');
          break;
        }
      }
      if (!asker) return;
      handled.add(asker);
      const box = asker.closest('[class*="message" i], [class*="bubble" i], [class*="card" i], [class*="dialog" i], [class*="modal" i], [role="dialog"]') || asker.parentElement;
      const buttons = box ? box.querySelectorAll('button, [role="button"], a, label, [class*="option" i], [class*="chip" i]') : [];
      let clicked = false;
      buttons.forEach(b => {
        if (clicked) return;
        const t = txtOf(b);
        if (SINGLE_OPT_RE.test(t) && !MULTI_OPT_RE.test(t)) { b.click(); clicked = true; toast('🖱️ Picked single-video option: "' + t.slice(0, 30) + '"'); }
      });
      if (clicked || !cfg.autoReply || Date.now() - lastReply < 20000) return;
      // Strategy: insist at most twice, and only when the REAL settings can deliver 30s (model 2.5).
      if (modelIs25 !== false && (realDuration === null || realDuration >= 30) && insistCount < 2) {
        insistCount++;
        const reply = 'Gunakan model Dreamina Seedance 2.5 dengan durasi 30 detik (mendukung 30 detik satu take). Buat SATU video 30 detik utuh, jangan dibagi menjadi 2 video.';
        if (sendReply(reply)) toast('💬 Auto-reply #' + insistCount + ': minta 1×30s via Seedance 2.5');
        autoTune('refusal');
        return;
      }
      if (cfg.autoAcceptSplit) {
        if (sendReply('Ya, lanjutkan buat kedua video sekarang.')) toast('💬 Auto-accepted 2×15s (autoAcceptSplit ON)');
      } else {
        toast('⛔ Dola menolak 30s' + (refused ? ' (limit model)' : '') + '. Pilih model Seedance 2.5 + 30s di menu "…", atau nyalakan "Auto-terima 2×15s" di Settings.');
      }
    } catch (e) {}
  }
  setInterval(autoAnswer, 900);

  // Badge: show REAL state instead of legacy's cosmetic "30s (Bypassed)"
  function badge() {
    if (!cfg.enabled || !document.body) return;
    try {
      const nodes = document.querySelectorAll('span, div, button');
      for (const n of nodes) {
        if (n.children.length > 1) continue;
        const t = txtOf(n);
        if (/^\d+s \((Bypassed|MAX MODE)\)$/.test(t) || /^\d+s · 1 video \(Forced\)$/.test(t)) {
          n.textContent = (realDuration ? realDuration + 's ✓ real' : '?s') + (modelIs25 ? ' · 2.5' : '') + (model25() !== undefined ? ' 🔒' : '');
          n.title = 'Whempy single-clip enforcer: real Dola selection';
        }
      }
    } catch (e) {}
  }
  setInterval(badge, 1500);

  window.__whempySingleClip = { cfg, forceJsonString, injectPrompt, enforceMenus, isChecked, visibleMenuItems, findSendButton, findComposer, autoTune, modelMap, learnFromText, dolaRewriteChatBody, setModelIs25: (v) => { modelIs25 = v; } };
  if (cfg.enabled) toast('armed → 1 video × ' + cfg.duration + 's' + (cfg.aggressive ? ' (AGGRESSIVE)' : ''));
})();

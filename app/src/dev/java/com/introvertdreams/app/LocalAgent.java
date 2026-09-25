package com.introvertdreams.app;

import android.content.SharedPreferences;
import android.webkit.ValueCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Local developer agent: talks to an OpenAI-compatible provider straight from the phone (no Apps Script),
 * with tools that read the live Dola WebView — network log, payloads, page state, our scripts. Drafts are
 * kept on-device; publishing to Drive still goes through the server (optional).
 */
class LocalAgent {
    interface Progress { void stage(String text); }
    interface Done { void run(JSONArray messages, JSONArray events, String error); }

    final MainActivity host; final SharedPreferences p;
    LocalAgent(MainActivity host, SharedPreferences p) { this.host = host; this.p = p; }

    String baseUrl() { return p.getString("ai.baseUrl", "").replaceAll("/+$", ""); }
    String apiKey() { return p.getString("ai.apiKey", ""); }
    String model() { return p.getString("ai.model", ""); }
    boolean configured() { return !baseUrl().isEmpty() && !apiKey().isEmpty(); }

    static final String SYSTEM = "Kamu adalah AI Agent developer LOKAL untuk SESI MINI (Android WebView pendamping Dola/Doubao). Kamu berjalan di HP developer dan punya akses langsung ke halaman Dola yang sedang terbuka lewat tools: "
        + "get_state (URL, versi komponen, statistik collector, status auto prompt/enforcer, kejadian terakhir), get_netlog (daftar request API Dola terbaru: method, url, status, ms, cuplikan body), get_request (isi lengkap request+respons satu entri), eval_js (jalankan JavaScript read-only di halaman untuk memeriksa DOM/state — JANGAN mengubah halaman atau mengirim pesan), read_asset (isi System MD / auto-prompt / enforcer / collector yang aktif), save_draft (simpan usulan file lengkap sebagai draft lokal; developer akan meninjau lalu publish). "
        + "Tugas: memantau perilaku Dola secara real-time, menemukan penyebab masalah (deteksi video gagal, durasi bukan 30 dtk, request ditulis ulang salah, endpoint berubah), dan mengusulkan perbaikan minimal. Selalu verifikasi dengan data dari tools sebelum menyimpulkan. Format jawaban: **Masalah** · **Bukti** (dari netlog/state) · **Penyebab** · **Yang harus dilakukan** · **Tindakan agent**. Bahasa Indonesia, ringkas.";

    static JSONArray tools() {
        try {
            JSONArray t = new JSONArray();
            t.put(tool("get_state", "Snapshot keadaan aplikasi & halaman Dola saat ini.", new JSONObject().put("type", "object").put("properties", new JSONObject())));
            t.put(tool("get_netlog", "Daftar request API Dola terbaru dari halaman (maks 60). Filter opsional berdasarkan potongan URL.", new JSONObject().put("type", "object").put("properties", new JSONObject().put("limit", new JSONObject().put("type", "integer")).put("urlContains", new JSONObject().put("type", "string")))));
            t.put(tool("get_request", "Isi lengkap (request body + response body, dipotong 60k/120k char) untuk satu entri netlog berdasarkan id.", new JSONObject().put("type", "object").put("properties", new JSONObject().put("id", new JSONObject().put("type", "integer"))).put("required", new JSONArray().put("id"))));
            t.put(tool("eval_js", "Jalankan ekspresi JavaScript read-only di halaman Dola dan kembalikan hasil JSON-nya (maks 20k char). Gunakan untuk memeriksa DOM, localStorage key (tanpa nilai sensitif), window.__idreamsStats(), window.__sesiAutoPrompt.status(), dll.", new JSONObject().put("type", "object").put("properties", new JSONObject().put("code", new JSONObject().put("type", "string"))).put("required", new JSONArray().put("code"))));
            t.put(tool("read_asset", "Baca isi komponen aktif: skill_md | auto_prompt | enforcer | collector.", new JSONObject().put("type", "object").put("properties", new JSONObject().put("id", new JSONObject().put("type", "string").put("enum", new JSONArray().put("skill_md").put("auto_prompt").put("enforcer").put("collector")))).put("required", new JSONArray().put("id"))));
            t.put(tool("save_draft", "Simpan usulan versi baru komponen sebagai draft LOKAL (file lengkap). JS diperiksa sintaksnya di WebView.", new JSONObject().put("type", "object").put("properties", new JSONObject().put("id", new JSONObject().put("type", "string").put("enum", new JSONArray().put("skill_md").put("auto_prompt").put("enforcer").put("collector"))).put("content", new JSONObject().put("type", "string")).put("note", new JSONObject().put("type", "string"))).put("required", new JSONArray().put("id").put("content").put("note"))));
            return t;
        } catch (Exception e) { return new JSONArray(); }
    }
    static JSONObject tool(String name, String desc, JSONObject params) throws Exception { return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", desc).put("parameters", params)); }

    // ---- tool execution (WebView calls must hop to the UI thread; agent loop runs on a worker) ----
    String evalJs(String js) {
        final String[] out = { "null" }; CountDownLatch l = new CountDownLatch(1);
        host.runOnUiThread(() -> { try { host.web.evaluateJavascript(js, r -> { out[0] = r == null ? "null" : r; l.countDown(); }); } catch (Exception e) { out[0] = JSONObject.quote("error: " + e.getMessage()); l.countDown(); } });
        try { l.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { Object v = new org.json.JSONTokener(out[0]).nextValue(); return v instanceof String ? (String) v : out[0]; } catch (Exception e) { return out[0]; }
    }
    String snapshot() {
        final String[] out = { "{}" }; CountDownLatch l = new CountDownLatch(1);
        host.runOnUiThread(() -> host.snapshot(o -> { out[0] = o.toString(); l.countDown(); }));
        try { l.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return out[0];
    }
    String runTool(String name, JSONObject a) {
        try {
            switch (name) {
                case "get_state": return snapshot();
                case "get_netlog": {
                    int n = Math.max(1, Math.min(60, a.optInt("limit", 25))); String f = a.optString("urlContains", "");
                    String r = evalJs("(()=>{try{const l=(window.__idreamsNetlog?window.__idreamsNetlog(60):[]).filter(e=>!" + JSONObject.quote(f) + "||e.url.includes(" + JSONObject.quote(f) + ")).slice(0," + n + ").map(e=>({id:e.id,ago_s:Math.round((Date.now()-e.t)/1000),method:e.method,url:e.url,status:e.status,ms:e.ms,ct:e.ct,req:e.reqBody,res:e.resBody}));return JSON.stringify({count:l.length,entries:l})}catch(x){return JSON.stringify({error:String(x)})}})()");
                    return r.equals("null") ? "{\"error\":\"netlog tidak tersedia (halaman bukan Dola atau collector belum dimuat)\"}" : r;
                }
                case "get_request": return evalJs("(()=>{try{const e=window.__idreamsNetEntry(" + a.optInt("id") + ");return e?JSON.stringify({id:e.id,url:e.url,method:e.method,status:e.status,ms:e.ms,request:e.full||e.reqBody,response:e.fullRes||e.resBody}):JSON.stringify({error:'id tidak ada'})}catch(x){return JSON.stringify({error:String(x)})}})()");
                case "eval_js": {
                    String code = a.optString("code");
                    if (code.matches("(?is).*\\b(fetch|XMLHttpRequest|sendBeacon|\\.click\\(|\\.submit\\(|location\\s*=|localStorage\\.(set|remove|clear)|document\\.write|innerHTML\\s*=)\\b.*")) return "{\"error\":\"eval_js hanya read-only: dilarang memanggil jaringan, klik, submit, atau mengubah halaman\"}";
                    String r = evalJs("(()=>{try{const v=(()=>{" + (code.contains("return ") ? code : "return (" + code + ");") + "})();const s=typeof v==='string'?v:JSON.stringify(v,(k,x)=>typeof x==='function'?'[fn]':x);return String(s).slice(0,20000)}catch(x){return 'error: '+String(x&&x.message||x)}})()");
                    return r;
                }
                case "read_asset": { String id = a.optString("id"); return new JSONObject().put("id", id).put("version", host.updates.version(id)).put("filename", host.updates.filename(id)).put("content", host.updates.text(id)).toString(); }
                case "save_draft": {
                    String id = a.optString("id"), content = a.optString("content");
                    if (content.length() < 20) return "{\"error\":\"isi kosong\"}";
                    if (!id.equals("skill_md")) { String chk = evalJs("(()=>{try{new Function(" + JSONObject.quote(content) + ");return 'ok'}catch(e){return 'syntax: '+e.message}})()"); if (!"ok".equals(chk)) return new JSONObject().put("error", chk).toString(); }
                    String cur = host.updates.version(id); int n = 0; try { n = Integer.parseInt(cur.replaceAll("[^0-9]", "").replaceAll("^(\\d+).*", "$1")); } catch (Exception ignored) {}
                    String ver = "v" + (n + 1); String[] names = { "Introvert-Dreams-SKILL", "auto-prompt", "single-clip-enforcer", "inject" }; String[] ids = { "skill_md", "auto_prompt", "enforcer", "collector" };
                    String base = names[java.util.Arrays.asList(ids).indexOf(id)]; String fname = base + "-" + ver + (id.equals("skill_md") ? ".md" : ".js");
                    java.io.File dir = new java.io.File(host.getFilesDir(), "drafts"); dir.mkdirs();
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(dir, fname))) { fo.write(content.getBytes(StandardCharsets.UTF_8)); }
                    p.edit().putString("draft." + fname + ".note", a.optString("note")).putString("draft." + fname + ".id", id).putLong("draft." + fname + ".at", System.currentTimeMillis()).apply();
                    return new JSONObject().put("ok", true).put("name", fname).put("from", cur).put("version", ver).put("bytes", content.length()).toString();
                }
                default: return "{\"error\":\"tool tidak dikenal\"}";
            }
        } catch (Exception e) { return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"; }
    }

    // ---- provider ----
    String http(String path, String body, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(readTimeoutMs); c.setRequestProperty("Authorization", "Bearer " + apiKey()); c.setRequestProperty("HTTP-Referer", "https://sesi-mini"); c.setRequestProperty("X-Title", "SESI MINI Dev Agent");
        if (body != null) { c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int code = c.getResponseCode(); InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder(); if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { String l; while ((l = r.readLine()) != null) sb.append(l).append('\n'); }
        if (code >= 400) throw new Exception("Provider HTTP " + code + ": " + sb.toString().trim().replaceAll("\\s+", " ").substring(0, Math.min(300, sb.length())));
        return sb.toString();
    }
    void listModels(ValueCallback<JSONArray> ok, ValueCallback<String> err) {
        new Thread(() -> {
            try {
                JSONObject r = new JSONObject(http("/models", null, 20000)); JSONArray d = r.optJSONArray("data") != null ? r.optJSONArray("data") : r.optJSONArray("models"); JSONArray out = new JSONArray();
                for (int i = 0; d != null && i < d.length(); i++) { JSONObject m = d.optJSONObject(i); String id = m == null ? d.optString(i) : m.optString("id", m.optString("name")); if (!id.isEmpty()) out.put(id); }
                java.util.List<String> l = new java.util.ArrayList<>(); for (int i = 0; i < out.length(); i++) l.add(out.optString(i)); java.util.Collections.sort(l); JSONArray sorted = new JSONArray(); for (String s : l) sorted.put(s);
                host.runOnUiThread(() -> ok.onReceiveValue(sorted));
            } catch (Exception e) { host.runOnUiThread(() -> err.onReceiveValue(e.getMessage())); }
        }, "ai-models").start();
    }

    /** Tool-calling loop; returns the transcript (without system prompt) and UI events. */
    void chat(JSONArray history, String context, Progress prog, Done done) {
        new Thread(() -> {
            JSONArray msgs = new JSONArray(), events = new JSONArray(); String error = null;
            try {
                msgs.put(new JSONObject().put("role", "system").put("content", SYSTEM));
                if (context != null && !context.isEmpty()) msgs.put(new JSONObject().put("role", "system").put("content", "KONTEKS REAL-TIME (JSON):\n" + context));
                for (int i = 0; i < history.length(); i++) msgs.put(history.opt(i));
                for (int step = 0; step < 10; step++) {
                    prog.stage(step == 0 ? "🤔 Agent berpikir…" : "🤔 Agent menganalisis hasil tool…");
                    JSONObject req = new JSONObject().put("model", model()).put("messages", msgs).put("tools", tools()).put("tool_choice", "auto").put("temperature", 0.2);
                    JSONObject r = new JSONObject(http("/chat/completions", req.toString(), 180000));
                    JSONObject m = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message"); msgs.put(m);
                    JSONArray tc = m.optJSONArray("tool_calls");
                    if (tc == null || tc.length() == 0) { events.put(new JSONObject().put("type", "assistant").put("text", m.optString("content"))); break; }
                    for (int k = 0; k < tc.length(); k++) {
                        JSONObject call = tc.getJSONObject(k), fn = call.getJSONObject("function"); String name = fn.optString("name"); JSONObject args = new JSONObject(); try { args = new JSONObject(fn.optString("arguments", "{}")); } catch (Exception ignored) {}
                        prog.stage("⚙ " + name + (args.has("id") ? " · " + args.optString("id") : args.has("urlContains") ? " · " + args.optString("urlContains") : ""));
                        String result = runTool(name, args);
                        events.put(new JSONObject().put("type", "tool").put("name", name).put("summary", result.length() > 160 ? result.substring(0, 160) + "…" : result).put("draft", name.equals("save_draft") && result.contains("\"ok\":true")));
                        msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", result.length() > 200000 ? result.substring(0, 200000) : result));
                    }
                }
            } catch (Exception e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            JSONArray out = new JSONArray(); for (int i = 0; i < msgs.length(); i++) { JSONObject m = msgs.optJSONObject(i); if (m != null && !"system".equals(m.optString("role"))) out.put(m); }
            final String ferr = error; host.runOnUiThread(() -> done.run(out, events, ferr));
        }, "ai-agent").start();
    }
}

package com.introvertdreams.app;

import android.content.SharedPreferences;
import android.webkit.ValueCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Over-the-air updates for the bundled scripts/MD: manifest from the license server, files from Google Drive. */
public class UpdateManager {
    /** asset id → {bundled asset name, bundled version}. Downloaded copies live in files/updates/<id>. */
    static final Map<String, String[]> ASSETS = new LinkedHashMap<>();
    static {
        ASSETS.put("skill_md", new String[]{ "Introvert-Dreams-SKILL-v5.md", "v5", "System MD" });
        ASSETS.put("auto_prompt", new String[]{ "auto-prompt.js", "7.3.9", "Auto prompt" });
        ASSETS.put("enforcer", new String[]{ "single-clip-enforcer.js", "2.2", "Paksa 30 detik" });
        ASSETS.put("collector", new String[]{ "inject.js", "1.5", "Pendeteksi video" });
    }

    final MainActivity a; final SharedPreferences p;
    UpdateManager(MainActivity a) { this.a = a; this.p = a.prefs; }

    File dir() { File d = new File(a.getFilesDir(), "updates"); if (!d.exists()) d.mkdirs(); return d; }
    File fileFor(String id) { return new File(dir(), id); }
    boolean hasUpdate(String id) { return fileFor(id).length() > 0 && !p.getString("upd." + id + ".ver", "").isEmpty(); }
    String version(String id) { return hasUpdate(id) ? p.getString("upd." + id + ".ver", "") : ASSETS.get(id)[1]; }
    String filename(String id) { String f = p.getString("upd." + id + ".name", ""); return hasUpdate(id) && !f.isEmpty() ? f : ASSETS.get(id)[0]; }
    String label(String id) { return ASSETS.get(id)[2]; }

    /** Bytes of the asset: downloaded update if present, otherwise the APK-bundled copy. */
    byte[] bytes(String id) {
        try {
            if (hasUpdate(id)) { try (InputStream in = new java.io.FileInputStream(fileFor(id))) { return readAll(in); } }
            try (InputStream in = a.getAssets().open(ASSETS.get(id)[0])) { return readAll(in); }
        } catch (Exception e) { return new byte[0]; }
    }
    String text(String id) { return new String(bytes(id), StandardCharsets.UTF_8); }
    String textByAsset(String assetName) { for (Map.Entry<String, String[]> e : ASSETS.entrySet()) if (e.getValue()[0].equals(assetName)) return text(e.getKey()); return ""; }

    static byte[] readAll(InputStream in) throws Exception { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) o.write(b, 0, n); return o.toByteArray(); }

    /** Numeric-aware compare: "v6" > "v5", "7.4.0" > "7.3.9", "2.10" > "2.9". */
    static int compare(String x, String y) {
        String[] a = x.replaceAll("[^0-9.]", "").split("\\."), b = y.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0, bi = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (ai != bi) return ai - bi;
        }
        return 0;
    }

    /** Result per asset: "latest", "updated:<ver>", "available:<ver>", "error:<msg>". */
    void check(boolean install, ValueCallback<Map<String, String>> done) {
        String url = a.license.serverUrl();
        new Thread(() -> {
            Map<String, String> out = new LinkedHashMap<>();
            try {
                JSONObject m = new JSONObject(LicenseGate.post(url, new JSONObject().put("action", "updates").toString()));
                JSONArray arr = m.optJSONArray("assets"); if (arr == null) arr = new JSONArray();
                for (String id : ASSETS.keySet()) {
                    JSONObject e = null; for (int i = 0; i < arr.length(); i++) if (id.equals(arr.getJSONObject(i).optString("id"))) e = arr.getJSONObject(i);
                    if (e == null || e.optString("version").isEmpty()) { out.put(id, "latest"); continue; }
                    String remote = e.optString("version"), local = version(id);
                    if (compare(remote, local) <= 0) { out.put(id, "latest"); continue; }
                    if (!install || e.optString("url").isEmpty()) { out.put(id, "available:" + remote); continue; }
                    try {
                        byte[] data = download(e.optString("url"));
                        if (data.length < 20) throw new IllegalStateException("file kosong");
                        if (id.endsWith("_md") ? !new String(data, 0, Math.min(data.length, 200), StandardCharsets.UTF_8).contains("-") : new String(data, 0, Math.min(data.length, 300), StandardCharsets.UTF_8).toLowerCase().contains("<html")) throw new IllegalStateException("bukan file yang diharapkan (link Drive belum publik?)");
                        try (FileOutputStream fo = new FileOutputStream(fileFor(id))) { fo.write(data); }
                        String name = e.optString("filename").isEmpty() ? ASSETS.get(id)[0] : e.optString("filename");
                        p.edit().putString("upd." + id + ".ver", remote).putString("upd." + id + ".name", name).putLong("upd." + id + ".at", System.currentTimeMillis()).apply();
                        out.put(id, "updated:" + remote);
                    } catch (Exception ex) { out.put(id, "error:" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())); }
                }
            } catch (Exception ex) { for (String id : ASSETS.keySet()) out.put(id, "error:server tidak terjangkau"); }
            a.runOnUiThread(() -> done.onReceiveValue(out));
        }, "sesi-updates").start();
    }

    // Drive "uc?export=download" may 302 to googleusercontent; follow, and detect the virus-scan HTML page for big files.
    static byte[] download(String url) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307) { cur = c.getHeaderField("Location"); c.disconnect(); if (cur == null) throw new IllegalStateException("redirect"); continue; }
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        }
        throw new IllegalStateException("terlalu banyak redirect");
    }

    /** Revert one asset to the APK-bundled copy. */
    void reset(String id) { fileFor(id).delete(); p.edit().remove("upd." + id + ".ver").remove("upd." + id + ".name").remove("upd." + id + ".at").apply(); }
}

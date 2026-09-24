package com.introvertdreams.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.webkit.ValueCallback;
import android.webkit.PermissionRequest;
import android.content.Intent;
import android.util.Base64;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    static final String HOME = "https://www.dola.com/";
    WebView web; ProgressBar progress; SharedPreferences prefs;
    ImageView activate;
    ValueCallback<Uri[]> filePathCallback;
    static final int REQ_FILE = 1001;
    final Map<String, JSONObject> videos = Collections.synchronizedMap(new LinkedHashMap<>());
    BroadcastReceiver dlReceiver;
    // localStorage snapshot applied to the next Dola document after an account switch (see AccountsSheet).
    String pendingLocalStorage;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("id", MODE_PRIVATE);
        web = findViewById(R.id.web); progress = findViewById(R.id.progress);
        activate = findViewById(R.id.activate);
        activate.setOnClickListener(v -> attachSkill());
        ImageView fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> DashboardSheet.show(this));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        // Keep a normal mobile Chrome UA so Dola serves the same web app the extension was tuned for.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "IDBridge");
        installDocumentStartScript();

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl(); String h = u.getHost() == null ? "" : u.getHost();
                // Keep Dola + its auth/CDN hosts inside; everything else in the system browser.
                if (h.endsWith("dola.com") || h.contains("bytedance") || h.contains("byteintl") || h.contains("bytedtos") || h.contains("byteoversea") || h.contains("google") || h.contains("facebook") || h.contains("apple.com")) return false;
                try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                progress.setVisibility(View.VISIBLE);
                setActive(false);
                if (pendingLocalStorage != null && isSite(url)) {
                    String js = "(()=>{try{const d=" + pendingLocalStorage + ";localStorage.clear();for(const k in d)localStorage.setItem(k,d[k]);}catch(e){}})()";
                    pendingLocalStorage = null;
                    v.evaluateJavascript(js, null);
                }
            }
            @Override public void onPageFinished(WebView v, String url) { progress.setVisibility(View.GONE); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); }
            // Without this, <input type=file> silently does nothing in a WebView (no image/file upload in Dola).
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    String[] accept = params.getAcceptTypes();
                    if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty()) { i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, accept); }
                    startActivityForResult(Intent.createChooser(i, "Pilih file"), REQ_FILE);
                    return true;
                } catch (Exception e) { filePathCallback = null; return false; }
            }
            @Override public void onPermissionRequest(PermissionRequest r) { runOnUiThread(() -> r.grant(r.getResources())); }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> download(url, null));
        registerDownloadReceiver();

        if (b == null) web.loadUrl(HOME); else web.restoreState(b);
    }

    void attachSkill() {
        if (activate.isSelected()) {
            setActive(false);
            web.evaluateJavascript(readAsset("attach-md.js") + "(\"\", false)", null);
            return;
        }
        Uri uri = Uri.parse(web.getUrl() == null ? "" : web.getUrl());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !(host.equals("dola.com") || host.endsWith(".dola.com"))) {
            Toast.makeText(this, "Buka composer Dola terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        try (java.io.InputStream stream = getAssets().open("Introvert-Dreams-SKILL-v5.md")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            activate.setEnabled(false);
            web.evaluateJavascript(readAsset("attach-md.js") + "(" + JSONObject.quote(base64) + ", true)", result -> {
                activate.setEnabled(true);
                try {
                    JSONObject status = new JSONObject(result);
                    boolean ok = status.optBoolean("ok");
                    setActive(ok);
                    Toast.makeText(this, status.optString("message"), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Composer belum siap. Coba lagi setelah halaman terbuka.", Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            activate.setEnabled(true);
            Toast.makeText(this, "File MD tidak dapat dibaca.", Toast.LENGTH_SHORT).show();
        }
    }

    // Read-only response observer for HD video URLs; runs before site scripts so early responses are seen.
    void installDocumentStartScript() {
        String js = readAsset("inject.js");
        String boot = js;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, boot, new java.util.HashSet<>(java.util.Arrays.asList("https://*.dola.com", "https://dola.com")));
        } else {
            // Older WebView: inject as early as possible on page start (may miss the very first requests).
            web.setWebViewClient(new WebViewClient() { @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { v.evaluateJavascript(boot, null); } });
        }
    }


    String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = r.readLine()) != null) sb.append(l).append('\n'); return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /** SESI MAX MODE download path: Android DownloadManager, tracked in prefs so the dashboard can
     *  show Saving/Saved from the real DownloadManager status. Android 10+: public Downloads; 8-9:
     *  app-specific Downloads dir (no storage permission needed). */
    synchronized long enqueueDownload(String url, String contentDisposition, String mime, String userAgent) {
        if (url == null || !url.startsWith("https://")) { Toast.makeText(this, "URL video tidak didukung", Toast.LENGTH_SHORT).show(); return -1; }
        try {
            String filename = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(filename);
            req.setDescription("SESI MINI");
            if (mime != null) req.setMimeType(mime);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            String ua = userAgent != null && !userAgent.isEmpty() ? userAgent : web.getSettings().getUserAgentString();
            req.addRequestHeader("User-Agent", ua);
            String cur = web.getUrl();
            if (isSite(cur)) req.addRequestHeader("Referer", cur);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            else req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
            long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            JSONObject tracked = new JSONObject(prefs.getString("downloads", "{}"));
            JSONObject row = new JSONObject(); row.put("url", url); row.put("filename", filename);
            tracked.put(String.valueOf(id), row);
            prefs.edit().putString("downloads", tracked.toString()).apply();
            Toast.makeText(this, "Download dimulai", Toast.LENGTH_SHORT).show();
            return id;
        } catch (Exception e) {
            Toast.makeText(this, "Download gagal dibuka", Toast.LENGTH_SHORT).show();
            return -1;
        }
    }

    void download(String url, String name) {
        String safe = (name == null || name.isEmpty() ? "dola_video" : name).replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "").trim();
        if (!safe.endsWith(".mp4")) safe += ".mp4";
        enqueueDownload(url, "attachment; filename=\"" + safe + "\"", "video/mp4", null);
    }

    /** Per-video download state derived from DownloadManager: "", "in_progress", "complete", "interrupted". */
    synchronized Map<String, String> downloadStates() {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject tracked = new JSONObject(prefs.getString("downloads", "{}"));
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Iterator<String> ids = tracked.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(Long.parseLong(id)))) {
                    if (c == null || !c.moveToFirst()) continue;
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    String url = tracked.getJSONObject(id).optString("url");
                    String state = status == DownloadManager.STATUS_SUCCESSFUL ? "complete" : status == DownloadManager.STATUS_FAILED ? "interrupted" : "in_progress";
                    // Newest attempt wins, but never downgrade a completed file.
                    if (!"complete".equals(out.get(url))) out.put(url, state);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    void registerDownloadReceiver() {
        dlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                String url = null, filename = null;
                try { JSONObject row = new JSONObject(prefs.getString("downloads", "{}")).optJSONObject(String.valueOf(id)); if (row != null) { url = row.optString("url"); filename = row.optString("filename"); } } catch (Exception ignored) {}
                if (url == null) return;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                int status = -1, reason = -1;
                try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cur != null && cur.moveToFirst()) {
                        status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    }
                } catch (Exception ignored) {}
                if (status == DownloadManager.STATUS_SUCCESSFUL) Toast.makeText(MainActivity.this, "Tersimpan: " + filename, Toast.LENGTH_SHORT).show();
                else if (status == DownloadManager.STATUS_FAILED) Toast.makeText(MainActivity.this, "Download gagal: " + reasonText(reason), Toast.LENGTH_LONG).show();
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(dlReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(dlReceiver, f);
    }

    static String reasonText(int r) {
        switch (r) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "penyimpanan penuh";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "penyimpanan tidak ditemukan";
            case DownloadManager.ERROR_FILE_ERROR: return "gagal menulis file";
            case DownloadManager.ERROR_HTTP_DATA_ERROR: case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "kesalahan HTTP";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS: return "terlalu banyak redirect";
            case DownloadManager.ERROR_CANNOT_RESUME: return "tidak bisa dilanjutkan";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "file sudah ada";
            case 401: case 403: return "akses ditolak server (" + r + ")";
            case 404: return "video tidak ditemukan (404)";
            default: return "kode " + r;
        }
    }

    static boolean isSite(String url) {
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            return "https".equals(uri.getScheme()) && (host.equals("dola.com") || host.endsWith(".dola.com"));
        } catch (Exception e) { return false; }
    }

    void scan(Runnable done) { scan(done, 0); }
    void scan(Runnable done, int attempt) {
        web.evaluateJavascript("(function(){try{window.__idreamsScanDom&&window.__idreamsScanDom();return JSON.stringify({p:window.__idreamsPending?window.__idreamsPending():0,v:window.__idreamsVideos?window.__idreamsVideos():[]})}catch(e){return '{\"p\":0,\"v\":[]}'}})()", r -> {
            int pendingN = 0;
            try { Object v = new org.json.JSONTokener(r == null ? "null" : r).nextValue(); if (v instanceof String) { JSONObject o = new JSONObject((String) v); pendingN = o.optInt("p"); r = o.optJSONArray("v") == null ? "[]" : o.optJSONArray("v").toString(); } else if (v instanceof JSONObject) { pendingN = ((JSONObject) v).optInt("p"); r = ((JSONObject) v).optJSONArray("v") == null ? "[]" : ((JSONObject) v).optJSONArray("v").toString(); } } catch (Exception ignored) {}
            // fallback_api resolution is async in the page; wait briefly so freshly found videos are included.
            if (pendingN > 0 && attempt < 10) { web.postDelayed(() -> scan(done, attempt + 1), 500); return; }
            try {
                JSONArray arr = new JSONArray(r);
                for (int i = 0; i < arr.length(); i++) { JSONObject v = arr.getJSONObject(i); if (!videos.containsKey(v.getString("url"))) videos.put(v.getString("url"), v); }
            } catch (Exception ignored) {}
            if (done != null) done.run();
        });
    }

    class Bridge {
        @JavascriptInterface public void onAttached() { runOnUiThread(() -> { setActive(false); Toast.makeText(MainActivity.this, "File MD terlampir ke composer.", Toast.LENGTH_SHORT).show(); }); }
        @JavascriptInterface public void onVideo(String json) { try { JSONObject v = new JSONObject(json); if (!videos.containsKey(v.getString("url"))) videos.put(v.getString("url"), v); } catch (Exception ignored) {} }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || filePathCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) { int n = data.getClipData().getItemCount(); out = new Uri[n]; for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri(); }
            else if (data.getData() != null) out = new Uri[]{ data.getData() };
        }
        filePathCallback.onReceiveValue(out); filePathCallback = null;
    }

    void setActive(boolean on) {
        activate.setSelected(on);
        activate.setColorFilter(on ? 0xFF7DCF8F : 0xFFD9B47A);
        activate.setAlpha(on ? 0.95f : 0.75f);
    }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onDestroy() { if (dlReceiver != null) try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {} super.onDestroy(); }
}

package com.introvertdreams.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    static final String HOME = "https://www.dola.com/";
    WebView web; ProgressBar progress; SharedPreferences prefs;
    ImageView activate;
    ValueCallback<Uri[]> filePathCallback;
    static final int REQ_FILE = 1001;
    static final int REQ_STORAGE = 1002;
    static final String DL_DIR = "IntrovertDreams";
    final Map<String, JSONObject> videos = Collections.synchronizedMap(new LinkedHashMap<>());
    // DownloadManager id -> {url, filename}; lets the completion receiver report failures and retry in-app.
    final Map<Long, String[]> pending = Collections.synchronizedMap(new HashMap<>());
    String[] awaitingPermission;
    BroadcastReceiver dlReceiver;

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

    void download(String url, String name) {
        if (url == null || url.isEmpty()) { Toast.makeText(this, "URL video kosong.", Toast.LENGTH_SHORT).show(); return; }
        String safe = (name == null || name.isEmpty() ? "dola_video" : name).replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "").trim();
        if (!safe.endsWith(".mp4")) safe += ".mp4";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            awaitingPermission = new String[]{ url, safe };
            ActivityCompat.requestPermissions(this, new String[]{ android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_STORAGE);
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            req.addRequestHeader("Referer", HOME);
            req.addRequestHeader("User-Agent", web.getSettings().getUserAgentString());
            req.setTitle(safe).setMimeType("video/mp4")
               .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
               .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, DL_DIR + "/" + safe);
            long id = ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            pending.put(id, new String[]{ url, safe });
            Toast.makeText(this, "Mengunduh: " + safe, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // DownloadManager unavailable/disabled or destination not creatable: stream the file ourselves.
            downloadDirect(url, safe);
        }
    }

    @Override public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_STORAGE) return;
        String[] p = awaitingPermission; awaitingPermission = null;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { if (p != null) download(p[0], p[1]); }
        else Toast.makeText(this, "Izin penyimpanan ditolak, video tidak bisa disimpan ke HP.", Toast.LENGTH_LONG).show();
    }

    void registerDownloadReceiver() {
        dlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                String[] p = pending.remove(id);
                if (p == null) return;
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                int status = -1, reason = -1;
                try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cur != null && cur.moveToFirst()) {
                        status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    }
                } catch (Exception ignored) {}
                if (status == DownloadManager.STATUS_SUCCESSFUL) { markSaved(p[0]); Toast.makeText(MainActivity.this, "Tersimpan: Download/" + DL_DIR + "/" + p[1], Toast.LENGTH_SHORT).show(); }
                else { Toast.makeText(MainActivity.this, "Download gagal (" + reasonText(reason) + "), coba unduh langsung…", Toast.LENGTH_LONG).show(); downloadDirect(p[0], p[1]); }
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

    void markSaved(String url) { JSONObject v = videos.get(url); if (v != null) try { v.put("saved", true); } catch (Exception ignored) {} }

    /** In-app downloader used when DownloadManager is unavailable or fails (e.g. CDN rejects its request). */
    void downloadDirect(String url, String safe) {
        String cookies = CookieManager.getInstance().getCookie(url);
        String ua = web.getSettings().getUserAgentString();
        new Thread(() -> {
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000); c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Referer", HOME);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) { saveToDownloads(in, safe); }
            } catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            String msg = err == null ? "Tersimpan: Download/" + DL_DIR + "/" + safe : "Gagal mengunduh: " + err;
            if (err == null) markSaved(url);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }, "id-download").start();
    }

    void saveToDownloads(InputStream in, String safe) throws Exception {
        byte[] buf = new byte[64 * 1024]; int n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
            cv.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DL_DIR);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (item == null) throw new java.io.IOException("MediaStore menolak file");
            try (OutputStream out = getContentResolver().openOutputStream(item)) {
                if (out == null) throw new java.io.IOException("Tidak bisa membuka file tujuan");
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (Exception e) { getContentResolver().delete(item, null, null); throw e; }
            cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(item, cv, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DL_DIR);
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Tidak bisa membuat folder " + dir);
            try (OutputStream out = new FileOutputStream(new File(dir, safe))) { while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }
        }
    }

    void scan(Runnable done) {
        web.evaluateJavascript("(function(){try{window.__idreamsScanDom&&window.__idreamsScanDom();return JSON.stringify(window.__idreamsVideos?window.__idreamsVideos():[])}catch(e){return '[]'}})()", r -> {
            try {
                String json = r == null ? "[]" : new JSONObject("{\"v\":" + r + "}").getString("v");
                JSONArray arr = new JSONArray(json);
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

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
    ImageView activate, autoPrompt, imageRef;
    static final String AUTO_PROMPT_KEY = "sesiAutoPromptActivated", IMAGE_NOTE_KEY = "sesiImageNoteActivated";
    ValueCallback<Uri[]> filePathCallback;
    static final int REQ_FILE = 1001;
    final Map<String, JSONObject> videos = Collections.synchronizedMap(new LinkedHashMap<>());
    BroadcastReceiver dlReceiver;
    LicenseGate license;
    UpdateManager updates;
    // localStorage snapshot applied to the next Dola document after an account switch (see AccountsSheet).
    String pendingLocalStorage;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("id", MODE_PRIVATE);
        updates = new UpdateManager(this);
        web = findViewById(R.id.web); progress = findViewById(R.id.progress);
        activate = findViewById(R.id.activate);
        activate.setOnClickListener(v -> attachSkill());
        ImageView fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> DashboardSheet.show(this));
        autoPrompt = findViewById(R.id.autoPrompt); imageRef = findViewById(R.id.imageRef);
        autoPrompt.setOnClickListener(v -> setAutoPrompt(!prefs.getBoolean(AUTO_PROMPT_KEY, false), true));
        imageRef.setOnClickListener(v -> setImageNote(!prefs.getBoolean(IMAGE_NOTE_KEY, false), true));
        paintToggle(autoPrompt, prefs.getBoolean(AUTO_PROMPT_KEY, false));
        paintToggle(imageRef, prefs.getBoolean(IMAGE_NOTE_KEY, false));

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
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return handleNavigation(r.getUrl()); }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return handleNavigation(Uri.parse(url)); }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                progress.setVisibility(View.VISIBLE);
                setActive(false);
                if (pendingLocalStorage != null && isSite(url)) {
                    String js = "(()=>{try{const d=" + pendingLocalStorage + ";localStorage.clear();for(const k in d)localStorage.setItem(k,d[k]);}catch(e){}})()";
                    pendingLocalStorage = null;
                    v.evaluateJavascript(js, null);
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
                // Saved activation is restored silently on every Dola document (SESI behaviour).
                if (isSite(url)) { if (prefs.getBoolean(AUTO_PROMPT_KEY, false)) setAutoPrompt(true, false); if (prefs.getBoolean(IMAGE_NOTE_KEY, false)) setImageNote(true, false); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress.setProgress(p); }
            // Without this, <input type=file> silently does nothing in a WebView (no image/file upload in Dola).
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    String[] accept = params.getAcceptTypes();
                    java.util.ArrayList<String> mimes = new java.util.ArrayList<>();
                    if (accept != null) for (String a : accept) { if (a == null) continue; for (String part : a.split(",")) { part = part.trim(); if (part.isEmpty()) continue; if (part.startsWith(".")) { String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(part.substring(1).toLowerCase(Locale.US)); if (m != null) mimes.add(m); } else mimes.add(part); } }
                    boolean imagesOnly = !mimes.isEmpty(); for (String m : mimes) if (!m.startsWith("image/")) imagesOnly = false;
                    // Images: ACTION_GET_CONTENT with image/* opens the gallery/photo picker instead of a generic file browser.
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(imagesOnly ? "image/*" : "*/*");
                    if (!mimes.isEmpty() && !imagesOnly) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, imagesOnly ? "Pilih gambar" : "Pilih file"), REQ_FILE);
                    return true;
                } catch (Exception e) { filePathCallback = null; return false; }
            }
            @Override public void onPermissionRequest(PermissionRequest r) { runOnUiThread(() -> r.grant(r.getResources())); }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> download(url, null));
        registerDownloadReceiver();

        license = new LicenseGate(this);
        // Dola is only loaded once the online license check (or its sealed cache) passes.
        final Bundle saved = b;
        if (BuildConfig.DEV_EDITION) { if (saved == null) web.loadUrl(HOME); else web.restoreState(saved); }
        else license.ensure(() -> { if (saved == null || web.getUrl() == null) web.loadUrl(HOME); else web.restoreState(saved); });
    }

    boolean isLicensed() { return BuildConfig.DEV_EDITION || (license != null && license.isLicensed()); }

    /** Anonymous diagnostics for the developer agent (component/event/detail only; rate-limited, deduped per session). */
    final java.util.Set<String> reportedKeys = Collections.synchronizedSet(new java.util.HashSet<>());
    void report(String component, String event, String detail) {
        if (license == null || license.serverUrl().isEmpty() || !(BuildConfig.DEV_EDITION || prefs.getBoolean("diag", false))) return; // dev edition always reports; user edition never
        String key = component + "|" + event + "|" + (detail == null ? "" : detail.substring(0, Math.min(60, detail.length())));
        if (reportedKeys.size() > 40 || !reportedKeys.add(key)) return;
        new Thread(() -> {
            try {
                String ver; try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { ver = "?"; }
                JSONObject b = new JSONObject().put("action", "report").put("deviceId", license.deviceId()).put("app", "SesiMini " + ver + " · Android " + Build.VERSION.RELEASE)
                        .put("component", component).put("event", event).put("detail", detail == null ? "" : detail);
                b.put("versions", new JSONObject().put("skill_md", updates.version("skill_md")).put("auto_prompt", updates.version("auto_prompt")).put("enforcer", updates.version("enforcer")).put("collector", updates.version("collector")));
                LicenseGate.post(license.serverUrl(), b.toString());
            } catch (Exception ignored) {}
        }, "sesi-report").start();
    }
    boolean requireLicense() {
        if (isLicensed()) return true;
        Toast.makeText(this, "Aktivasi lisensi terlebih dahulu", Toast.LENGTH_SHORT).show();
        license.show(() -> { if (web.getUrl() == null || "about:blank".equals(web.getUrl())) web.loadUrl(HOME); });
        return false;
    }
    void onLicenseLost(String why) {
        web.stopLoading(); web.loadUrl("about:blank");
        Toast.makeText(this, "Lisensi tidak valid: " + why, Toast.LENGTH_LONG).show();
        license.show(() -> web.loadUrl(HOME));
    }
    @Override protected void onResume() {
        super.onResume();
        if (!BuildConfig.DEV_EDITION && license != null && !isLicensed() && web.getUrl() != null && !"about:blank".equals(web.getUrl())) onLicenseLost("masa tenggang habis, verifikasi online diperlukan");
    }

    void attachSkill() {
        if (!requireLicense()) return;
        if (activate.isSelected()) {
            setActive(false);
            web.evaluateJavascript(readAsset("attach-md.js") + "(\"\", false, \"\")", null);
            return;
        }
        Uri uri = Uri.parse(web.getUrl() == null ? "" : web.getUrl());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !(host.equals("dola.com") || host.endsWith(".dola.com"))) {
            Toast.makeText(this, "Buka composer Dola terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] md = updates.bytes("skill_md");
            if (md.length == 0) throw new java.io.IOException("empty");
            String base64 = Base64.encodeToString(md, Base64.NO_WRAP);
            activate.setEnabled(false);
            web.evaluateJavascript(readAsset("attach-md.js") + "(" + JSONObject.quote(base64) + ", true, " + JSONObject.quote(updates.filename("skill_md")) + ")", result -> {
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

    // ---- SESI auto prompt: page-start hooks wrap each chat send with the Seedance 2.5 / 30s directive ----
    void paintToggle(ImageView v, boolean on) { v.setColorFilter(on ? 0xFF7DCF8F : 0xFFD9B47A); v.setAlpha(on ? 0.95f : 0.75f); v.setSelected(on); }

    void setAutoPrompt(boolean enabled, boolean showNotice) {
        if (enabled && showNotice && !requireLicense()) return;
        if (!isSite(web.getUrl())) { if (showNotice) Toast.makeText(this, "Buka Dola terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        web.evaluateJavascript("(()=>{try{const a=window.__sesiAutoPrompt;if(!a)return 'missing';const s=" + (enabled ? "a.activate()" : "a.deactivate()") + ";return s.ready&&s.enabled===" + enabled + "?'ok':'notready'}catch(e){return 'err:'+e.message}})()", r -> {
            boolean ok = r != null && r.contains("ok");
            if (ok) {
                prefs.edit().putBoolean(AUTO_PROMPT_KEY, enabled).apply();
                web.evaluateJavascript("(()=>{try{const e=window.__whempySingleClip;if(e){e.cfg.enabled=" + enabled + ";e.cfg.aggressive=" + enabled + ";e.cfg.duration=30;e.cfg.forceModel25=" + enabled + ";}}catch(_){}})()", null);
            }
            paintToggle(autoPrompt, ok ? enabled : prefs.getBoolean(AUTO_PROMPT_KEY, false));
            if (!ok && r != null) report("auto_prompt", "not_ready", r);
            if (showNotice) Toast.makeText(this, ok ? (enabled ? "Auto prompt aktif · paksa 1 video × 30 detik ☕" : "Auto prompt nonaktif") : "Skrip auto prompt belum siap. Refresh Dola lalu coba lagi.", Toast.LENGTH_SHORT).show();
        });
    }

    void setImageNote(boolean enabled, boolean showNotice) {
        if (enabled && showNotice && !requireLicense()) return;
        if (!isSite(web.getUrl())) { if (showNotice) Toast.makeText(this, "Buka Dola terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        web.evaluateJavascript("(()=>{try{const a=window.__sesiAutoPrompt;if(!a)return 'missing';const s=a.setImageNote(" + enabled + ");return s.ready&&s.imageNoteEnabled===" + enabled + "?'ok':'notready'}catch(e){return 'err:'+e.message}})()", r -> {
            boolean ok = r != null && r.contains("ok");
            if (ok) prefs.edit().putBoolean(IMAGE_NOTE_KEY, enabled).apply();
            paintToggle(imageRef, ok ? enabled : prefs.getBoolean(IMAGE_NOTE_KEY, false));
            if (showNotice) Toast.makeText(this, ok ? (enabled ? "Image referensi aktif (catatan karakter animasi)" : "Image referensi nonaktif") : "Skrip auto prompt belum siap. Refresh Dola lalu coba lagi.", Toast.LENGTH_SHORT).show();
        });
    }

    // Document-start bundle: auto-prompt hooks first (they must own fetch/XHR before the site), then the read-only video collector.
    void installDocumentStartScript() {
        String js = readAsset("auto-prompt.js") + "\n" + readAsset("inject.js") + "\n" + readAsset("single-clip-enforcer.js");
        String boot = js;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, boot, new java.util.HashSet<>(java.util.Arrays.asList("https://*.dola.com", "https://dola.com")));
        } else {
            // Older WebView: inject as early as possible on page start (may miss the very first requests).
            web.setWebViewClient(new WebViewClient() { @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { v.evaluateJavascript(boot, null); } });
        }
    }


    String readAsset(String name) {
        if (updates != null) { String ota = updates.textByAsset(name); if (!ota.isEmpty()) return ota; }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = r.readLine()) != null) sb.append(l).append('\n'); return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /** SESI MAX MODE download path: Android DownloadManager, tracked in prefs so the dashboard can
     *  show Saving/Saved from the real DownloadManager status. Android 10+: public Downloads; 8-9:
     *  app-specific Downloads dir (no storage permission needed). */
    synchronized long enqueueDownload(String url, String contentDisposition, String mime, String userAgent) {
        if (url != null) url = url.trim().replaceFirst("(?i)^http://", "https://");
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) { Toast.makeText(this, "URL video tidak didukung: " + (url == null ? "kosong" : url.length() > 40 ? url.substring(0, 40) + "…" : url), Toast.LENGTH_LONG).show(); return -1; }
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
        if (!requireLicense()) return;
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
                else if (status == DownloadManager.STATUS_FAILED) { Toast.makeText(MainActivity.this, "Download gagal: " + reasonText(reason), Toast.LENGTH_LONG).show(); try { report("download", "failed", reasonText(reason) + " · host=" + Uri.parse(url).getHost()); } catch (Exception ignored) {} }
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

    /** SESI navigation policy: every http(s) page (Google/Apple/Facebook sign-in, redirects, CDN) stays in this
     *  WebView so the login round-trip returns to Dola; only WhatsApp/intent/mailto/tel leave the app. */
    boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            if (host.equals("wa.me") || host.endsWith("whatsapp.com")) { try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {} return true; }
            return false;
        }
        if ("intent".equals(scheme)) { try { startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)); } catch (Exception ignored) {} return true; }
        if ("mailto".equals(scheme) || "tel".equals(scheme)) { try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {} return true; }
        return true;
    }

    Map<String, String> mediaHeaders(String url) {
        Map<String, String> h = new HashMap<>();
        String c = CookieManager.getInstance().getCookie(url); if (c != null && !c.isEmpty()) h.put("Cookie", c);
        h.put("User-Agent", web.getSettings().getUserAgentString());
        String cur = web.getUrl(); h.put("Referer", isSite(cur) ? cur : HOME);
        return h;
    }

    final java.util.concurrent.ExecutorService thumbExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
    final android.util.LruCache<String, android.graphics.Bitmap> thumbCache = new android.util.LruCache<String, android.graphics.Bitmap>(60) {
        @Override protected int sizeOf(String k, android.graphics.Bitmap b) { return 1; }
    };
    /** Native thumbnail like SESI: MediaMetadataRetriever over HTTPS with the Dola cookies/UA; poster image if the API gave one. */
    void requestThumb(String url, String poster, ValueCallback<android.graphics.Bitmap> done) {
        android.graphics.Bitmap cached = thumbCache.get(url);
        if (cached != null) { done.onReceiveValue(cached); return; }
        thumbExecutor.execute(() -> {
            android.graphics.Bitmap bmp = null;
            if (poster != null && poster.startsWith("http")) {
                try { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(poster.replaceFirst("(?i)^http://", "https://")).openConnection(); c.setConnectTimeout(8000); c.setReadTimeout(8000); for (Map.Entry<String, String> e : mediaHeaders(poster).entrySet()) c.setRequestProperty(e.getKey(), e.getValue()); try (java.io.InputStream in = c.getInputStream()) { bmp = android.graphics.BitmapFactory.decodeStream(in); } } catch (Exception ignored) {}
            }
            if (bmp == null) {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(url, mediaHeaders(url));
                    long durUs = 0; try { durUs = Long.parseLong(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L; } catch (Exception ignored) {}
                    long at = durUs > 0 ? Math.min(600000L, durUs / 3) : 0;
                    bmp = mmr.getFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (bmp == null) bmp = mmr.getFrameAtTime();
                } catch (Exception ignored) {} finally { try { mmr.release(); } catch (Exception ignored) {} }
            }
            if (bmp != null) {
                int w = Math.min(320, bmp.getWidth()), h = Math.max(1, Math.round(w * (float) bmp.getHeight() / Math.max(1, bmp.getWidth())));
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true);
                if (small != bmp) bmp.recycle();
                bmp = small; thumbCache.put(url, bmp);
            }
            final android.graphics.Bitmap out = bmp;
            runOnUiThread(() -> done.onReceiveValue(out));
        });
    }

    android.app.Dialog playerDialog;
    /** Pop-up player (SESI): plays the CDN URL with the site's cookies, Download button at the bottom. */
    void showPlayer(String url, String title) {
        if (playerDialog != null) { try { playerDialog.dismiss(); } catch (Exception ignored) {} }
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar); playerDialog = d;
        android.widget.FrameLayout root = new android.widget.FrameLayout(this); root.setBackgroundColor(0xEE000000); root.setFitsSystemWindows(true);
        android.widget.VideoView video = new android.widget.VideoView(this);
        root.addView(video, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER));
        ProgressBar spinner = new ProgressBar(this);
        int dp40 = (int) (40 * getResources().getDisplayMetrics().density);
        root.addView(spinner, new android.widget.FrameLayout.LayoutParams(dp40, dp40, android.view.Gravity.CENTER));
        android.widget.LinearLayout top = DashboardSheet.row(this); int p = DashboardSheet.dp(root, 12); top.setPadding(p + 4, p, p, p);
        android.widget.TextView t = DashboardSheet.tv(this, title == null || title.isEmpty() ? "Pratinjau" : title, 15, 0xFFFFFFFF, true); t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(t, DashboardSheet.weight());
        android.widget.Button close = DashboardSheet.btn(this, "✕", false); close.setOnClickListener(v -> d.dismiss()); top.addView(close);
        root.addView(top, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP));
        android.widget.LinearLayout bottom = DashboardSheet.row(this); bottom.setGravity(android.view.Gravity.CENTER); bottom.setPadding(p, p, p, p + 8);
        android.widget.Button dl = DashboardSheet.btn(this, "⬇  Download video ini", true);
        dl.setOnClickListener(v -> { download(url, title); d.dismiss(); });
        bottom.addView(dl);
        root.addView(bottom, new android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
        android.widget.MediaController mc = new android.widget.MediaController(this); mc.setAnchorView(video); video.setMediaController(mc);
        video.setOnPreparedListener(mp -> { spinner.setVisibility(View.GONE); mp.setLooping(true); video.start(); });
        video.setOnErrorListener((mp, what, extra) -> { spinner.setVisibility(View.GONE); Toast.makeText(this, "Video tidak bisa diputar (" + what + "/" + extra + ")", Toast.LENGTH_LONG).show(); return true; });
        video.setVideoURI(Uri.parse(url), mediaHeaders(url));
        root.setOnClickListener(v -> d.dismiss());
        d.setOnDismissListener(x -> { try { video.stopPlayback(); } catch (Exception ignored) {} if (playerDialog == d) playerDialog = null; });
        d.setContentView(root); d.show();
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
                for (int i = 0; i < arr.length(); i++) addVideo(arr.getJSONObject(i));
            } catch (Exception ignored) {}
            if (done != null) done.run();
        });
    }

    void addVideo(JSONObject v) throws Exception {
        String key = v.optString("vid", ""); if (key.isEmpty()) key = v.getString("url");
        if (videos.containsKey(key)) return;
        v.put("seq", videos.size() + 1); v.put("foundAt", System.currentTimeMillis());
        videos.put(key, v);
    }

    class Bridge {
        @JavascriptInterface public void onAttached() { runOnUiThread(() -> { setActive(false); Toast.makeText(MainActivity.this, "File MD terlampir ke composer.", Toast.LENGTH_SHORT).show(); }); }
        @JavascriptInterface public void onVideo(String json) { try { addVideo(new JSONObject(json)); } catch (Exception ignored) {} }
        @JavascriptInterface public void onReport(String json) { try { JSONObject r = new JSONObject(json); report(r.optString("component", "page"), r.optString("event"), r.optString("detail")); } catch (Exception ignored) {} }
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

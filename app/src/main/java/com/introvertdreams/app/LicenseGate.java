package com.introvertdreams.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.introvertdreams.app.DashboardSheet.*;

/** Online license gate backed by the Apps Script server in /server. Cached activation is HMAC-sealed and expires at graceUntil. */
public class LicenseGate {
    static final String ALPHA = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final long RECHECK_MS = 6 * 60 * 60 * 1000L;
    static final SecureRandom RND = new SecureRandom();

    final MainActivity a; final SharedPreferences p;
    AlertDialog dialog;

    LicenseGate(MainActivity a) { this.a = a; this.p = a.prefs; }

    String deviceId() {
        String id = p.getString("sesiDeviceId", "");
        if (id.matches("[2-9A-HJ-NP-Z]{6}")) return id;
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < 6; i++) sb.append(ALPHA.charAt(RND.nextInt(ALPHA.length())));
        id = sb.toString(); p.edit().putString("sesiDeviceId", id).apply(); return id;
    }
    String serverUrl() { String u = p.getString("lic.url", ""); if (!u.isEmpty()) return u; return a.getString(R.string.license_url); }
    String savedKey() { return p.getString("lic.key", ""); }

    // Local seal: prevents editing the cached activation in prefs on a rooted device without also forging the HMAC.
    String seal(String key, long until) {
        try {
            String base = Settings.Secure.getString(a.getContentResolver(), Settings.Secure.ANDROID_ID) + "|" + a.getPackageName() + "|" + deviceId();
            byte[] k = MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return android.util.Base64.encodeToString(mac.doFinal((key + "|" + until).getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    /** Licensed right now according to the sealed cache (no network). */
    boolean isLicensed() {
        String key = savedKey(); long until = p.getLong("lic.until", 0);
        return !key.isEmpty() && until > System.currentTimeMillis() && seal(key, until).equals(p.getString("lic.seal", ""));
    }
    String licenseName() { return p.getString("lic.name", ""); }
    long expiresAt() { return p.getLong("lic.expiresAt", 0); }

    void store(String key, JSONObject r) {
        long until = r.optLong("graceUntil", 0);
        p.edit().putString("lic.key", key).putLong("lic.until", until).putString("lic.seal", seal(key, until))
                .putString("lic.name", r.optString("name", "")).putLong("lic.expiresAt", r.optLong("expiresAt", 0))
                .putLong("lic.checkedAt", System.currentTimeMillis()).apply();
    }
    void clear() { p.edit().remove("lic.until").remove("lic.seal").remove("lic.checkedAt").apply(); }

    interface Done { void run(boolean ok, String message); }

    /** Called at startup: proceeds immediately when cached; otherwise shows the gate. Re-verifies online in the background. */
    void ensure(Runnable onLicensed) {
        if (isLicensed()) {
            onLicensed.run();
            if (System.currentTimeMillis() - p.getLong("lic.checkedAt", 0) > RECHECK_MS) request("verify", savedKey(), (ok, msg) -> {
                if (!ok && msg != null && msg.startsWith("!")) { clear(); a.onLicenseLost(msg.substring(1)); }
            });
            return;
        }
        show(onLicensed);
        if (!savedKey().isEmpty() && !serverUrl().isEmpty()) request("verify", savedKey(), (ok, msg) -> { if (ok && dialog != null) { dialog.dismiss(); onLicensed.run(); } });
    }

    static String reasonText(String r) {
        switch (r) {
            case "not_found": return "Key tidak ditemukan.";
            case "revoked": return "Key sudah dicabut.";
            case "expired": return "Masa berlaku key habis.";
            case "device_limit": return "Key sudah dipakai di perangkat lain (batas tercapai).";
            case "device_mismatch": return "Key tidak terdaftar untuk perangkat ini.";
            case "server_setup": return "Server belum di-setup (jalankan setupSheet).";
            default: return "Permintaan ditolak (" + r + ").";
        }
    }

    /** Definitive server answers come back as "!message" so callers can distinguish them from network failures. */
    void request(String action, String key, Done done) {
        String url = serverUrl();
        if (url.isEmpty()) { done.run(false, "URL server lisensi belum diatur."); return; }
        String nonce = Long.toString(RND.nextLong() & Long.MAX_VALUE, 36);
        new Thread(() -> {
            boolean ok = false; String msg;
            try {
                JSONObject body = new JSONObject().put("action", action).put("key", key).put("deviceId", deviceId()).put("nonce", nonce)
                        .put("note", "SesiMini " + a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName + " · Android " + android.os.Build.VERSION.RELEASE);
                String text = post(url, body.toString());
                JSONObject r = new JSONObject(text);
                if (!nonce.equals(r.optString("nonce"))) throw new IllegalStateException("nonce");
                if (r.optBoolean("ok")) {
                    if (!"deactivate".equals(action)) { if (!deviceId().equals(r.optString("deviceId"))) throw new IllegalStateException("device"); store(key, r); }
                    ok = true; msg = "Aktivasi berhasil… selamat menikmati ☕";
                } else msg = "!" + reasonText(r.optString("reason", "unknown"));
            } catch (Exception e) { msg = "Tidak bisa menghubungi server lisensi. Periksa internet lalu coba lagi."; }
            final boolean fok = ok; final String fmsg = msg;
            a.runOnUiThread(() -> done.run(fok, fmsg));
        }, "sesi-license").start();
    }

    // Apps Script answers POST with a 302 to script.googleusercontent.com; follow it manually with GET.
    static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        for (int hop = 0; hop < 3 && (code == 301 || code == 302 || code == 303 || code == 307); hop++) {
            String loc = c.getHeaderField("Location"); c.disconnect();
            if (loc == null || !loc.startsWith("https://")) throw new IllegalStateException("redirect");
            c = (HttpURLConnection) new URL(loc).openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(20000);
            code = c.getResponseCode();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = r.readLine()) != null) sb.append(l); return sb.toString();
        }
    }

    void show(Runnable onLicensed) {
        if (dialog != null && dialog.isShowing()) return;
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        int pad = dp(root, 22); root.setPadding(pad, pad, pad, pad);
        TextView title = tv(a, "Aktivasi SESI MINI", 20, TEXT, true); root.addView(title);
        root.addView(tv(a, "Masukkan license key untuk membuka aplikasi. Key diverifikasi online dan terikat ke Device ID perangkat ini.", 12, TEXT2, false));

        LinearLayout idRow = row(a); idRow.setPadding(0, dp(root, 16), 0, 0);
        LinearLayout idCol = new LinearLayout(a); idCol.setOrientation(LinearLayout.VERTICAL);
        idCol.addView(tv(a, "DEVICE ID", 10, TEXT2, false)); TextView idText = tv(a, deviceId(), 22, GREEN, true); idText.setLetterSpacing(0.18f); idCol.addView(idText);
        idRow.addView(idCol, weight());
        Button copy = btn(a, "Salin", false);
        copy.setOnClickListener(v -> { ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Device ID", deviceId())); Toast.makeText(a, "Device ID disalin", Toast.LENGTH_SHORT).show(); });
        idRow.addView(copy); root.addView(idRow);

        EditText input = new EditText(a); input.setHint("SESI-XXXX-XXXX-XXXX"); input.setText(savedKey()); input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTextColor(TEXT); input.setHintTextColor(TEXT2); input.setTextSize(16); input.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); ilp.topMargin = dp(root, 16); root.addView(input, ilp);

        TextView status = tv(a, serverUrl().isEmpty() ? "URL server lisensi belum diatur — tekan lama judul untuk mengisi." : "", 12, ORANGE, false); status.setPadding(0, dp(root, 8), 0, 0); root.addView(status);
        Button go = btn(a, "Aktivasi", true); root.addView(go, mt(root, 12));
        LinearLayout foot = row(a); foot.setGravity(Gravity.CENTER); foot.setPadding(0, dp(root, 14), 0, 0);
        TextView wa = tv(a, "Belum punya key? Hubungi Whempy & Dhon (WhatsApp)", 12, TEXT2, false); wa.setGravity(Gravity.CENTER);
        wa.setOnClickListener(v -> { try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/628889841098?text=" + android.net.Uri.encode("Halo, saya mau key SESI MINI. Device ID: " + deviceId())))); } catch (Exception ignored) {} });
        foot.addView(wa); root.addView(foot);

        // Developer escape hatch: set the Apps Script URL without rebuilding.
        title.setOnLongClickListener(v -> {
            EditText u = new EditText(a); u.setHint("https://script.google.com/macros/s/…/exec"); u.setText(p.getString("lic.url", "")); u.setSingleLine();
            new AlertDialog.Builder(a).setTitle("Server lisensi").setView(u).setPositiveButton("Simpan", (d, w) -> { p.edit().putString("lic.url", u.getText().toString().trim()).apply(); status.setText(serverUrl().isEmpty() ? "URL server lisensi belum diatur." : "Server diatur."); }).setNegativeButton("Batal", null).show();
            return true;
        });

        go.setOnClickListener(v -> {
            String key = input.getText().toString().trim().toUpperCase().replaceAll("[^A-Z0-9]", "").replaceFirst("^SESI", "");
            if (key.length() != 12) { status.setText("Format key: SESI-XXXX-XXXX-XXXX"); return; }
            key = "SESI-" + key.substring(0, 4) + "-" + key.substring(4, 8) + "-" + key.substring(8);
            go.setEnabled(false); status.setTextColor(TEXT2); status.setText("Memverifikasi…");
            request("activate", key, (ok, msg) -> {
                go.setEnabled(true);
                status.setTextColor(ok ? GREEN : ORANGE); status.setText(msg.startsWith("!") ? msg.substring(1) : msg);
                if (ok) { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show(); dialog.dismiss(); onLicensed.run(); }
            });
        });

        dialog = new AlertDialog.Builder(a).setView(root).setCancelable(false).create();
        dialog.setOnKeyListener((d, keyCode, ev) -> { if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { a.finish(); return true; } return false; });
        dialog.show();
    }
}

package com.introvertdreams.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Admin calls to the Apps Script server; same POST→302→GET handling as the client app. */
class Api {
    interface Done { void run(JSONObject r, String error); }
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final Handler UI = new Handler(Looper.getMainLooper());
    static final SecureRandom RND = new SecureRandom();

    static void call(String url, String token, String action, JSONObject params, Done done) {
        String nonce = Long.toString(RND.nextLong() & Long.MAX_VALUE, 36);
        IO.execute(() -> {
            JSONObject r = null; String err = null;
            try {
                JSONObject body = params == null ? new JSONObject() : params;
                body.put("action", action).put("adminToken", token).put("nonce", nonce);
                r = new JSONObject(post(url, body.toString()));
                if (!nonce.equals(r.optString("nonce"))) throw new IllegalStateException("nonce");
                if (!r.optBoolean("ok")) { err = "agent_error".equals(r.optString("reason")) ? "Agent: " + r.optString("error") : reason(r.optString("reason")); r = null; }
            } catch (Exception e) {
                if (err == null) err = e.getMessage() != null && e.getMessage().contains("DOCTYPE") ? "Server mengembalikan halaman error (kode Apps Script rusak/tidak lengkap). Tempel ulang Code.gs, Run setup(), deploy versi baru." : "Tidak bisa menghubungi server (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
            }
            final JSONObject fr = r; final String fe = err;
            UI.post(() -> done.run(fr, fe));
        });
    }

    static String reason(String r) {
        switch (r) {
            case "unauthorized": return "Token admin salah.";
            case "admin_setup": return "Server belum setup admin: jalankan fungsi setup() di Apps Script.";
            case "code_incomplete": return "Code.gs di server tidak lengkap. Tempel ulang seluruh file lalu Run setup() dan deploy versi baru.";
            case "ai_not_configured": return "Provider AI belum diatur (ai_not_configured). Buka ⚙ Provider.";
            case "not_found": return "Key tidak ditemukan.";
            case "bad_request": return "Permintaan tidak valid.";
            default: return "Ditolak: " + r;
        }
    }

    static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(320000);
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        for (int hop = 0; hop < 3 && (code == 301 || code == 302 || code == 303 || code == 307); hop++) {
            String loc = c.getHeaderField("Location"); c.disconnect();
            if (loc == null || !loc.startsWith("https://")) throw new IllegalStateException("redirect");
            c = (HttpURLConnection) new URL(loc).openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(320000);
            code = c.getResponseCode();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String l; while ((l = r.readLine()) != null) sb.append(l); return sb.toString();
        }
    }
}

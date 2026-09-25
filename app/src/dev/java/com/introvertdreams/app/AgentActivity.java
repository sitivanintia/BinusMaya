package com.introvertdreams.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** AI Agent (OpenAI-compatible provider) + diagnostics: chat with tools, drafts with diff, publish to the Drive update folder. */
public class AgentActivity extends AppCompatActivity {
    static final int BG = 0xFF1A1A1F, CARD = 0xFF24242B, TEXT = 0xFFF4F4F5, TEXT2 = 0xFFA8A8AD, GREEN = 0xFF7DCF8F, ORANGE = 0xFFD9B47A, RED = 0xFFE07A7A, SILVER = 0xFFE6E6E8;
    SharedPreferences prefs;
    LinearLayout chat, drafts, reports; ScrollView chatScroll; EditText input; Button send; TextView modelLabel, status;
    JSONArray messages = new JSONArray(); int tab = 0; LinearLayout[] panes = new LinearLayout[3]; TextView[] tabs = new TextView[3];
    // Real-time: pending context snapshot for the next turn, live event queue, live switch.
    LocalAgent agent;
    static volatile AgentActivity live; String pendingContext; final JSONArray liveQueue = new JSONArray(); boolean liveOn, busy; TextView liveBtn; final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    String url() { String u = prefs.getString("url", ""); return u.isEmpty() ? getString(R.string.license_url) : u; }
    String token() { return prefs.getString("token", ""); }
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("admin", MODE_PRIVATE);
        try { messages = new JSONArray(prefs.getString("agentMessages", "[]")); } catch (Exception e) { messages = new JSONArray(); }
        build();
        liveOn = prefs.getBoolean("agentLive", true); paintLive();
        agent = new LocalAgent(MainActivity.instance, prefs);
        loadConfig(); renderChat(); loadDrafts(); loadReports(); handleIntent(getIntent());
    }

    void build() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setFitsSystemWindows(true);
        LinearLayout hdr = row(); hdr.setPadding(dp(12), dp(12), dp(12), dp(6));
        Button back = btn("‹", false); back.setOnClickListener(v -> finish()); hdr.addView(back);
        LinearLayout tcol = new LinearLayout(this); tcol.setOrientation(LinearLayout.VERTICAL); tcol.setPadding(dp(10), 0, 0, 0);
        TextView ttl = tv("AI Agent", 20, TEXT, true); ttl.setOnLongClickListener(v -> { prefs.edit().remove("token").apply(); askToken(); return true; }); tcol.addView(ttl); modelLabel = tv("model belum dipilih", 11, TEXT2, false); tcol.addView(modelLabel);
        hdr.addView(tcol, weight());
        liveBtn = tv("● Live", 12, GREEN, true); liveBtn.setPadding(dp(12), dp(8), dp(12), dp(8)); liveBtn.setOnClickListener(v -> { liveOn = !liveOn; prefs.edit().putBoolean("agentLive", liveOn).apply(); paintLive(); toast(liveOn ? "Live aktif: kejadian dari aplikasi dianalisis otomatis" : "Live nonaktif"); });
        LinearLayout.LayoutParams ll2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); ll2.rightMargin = dp(6); hdr.addView(liveBtn, ll2);
        Button cfg = btn("⚙", false); cfg.setOnClickListener(v -> showConfig()); hdr.addView(cfg);
        root.addView(hdr);
        LinearLayout tabRow = row(); tabRow.setPadding(dp(12), dp(4), dp(12), dp(6));
        String[] names = { "Chat", "Draft", "Diagnostik" };
        for (int i = 0; i < 3; i++) { final int idx = i; TextView t = tv(names[i], 12, TEXT2, true); t.setPadding(dp(14), dp(7), dp(14), dp(7)); t.setOnClickListener(v -> showTab(idx)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.rightMargin = dp(6); tabRow.addView(t, lp); tabs[i] = t; }
        root.addView(tabRow);
        status = tv("", 11, TEXT2, false); status.setPadding(dp(16), 0, dp(16), dp(4)); root.addView(status);

        // chat pane
        LinearLayout chatPane = new LinearLayout(this); chatPane.setOrientation(LinearLayout.VERTICAL);
        chatScroll = new ScrollView(this); chat = new LinearLayout(this); chat.setOrientation(LinearLayout.VERTICAL); chat.setPadding(dp(12), dp(4), dp(12), dp(12)); chatScroll.addView(chat);
        chatPane.addView(chatScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout quick = row(); quick.setPadding(dp(12), 0, dp(12), dp(4));
        for (String[] q : new String[][]{{"Cek respons Dola", "Lihat get_state dan get_netlog (25 terakhir). Ringkas endpoint API Dola yang aktif, status, dan anomali (error, lambat, struktur tak terduga). Tandai request yang berkaitan video/generate."}, {"Kenapa 15 detik?", "Video hasil 15 detik padahal diminta 30. Periksa netlog: cari request generate/completion video, buka get_request untuk melihat body lengkap setelah rewrite (duration/count/model), lalu respons servernya. Tentukan apakah penyebabnya di skrip kita (request belum 30) atau server Dola memangkas (request 30 tapi hasil 15). Jelaskan buktinya dan yang harus dilakukan; buat draft enforcer/MD jika perlu."}, {"Deteksi video gagal", "Pendeteksi video tidak menemukan video. Periksa get_state (statistik collector), netlog untuk respons yang mengandung fallback_api / video, dan struktur payload aktual. Baca collector (inject.js), temukan ketidakcocokan, buat draft perbaikan minimal."}, {"Get API Dola", "Dari netlog, daftarkan semua endpoint API Dola unik (method + path), fungsinya masing-masing berdasarkan body/respons, dan mana yang berubah dari yang diasumsikan skrip kita."}}) {
            TextView c = tv(q[0], 11, TEXT, false); c.setPadding(dp(10), dp(5), dp(10), dp(5)); c.setBackground(pill(CARD)); c.setOnClickListener(v -> { input.setText(q[1]); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.rightMargin = dp(6); quick.addView(c, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(quick); chatPane.addView(hs);
        LinearLayout inRow = row(); inRow.setPadding(dp(12), dp(4), dp(12), dp(12));
        input = new EditText(this); input.setHint("Minta agent menyesuaikan MD / skrip… (boleh tempel payload Dola)"); input.setTextColor(TEXT); input.setHintTextColor(TEXT2); input.setTextSize(14); input.setMaxLines(6);
        input.setBackground(pill(CARD)); input.setPadding(dp(14), dp(10), dp(14), dp(10)); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inRow.addView(input, weight());
        send = btn("Kirim", true); send.setOnClickListener(v -> sendMessage()); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); sl.leftMargin = dp(8); inRow.addView(send, sl);
        chatPane.addView(inRow);
        panes[0] = chatPane;

        // drafts pane
        LinearLayout draftPane = new LinearLayout(this); draftPane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dhdr = row(); dhdr.setPadding(dp(12), 0, dp(12), 0); dhdr.addView(tv("Draft menunggu persetujuan", 12, TEXT2, false), weight()); Button dr = btn("↻", false); dr.setOnClickListener(v -> loadDrafts()); dhdr.addView(dr); draftPane.addView(dhdr);
        ScrollView ds = new ScrollView(this); drafts = new LinearLayout(this); drafts.setOrientation(LinearLayout.VERTICAL); drafts.setPadding(dp(12), dp(6), dp(12), dp(12)); ds.addView(drafts); draftPane.addView(ds, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        panes[1] = draftPane;

        // reports pane
        LinearLayout repPane = new LinearLayout(this); repPane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rhdr = row(); rhdr.setPadding(dp(12), 0, dp(12), 0); rhdr.addView(tv("Laporan dari aplikasi pengguna (100 terbaru)", 12, TEXT2, false), weight()); Button rr = btn("↻", false); rr.setOnClickListener(v -> loadReports()); rhdr.addView(rr); repPane.addView(rhdr);
        ScrollView rs = new ScrollView(this); reports = new LinearLayout(this); reports.setOrientation(LinearLayout.VERTICAL); reports.setPadding(dp(12), dp(6), dp(12), dp(12)); rs.addView(reports); repPane.addView(rs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        panes[2] = repPane;

        for (LinearLayout p : panes) { root.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1)); }
        setContentView(root); showTab(0);
    }
    void showTab(int i) { tab = i; for (int k = 0; k < 3; k++) { panes[k].setVisibility(k == i ? View.VISIBLE : View.GONE); tabs[k].setBackground(pill(k == i ? SILVER : CARD)); tabs[k].setTextColor(k == i ? BG : TEXT2); } }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleIntent(i); }
    @Override protected void onResume() { super.onResume(); live = this; }
    @Override protected void onPause() { super.onPause(); if (live == this) live = null; }
    void handleIntent(Intent i) {
        if (i == null) return;
        String ctx = i.getStringExtra("context"), prompt = i.getStringExtra("prompt"); i.removeExtra("context"); i.removeExtra("prompt");
        if (ctx != null) pendingContext = ctx;
        if (prompt != null && !prompt.isEmpty()) { input.setText(prompt); sendMessage(); }
    }
    /** Live mode: events from the running app arrive here and are analysed automatically (debounced, one turn at a time). */
    void onLiveEvent(JSONObject e) {
        if (!liveOn || !agent.configured()) return;
        liveQueue.put(e); status.setTextColor(TEXT2); status.setText("Live: " + liveQueue.length() + " kejadian baru — menganalisis…");
        h.removeCallbacks(flushLive); h.postDelayed(flushLive, 6000);
    }
    final Runnable flushLive = new Runnable() { public void run() {
        if (busy) { h.postDelayed(this, 4000); return; }
        if (liveQueue.length() == 0) return;
        StringBuilder sb = new StringBuilder("Kejadian real-time baru dari aplikasi:\n");
        for (int i = 0; i < liveQueue.length(); i++) { JSONObject e = liveQueue.optJSONObject(i); if (e != null) sb.append("- ").append(e.optString("component")).append(" → ").append(e.optString("event")).append(": ").append(e.optString("detail")).append("\n"); }
        sb.append("Jelaskan masalahnya, penyebab, dan apa yang harus saya lakukan. Buat draft bila perlu perubahan MD/skrip.");
        while (liveQueue.length() > 0) liveQueue.remove(0);
        MainActivity host = MainActivity.instance;
        if (host != null) host.snapshot(snap -> { pendingContext = snap.toString(); input.setText(sb.toString()); sendMessage(); });
        else { input.setText(sb.toString()); sendMessage(); }
    } };
    void paintLive() { if (liveBtn == null) return; liveBtn.setText(liveOn ? "● Live" : "○ Live"); liveBtn.setTextColor(liveOn ? GREEN : TEXT2); liveBtn.setBackground(pill(liveOn ? 0xFF2E3A33 : CARD)); }

    /** Developer edition talks to the same Apps Script server with the admin token (from setup() in Apps Script). */
    void askToken() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(tv("Hanya untuk Publish ke folder Drive. Token admin dari fungsi setup() di Apps Script (Execution log).", 12, TEXT2, false));
        EditText tok = new EditText(this); tok.setHint("ADM-XXXXXX-XXXXXX-XXXXXX"); tok.setText(token()); tok.setSingleLine(); tok.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(tok);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Masuk Developer").setView(box).setCancelable(true).setPositiveButton("Masuk", null).setNegativeButton("Batal", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = tok.getText().toString().trim(); if (t.isEmpty()) { tok.setError("Wajib diisi"); return; }
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Api.call(url(), t, "admin_ping", null, (r, err) -> { d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); if (err != null) { tok.setError(err); return; } prefs.edit().putString("token", t).apply(); d.dismiss(); toast("Token tersimpan — tekan Publish lagi"); });
        }));
        d.show();
    }
    @Override public boolean onCreateOptionsMenu(android.view.Menu m) { return false; }

    // ---- provider config + model picker ----
    void loadConfig() {
        modelLabel.setText(!agent.configured() ? "provider belum diatur — ketuk ⚙" : (agent.model().isEmpty() ? "model belum dipilih" : agent.model()) + " · " + agent.baseUrl().replaceAll("^https?://", "").replaceAll("/.*$", "") + " · lokal");
    }
    void showConfig() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(tv("Agent lokal: HP ini memanggil provider langsung (tanpa Apps Script). Contoh base URL: https://api.openai.com/v1 · https://openrouter.ai/api/v1 · https://api.groq.com/openai/v1", 11, TEXT2, false));
        EditText base = new EditText(this); base.setHint("Base URL"); base.setText(agent.baseUrl()); base.setSingleLine(); base.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); box.addView(base);
        EditText key = new EditText(this); key.setHint(agent.apiKey().isEmpty() ? "API key" : "API key tersimpan — kosongkan untuk tetap"); key.setSingleLine(); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(key);
        TextView model = tv("Model: " + (agent.model().isEmpty() ? "— (ambil daftar dulu)" : agent.model()), 13, TEXT, true); model.setPadding(0, dp(12), 0, dp(6)); box.addView(model);
        Button pick = btn("Ambil daftar model dari provider", false); box.addView(pick);
        final String[] chosen = { agent.model() };
        Runnable saveCreds = () -> { SharedPreferences.Editor e = prefs.edit().putString("ai.baseUrl", base.getText().toString().trim()); if (!key.getText().toString().trim().isEmpty()) e.putString("ai.apiKey", key.getText().toString().trim()); e.apply(); };
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Provider AI (lokal)").setView(box).setPositiveButton("Simpan", null).setNegativeButton("Batal", null).create();
        pick.setOnClickListener(v -> {
            saveCreds.run(); if (!agent.configured()) { toast("Isi Base URL dan API key dulu"); return; }
            pick.setEnabled(false); final long t0 = System.currentTimeMillis(); final Runnable[] tick = new Runnable[1];
            tick[0] = () -> { if (!pick.isEnabled()) { pick.setText("⏳ Menghubungi provider… " + (System.currentTimeMillis() - t0) / 1000 + " dtk"); pick.postDelayed(tick[0], 500); } }; tick[0].run();
            agent.listModels(arr -> {
                pick.setEnabled(true); pick.setText("Ambil daftar model dari provider");
                if (arr.length() == 0) { toast("Provider tidak mengembalikan daftar model"); return; }
                List<String> ids = new ArrayList<>(); for (int i = 0; i < arr.length(); i++) ids.add(arr.optString(i));
                EditText filter = new EditText(this); filter.setHint("Cari model…"); filter.setSingleLine();
                LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setPadding(dp(16), 0, dp(16), 0); wrap.addView(filter);
                android.widget.ListView lv = new android.widget.ListView(this); android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(ids)); lv.setAdapter(ad); wrap.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
                filter.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int b2, int c) {} public void afterTextChanged(android.text.Editable s) {} public void onTextChanged(CharSequence s, int a, int b2, int c) { ad.getFilter().filter(s); } });
                AlertDialog md = new AlertDialog.Builder(this).setTitle(ids.size() + " model tersedia (" + (System.currentTimeMillis() - t0) / 1000 + " dtk)").setView(wrap).setNegativeButton("Batal", null).create();
                lv.setOnItemClickListener((av, view, pos, id) -> { chosen[0] = ad.getItem(pos); model.setText("Model: " + chosen[0]); md.dismiss(); });
                md.show();
            }, err -> { pick.setEnabled(true); pick.setText("Ambil daftar model dari provider"); toast(err); });
        });
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { saveCreds.run(); prefs.edit().putString("ai.model", chosen[0]).apply(); loadConfig(); d.dismiss(); toast("Provider tersimpan (lokal di HP ini)"); }));
        d.show();
    }

    // ---- chat ----
    void sendMessage() {
        String text = input.getText().toString().trim(); if (text.isEmpty()) return;
        if (!agent.configured() || agent.model().isEmpty()) { toast("Atur provider & model dulu (⚙)"); showConfig(); return; }
        try { messages.put(new JSONObject().put("role", "user").put("content", text)); } catch (Exception ignored) {}
        input.setText(""); renderChat(); send.setEnabled(false); busy = true; status.setTextColor(TEXT2);
        final long t0 = System.currentTimeMillis(); final String[] stage = { "⏳ Menghubungi provider…" }; final Runnable[] tick = new Runnable[1];
        tick[0] = () -> { if (busy) { status.setText(stage[0] + " " + (System.currentTimeMillis() - t0) / 1000 + " dtk"); status.postDelayed(tick[0], 500); } }; tick[0].run();
        // Bounded transcript: tool payloads are large, only the last 14 messages travel with each turn.
        JSONArray window = new JSONArray(); int start = Math.max(0, messages.length() - 14); for (int i = start; i < messages.length(); i++) window.put(messages.opt(i));
        String ctx = pendingContext; pendingContext = null;
        agent.chat(window, ctx, st -> runOnUiThread(() -> stage[0] = st), (msgs, events, err) -> {
            send.setEnabled(true); busy = false;
            if (err != null) { status.setTextColor(ORANGE); status.setText(err); try { messages.put(new JSONObject().put("role", "assistant").put("content", "⚠ " + err)); } catch (Exception ignored) {} renderChat(); return; }
            status.setTextColor(TEXT2); status.setText("Selesai dalam " + (System.currentTimeMillis() - t0) / 1000 + " dtk");
            JSONArray merged = new JSONArray(); for (int i = 0; i < start; i++) merged.put(messages.opt(i)); for (int i = 0; i < msgs.length(); i++) merged.put(msgs.opt(i)); messages = merged;
            boolean draft = false; for (int i = 0; i < events.length(); i++) if (events.optJSONObject(i) != null && events.optJSONObject(i).optBoolean("draft")) draft = true;
            prefs.edit().putString("agentMessages", messages.toString()).apply(); renderChat();
            if (draft) { toast("Draft baru dibuat — periksa tab Draft."); loadDrafts(); }
        });
    }
    void renderChat() {
        chat.removeAllViews();
        if (messages.length() == 0) { TextView e = tv("Mode Live: saat aplikasi mendeteksi masalah (deteksi video gagal, download gagal, skrip error), agent otomatis menerima kejadian + snapshot keadaan dan menjawab: masalah, penyebab, yang harus dilakukan. Kamu juga bisa tekan “Diagnosa sekarang” di dashboard. Draft perbaikan hanya aktif setelah kamu Terapkan.", 13, TEXT2, false); e.setPadding(dp(8), dp(20), dp(8), 0); chat.addView(e); }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i); if (m == null) continue; String role = m.optString("role");
            if (role.equals("tool")) continue;
            if (role.equals("assistant") && m.has("tool_calls")) { JSONArray tc = m.optJSONArray("tool_calls"); for (int k = 0; tc != null && k < tc.length(); k++) { JSONObject f = tc.optJSONObject(k).optJSONObject("function"); String name = f == null ? "?" : f.optString("name"); String args = f == null ? "" : f.optString("arguments"); String brief = name; try { JSONObject a = new JSONObject(args); if (a.has("id")) brief += " · " + a.optString("id"); if (a.has("note")) brief += " — " + a.optString("note"); if (a.has("component")) brief += " · " + a.optString("component"); } catch (Exception ignored) {} TextView t = tv("⚙ " + brief, 11, TEXT2, false); t.setPadding(dp(12), dp(4), dp(12), dp(4)); chat.addView(t); } if (m.optString("content").isEmpty()) continue; }
            String content = m.optString("content"); if (content.isEmpty() || content.equals("null")) continue;
            boolean user = role.equals("user");
            TextView t = tv(content, 13, TEXT, false); t.setPadding(dp(12), dp(10), dp(12), dp(10)); t.setTextIsSelectable(true); t.setLineSpacing(0, 1.15f);
            GradientDrawable bg = new GradientDrawable(); bg.setColor(user ? 0xFF2E3A33 : CARD); bg.setCornerRadius(dp(14)); t.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.gravity = user ? Gravity.END : Gravity.START; lp.topMargin = dp(6); lp.setMargins(user ? dp(40) : 0, dp(6), user ? 0 : dp(40), 0);
            chat.addView(t, lp);
        }
        if (messages.length() > 0) { TextView clr = tv("Bersihkan percakapan", 11, TEXT2, false); clr.setGravity(Gravity.CENTER); clr.setPadding(0, dp(14), 0, 0); clr.setOnClickListener(v -> { messages = new JSONArray(); prefs.edit().remove("agentMessages").apply(); renderChat(); }); chat.addView(clr); }
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---- drafts (local files/drafts; publish uploads to Drive via the server when a token is set) ----
    void loadDrafts() {
        drafts.removeAllViews();
        java.io.File dir = new java.io.File(getFilesDir(), "drafts"); java.io.File[] files = dir.listFiles();
        if (files == null || files.length == 0) { TextView e = tv("Belum ada draft. Minta agent membuat perbaikan di tab Chat.", 13, TEXT2, false); e.setPadding(dp(8), dp(16), dp(8), 0); drafts.addView(e); return; }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (java.io.File f : files) {
            final String name = f.getName(); final String id = prefs.getString("draft." + name + ".id", "");
            LinearLayout c = card(); c.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView n = tv(name, 15, TEXT, true); n.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); c.addView(n);
            c.addView(tv((prefs.getString("draft." + name + ".note", "").isEmpty() ? "" : prefs.getString("draft." + name + ".note", "") + "\n") + (f.length() / 1024) + " KB · " + DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 11, TEXT2, false));
            LinearLayout acts = row(); acts.setPadding(0, dp(10), 0, 0);
            Button diff = btn("Lihat perubahan", false); diff.setOnClickListener(v -> showDiff(f, id));
            Button apply = btn("Coba di HP ini", false); apply.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Coba " + name + "?").setMessage("Draft dipasang sebagai versi OTA lokal di HP ini saja (tidak dipublish). Halaman Dola dimuat ulang. Tekan lama baris komponen di dashboard untuk kembali ke bawaan.").setPositiveButton("Coba", (dd, w) -> { try { byte[] data = java.nio.file.Files.readAllBytes(f.toPath()); try (java.io.FileOutputStream fo = new java.io.FileOutputStream(MainActivity.instance.updates.fileFor(id))) { fo.write(data); } String ver = name.replaceAll("^.*-(v[0-9.]+)\\.[a-z]+$", "$1"); prefs.getClass(); MainActivity.instance.prefs.edit().putString("upd." + id + ".ver", ver).putString("upd." + id + ".name", name).putLong("upd." + id + ".at", System.currentTimeMillis()).apply(); MainActivity.instance.web.reload(); toast(name + " dipasang lokal ✓"); } catch (Exception e) { toast("Gagal: " + e.getMessage()); } }).setNegativeButton("Batal", null).show());
            Button pub = btn("✓ Publish", true); pub.setOnClickListener(v -> publishDraft(f, id));
            Button del = btn("Buang", false); del.setOnClickListener(v -> { f.delete(); loadDrafts(); });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)); bl.rightMargin = dp(6);
            acts.addView(diff, bl); acts.addView(apply, bl); acts.addView(new View(this), weight()); acts.addView(del, bl); acts.addView(pub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            c.addView(acts); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); drafts.addView(c, lp);
        }
    }
    void showDiff(java.io.File f, String id) {
        try {
            String a = MainActivity.instance.updates.text(id), b2 = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            TextView t = new TextView(this); t.setText(Diff.render(a, b2)); t.setTextSize(11); t.setTypeface(Typeface.MONOSPACE); t.setTextColor(TEXT); t.setPadding(dp(14), dp(8), dp(14), dp(8)); t.setTextIsSelectable(true); t.setHorizontallyScrolling(true);
            HorizontalScrollView hs = new HorizontalScrollView(this); hs.addView(t); ScrollView sv = new ScrollView(this); sv.addView(hs);
            new AlertDialog.Builder(this).setTitle(MainActivity.instance.updates.version(id) + " → " + f.getName()).setView(sv).setPositiveButton("Tutup", null).show();
        } catch (Exception e) { toast("Gagal membaca draft: " + e.getMessage()); }
    }
    /** Publish = upload the draft to the Drive update folder through the server (needs admin token). */
    void publishDraft(java.io.File f, String id) {
        if (token().isEmpty()) { askToken(); return; }
        new AlertDialog.Builder(this).setTitle("Publish " + f.getName() + "?").setMessage("File diunggah ke folder update Drive lewat server. Semua pengguna mendapatkannya saat “Cek & update”.").setPositiveButton("Publish", (dd, w) -> {
            status.setText("Mengunggah…");
            try {
                JSONObject p = new JSONObject().put("id", id).put("content", new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)).put("note", prefs.getString("draft." + f.getName() + ".note", "")).put("publish", true);
                Api.call(url(), token(), "admin_draft_upload", p, (r, err) -> { status.setText(err != null ? err : "Dipublish: " + r.optString("name")); if (err != null) toast(err); else { f.delete(); loadDrafts(); } });
            } catch (Exception e) { toast("Gagal: " + e.getMessage()); }
        }).setNegativeButton("Batal", null).show();
    }

    // ---- reports: local recent events from this device (no server needed) ----
    void loadReports() {
        reports.removeAllViews();
        java.util.List<JSONObject> list = new java.util.ArrayList<>(); MainActivity h2 = MainActivity.instance;
        if (h2 != null) synchronized (h2.recentEvents) { list.addAll(h2.recentEvents); }
        if (list.isEmpty()) { TextView e = tv("Belum ada kejadian di sesi ini. Kejadian muncul otomatis saat deteksi/download/skrip bermasalah, atau saat request video tidak 30 detik.", 13, TEXT2, false); e.setPadding(dp(8), dp(16), dp(8), 0); reports.addView(e); return; }
        for (JSONObject x : list) {
            LinearLayout c = card(); c.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout top = row(); String ev = x.optString("event"); TextView comp = tv(x.optString("component") + " · " + ev, 13, ev.contains("fail") || ev.contains("error") || ev.contains("no_") || ev.contains("not_") || ev.contains("short") ? ORANGE : TEXT, true); top.addView(comp, weight());
            top.addView(tv(DateUtils.getRelativeTimeSpanString(x.optLong("t"), System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString(), 10, TEXT2, false)); c.addView(top);
            if (!x.optString("detail").isEmpty()) { TextView dt = tv(x.optString("detail"), 11, TEXT2, false); dt.setTypeface(Typeface.MONOSPACE); dt.setMaxLines(6); dt.setEllipsize(android.text.TextUtils.TruncateAt.END); dt.setPadding(0, dp(4), 0, 0); dt.setOnClickListener(v -> dt.setMaxLines(dt.getMaxLines() > 6 ? 6 : 200)); c.addView(dt); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(6); reports.addView(c, lp);
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); c.setBackground(bg); return c; }
    GradientDrawable pill(int color) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(999)); return g; }
    TextView tv(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    Button btn(String s, boolean primary) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(primary ? BG : TEXT); b.setMinHeight(0); b.setMinimumHeight(dp(36)); b.setPadding(dp(12), 0, dp(12), 0); GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? SILVER : Color.TRANSPARENT); bg.setStroke(dp(1), 0x33FFFFFF); bg.setCornerRadius(dp(12)); b.setBackground(bg); return b; }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
}

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
        if (token().isEmpty()) askToken(); else { loadConfig(); renderChat(); loadDrafts(); loadReports(); handleIntent(getIntent()); }
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
        for (String[] q : new String[][]{{"Analisis laporan", "Baca laporan diagnostik terbaru (get_reports), kelompokkan masalahnya, dan jelaskan apa yang berubah di Dola serta komponen mana yang perlu disesuaikan. Belum perlu mengubah apa pun."}, {"Perbaiki pendeteksi", "Pendeteksi video (collector) bermasalah. Baca laporan diagnostik komponen collector dan inject.js terbaru, cari akar masalahnya, lalu buat draft perbaikan minimal dengan write_draft."}, {"Perbaiki MD", "Baca System MD terbaru dan laporan. Sesuaikan instruksi MD agar Dola kembali mengikuti perilaku yang diinginkan (satu video 30 detik Seedance 2.5, preview native di balasan pertama). Simpan sebagai draft."}}) {
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
        if (i == null || token().isEmpty()) return;
        String ctx = i.getStringExtra("context"), prompt = i.getStringExtra("prompt"); i.removeExtra("context"); i.removeExtra("prompt");
        if (ctx != null) pendingContext = ctx;
        if (prompt != null && !prompt.isEmpty()) { input.setText(prompt); sendMessage(); }
    }
    /** Live mode: events from the running app arrive here and are analysed automatically (debounced, one turn at a time). */
    void onLiveEvent(JSONObject e) {
        if (!liveOn || token().isEmpty()) return;
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
        box.addView(tv("Token admin dari fungsi setup() di Apps Script (Execution log).", 12, TEXT2, false));
        EditText tok = new EditText(this); tok.setHint("ADM-XXXXXX-XXXXXX-XXXXXX"); tok.setText(token()); tok.setSingleLine(); tok.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(tok);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Masuk Developer").setView(box).setCancelable(false).setPositiveButton("Masuk", null).setNegativeButton("Kembali", (dd, w) -> finish()).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = tok.getText().toString().trim(); if (t.isEmpty()) { tok.setError("Wajib diisi"); return; }
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Api.call(url(), t, "admin_ping", null, (r, err) -> { d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); if (err != null) { tok.setError(err); return; } prefs.edit().putString("token", t).apply(); d.dismiss(); loadConfig(); renderChat(); loadDrafts(); loadReports(); });
        }));
        d.show();
    }
    @Override public boolean onCreateOptionsMenu(android.view.Menu m) { return false; }

    // ---- provider config + model picker ----
    void loadConfig() {
        // Old server code (without agent routes) answers admin_ai_* with not_found; detect it once and explain.
        Api.call(url(), token(), "admin_ai_get", null, (r, err) -> { if (err != null && err.contains("Key tidak ditemukan")) { status.setTextColor(ORANGE); status.setText("Server masih Code.gs lama (tanpa AI Agent). Tempel Code.gs terbaru (±375 baris, CODE_VERSION 5), Run setup, lalu Deploy → Manage deployments → ✏ → New version."); modelLabel.setText("server perlu diperbarui"); return; } if (r != null) modelLabel.setText(r.optString("baseUrl").isEmpty() ? "provider belum diatur" : (r.optString("model").isEmpty() ? "model belum dipilih" : r.optString("model")) + " · " + r.optString("baseUrl").replaceAll("^https?://", "").replaceAll("/.*$", "")); });
    }
    void showConfig() {
        Api.call(url(), token(), "admin_ai_get", null, (cur, err) -> {
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
            box.addView(tv("Provider OpenAI-compatible. Contoh base URL: https://api.openai.com/v1 · https://openrouter.ai/api/v1 · https://api.groq.com/openai/v1 · https://api.deepseek.com/v1", 11, TEXT2, false));
            EditText base = new EditText(this); base.setHint("Base URL"); base.setText(cur == null ? "" : cur.optString("baseUrl")); base.setSingleLine(); base.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); box.addView(base);
            EditText key = new EditText(this); key.setHint(cur != null && cur.optBoolean("hasKey") ? "API key tersimpan — kosongkan untuk tetap" : "API key"); key.setSingleLine(); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(key);
            TextView model = tv("Model: " + (cur == null || cur.optString("model").isEmpty() ? "— (ambil daftar dulu)" : cur.optString("model")), 13, TEXT, true); model.setPadding(0, dp(12), 0, dp(6)); box.addView(model);
            Button pick = btn("Ambil daftar model dari provider", false); box.addView(pick);
            final String[] chosen = { cur == null ? "" : cur.optString("model") };
            AlertDialog d = new AlertDialog.Builder(this).setTitle("Provider AI").setView(box).setPositiveButton("Simpan", null).setNegativeButton("Batal", null).create();
            pick.setOnClickListener(v -> {
                pick.setEnabled(false);
                final long t0 = System.currentTimeMillis(); final Runnable[] tick = new Runnable[1];
                tick[0] = () -> { if (!pick.isEnabled()) { pick.setText("⏳ Menghubungi server… " + (System.currentTimeMillis() - t0) / 1000 + " dtk (Apps Script bisa 5–40 dtk)"); pick.postDelayed(tick[0], 1000); } }; tick[0].run();
                JSONObject p = new JSONObject(); try { p.put("baseUrl", base.getText().toString().trim()); if (!key.getText().toString().trim().isEmpty()) p.put("apiKey", key.getText().toString().trim()); p.put("withModels", true); } catch (Exception ignored) {}
                Api.call(url(), token(), "admin_ai_set", p, (r2, e2) -> {
                    pick.setEnabled(true); pick.setText("Ambil daftar model dari provider");
                    if (e2 != null) { toast(e2); return; }
                    if (r2.has("modelsError")) { toast("Provider: " + r2.optString("modelsError")); return; }
                    JSONArray arr = r2.optJSONArray("models"); if (arr == null || arr.length() == 0) { toast("Provider tidak mengembalikan daftar model"); return; }
                    List<String> ids = new ArrayList<>(); for (int i = 0; i < arr.length(); i++) ids.add(arr.optString(i));
                    EditText filter = new EditText(this); filter.setHint("Cari model…"); filter.setSingleLine();
                    LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setPadding(dp(16), 0, dp(16), 0); wrap.addView(filter);
                    android.widget.ListView lv = new android.widget.ListView(this); android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(ids)); lv.setAdapter(ad); wrap.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
                    filter.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int b2, int c) {} public void afterTextChanged(android.text.Editable s) {} public void onTextChanged(CharSequence s, int a, int b2, int c) { ad.getFilter().filter(s); } });
                    AlertDialog md = new AlertDialog.Builder(this).setTitle(ids.size() + " model tersedia").setView(wrap).setNegativeButton("Batal", null).create();
                    lv.setOnItemClickListener((av, view, pos, id) -> { chosen[0] = ad.getItem(pos); model.setText("Model: " + chosen[0]); md.dismiss(); });
                    md.show();
                });
            });
            d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                JSONObject p = new JSONObject(); try { p.put("baseUrl", base.getText().toString().trim()); p.put("model", chosen[0]); if (!key.getText().toString().trim().isEmpty()) p.put("apiKey", key.getText().toString().trim()); } catch (Exception ignored) {}
                Api.call(url(), token(), "admin_ai_set", p, (r, e) -> { if (e != null) toast(e); else { toast("Provider tersimpan"); loadConfig(); d.dismiss(); } });
            }));
            d.show();
        });
    }

    // ---- chat ----
    void sendMessage() {
        String text = input.getText().toString().trim(); if (text.isEmpty()) return;
        try { messages.put(new JSONObject().put("role", "user").put("content", text)); } catch (Exception ignored) {}
        input.setText(""); renderChat(); send.setEnabled(false); busy = true; status.setTextColor(TEXT2);
        final long t0 = System.currentTimeMillis(); final Runnable[] tick = new Runnable[1];
        tick[0] = () -> { if (busy) { long sec = (System.currentTimeMillis() - t0) / 1000; status.setText((sec < 8 ? "⏳ Menghubungi server Apps Script…" : sec < 40 ? "🤔 Agent berpikir / membaca skrip…" : sec < 120 ? "✍️ Agent menulis (skrip besar bisa 1–3 menit)…" : "⏳ Masih bekerja…") + " " + sec + " dtk"); status.postDelayed(tick[0], 1000); } }; tick[0].run();
        // Keep the transcript bounded: tool payloads are large, so only the last 14 messages travel with each turn.
        JSONArray window = new JSONArray(); int start = Math.max(0, messages.length() - 14); for (int i = start; i < messages.length(); i++) window.put(messages.opt(i));
        JSONObject p = new JSONObject(); try { p.put("messages", window); if (pendingContext != null) { p.put("context", pendingContext); pendingContext = null; } } catch (Exception ignored) {}
        Api.call(url(), token(), "admin_agent", p, (r, err) -> {
            send.setEnabled(true); busy = false;
            if (err != null) { status.setText(err); status.setTextColor(ORANGE); try { messages.put(new JSONObject().put("role", "assistant").put("content", "⚠ " + err)); } catch (Exception ignored) {} renderChat(); if (err.contains("ai_not_configured") || err.contains("belum")) showConfig(); return; }
            status.setTextColor(TEXT2); status.setText("Selesai dalam " + (System.currentTimeMillis() - t0) / 1000 + " dtk");
            JSONArray msgs = r.optJSONArray("messages"); if (msgs != null) { JSONArray merged = new JSONArray(); for (int i = 0; i < start; i++) merged.put(messages.opt(i)); for (int i = 0; i < msgs.length(); i++) merged.put(msgs.opt(i)); messages = merged; }
            JSONArray ev = r.optJSONArray("events"); boolean draft = false; for (int i = 0; ev != null && i < ev.length(); i++) if (ev.optJSONObject(i) != null && ev.optJSONObject(i).has("draft")) draft = true;
            prefs.edit().putString("agentMessages", messages.toString()).apply(); renderChat();
            if (draft) { toast("Draft baru dibuat — periksa tab Draft lalu Terapkan."); loadDrafts(); }
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

    // ---- drafts ----
    void loadDrafts() {
        Api.call(url(), token(), "admin_drafts", null, (r, err) -> {
            drafts.removeAllViews();
            if (err != null) { drafts.addView(tv(err, 12, ORANGE, false)); return; }
            JSONArray arr = r.optJSONArray("drafts"); if (arr == null || arr.length() == 0) { TextView e = tv("Belum ada draft. Minta agent membuat perbaikan di tab Chat.", 13, TEXT2, false); e.setPadding(dp(8), dp(16), dp(8), 0); drafts.addView(e); return; }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i); if (d == null) continue; final String fid = d.optString("fileId"), name = d.optString("name");
                LinearLayout c = card(); c.setPadding(dp(14), dp(12), dp(14), dp(12));
                TextView n = tv(name, 15, TEXT, true); n.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); c.addView(n);
                c.addView(tv((d.optString("note").isEmpty() ? "" : d.optString("note") + "\n") + (d.optLong("size") / 1024) + " KB · " + DateUtils.getRelativeTimeSpanString(d.optLong("updated"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 11, TEXT2, false));
                LinearLayout acts = row(); acts.setPadding(0, dp(10), 0, 0);
                Button diff = btn("Lihat perubahan", false); diff.setOnClickListener(v -> showDiff(fid, name));
                Button pub = btn("✓ Terapkan", true); pub.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Terapkan " + name + "?").setMessage("File dipindah ke folder update. Semua pengguna mendapatkannya saat menekan “Cek & update”.").setPositiveButton("Terapkan", (dd, w) -> act("admin_draft_publish", fid, name + " diterapkan ✓")).setNegativeButton("Batal", null).show());
                Button del = btn("Buang", false); del.setOnClickListener(v -> act("admin_draft_delete", fid, "Draft dibuang"));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)); bl.rightMargin = dp(6);
                acts.addView(diff, bl); acts.addView(new View(this), weight()); acts.addView(del, bl); acts.addView(pub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
                c.addView(acts); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); drafts.addView(c, lp);
            }
        });
    }
    void act(String action, String fid, String okMsg) { JSONObject p = new JSONObject(); try { p.put("fileId", fid); } catch (Exception ignored) {} Api.call(url(), token(), action, p, (r, err) -> { toast(err != null ? err : okMsg); loadDrafts(); }); }
    void showDiff(String fid, String name) {
        status.setText("Mengambil draft…");
        JSONObject p = new JSONObject(); try { p.put("fileId", fid); } catch (Exception ignored) {}
        Api.call(url(), token(), "admin_draft_get", p, (r, err) -> {
            status.setText(""); if (err != null) { toast(err); return; }
            JSONObject cur = r.optJSONObject("current");
            String a = cur == null ? "" : cur.optString("content"), b2 = r.optString("content");
            android.text.SpannableStringBuilder sb = Diff.render(a, b2);
            TextView t = new TextView(this); t.setText(sb); t.setTextSize(11); t.setTypeface(Typeface.MONOSPACE); t.setTextColor(TEXT); t.setPadding(dp(14), dp(8), dp(14), dp(8)); t.setTextIsSelectable(true); t.setHorizontallyScrolling(true);
            HorizontalScrollView hs = new HorizontalScrollView(this); hs.addView(t); ScrollView sv = new ScrollView(this); sv.addView(hs);
            new AlertDialog.Builder(this).setTitle((cur == null ? "baru" : cur.optString("version")) + " → " + name).setView(sv).setPositiveButton("Tutup", null).show();
        });
    }

    // ---- reports ----
    void loadReports() {
        Api.call(url(), token(), "admin_reports", null, (r, err) -> {
            reports.removeAllViews();
            if (err != null) { reports.addView(tv(err, 12, ORANGE, false)); return; }
            JSONArray arr = r.optJSONArray("reports"); if (arr == null || arr.length() == 0) { TextView e = tv("Belum ada laporan. Aplikasi pengguna mengirim laporan otomatis saat deteksi/download/skrip bermasalah.", 13, TEXT2, false); e.setPadding(dp(8), dp(16), dp(8), 0); reports.addView(e); return; }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i); if (x == null) continue;
                LinearLayout c = card(); c.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout top = row(); TextView comp = tv(x.optString("component") + " · " + x.optString("event"), 13, x.optString("event").contains("fail") || x.optString("event").contains("error") || x.optString("event").contains("no_") ? ORANGE : TEXT, true); top.addView(comp, weight());
                long ts = 0; try { ts = java.time.Instant.parse(x.optString("time")).toEpochMilli(); } catch (Exception ignored) {}
                top.addView(tv(ts > 0 ? DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() : "", 10, TEXT2, false)); c.addView(top);
                c.addView(tv(x.optString("device") + " · " + x.optString("app"), 10, TEXT2, false));
                if (!x.optString("detail").isEmpty()) { TextView dt = tv(x.optString("detail"), 11, TEXT2, false); dt.setTypeface(Typeface.MONOSPACE); dt.setMaxLines(6); dt.setEllipsize(android.text.TextUtils.TruncateAt.END); dt.setPadding(0, dp(4), 0, 0); dt.setOnClickListener(v -> dt.setMaxLines(dt.getMaxLines() > 6 ? 6 : 200)); c.addView(dt); }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(6); reports.addView(c, lp);
            }
        });
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); c.setBackground(bg); return c; }
    GradientDrawable pill(int color) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(999)); return g; }
    TextView tv(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    Button btn(String s, boolean primary) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(primary ? BG : TEXT); b.setMinHeight(0); b.setMinimumHeight(dp(36)); b.setPadding(dp(12), 0, dp(12), 0); GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? SILVER : Color.TRANSPARENT); bg.setStroke(dp(1), 0x33FFFFFF); bg.setCornerRadius(dp(12)); b.setBackground(bg); return b; }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
}

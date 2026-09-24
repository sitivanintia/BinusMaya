package com.introvertdreams.sesiadmin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** SESI Admin — generate, revoke/restore, release devices, delete and monitor license keys. */
public class AdminActivity extends AppCompatActivity {
    static final int BG = 0xFF1A1A1F, CARD = 0xFF24242B, TEXT = 0xFFF4F4F5, TEXT2 = 0xFFA8A8AD, GREEN = 0xFF7DCF8F, ORANGE = 0xFFD9B47A, RED = 0xFFE07A7A, SILVER = 0xFFE6E6E8;
    SharedPreferences prefs;
    LinearLayout list, counters, chips; TextView status; SwipeRefreshLayout swipe; EditText search;
    JSONArray licenses = new JSONArray(); String filter = "all";

    String url() { String u = prefs.getString("url", ""); return u.isEmpty() ? getString(R.string.license_url) : u; }
    String token() { return prefs.getString("token", ""); }
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("admin", MODE_PRIVATE);
        buildUi();
        if (token().isEmpty()) showLogin(); else refresh();
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setFitsSystemWindows(true);
        LinearLayout hdr = row(); hdr.setPadding(dp(16), dp(14), dp(12), dp(6));
        android.widget.ImageView logo = new android.widget.ImageView(this); logo.setImageResource(R.drawable.ic_sesi);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(30), dp(30)); ll.rightMargin = dp(10); hdr.addView(logo, ll);
        LinearLayout tcol = new LinearLayout(this); tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.addView(tv("SESI Admin", 20, TEXT, true)); tcol.addView(tv("Lisensi · Google Sheet", 11, TEXT2, false));
        hdr.addView(tcol, weight());
        Button gear = btn("⚙", false); gear.setOnClickListener(v -> showLogin()); hdr.addView(gear);
        root.addView(hdr);

        counters = row(); counters.setPadding(dp(12), dp(6), dp(12), 0); root.addView(counters);

        LinearLayout actions = row(); actions.setPadding(dp(12), dp(10), dp(12), 0);
        Button create = btn("＋ Buat key", true); create.setOnClickListener(v -> showCreate());
        LinearLayout.LayoutParams a1 = weight(); a1.rightMargin = dp(4); actions.addView(create, a1);
        Button reload = btn("↻ Muat ulang", false); reload.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams a2 = weight(); a2.leftMargin = dp(4); actions.addView(reload, a2);
        root.addView(actions);

        LinearLayout filt = row(); filt.setPadding(dp(12), dp(8), dp(12), 0); chips = filt;
        for (String[] f : new String[][]{{"all", "Semua"}, {"active", "Aktif"}, {"online", "Online 24j"}, {"revoked", "Dicabut"}, {"expired", "Expired"}, {"unused", "Belum dipakai"}}) {
            TextView chip = tv(f[1], 11, TEXT2, false); chip.setPadding(dp(10), dp(5), dp(10), dp(5)); chip.setTag(f[0]);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); cl.rightMargin = dp(6);
            chip.setOnClickListener(v -> { filter = (String) v.getTag(); render(); });
            filt.addView(chip, cl);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(filt); root.addView(hs);

        search = new EditText(this); search.setHint("Cari key / nama / Device ID"); search.setSingleLine(); search.setTextColor(TEXT); search.setHintTextColor(TEXT2); search.setTextSize(14);
        search.setBackground(pill(CARD)); search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int b, int c) {} public void onTextChanged(CharSequence s, int a, int b, int c) { render(); } public void afterTextChanged(android.text.Editable s) {} });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); sl.setMargins(dp(12), dp(10), dp(12), 0); root.addView(search, sl);

        status = tv("", 12, TEXT2, false); status.setPadding(dp(16), dp(8), dp(16), 0); root.addView(status);

        swipe = new SwipeRefreshLayout(this); swipe.setOnRefreshListener(this::refresh); swipe.setColorSchemeColors(GREEN);
        ScrollView sv = new ScrollView(this); list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(12), dp(8), dp(12), dp(24)); sv.addView(list); swipe.addView(sv);
        root.addView(swipe, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    void showLogin() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(tv("Token admin dari setupAdmin() di Apps Script (lihat Execution log).", 12, TEXT2, false));
        EditText tok = new EditText(this); tok.setHint("ADM-XXXXXX-XXXXXX-XXXXXX"); tok.setText(token()); tok.setSingleLine(); tok.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(tok);
        EditText u = new EditText(this); u.setHint("URL server (kosong = bawaan)"); u.setText(prefs.getString("url", "")); u.setSingleLine(); u.setTextSize(12); box.addView(u);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Masuk Admin").setView(box).setCancelable(!token().isEmpty())
                .setPositiveButton("Masuk", null).setNegativeButton(token().isEmpty() ? "Keluar" : "Batal", (dd, w) -> { if (token().isEmpty()) finish(); }).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = tok.getText().toString().trim(); String uu = u.getText().toString().trim();
            if (t.isEmpty()) { tok.setError("Wajib diisi"); return; }
            String testUrl = uu.isEmpty() ? getString(R.string.license_url) : uu;
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Api.call(testUrl, t, "admin_ping", null, (r, err) -> {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (err != null) { tok.setError(err); return; }
                prefs.edit().putString("token", t).putString("url", uu).apply(); d.dismiss(); refresh();
            });
        }));
        d.show();
    }

    void refresh() {
        if (token().isEmpty()) return;
        swipe.setRefreshing(true); status.setText("Memuat…");
        Api.call(url(), token(), "admin_list", null, (r, err) -> {
            swipe.setRefreshing(false);
            if (err != null) { status.setText(err); status.setTextColor(ORANGE); if (err.startsWith("Token")) showLogin(); return; }
            licenses = r.optJSONArray("licenses") == null ? new JSONArray() : r.optJSONArray("licenses");
            status.setTextColor(TEXT2); status.setText("Diperbarui " + DateFormat.format("HH:mm:ss", System.currentTimeMillis()));
            render();
        });
    }

    void apply(JSONObject r) { if (r != null && r.optJSONArray("licenses") != null) { licenses = r.optJSONArray("licenses"); render(); } }

    static String state(JSONObject l) {
        if ("revoked".equals(l.optString("status"))) return "revoked";
        long exp = l.optLong("expiresAt"); if (exp > 0 && exp < System.currentTimeMillis()) return "expired";
        return "active";
    }

    void render() {
        long now = System.currentTimeMillis(); int total = licenses.length(), active = 0, online = 0, revoked = 0, expired = 0, unused = 0, devices = 0;
        List<JSONObject> shown = new ArrayList<>(); String q = search.getText().toString().trim().toUpperCase();
        for (int i = 0; i < licenses.length(); i++) {
            JSONObject l = licenses.optJSONObject(i); if (l == null) continue;
            String st = state(l); JSONArray dv = l.optJSONArray("devices"); int dn = dv == null ? 0 : dv.length(); devices += dn;
            boolean isOnline = now - l.optLong("lastSeen") < 86400000L && l.optLong("lastSeen") > 0;
            if ("active".equals(st)) active++; if ("revoked".equals(st)) revoked++; if ("expired".equals(st)) expired++; if (isOnline) online++; if (dn == 0) unused++;
            boolean pass = filter.equals("all") || (filter.equals("online") ? isOnline : filter.equals("unused") ? dn == 0 : filter.equals(st));
            if (pass && !q.isEmpty()) pass = (l.optString("key") + " " + l.optString("name") + " " + (dv == null ? "" : dv.toString())).toUpperCase().contains(q);
            if (pass) shown.add(l);
        }
        counters.removeAllViews();
        for (String[] c : new String[][]{{String.valueOf(total), "Key"}, {String.valueOf(active), "Aktif"}, {String.valueOf(devices), "HP"}, {String.valueOf(online), "Online 24j"}, {String.valueOf(revoked + expired), "Mati"}}) {
            LinearLayout box = card(); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(8), 0, dp(8));
            box.addView(tv(c[0], 17, TEXT, true)); box.addView(tv(c[1], 10, TEXT2, false));
            LinearLayout.LayoutParams lp = weight(); lp.setMargins(dp(3), 0, dp(3), 0); counters.addView(box, lp);
        }
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean on = filter.equals(chip.getTag()); chip.setBackground(pill(on ? SILVER : CARD)); chip.setTextColor(on ? BG : TEXT2);
        }
        list.removeAllViews();
        if (shown.isEmpty()) { TextView e = tv(total == 0 ? "Belum ada key. Ketuk “Buat key”." : "Tidak ada yang cocok.", 13, TEXT2, false); e.setGravity(Gravity.CENTER); e.setPadding(0, dp(30), 0, 0); list.addView(e); return; }
        for (JSONObject l : shown) list.addView(rowFor(l), mt(8));
    }

    View rowFor(JSONObject l) {
        String key = l.optString("key"), name = l.optString("name"), st = state(l);
        JSONArray dv = l.optJSONArray("devices"); int dn = dv == null ? 0 : dv.length(); long seen = l.optLong("lastSeen"), exp = l.optLong("expiresAt");
        LinearLayout c = card(); c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout top = row();
        View led = new View(this); GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(st.equals("active") ? (dn > 0 ? GREEN : ORANGE) : RED); led.setBackground(dot);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(9), dp(9)); dl.rightMargin = dp(10); top.addView(led, dl);
        TextView k = tv(key, 15, TEXT, true); k.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); top.addView(k, weight());
        TextView badge = tv(st.equals("active") ? (dn > 0 ? "AKTIF" : "BELUM DIPAKAI") : st.equals("revoked") ? "DICABUT" : "EXPIRED", 9, BG, true);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2)); badge.setBackground(pill(st.equals("active") ? (dn > 0 ? GREEN : ORANGE) : RED)); top.addView(badge);
        c.addView(top);
        StringBuilder meta = new StringBuilder();
        if (!name.isEmpty()) meta.append(name).append(" · ");
        meta.append(dn).append("/").append(l.optInt("maxDevices", 1)).append(" HP");
        meta.append(" · ").append(exp > 0 ? "s/d " + DateFormat.format("d MMM yyyy", exp) : "lifetime");
        if (seen > 0) meta.append("\nTerakhir online ").append(DateUtils.getRelativeTimeSpanString(seen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        if (dn > 0) { meta.append("\nDevice: "); for (int i = 0; i < dn; i++) meta.append(i > 0 ? ", " : "").append(dv.optString(i)); }
        if (!l.optString("note").isEmpty()) meta.append("\n").append(l.optString("note"));
        TextView m = tv(meta.toString(), 11, TEXT2, false); m.setPadding(dp(19), dp(4), 0, 0); m.setLineSpacing(0, 1.15f); c.addView(m);
        LinearLayout acts = row(); acts.setPadding(0, dp(10), 0, 0);
        Button copy = btn("Salin", false); copy.setOnClickListener(v -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("key", key)); toast("Key disalin"); });
        Button share = btn("Kirim", false); share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "License key SESI MINI kamu:\n" + key + "\n\nBuka aplikasi → masukkan key → Aktivasi."), "Kirim key")));
        Button tog = btn(st.equals("revoked") ? "Aktifkan" : "Nonaktifkan", !st.equals("revoked"));
        tog.setOnClickListener(v -> confirm(st.equals("revoked") ? "Aktifkan key" : "Nonaktifkan key", key + (st.equals("revoked") ? "\n\nKey bisa dipakai lagi." : "\n\nAplikasi pemakai akan terkunci pada verifikasi berikutnya (≤ 3 hari)."), () -> act("admin_set_status", key, new JSONObject(), "status", st.equals("revoked") ? "active" : "revoked")));
        Button more = btn("⋯", false); more.setOnClickListener(v -> moreMenu(l));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)); bl.rightMargin = dp(6);
        acts.addView(copy, bl); acts.addView(share, bl); acts.addView(new View(this), weight()); acts.addView(tog, bl); acts.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        c.addView(acts);
        return c;
    }

    void moreMenu(JSONObject l) {
        String key = l.optString("key"); JSONArray dv = l.optJSONArray("devices"); int dn = dv == null ? 0 : dv.length();
        List<String> items = new ArrayList<>(); items.add("Ganti nama"); items.add("Ubah max perangkat"); items.add("Ubah masa berlaku");
        for (int i = 0; i < dn; i++) items.add("Lepas perangkat " + dv.optString(i)); items.add("Hapus key");
        new AlertDialog.Builder(this).setTitle(key).setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == 0) prompt("Nama", l.optString("name"), InputType.TYPE_CLASS_TEXT, v -> act("admin_rename", key, new JSONObject(), "name", v));
            else if (w == 1) prompt("Max perangkat", String.valueOf(l.optInt("maxDevices", 1)), InputType.TYPE_CLASS_NUMBER, v -> act("admin_set_max", key, new JSONObject(), "maxDevices", v));
            else if (w == 2) prompt("Berlaku berapa hari dari sekarang (0 = lifetime)", "30", InputType.TYPE_CLASS_NUMBER, v -> act("admin_set_expiry", key, new JSONObject(), "days", v));
            else if (w < 3 + dn) { String dev = dv.optString(w - 3); confirm("Lepas perangkat", dev + " dilepas dari " + key + ". Pemakai HP itu akan terkunci; slot bisa dipakai HP lain.", () -> act("admin_release", key, new JSONObject(), "deviceId", dev)); }
            else confirm("Hapus key", key + " dihapus permanen dari sheet.", () -> act("admin_delete", key, new JSONObject(), null, null));
        }).show();
    }

    void act(String action, String key, JSONObject p, String field, String value) {
        try { p.put("key", key); if (field != null) p.put(field, value); } catch (Exception ignored) {}
        status.setText("Menyimpan…");
        Api.call(url(), token(), action, p, (r, err) -> { if (err != null) { toast(err); status.setText(err); } else { status.setText("Tersimpan"); apply(r); } });
    }

    void showCreate() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = field(box, "Nama / label (mis. Toko A, Batch Sept)", "", InputType.TYPE_CLASS_TEXT);
        EditText count = field(box, "Jumlah key", "1", InputType.TYPE_CLASS_NUMBER);
        EditText max = field(box, "Max perangkat per key", "1", InputType.TYPE_CLASS_NUMBER);
        EditText days = field(box, "Berlaku (hari, 0 = lifetime)", "0", InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("Buat key baru").setView(box).setPositiveButton("Buat", (d, w) -> {
            try {
                JSONObject p = new JSONObject().put("name", name.getText().toString().trim()).put("count", num(count, 1)).put("maxDevices", num(max, 1)).put("days", num(days, 0));
                status.setText("Membuat key…");
                Api.call(url(), token(), "admin_create", p, (r, err) -> {
                    if (err != null) { toast(err); status.setText(err); return; }
                    apply(r); JSONArray keys = r.optJSONArray("keys"); StringBuilder sb = new StringBuilder();
                    for (int i = 0; keys != null && i < keys.length(); i++) sb.append(i > 0 ? "\n" : "").append(keys.optString(i));
                    TextView t = tv(sb.toString(), 15, GREEN, true); t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); t.setPadding(dp(20), dp(12), dp(20), dp(4)); t.setTextIsSelectable(true);
                    new AlertDialog.Builder(this).setTitle((keys == null ? 0 : keys.length()) + " key dibuat").setView(t)
                            .setPositiveButton("Salin semua", (dd, ww) -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("keys", sb.toString())); toast("Disalin"); })
                            .setNeutralButton("Kirim", (dd, ww) -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "License key SESI MINI:\n" + sb), "Kirim key")))
                            .setNegativeButton("Tutup", null).show();
                });
            } catch (Exception ignored) {}
        }).setNegativeButton("Batal", null).show();
    }

    // ---- small UI helpers ----
    static int num(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; } }
    EditText field(LinearLayout box, String hint, String val, int type) { EditText e = new EditText(this); e.setHint(hint); e.setText(val); e.setSingleLine(); e.setInputType(type); box.addView(e); return e; }
    void prompt(String title, String initial, int type, android.webkit.ValueCallback<String> done) {
        EditText e = new EditText(this); e.setText(initial); e.setSingleLine(); e.setInputType(type); e.setSelection(e.getText().length());
        FrameLayout wrap = new FrameLayout(this); wrap.setPadding(dp(20), dp(8), dp(20), 0); wrap.addView(e);
        new AlertDialog.Builder(this).setTitle(title).setView(wrap).setPositiveButton("Simpan", (d, w) -> done.onReceiveValue(e.getText().toString().trim())).setNegativeButton("Batal", null).show();
    }
    void confirm(String title, String msg, Runnable ok) { new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Lanjut", (d, w) -> ok.run()).setNegativeButton("Batal", null).show(); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); c.setBackground(bg); return c; }
    GradientDrawable pill(int color) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(999)); return g; }
    TextView tv(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    Button btn(String s, boolean primary) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(primary ? BG : TEXT); b.setMinHeight(0); b.setMinimumHeight(dp(36)); b.setPadding(dp(12), 0, dp(12), 0); GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? SILVER : Color.TRANSPARENT); bg.setStroke(dp(1), 0x33FFFFFF); bg.setCornerRadius(dp(12)); b.setBackground(bg); return b; }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    LinearLayout.LayoutParams mt(int t) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); l.topMargin = dp(t); return l; }
}

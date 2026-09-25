package com.introvertdreams.app;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;

import static com.introvertdreams.app.DashboardSheet.*;

/** SESI MAX MODE "Sesi Akun": save the Dola login (cookies + localStorage) per account and switch without re-login. */
public class AccountsSheet {
    static final String ACCOUNTS = "accounts", ACTIVE_ACCOUNT = "activeAccount";
    static final String[] COOKIE_URLS = { "https://www.dola.com/", "https://dola.com/", "https://api.dola.com/", "https://accounts.dola.com/", "https://auth.dola.com/" };
    static final SecureRandom RND = new SecureRandom();

    static void show(MainActivity a) {
        BottomSheetDialog d = new BottomSheetDialog(a);
        ScrollView sv = new ScrollView(a); sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(sv, 16); root.setPadding(p, p, p, p); sv.addView(root);

        root.addView(tv(a, "Sesi Akun", 20, TEXT, true));
        TextView sub = tv(a, "Simpan login Dola per akun (cookies + penyimpanan situs) dan ganti akun tanpa login ulang.", 12, TEXT2, false);
        sub.setPadding(0, dp(sv, 4), 0, 0); root.addView(sub);

        LinearLayout actions = row(a);
        Button save = btn(a, "Simpan sesi ini", true); LinearLayout.LayoutParams s1 = weight(); s1.rightMargin = dp(sv, 4); actions.addView(save, s1);
        Button fresh = btn(a, "Akun baru", false); LinearLayout.LayoutParams s2 = weight(); s2.leftMargin = dp(sv, 4); actions.addView(fresh, s2);
        root.addView(actions, mt(sv, 12));

        root.addView(groupTitle(a, "Tersimpan"));
        LinearLayout list = new LinearLayout(a); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
        TextView note = tv(a, "Sesi disimpan lokal di perangkat ini. Situs bisa mencabut sesi lama kapan saja; jika itu terjadi, login ulang lalu simpan lagi.", 11, Color.parseColor("#6ef4f4f5"), false);
        note.setPadding(dp(sv, 4), dp(sv, 16), dp(sv, 4), 0); root.addView(note);

        Runnable[] render = new Runnable[1];
        render[0] = () -> renderAccounts(a, list, render[0], d);
        save.setOnClickListener(v -> promptSaveSession(a, render[0]));
        fresh.setOnClickListener(v -> confirm(a, "Akun baru", "Sesi Dola yang sedang aktif akan dikeluarkan (logout) supaya kamu bisa login akun lain. Sesi saat ini disimpan dulu bila sudah terdaftar.", () -> { startFreshSession(a); d.dismiss(); }));
        render[0].run();
        d.setContentView(sv); d.show();
    }

    static void renderAccounts(MainActivity a, LinearLayout list, Runnable rerender, BottomSheetDialog d) {
        list.removeAllViews();
        JSONArray arr = loadAccounts(a);
        String active = a.prefs.getString(ACTIVE_ACCOUNT, "");
        if (arr.length() == 0) {
            LinearLayout c = card(a);
            TextView e = tv(a, "Belum ada sesi tersimpan.\nLogin di Dola, lalu ketuk “Simpan sesi ini”.", 13, TEXT2, false);
            e.setGravity(Gravity.CENTER); e.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 24)); c.addView(e);
            list.addView(c);
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject acc = arr.optJSONObject(i); if (acc == null) continue;
            final String id = acc.optString("id"); final String name = acc.optString("name", "Akun");
            boolean isActive = id.equals(active);
            LinearLayout r = row(a); r.setPadding(dp(r, 12), dp(r, 10), dp(r, 8), dp(r, 10));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(r, 14)); r.setBackground(bg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); rlp.bottomMargin = dp(r, 8);
            View led = new View(a); android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable(); dot.setShape(android.graphics.drawable.GradientDrawable.OVAL); dot.setColor(isActive ? GREEN : Color.parseColor("#3cffffff")); led.setBackground(dot);
            r.addView(led, new LinearLayout.LayoutParams(dp(r, 8), dp(r, 8)));
            LinearLayout copy = new LinearLayout(a); copy.setOrientation(LinearLayout.VERTICAL); copy.setPadding(dp(r, 12), 0, dp(r, 8), 0);
            TextView n = tv(a, name, 14, TEXT, true); n.setSingleLine(); n.setEllipsize(TextUtils.TruncateAt.END); copy.addView(n);
            String when = DateUtils.getRelativeTimeSpanString(acc.optLong("savedAt", 0), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
            copy.addView(tv(a, (isActive ? "Aktif · " : "") + "disimpan " + when, 11, TEXT2, false));
            r.addView(copy, weight());
            Button use = btn(a, isActive ? "Segarkan" : "Pakai", !isActive);
            use.setOnClickListener(v -> { if (isActive) saveSessionInto(a, id, null, rerender); else { switchToAccount(a, id); d.dismiss(); } });
            r.addView(use);
            Button more = btn(a, "⋯", false);
            more.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle(name).setItems(new String[]{ "Ganti nama", "Hapus" }, (dlg, which) -> {
                if (which == 0) promptName(a, "Ganti nama", name, newName -> { updateAccount(a, id, o -> o.put("name", newName)); rerender.run(); });
                else confirm(a, "Hapus sesi", "Hapus sesi “" + name + "”? Login di situs tidak terpengaruh.", () -> {
                    JSONArray cur = loadAccounts(a), next = new JSONArray();
                    for (int k = 0; k < cur.length(); k++) { JSONObject o = cur.optJSONObject(k); if (o != null && !id.equals(o.optString("id"))) next.put(o); }
                    storeAccounts(a, next);
                    if (id.equals(a.prefs.getString(ACTIVE_ACCOUNT, ""))) a.prefs.edit().remove(ACTIVE_ACCOUNT).apply();
                    rerender.run();
                });
            }).show());
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); mlp.leftMargin = dp(r, 4);
            r.addView(more, mlp);
            list.addView(r, rlp);
        }
    }

    static JSONArray loadAccounts(MainActivity a) { try { return new JSONArray(a.prefs.getString(ACCOUNTS, "[]")); } catch (JSONException e) { return new JSONArray(); } }
    static void storeAccounts(MainActivity a, JSONArray arr) { a.prefs.edit().putString(ACCOUNTS, arr.toString()).apply(); }

    interface AccountEdit { void apply(JSONObject o) throws JSONException; }
    static void updateAccount(MainActivity a, String id, AccountEdit edit) {
        JSONArray arr = loadAccounts(a);
        for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) { try { edit.apply(o); } catch (JSONException ignored) {} break; } }
        storeAccounts(a, arr);
    }

    static boolean hasSessionCookies() {
        CookieManager cm = CookieManager.getInstance();
        for (String u : COOKIE_URLS) { String c = cm.getCookie(u); if (c != null && !c.trim().isEmpty()) return true; }
        return false;
    }

    static void promptSaveSession(MainActivity a, Runnable rerender) {
        if (!hasSessionCookies()) { Toast.makeText(a, "Belum ada sesi Dola. Login dulu di Dola.", Toast.LENGTH_LONG).show(); return; }
        String active = a.prefs.getString(ACTIVE_ACCOUNT, "");
        if (!active.isEmpty()) { saveSessionInto(a, active, null, rerender); return; }
        promptName(a, "Nama akun", "", name -> saveSessionInto(a, null, name, rerender));
    }

    static JSONObject snapshotCookies(MainActivity a) throws JSONException {
        JSONObject cookies = new JSONObject();
        CookieManager cm = CookieManager.getInstance();
        LinkedHashSet<String> urls = new LinkedHashSet<>(Arrays.asList(COOKIE_URLS));
        try { String cur = a.web.getUrl(); Uri u = Uri.parse(cur == null ? "" : cur); if (MainActivity.isSite(cur) && u.getHost() != null) urls.add("https://" + u.getHost() + "/"); } catch (Exception ignored) {}
        for (String u : urls) { String c = cm.getCookie(u); if (c != null && !c.trim().isEmpty()) cookies.put(u, c); }
        return cookies;
    }

    static void saveSessionInto(MainActivity a, String id, String name, Runnable rerender) {
        captureLocalStorage(a, local -> {
            try {
                JSONObject cookies = snapshotCookies(a);
                JSONArray arr = loadAccounts(a);
                JSONObject target = null;
                if (id != null) for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) { target = o; break; } }
                if (target == null) {
                    target = new JSONObject();
                    target.put("id", Long.toString(System.currentTimeMillis(), 36) + Integer.toString(RND.nextInt(1 << 20), 36));
                    target.put("name", name == null || name.trim().isEmpty() ? "Akun " + (arr.length() + 1) : name.trim());
                    arr.put(target);
                }
                target.put("cookies", cookies);
                target.put("local", local == null ? "{}" : local);
                target.put("savedAt", System.currentTimeMillis());
                storeAccounts(a, arr);
                a.prefs.edit().putString(ACTIVE_ACCOUNT, target.optString("id")).apply();
                Toast.makeText(a, "Sesi “" + target.optString("name") + "” tersimpan", Toast.LENGTH_SHORT).show();
                if (rerender != null) rerender.run();
            } catch (JSONException e) { Toast.makeText(a, "Gagal menyimpan sesi", Toast.LENGTH_SHORT).show(); }
        });
    }

    static void captureLocalStorage(MainActivity a, ValueCallback<String> done) {
        if (a.web == null || !MainActivity.isSite(a.web.getUrl())) { done.onReceiveValue("{}"); return; }
        a.web.evaluateJavascript("(()=>{try{const o={};for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);o[k]=localStorage.getItem(k);}return JSON.stringify(o);}catch(e){return '{}'}})()", r -> {
            String json = "{}";
            try { Object v = new org.json.JSONTokener(r == null ? "null" : r).nextValue(); if (v instanceof String) json = new JSONObject((String) v).toString(); } catch (Exception ignored) {}
            done.onReceiveValue(json);
        });
    }

    static void switchToAccount(MainActivity a, String id) {
        JSONObject target = null;
        JSONArray arr = loadAccounts(a);
        for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) { target = o; break; } }
        if (target == null) return;
        final JSONObject acc = target;
        String active = a.prefs.getString(ACTIVE_ACCOUNT, "");
        Runnable apply = () -> applySession(a, acc);
        // Keep the current account's latest state before overwriting the shared cookie jar.
        if (!active.isEmpty() && !active.equals(id) && hasSessionCookies()) captureLocalStorage(a, local -> {
            try { JSONObject cookies = snapshotCookies(a); updateAccount(a, active, o -> { o.put("cookies", cookies); o.put("local", local == null ? "{}" : local); o.put("savedAt", System.currentTimeMillis()); }); } catch (JSONException ignored) {}
            apply.run();
        });
        else apply.run();
    }

    static void applySession(MainActivity a, JSONObject acc) {
        CookieManager cm = CookieManager.getInstance();
        a.web.stopLoading();
        a.web.loadUrl("about:blank");
        cm.removeAllCookies(ok -> {
            WebStorage.getInstance().deleteAllData();
            JSONObject cookies = acc.optJSONObject("cookies");
            if (cookies != null) {
                Iterator<String> urls = cookies.keys();
                while (urls.hasNext()) {
                    String u = urls.next();
                    String host = Uri.parse(u).getHost() == null ? "" : Uri.parse(u).getHost();
                    String[] labels = host.split("\\.");
                    // Restore as domain cookies so every subdomain (api., accounts., …) sees the session.
                    String domain = labels.length >= 2 ? "; Domain=." + labels[labels.length - 2] + "." + labels[labels.length - 1] : "";
                    for (String pair : cookies.optString(u).split(";\\s*")) {
                        if (pair.indexOf('=') <= 0) continue;
                        // getCookie() drops attributes, so restored cookies get a long lifetime to survive process restarts.
                        cm.setCookie(u, pair + (pair.startsWith("__Host-") ? "" : domain) + "; Path=/; Max-Age=31536000; Secure", null);
                    }
                }
            }
            cm.flush();
            a.pendingLocalStorage = acc.optString("local", "{}");
            if (a.pendingLocalStorage.length() < 3) a.pendingLocalStorage = null;
            a.prefs.edit().putString(ACTIVE_ACCOUNT, acc.optString("id")).apply();
            a.web.clearHistory();
            a.web.loadUrl(MainActivity.HOME);
            Toast.makeText(a, "Beralih ke “" + acc.optString("name") + "”", Toast.LENGTH_SHORT).show();
        });
    }

    static void startFreshSession(MainActivity a) {
        String active = a.prefs.getString(ACTIVE_ACCOUNT, "");
        Runnable clear = () -> {
            a.web.stopLoading();
            CookieManager.getInstance().removeAllCookies(ok -> {
                WebStorage.getInstance().deleteAllData();
                CookieManager.getInstance().flush();
                a.prefs.edit().remove(ACTIVE_ACCOUNT).apply();
                a.web.clearHistory();
                a.web.loadUrl(MainActivity.HOME);
                Toast.makeText(a, "Login akun baru, lalu simpan sesinya di menu Akun", Toast.LENGTH_LONG).show();
            });
        };
        if (!active.isEmpty() && hasSessionCookies()) captureLocalStorage(a, local -> {
            try { JSONObject cookies = snapshotCookies(a); updateAccount(a, active, o -> { o.put("cookies", cookies); o.put("local", local == null ? "{}" : local); o.put("savedAt", System.currentTimeMillis()); }); } catch (JSONException ignored) {}
            clear.run();
        });
        else clear.run();
    }

    static void promptName(MainActivity a, String title, String initial, ValueCallback<String> done) {
        EditText input = new EditText(a);
        input.setText(initial); input.setHint("mis. Akun utama"); input.setSingleLine(); input.setSelection(input.getText().length());
        FrameLayout wrap = new FrameLayout(a); wrap.setPadding(dp(wrap, 20), dp(wrap, 8), dp(wrap, 20), 0); wrap.addView(input);
        new AlertDialog.Builder(a).setTitle(title).setView(wrap)
                .setPositiveButton("Simpan", (d, w) -> done.onReceiveValue(input.getText().toString()))
                .setNegativeButton("Batal", null).show();
    }
    static void confirm(MainActivity a, String title, String message, Runnable ok) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Lanjut", (d, w) -> ok.run()).setNegativeButton("Batal", null).show();
    }
}

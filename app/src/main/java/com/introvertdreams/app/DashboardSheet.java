package com.introvertdreams.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** SESI-style dashboard: status, counters, manual scan, per-video download, switches. */
public class DashboardSheet {
    static final int BG = Color.parseColor("#1a1a1f"), CARD = Color.parseColor("#24242b"), TEXT = Color.parseColor("#f4f4f5"),
            TEXT2 = Color.parseColor("#a8a8ad"), GREEN = Color.parseColor("#7dcf8f"), ORANGE = Color.parseColor("#d9b47a"), SILVER = Color.parseColor("#e6e6e8");

    static int dp(View v, int d) { return (int) (d * v.getResources().getDisplayMetrics().density); }

    static void show(MainActivity a) {
        BottomSheetDialog d = new BottomSheetDialog(a);
        ScrollView sv = new ScrollView(a); sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(sv, 16); root.setPadding(p, p, p, p); sv.addView(root);

        // header
        LinearLayout hdr = row(a); 
        TextView title = tv(a, "SESI MINI", 20, TEXT, true); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); hdr.addView(title, lp);
        TextView pill = tv(a, "", 12, GREEN, true); hdr.addView(pill); root.addView(hdr);
        TextView sub = tv(a, "Introvert Dreams · Dola companion", 12, TEXT2, false); root.addView(sub);
        boolean lic = a.isLicensed();
        pill.setText(lic ? "● Active" : "○ Belum aktif"); pill.setTextColor(lic ? GREEN : ORANGE);

        // counters
        LinearLayout counts = row(a); counts.setPadding(0, dp(sv, 14), 0, 0);
        LinearLayout found = counter(a, "Found"), saved = counter(a, "Saved");
        LinearLayout.LayoutParams w1 = weight(), w2 = weight(); w1.rightMargin = dp(sv, 4); w2.leftMargin = dp(sv, 4); counts.addView(found, w1); counts.addView(saved, w2); root.addView(counts);

        // actions
        LinearLayout actions = row(a);
        Button scan = btn(a, "Scan chat", true); LinearLayout.LayoutParams s1 = weight(); s1.rightMargin = dp(sv, 4); actions.addView(scan, s1);
        Button acct = btn(a, "Akun", false); LinearLayout.LayoutParams s2 = weight(); s2.leftMargin = dp(sv, 4); actions.addView(acct, s2);
        root.addView(actions, mt(sv, 12));
        acct.setOnClickListener(v -> { d.dismiss(); AccountsSheet.show(a); });
        TextView hint = tv(a, "", 12, TEXT2, false); root.addView(hint);

        // video list
        root.addView(groupTitle(a, "Video siap download"));
        LinearLayout list = card(a); root.addView(list);

        // Videos first seen since the dashboard was last opened get a BARU badge (SESI behaviour).
        Set<String> seenAtOpen = new HashSet<>(a.prefs.getStringSet("seenVideos", Collections.<String>emptySet()));
        Set<String> nowSeen = new HashSet<>(seenAtOpen);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            ArrayList<JSONObject> vs = new ArrayList<>(a.videos.values());
            Collections.reverse(vs); // newest first
            Map<String, String> states = a.downloadStates();
            int savedN = 0; boolean busy = false;
            for (JSONObject v : vs) { String st = states.get(v.optString("url")); if ("complete".equals(st)) savedN++; if ("in_progress".equals(st)) busy = true; }
            ((TextView) found.getTag()).setText(String.valueOf(vs.size())); ((TextView) saved.getTag()).setText(String.valueOf(savedN));
            if (vs.isEmpty()) { TextView e = tv(a, "Belum ada video. Buka chat yang berisi video lalu ketuk Scan chat.", 13, TEXT2, false); e.setPadding(dp(sv, 12), dp(sv, 14), dp(sv, 12), dp(sv, 14)); list.addView(e); return; }
            for (JSONObject v : vs) {
                String url = v.optString("url"); String key = v.optString("vid", url); nowSeen.add(key);
                boolean isNew = !seenAtOpen.contains(key);
                LinearLayout r = row(a); r.setPadding(dp(sv, 12), dp(sv, 10), dp(sv, 12), dp(sv, 10)); r.setGravity(Gravity.CENTER_VERTICAL);
                // thumbnail (native decode with Dola cookies); tap → pop-up player
                android.widget.FrameLayout thumbWrap = new android.widget.FrameLayout(a);
                android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable(); tbg.setColor(Color.parseColor("#33000000")); tbg.setCornerRadius(dp(r, 8)); thumbWrap.setBackground(tbg); thumbWrap.setClipToOutline(true);
                android.widget.ImageView thumb = new android.widget.ImageView(a); thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                thumbWrap.addView(thumb, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView play = tv(a, "▶", 16, Color.WHITE, true); play.setGravity(Gravity.CENTER); play.setShadowLayer(6, 0, 0, Color.BLACK);
                thumbWrap.addView(play, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (v.optInt("duration") > 0) { TextView dur = tv(a, fmtDur(v.optInt("duration")), 10, Color.WHITE, true); dur.setPadding(dp(r, 4), 0, dp(r, 4), 0); dur.setBackgroundColor(Color.parseColor("#99000000")); android.widget.FrameLayout.LayoutParams dl = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END); dl.setMargins(0, 0, dp(r, 3), dp(r, 3)); thumbWrap.addView(dur, dl); }
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(r, 72), dp(r, 48)); tlp.rightMargin = dp(r, 10);
                r.addView(thumbWrap, tlp);
                a.requestThumb(url, v.optString("poster", ""), bmp -> { if (bmp != null) thumb.setImageBitmap(bmp); });
                String name = v.optString("name", "video");
                thumbWrap.setOnClickListener(x -> a.showPlayer(url, name));
                LinearLayout meta = new LinearLayout(a); meta.setOrientation(LinearLayout.VERTICAL);
                String dim = v.optInt("width") > 0 ? v.optInt("width") + "×" + v.optInt("height") : v.optString("definition", "mp4");
                String st = states.get(url);
                String status = "complete".equals(st) ? "Saved · " + dim : "in_progress".equals(st) ? "Saving…" : "interrupted".equals(st) ? "Gagal · ketuk untuk ulangi" : dim;
                LinearLayout nameRow = row(a);
                if (isNew) { TextView badge = tv(a, "BARU", 9, BG, true); badge.setPadding(dp(r, 5), dp(r, 1), dp(r, 5), dp(r, 1)); android.graphics.drawable.GradientDrawable bb = new android.graphics.drawable.GradientDrawable(); bb.setColor(GREEN); bb.setCornerRadius(dp(r, 6)); badge.setBackground(bb); LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); bl.rightMargin = dp(r, 6); nameRow.addView(badge, bl); }
                TextView nm = tv(a, name, 13, TEXT, true); nm.setSingleLine(); nm.setEllipsize(android.text.TextUtils.TruncateAt.END); nameRow.addView(nm, weight());
                meta.addView(nameRow);
                meta.addView(tv(a, "#" + v.optInt("seq") + " · " + status, 11, "complete".equals(st) ? GREEN : "interrupted".equals(st) ? ORANGE : TEXT2, false));
                r.addView(meta, weight());
                Button dl = btn(a, "complete".equals(st) ? "✓ Ulangi" : "in_progress".equals(st) ? "…" : "⬇ Download", false);
                dl.setEnabled(!"in_progress".equals(st));
                dl.setOnClickListener(x -> { a.download(url, name); render[0].run(); }); r.addView(dl);
                list.addView(r);
            }
            // Poll DownloadManager while something is saving so Saving → Saved updates live.
            if (busy) list.postDelayed(() -> { if (d.isShowing()) render[0].run(); }, 1500);
        };
        render[0].run();
        d.setOnDismissListener(x -> a.prefs.edit().putStringSet("seenVideos", nowSeen).apply());
        scan.setOnClickListener(v -> { hint.setText("Memindai chat…"); a.scan(() -> { render[0].run(); hint.setText(a.videos.isEmpty() ? "Tidak ada video di halaman ini." : a.videos.size() + " video ditemukan"); }); });

        root.addView(groupTitle(a, "Lisensi"));
        LinearLayout lc = card(a); root.addView(lc);
        lc.addView(info(a, "Device ID", a.license.deviceId()));
        long exp = a.license.expiresAt();
        lc.addView(info(a, "Status", lic ? (a.license.licenseName().isEmpty() ? "Aktif" : "Aktif · " + a.license.licenseName()) + (exp > 0 ? " · s/d " + android.text.format.DateFormat.format("d MMM yyyy", exp) : " · lifetime") : "Belum aktif"));
        LinearLayout lrow = row(a); lrow.setPadding(dp(sv, 12), 0, dp(sv, 12), dp(sv, 12));
        Button lbtn = btn(a, lic ? "Verifikasi ulang" : "Aktivasi", !lic); lbtn.setOnClickListener(v -> { d.dismiss(); if (lic) a.license.request("verify", a.license.savedKey(), (ok, m) -> { android.widget.Toast.makeText(a, m.startsWith("!") ? m.substring(1) : (ok ? "Lisensi valid ✓" : m), android.widget.Toast.LENGTH_SHORT).show(); if (!ok && m.startsWith("!")) { a.license.clear(); a.onLicenseLost(m.substring(1)); } }); else a.requireLicense(); });
        lrow.addView(lbtn, weight()); lc.addView(lrow);

        // Update system: versions come from the "Updates" sheet, files from Google Drive.
        root.addView(groupTitle(a, "Update sistem"));
        LinearLayout uc = card(a); root.addView(uc);
        java.util.Map<String, TextView> verViews = new java.util.HashMap<>();
        for (String id : UpdateManager.ASSETS.keySet()) {
            LinearLayout r = row(a); r.setPadding(dp(sv, 12), dp(sv, 10), dp(sv, 12), dp(sv, 4));
            r.addView(tv(a, a.updates.label(id), 14, TEXT, true), weight());
            TextView ver = tv(a, a.updates.version(id) + (a.updates.hasUpdate(id) ? " · OTA" : ""), 13, TEXT2, false); verViews.put(id, ver); r.addView(ver);
            r.setOnLongClickListener(x -> { if (!a.updates.hasUpdate(id)) return false; a.updates.reset(id); ver.setText(a.updates.version(id)); android.widget.Toast.makeText(a, a.updates.label(id) + " dikembalikan ke bawaan APK", android.widget.Toast.LENGTH_SHORT).show(); return true; });
            uc.addView(r);
        }
        TextView uhint = tv(a, "", 11, TEXT2, false); uhint.setPadding(dp(sv, 12), dp(sv, 4), dp(sv, 12), 0); uc.addView(uhint);
        LinearLayout urow = row(a); urow.setPadding(dp(sv, 12), dp(sv, 8), dp(sv, 12), dp(sv, 12));
        Button ubtn = btn(a, "Cek & update", true);
        ubtn.setOnClickListener(v -> {
            if (!a.requireLicense()) return;
            ubtn.setEnabled(false); uhint.setText("Memeriksa versi terbaru…");
            a.updates.check(true, res -> {
                ubtn.setEnabled(true); int updated = 0; String err = null;
                for (java.util.Map.Entry<String, String> e : res.entrySet()) {
                    String st = e.getValue(); TextView ver = verViews.get(e.getKey());
                    if (st.startsWith("updated:")) { updated++; ver.setText(st.substring(8) + " · OTA"); ver.setTextColor(GREEN); }
                    else if (st.startsWith("error:")) { err = a.updates.label(e.getKey()) + ": " + st.substring(6); ver.setTextColor(ORANGE); }
                    else ver.setText(a.updates.version(e.getKey()) + (a.updates.hasUpdate(e.getKey()) ? " · OTA" : ""));
                }
                uhint.setTextColor(err != null ? ORANGE : TEXT2);
                uhint.setText(err != null ? err : updated == 0 ? "Semua sudah versi terbaru ✓" : updated + " komponen diperbarui. Muat ulang halaman Dola agar skrip baru aktif.");
                if (updated > 0) a.web.reload();
            });
        });
        urow.addView(ubtn, weight()); uc.addView(urow);

        root.addView(groupTitle(a, "Info"));
        LinearLayout g = card(a); root.addView(g);
        g.addView(info(a, "Sumber", "Tanpa watermark · kualitas tertinggi"));
        g.addView(info(a, "Folder", android.os.Build.VERSION.SDK_INT >= 29 ? "Download" : "Android/data/…/files/Download"));
        // credits
        root.addView(groupTitle(a, "Developer"));
        LinearLayout cred = card(a); root.addView(cred);
        LinearLayout wa = row(a); wa.setPadding(dp(sv, 12), dp(sv, 12), dp(sv, 12), dp(sv, 12)); wa.setClickable(true);
        android.widget.ImageView ic = new android.widget.ImageView(a); ic.setImageResource(R.drawable.ic_whatsapp);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(sv, 28), dp(sv, 28)); il.rightMargin = dp(sv, 12); wa.addView(ic, il);
        LinearLayout cm = new LinearLayout(a); cm.setOrientation(LinearLayout.VERTICAL);
        cm.addView(tv(a, "Whempy & Dhon – Introvert Dreams", 14, TEXT, true)); cm.addView(tv(a, "+62 888-984-1098 · WhatsApp", 12, TEXT2, false));
        wa.addView(cm, weight());
        wa.setOnClickListener(v -> { try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/628889841098"))); } catch (Exception ignored) {} });
        cred.addView(wa);

        d.setContentView(sv); d.show();
    }

    static String fmtDur(int sec) { return sec >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60) : String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60); }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    static LinearLayout.LayoutParams mt(View v, int t) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); l.topMargin = dp(v, t); return l; }
    static LinearLayout row(MainActivity a) { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static TextView tv(MainActivity a, String s, int sp, int color, boolean bold) { TextView t = new TextView(a); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    static TextView groupTitle(MainActivity a, String s) { TextView t = tv(a, s.toUpperCase(), 12, TEXT2, false); t.setPadding(dp(t, 12), dp(t, 16), 0, dp(t, 6)); return t; }
    static LinearLayout card(MainActivity a) { LinearLayout c = new LinearLayout(a); c.setOrientation(LinearLayout.VERTICAL); android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(c, 14)); c.setBackground(bg); return c; }
    static Button btn(MainActivity a, String s, boolean primary) { Button b = new Button(a); b.setText(s); b.setAllCaps(false); b.setTextColor(primary ? BG : TEXT); android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(primary ? SILVER : Color.TRANSPARENT); bg.setStroke(dp(b, 1), Color.parseColor("#33ffffff")); bg.setCornerRadius(dp(b, 12)); b.setBackground(bg); return b; }
    static LinearLayout counter(MainActivity a, String label) { LinearLayout c = card(a); c.setGravity(Gravity.CENTER); c.setPadding(0, dp(c, 8), 0, dp(c, 8)); TextView n = tv(a, "0", 19, TEXT, true); c.addView(n); c.addView(tv(a, label, 11, TEXT2, false)); c.setTag(n); return c; }
    static View info(MainActivity a, String k, String v) { LinearLayout r = row(a); r.setPadding(dp(r, 12), dp(r, 12), dp(r, 12), dp(r, 12)); r.addView(tv(a, k, 15, TEXT, true), weight()); r.addView(tv(a, v, 14, TEXT2, false)); return r; }
}

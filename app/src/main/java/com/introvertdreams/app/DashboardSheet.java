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
        pill.setText("● Active"); pill.setTextColor(GREEN);

        // counters
        LinearLayout counts = row(a); counts.setPadding(0, dp(sv, 14), 0, 0);
        LinearLayout found = counter(a, "Found"), saved = counter(a, "Saved");
        LinearLayout.LayoutParams w1 = weight(), w2 = weight(); w1.rightMargin = dp(sv, 4); w2.leftMargin = dp(sv, 4); counts.addView(found, w1); counts.addView(saved, w2); root.addView(counts);

        // actions
        Button scan = btn(a, "Scan chat", true); root.addView(scan, mt(sv, 12));
        TextView hint = tv(a, "", 12, TEXT2, false); root.addView(hint);

        // video list
        root.addView(groupTitle(a, "Video siap download"));
        LinearLayout list = card(a); root.addView(list);

        Runnable render = () -> {
            list.removeAllViews();
            ArrayList<JSONObject> vs = new ArrayList<>(a.videos.values());
            int savedN = 0; for (JSONObject v : vs) if (v.optBoolean("saved")) savedN++;
            ((TextView) found.getTag()).setText(String.valueOf(vs.size())); ((TextView) saved.getTag()).setText(String.valueOf(savedN));
            if (vs.isEmpty()) { TextView e = tv(a, "Belum ada video. Buka chat yang berisi video lalu ketuk Scan chat.", 13, TEXT2, false); e.setPadding(dp(sv, 12), dp(sv, 14), dp(sv, 12), dp(sv, 14)); list.addView(e); return; }
            int i = 0;
            for (JSONObject v : vs) {
                LinearLayout r = row(a); r.setPadding(dp(sv, 12), dp(sv, 10), dp(sv, 12), dp(sv, 10)); r.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout meta = new LinearLayout(a); meta.setOrientation(LinearLayout.VERTICAL);
                String name = v.optString("name", "video_" + (++i));
                String dim = v.optInt("width") > 0 ? v.optInt("width") + "×" + v.optInt("height") : v.optString("definition", "mp4");
                meta.addView(tv(a, name, 13, TEXT, true)); meta.addView(tv(a, v.optBoolean("saved") ? "Tersimpan" : dim, 11, TEXT2, false));
                r.addView(meta, weight());
                Button dl = btn(a, v.optBoolean("saved") ? "✓ Ulangi" : "⬇ Download", false);
                dl.setOnClickListener(x -> a.download(v.optString("url"), name)); r.addView(dl);
                list.addView(r);
            }
        };
        render.run();
        scan.setOnClickListener(v -> { hint.setText("Memindai chat…"); a.scan(() -> { render.run(); hint.setText(a.videos.isEmpty() ? "Tidak ada video di halaman ini." : a.videos.size() + " video ditemukan"); }); });

        root.addView(groupTitle(a, "Info"));
        LinearLayout g = card(a); root.addView(g);
        g.addView(info(a, "Sumber", "Kualitas tertinggi yang tersedia"));
        g.addView(info(a, "Folder", "Download/IntrovertDreams"));
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

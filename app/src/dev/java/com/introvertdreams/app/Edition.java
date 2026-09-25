package com.introvertdreams.app;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Developer edition: agent entry points + real-time problem banner. */
final class Edition {
    static long lastBanner;
    static void openAgent(MainActivity a) { a.startActivity(new Intent(a, AgentActivity.class)); }
    /** Dashboard "Diagnosa sekarang": snapshot → agent answers what is wrong and what to do. */
    static void diagnose(MainActivity a) {
        a.snapshot(snap -> a.startActivity(new Intent(a, AgentActivity.class).putExtra("context", snap.toString()).putExtra("prompt", "Diagnosa keadaan aplikasi SEKARANG berdasarkan konteks real-time. Apa masalahnya (jika ada), penyebabnya, dan apa yang harus saya lakukan? Jika perlu perubahan MD/skrip, buat draft.")));
    }
    /** Called on every diagnostic event. Live agent (if open) gets it immediately; otherwise show a tap-to-ask banner. */
    static void onEvent(MainActivity a, JSONObject e) {
        a.runOnUiThread(() -> {
            if (AgentActivity.live != null) { AgentActivity.live.onLiveEvent(e); return; }
            if (System.currentTimeMillis() - lastBanner < 15000) return; lastBanner = System.currentTimeMillis();
            android.view.ViewGroup root = (android.view.ViewGroup) a.findViewById(android.R.id.content);
            TextView b = new TextView(a); b.setText("⚠ " + e.optString("component") + ": " + e.optString("event") + "  ·  Tanya agent ›"); b.setTextColor(0xFF1A1A1F); b.setTextSize(12); b.setPadding(28, 18, 28, 18); b.setMaxLines(2);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(0xFFD9B47A); bg.setCornerRadius(40); b.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL); lp.topMargin = 90; lp.leftMargin = lp.rightMargin = 60;
            b.setOnClickListener(v -> { root.removeView(b); a.snapshot(snap -> a.startActivity(new Intent(a, AgentActivity.class).putExtra("context", snap.toString()).putExtra("prompt", "Masalah real-time terdeteksi: " + e.optString("component") + " → " + e.optString("event") + "\nDetail: " + e.optString("detail") + "\nAnalisis akar masalahnya dan tentukan apa yang harus saya lakukan. Jika perlu perubahan MD/skrip, buat draft."))); });
            root.addView(b, lp); b.postDelayed(() -> { if (b.getParent() != null) root.removeView(b); }, 12000);
        });
    }
}

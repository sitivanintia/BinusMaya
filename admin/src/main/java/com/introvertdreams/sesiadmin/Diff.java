package com.introvertdreams.sesiadmin;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

import java.util.ArrayList;
import java.util.List;

/** Line-based LCS diff rendered as a unified-style colored view (context collapsed to 3 lines). */
class Diff {
    static SpannableStringBuilder render(String a, String b) {
        String[] x = a.split("\n", -1), y = b.split("\n", -1);
        // Cap work for very large files: LCS is O(n*m) memory; fall back to a naive prefix/suffix diff.
        List<String[]> ops = x.length * (long) y.length > 4_000_000L ? naive(x, y) : lcs(x, y);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int changes = 0; for (String[] o : ops) if (!o[0].equals(" ")) changes++;
        if (changes == 0) { sb.append("Tidak ada perubahan baris."); return sb; }
        for (int i = 0; i < ops.size(); i++) {
            String[] o = ops.get(i);
            if (o[0].equals(" ")) {
                boolean near = false; for (int k = Math.max(0, i - 3); k <= Math.min(ops.size() - 1, i + 3); k++) if (!ops.get(k)[0].equals(" ")) near = true;
                if (!near) { if (i > 0 && !ops.get(i - 1)[0].equals("…")) { int s = sb.length(); sb.append("  …\n"); sb.setSpan(new ForegroundColorSpan(0xFF6E6E76), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); ops.set(i, new String[]{ "…", "" }); } else ops.set(i, new String[]{ "…", "" }); continue; }
                int s = sb.length(); sb.append("  ").append(o[1]).append('\n'); sb.setSpan(new ForegroundColorSpan(0xFF9A9AA2), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                int s = sb.length(); sb.append(o[0]).append(' ').append(o[1]).append('\n');
                boolean add = o[0].equals("+");
                sb.setSpan(new BackgroundColorSpan(add ? 0x2E7DCF8F : 0x2EE07A7A), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(add ? 0xFF9FE3AE : 0xFFF0A0A0), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        sb.insert(0, changes + " baris berubah\n\n");
        return sb;
    }
    static List<String[]> lcs(String[] x, String[] y) {
        int n = x.length, m = y.length; short[][] L = new short[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--) L[i][j] = (short) Math.min(Short.MAX_VALUE, x[i].equals(y[j]) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]));
        List<String[]> out = new ArrayList<>(); int i = 0, j = 0;
        while (i < n && j < m) { if (x[i].equals(y[j])) { out.add(new String[]{ " ", x[i] }); i++; j++; } else if (L[i + 1][j] >= L[i][j + 1]) out.add(new String[]{ "-", x[i++] }); else out.add(new String[]{ "+", y[j++] }); }
        while (i < n) out.add(new String[]{ "-", x[i++] }); while (j < m) out.add(new String[]{ "+", y[j++] });
        return out;
    }
    static List<String[]> naive(String[] x, String[] y) {
        int p = 0; while (p < x.length && p < y.length && x[p].equals(y[p])) p++;
        int s = 0; while (s < x.length - p && s < y.length - p && x[x.length - 1 - s].equals(y[y.length - 1 - s])) s++;
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < p; i++) out.add(new String[]{ " ", x[i] });
        for (int i = p; i < x.length - s; i++) out.add(new String[]{ "-", x[i] });
        for (int i = p; i < y.length - s; i++) out.add(new String[]{ "+", y[i] });
        for (int i = x.length - s; i < x.length; i++) out.add(new String[]{ " ", x[i] });
        return out;
    }
}

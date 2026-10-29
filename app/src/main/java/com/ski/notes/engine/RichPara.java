package com.ski.notes.engine;

import java.util.ArrayList;
import java.util.List;

/** A formatted paragraph: the common currency between Word files and typed text boxes. */
public final class RichPara {
    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    public final StringBuilder text = new StringBuilder();
    public final List<TextBox.Run> runs = new ArrayList<>();
    /** 0 = body, 1..3 = heading level, -1 = title. */
    public int heading;
    public boolean bullet;
    public int align = ALIGN_LEFT;
    /** A page break comes before this paragraph. */
    public boolean pageBreakBefore;
    /** Embedded picture (PNG/JPEG bytes) shown as its own paragraph, or null. */
    public byte[] image;
    public int imageW, imageH; // EMU-derived size in points, 0 if unknown

    public void append(String s, int flags, int color, float scale) {
        if (s.isEmpty()) return;
        int start = text.length();
        text.append(s);
        if (flags != 0 || color != 0 || scale != 1f) runs.add(new TextBox.Run(start, text.length(), flags, color, scale));
    }

    public String plain() {
        return text.toString();
    }

    /** Size scale a heading level implies. */
    public static float headingScale(int level) {
        switch (level) {
            case -1: return 2.2f;
            case 1: return 1.8f;
            case 2: return 1.45f;
            case 3: return 1.2f;
            default: return 1f;
        }
    }

    /**
     * Splits a text box into paragraphs (one per line of its text), keeping each paragraph's runs. Lines
     * starting with "• " become bullets.
     */
    public static List<RichPara> fromTextBox(TextBox t) {
        List<RichPara> out = new ArrayList<>();
        String s = t.text;
        int lineStart = 0;
        while (lineStart <= s.length()) {
            int nl = s.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? s.length() : nl;
            RichPara p = new RichPara();
            int contentStart = lineStart;
            if (s.startsWith("• ", lineStart)) {
                p.bullet = true;
                contentStart += 2;
            }
            p.text.append(s, contentStart, lineEnd);
            for (TextBox.Run r : t.runs) {
                int a = Math.max(r.start, contentStart), b = Math.min(r.end, lineEnd);
                if (a < b) p.runs.add(new TextBox.Run(a - contentStart, b - contentStart, r.flags, r.color, r.scale));
            }
            out.add(p);
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        return out;
    }
}

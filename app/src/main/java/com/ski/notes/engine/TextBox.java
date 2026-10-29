package com.ski.notes.engine;

import java.util.Arrays;

/**
 * A block of typed text placed on a page. Immutable: every edit makes a new box, which keeps undo simple.
 * Formatting is stored as style runs over character ranges.
 */
public final class TextBox {
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int UNDERLINE = 4;
    public static final int STRIKE = 8;

    /** A formatting run: [start, end) with style flags, a colour (0 = box colour) and a size scale. */
    public static final class Run {
        public final int start, end, flags, color;
        public final float scale;

        public Run(int start, int end, int flags, int color, float scale) {
            this.start = start;
            this.end = end;
            this.flags = flags;
            this.color = color;
            this.scale = scale;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Run)) return false;
            Run r = (Run) o;
            return r.start == start && r.end == end && r.flags == flags && r.color == color && r.scale == scale;
        }

        @Override
        public int hashCode() {
            return ((start * 31 + end) * 31 + flags) * 31 + color;
        }
    }

    public final float x, y, w;
    /** Base text size in page units. */
    public final float size;
    public final int color;
    public final String text;
    public final Run[] runs;

    public TextBox(float x, float y, float w, float size, int color, String text, Run[] runs) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.size = size;
        this.color = color;
        this.text = text == null ? "" : text;
        this.runs = runs == null ? new Run[0] : runs;
    }

    public TextBox moved(float nx, float ny) {
        return new TextBox(nx, ny, w, size, color, text, runs);
    }

    public TextBox withWidth(float nw) {
        return new TextBox(x, y, nw, size, color, text, runs);
    }

    public TextBox withContent(String t, Run[] r) {
        return new TextBox(x, y, w, size, color, t, r);
    }

    public TextBox withStyle(float nsize, int ncolor) {
        return new TextBox(x, y, w, nsize, ncolor, text, runs);
    }

    public boolean isBlank() {
        return text.trim().isEmpty();
    }

    public boolean sameAs(TextBox o) {
        return o != null && o.x == x && o.y == y && o.w == w && o.size == size && o.color == color
            && o.text.equals(text) && Arrays.equals(o.runs, runs);
    }
}

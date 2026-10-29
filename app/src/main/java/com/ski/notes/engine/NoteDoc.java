package com.ski.notes.engine;

import java.util.ArrayList;

/** A note: a stack of pages, each holding strokes and typed text in page coordinates. */
public final class NoteDoc {
    /** Page width in page units (A4 at 150 dpi). Every page has this width; height can vary (PDF pages). */
    public static final float PAGE_W = 1240f;
    public static final float PAGE_H = 1754f;

    public static final int PAPER_BLANK = 0;
    public static final int PAPER_LINED = 1;
    public static final int PAPER_GRID = 2;
    public static final int PAPER_DOTS = 3;
    public static final int PAPER_COUNT = 4;

    public static final int TONE_WHITE = 0;
    public static final int TONE_CREAM = 1;
    public static final int TONE_DARK = 2;
    public static final int TONE_COUNT = 3;

    public static final class Page {
        public final ArrayList<Stroke> strokes = new ArrayList<>();
        public final ArrayList<TextBox> texts = new ArrayList<>();
        public float height = PAGE_H;
        /** Page of the note's attached PDF drawn underneath, or -1 for plain paper. */
        public int pdfPage = -1;

        public Page() {
        }

        public Page(float height, int pdfPage) {
            this.height = height;
            this.pdfPage = pdfPage;
        }

        public boolean isEmpty() {
            return strokes.isEmpty() && texts.isEmpty() && pdfPage < 0;
        }

        /** Shallow copy (strokes and text boxes are never changed in place, so they can be shared). */
        public Page copy() {
            Page p = new Page(height, pdfPage);
            p.strokes.addAll(strokes);
            p.texts.addAll(texts);
            return p;
        }
    }

    public String id;
    public String title = "";
    public long created;
    public long modified;
    public int paper = PAPER_LINED;
    public int tone = TONE_WHITE;
    /** True when a PDF is attached to this note (stored next to the note file). */
    public boolean hasPdf;
    public final ArrayList<Page> pages = new ArrayList<>();

    public NoteDoc(String id) {
        this.id = id;
        created = modified = System.currentTimeMillis();
    }

    public static NoteDoc blank(String id) {
        NoteDoc d = new NoteDoc(id);
        d.pages.add(new Page());
        return d;
    }

    public int strokeCount() {
        int c = 0;
        for (Page pg : pages) c += pg.strokes.size();
        return c;
    }

    /** True if nothing has been written, typed or imported. */
    public boolean isEmpty() {
        for (Page pg : pages) if (!pg.isEmpty()) return false;
        return true;
    }

    /** True if the last page has content (so a fresh page can be appended for continued writing). */
    public boolean lastPageUsed() {
        return !pages.isEmpty() && !pages.get(pages.size() - 1).isEmpty();
    }

    /** All typed text in reading order, for search. */
    public String allText(int limit) {
        StringBuilder sb = new StringBuilder();
        for (Page pg : pages) {
            for (TextBox t : pg.texts) {
                if (sb.length() >= limit) return sb.substring(0, limit);
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.text);
            }
        }
        return sb.length() > limit ? sb.substring(0, limit) : sb.toString();
    }
}

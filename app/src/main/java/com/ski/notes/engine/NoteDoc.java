package com.ski.notes.engine;

import java.util.ArrayList;

/** A note: a stack of fixed-size pages, each holding strokes in page coordinates. */
public final class NoteDoc {
    /** Page size in page units (A4 at 150 dpi). */
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
    }

    public String id;
    public String title = "";
    public long created;
    public long modified;
    public int paper = PAPER_LINED;
    public int tone = TONE_WHITE;
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

    /** True if the last page has ink on it (so a fresh page can be appended for continued writing). */
    public boolean lastPageUsed() {
        return !pages.isEmpty() && !pages.get(pages.size() - 1).strokes.isEmpty();
    }
}

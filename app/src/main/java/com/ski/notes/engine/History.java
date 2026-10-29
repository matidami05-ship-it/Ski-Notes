package com.ski.notes.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Undo / redo. Every change is "on page P, remove these strokes and add those" plus, optionally, "the
 * page's typed text went from list A to list B". That covers drawing, erasing, moving, recolouring,
 * typing and deleting.
 */
public final class History {
    public static final class Edit {
        final int page;
        final int[] removedIdx; // ascending, indices before the edit
        final Stroke[] removed;
        final Stroke[] added;
        final TextBox[] textsBefore; // null = text not touched
        final TextBox[] textsAfter;

        Edit(int page, int[] removedIdx, Stroke[] removed, Stroke[] added, TextBox[] textsBefore, TextBox[] textsAfter) {
            this.page = page;
            this.removedIdx = removedIdx;
            this.removed = removed;
            this.added = added;
            this.textsBefore = textsBefore;
            this.textsAfter = textsAfter;
        }
    }

    private final NoteDoc doc;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>();
    private final ArrayDeque<Edit> redo = new ArrayDeque<>();
    private final int limit;

    public History(NoteDoc doc, int limit) {
        this.doc = doc;
        this.limit = limit;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Applies and records: remove strokes at the given indices of a page, then append the added strokes. */
    public void apply(int page, List<Integer> removeIndices, List<Stroke> added) {
        apply(page, removeIndices, added, null);
    }

    /** As above, and also replaces the page's typed text with {@code textsAfter} (null = leave text alone). */
    public void apply(int page, List<Integer> removeIndices, List<Stroke> added, List<TextBox> textsAfter) {
        List<Stroke> strokes = doc.pages.get(page).strokes;
        int[] idx = new int[removeIndices == null ? 0 : removeIndices.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = removeIndices.get(i);
        Arrays.sort(idx);
        Stroke[] removed = new Stroke[idx.length];
        for (int i = 0; i < idx.length; i++) removed[i] = strokes.get(idx[i]);
        Stroke[] add = added == null ? new Stroke[0] : added.toArray(new Stroke[0]);
        TextBox[] before = null, after = null;
        if (textsAfter != null) {
            before = doc.pages.get(page).texts.toArray(new TextBox[0]);
            after = textsAfter.toArray(new TextBox[0]);
            if (Arrays.equals(before, after)) before = after = null;
        }
        if (removed.length == 0 && add.length == 0 && after == null) return;
        Edit e = new Edit(page, idx, removed, add, before, after);
        doForward(e);
        undo.push(e);
        while (undo.size() > limit) undo.removeLast();
        redo.clear();
    }

    /** Replaces a page's typed text (one undo step). */
    public void setTexts(int page, List<TextBox> after) {
        apply(page, null, null, after);
    }

    /** Convenience for a single new stroke. */
    public void add(int page, Stroke s) {
        List<Stroke> one = new ArrayList<>(1);
        one.add(s);
        apply(page, null, one);
    }

    /** Returns the page that changed, or -1. */
    public int undo() {
        Edit e = undo.poll();
        if (e == null) return -1;
        doBackward(e);
        redo.push(e);
        return e.page;
    }

    public int redo() {
        Edit e = redo.poll();
        if (e == null) return -1;
        doForward(e);
        undo.push(e);
        return e.page;
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }

    private void doForward(Edit e) {
        NoteDoc.Page pg = doc.pages.get(e.page);
        List<Stroke> strokes = pg.strokes;
        for (int i = e.removedIdx.length - 1; i >= 0; i--) strokes.remove(e.removedIdx[i]);
        strokes.addAll(Arrays.asList(e.added));
        if (e.textsAfter != null) {
            pg.texts.clear();
            pg.texts.addAll(Arrays.asList(e.textsAfter));
        }
    }

    private void doBackward(Edit e) {
        NoteDoc.Page pg = doc.pages.get(e.page);
        List<Stroke> strokes = pg.strokes;
        for (int i = 0; i < e.added.length; i++) strokes.remove(strokes.size() - 1);
        for (int i = 0; i < e.removedIdx.length; i++) strokes.add(e.removedIdx[i], e.removed[i]);
        if (e.textsBefore != null) {
            pg.texts.clear();
            pg.texts.addAll(Arrays.asList(e.textsBefore));
        }
    }
}

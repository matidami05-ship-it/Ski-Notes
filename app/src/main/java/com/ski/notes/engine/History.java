package com.ski.notes.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Undo / redo. Every change is "on page P, remove these strokes and add those", which covers drawing,
 * erasing, cutting, moving, recolouring and deleting.
 */
public final class History {
    public static final class Edit {
        final int page;
        final int[] removedIdx; // ascending, indices before the edit
        final Stroke[] removed;
        final Stroke[] added;

        Edit(int page, int[] removedIdx, Stroke[] removed, Stroke[] added) {
            this.page = page;
            this.removedIdx = removedIdx;
            this.removed = removed;
            this.added = added;
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
        List<Stroke> strokes = doc.pages.get(page).strokes;
        int[] idx = new int[removeIndices == null ? 0 : removeIndices.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = removeIndices.get(i);
        Arrays.sort(idx);
        Stroke[] removed = new Stroke[idx.length];
        for (int i = 0; i < idx.length; i++) removed[i] = strokes.get(idx[i]);
        Stroke[] add = added == null ? new Stroke[0] : added.toArray(new Stroke[0]);
        if (removed.length == 0 && add.length == 0) return;
        Edit e = new Edit(page, idx, removed, add);
        doForward(e);
        undo.push(e);
        while (undo.size() > limit) undo.removeLast();
        redo.clear();
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
        List<Stroke> strokes = doc.pages.get(e.page).strokes;
        for (int i = e.removedIdx.length - 1; i >= 0; i--) strokes.remove(e.removedIdx[i]);
        strokes.addAll(Arrays.asList(e.added));
    }

    private void doBackward(Edit e) {
        List<Stroke> strokes = doc.pages.get(e.page).strokes;
        for (int i = 0; i < e.added.length; i++) strokes.remove(strokes.size() - 1);
        for (int i = 0; i < e.removedIdx.length; i++) strokes.add(e.removedIdx[i], e.removed[i]);
    }
}

package com.ski.notes.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class EngineTest {

    private static Stroke line(float x0, float y0, float x1, float y1) {
        Stroke s = new Stroke(Stroke.FOUNTAIN, 0xFF000000, 6f);
        s.setLine(x0, y0, x1, y1, 0.6f);
        return s;
    }

    // ---- Ruler ----

    @Test
    public void rulerSnapsHorizontalEdge() {
        Ruler r = new Ruler(500, 500, 1000, 100);
        assertEquals(1, r.edgeFor(300, 552, 20));   // just below lower edge (v = +50)
        assertEquals(-1, r.edgeFor(300, 445, 20));  // just above upper edge
        assertEquals(0, r.edgeFor(300, 700, 20));   // far away
        assertEquals(1, r.edgeFor(300, 530, 20));   // on the body, nearer the lower edge
        float[] out = new float[2];
        r.snap(321, 560, 1, 0, out);
        assertEquals(321, out[0], 1e-3);
        assertEquals(550, out[1], 1e-3);
    }

    @Test
    public void rulerSnapsRotatedEdge() {
        Ruler r = new Ruler(0, 0, 1000, 80);
        r.rotateAbout(0, 0, (float) Math.toRadians(30));
        float[] out = new float[2];
        // A point off the edge projects onto the edge line: v must equal +40 exactly.
        r.snap(100, 150, 1, 0, out);
        assertEquals(40, r.toV(out[0], out[1]), 1e-3);
        assertEquals(r.toU(100, 150), r.toU(out[0], out[1]), 1e-3);
        assertEquals(30, r.readoutDegrees());
    }

    @Test
    public void rulerAngleSnap() {
        Ruler r = new Ruler(0, 0, 100, 10);
        r.angle = (float) Math.toRadians(44.2);
        assertTrue(r.snapAngle(1.5f));
        assertEquals(45.0, Math.toDegrees(r.angle), 1e-3);
        r.angle = (float) Math.toRadians(37);
        assertFalse(r.snapAngle(1.5f));
        r.angle = (float) Math.toRadians(170);
        assertEquals(10, r.readoutDegrees());
    }

    @Test
    public void rulerIgnoresPastEnds() {
        Ruler r = new Ruler(500, 500, 400, 60);
        assertEquals(0, r.edgeFor(900, 532, 20)); // beyond the right end
    }

    // ---- Straight lines ----

    @Test
    public void lineSnapsToHorizontalAndDiagonal() {
        float[] out = new float[2];
        LineAssist.snapEnd(0, 0, 100, 4, 5, out);
        assertEquals(0, out[1], 1e-3);
        assertEquals(Math.hypot(100, 4), out[0], 1e-3);
        LineAssist.snapEnd(0, 0, 100, 97, 5, out);
        assertEquals(out[0], out[1], 1e-3);
        LineAssist.snapEnd(0, 0, 100, 40, 5, out); // ~21.8°, left alone
        assertEquals(40, out[1], 1e-3);
    }

    @Test
    public void holdDetector() {
        LineAssist.HoldDetector h = new LineAssist.HoldDetector(5, 400);
        h.feed(0, 0, 0);
        h.feed(2, 1, 200);
        assertFalse(h.held(300));
        assertTrue(h.held(450));
        h.feed(50, 0, 460); // moved: timer restarts
        assertFalse(h.held(500));
    }

    @Test
    public void setLineIsDenseAndStraight() {
        Stroke s = line(0, 0, 100, 0);
        assertTrue(s.n >= 25);
        for (int i = 0; i < s.n; i++) assertEquals(0, s.y[i], 1e-4);
        assertEquals(100, s.x[s.n - 1], 1e-4);
    }

    // ---- Eraser ----

    @Test
    public void eraserHitsAndMisses() {
        Stroke s = line(0, 0, 200, 0);
        assertTrue(Eraser.hits(s, 100, 5, 4));
        assertFalse(Eraser.hits(s, 100, 40, 4));
        assertTrue(Eraser.hitsSwept(s, 100, -50, 100, 50, 3));
    }

    @Test
    public void areaEraserCutsInTwo() {
        Stroke s = line(0, 0, 200, 0);
        List<Stroke> parts = Eraser.cut(s, 100, 0, 10);
        assertNotNull(parts);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).maxX < 100);
        assertTrue(parts.get(1).minX > 100);
        assertNull(Eraser.cut(s, 100, 80, 10));
        List<Stroke> gone = Eraser.cut(line(0, 0, 4, 0), 2, 0, 20);
        assertNotNull(gone);
        assertEquals(0, gone.size());
    }

    // ---- Lasso ----

    @Test
    public void lassoSelectsEnclosedStrokes() {
        List<Stroke> strokes = Arrays.asList(line(10, 10, 40, 10), line(200, 200, 260, 200), line(50, 90, 150, 90));
        float[] box = {0, 0, 100, 0, 100, 100, 0, 100};
        List<Integer> sel = Lasso.select(strokes, box, 4);
        assertEquals(Arrays.asList(0, 2), sel); // stroke 2 is half inside
        float[] b = Lasso.bounds(Arrays.asList(strokes.get(0)));
        assertTrue(b[0] < 10 && b[2] > 40);
    }

    // ---- Outline ----

    @Test
    public void outlineWindingIsConsistent() {
        Stroke s = new Stroke(Stroke.FOUNTAIN, 0, 8);
        for (int i = 0; i < 60; i++) {
            double a = i / 10.0;
            s.add((float) (100 + Math.cos(a) * 50), (float) (100 + Math.sin(a) * 30), (float) (0.3 + 0.5 * Math.abs(Math.sin(a))));
        }
        final int[] count = {0};
        final boolean[] mixed = {false};
        final float[] sign = {0};
        Outline.build(s, (pts, n) -> {
            float a = Outline.signedArea(pts, n);
            if (Math.abs(a) < 1e-4f) return;
            if (sign[0] == 0) sign[0] = Math.signum(a);
            else if (Math.signum(a) != sign[0]) mixed[0] = true;
            count[0]++;
        });
        assertTrue(count[0] > 50);
        assertFalse(mixed[0]);
    }

    @Test
    public void incrementalMatchesFullBuild() {
        Stroke full = new Stroke(Stroke.CALLIGRAPHY, 0, 10);
        for (int i = 0; i < 80; i++) full.add(i * 3f, (float) Math.sin(i / 6.0) * 40f, 0.2f + (i % 7) / 10f);
        final List<Float> a = new ArrayList<>();
        Outline.build(full, (pts, n) -> { for (int k = 0; k < 2 * n; k++) a.add(pts[k]); });
        Stroke live = new Stroke(Stroke.CALLIGRAPHY, 0, 10);
        final List<Float> b = new ArrayList<>();
        Outline.Incremental inc = new Outline.Incremental(live, (pts, n) -> { for (int k = 0; k < 2 * n; k++) b.add(pts[k]); });
        for (int i = 0; i < full.n; i++) {
            live.add(full.x[i], full.y[i], full.p[i]);
            inc.update();
        }
        inc.finish();
        assertEquals(a, b);
    }

    @Test
    public void outlineSinglePointIsDot() {
        Stroke s = new Stroke(Stroke.BALLPOINT, 0, 6);
        s.add(5, 5, 0.5f);
        final int[] polys = {0};
        Outline.build(s, (pts, n) -> polys[0]++);
        assertEquals(1, polys[0]);
    }

    @Test
    public void pressureChangesFountainWidth() {
        float thin = Outline.halfWidth(Stroke.FOUNTAIN, 10, 0.1f, 0);
        float thick = Outline.halfWidth(Stroke.FOUNTAIN, 10, 1f, 0);
        assertTrue(thick > thin * 2.5f);
        float across = Outline.halfWidth(Stroke.CALLIGRAPHY, 10, 0.5f, Math.toRadians(45));
        float along = Outline.halfWidth(Stroke.CALLIGRAPHY, 10, 0.5f, Math.toRadians(-45));
        assertTrue(across > along * 3);
    }

    // ---- History ----

    @Test
    public void undoRedoKeepsOrder() {
        NoteDoc d = NoteDoc.blank("x");
        History h = new History(d, 50);
        Stroke a = line(0, 0, 1, 1), b = line(2, 2, 3, 3), c = line(4, 4, 5, 5);
        h.add(0, a);
        h.add(0, b);
        h.add(0, c);
        // Erase b, replacing it with two pieces.
        Stroke b1 = line(2, 2, 2.4f, 2.4f), b2 = line(2.6f, 2.6f, 3, 3);
        h.apply(0, Arrays.asList(1), Arrays.asList(b1, b2));
        List<Stroke> st = d.pages.get(0).strokes;
        assertEquals(Arrays.asList(a, c, b1, b2), st);
        h.undo();
        assertEquals(Arrays.asList(a, b, c), st);
        h.redo();
        assertEquals(Arrays.asList(a, c, b1, b2), st);
        h.undo();
        h.undo();
        assertEquals(Arrays.asList(a, b), st);
        assertTrue(h.canRedo());
        h.add(0, line(9, 9, 10, 10));
        assertFalse(h.canRedo());
    }

    // ---- File format ----

    @Test
    public void codecRoundTrip() throws Exception {
        NoteDoc d = NoteDoc.blank("abc");
        d.title = "Meeting ✏️";
        d.paper = NoteDoc.PAPER_GRID;
        d.tone = NoteDoc.TONE_DARK;
        d.pages.add(new NoteDoc.Page());
        Stroke s = new Stroke(Stroke.CALLIGRAPHY, 0xFF2255AA, 7.5f);
        s.add(1, 2, 0.3f);
        s.add(3, 4, 0.9f);
        d.pages.get(1).strokes.add(s);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NoteCodec.write(d, bos);
        NoteDoc r = NoteCodec.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals("Meeting ✏️", r.title);
        assertEquals(NoteDoc.PAPER_GRID, r.paper);
        assertEquals(NoteDoc.TONE_DARK, r.tone);
        assertEquals(2, r.pages.size());
        Stroke t = r.pages.get(1).strokes.get(0);
        assertEquals(Stroke.CALLIGRAPHY, t.tool);
        assertEquals(0xFF2255AA, t.color);
        assertEquals(7.5f, t.width, 0);
        assertEquals(2, t.n);
        assertEquals(0.9f, t.p[1], 0);
        NoteCodec.Meta m = NoteCodec.readMeta(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(2, m.pageCount);
        assertEquals("abc", m.id);
    }

    @Test(expected = java.io.IOException.class)
    public void codecRejectsGarbage() throws Exception {
        NoteCodec.read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
    }

    @Test
    public void strokeTransforms() {
        Stroke s = line(0, 0, 10, 0);
        s.translate(5, 5);
        assertEquals(5, s.minX, 1e-4);
        assertEquals(5, s.minY, 1e-4);
        s.scale(5, 5, 2);
        assertEquals(25, s.maxX, 1e-4);
        assertEquals(12, s.width, 1e-4);
        Stroke c = s.copy();
        c.translate(1, 0);
        assertEquals(25, s.maxX, 1e-4);
        assertEquals(new ArrayList<>(), new ArrayList<>());
    }
}

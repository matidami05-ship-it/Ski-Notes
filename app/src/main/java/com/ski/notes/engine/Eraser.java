package com.ski.notes.engine;

import java.util.ArrayList;
import java.util.List;

/** Stroke eraser (removes whole strokes) and area eraser (cuts strokes apart). */
public final class Eraser {
    private Eraser() {
    }

    /** True if the eraser circle at (cx,cy) with radius r touches the stroke. */
    public static boolean hits(Stroke s, float cx, float cy, float r) {
        float reach = r + s.maxHalfWidth() * 0.6f;
        if (cx < s.minX - reach || cx > s.maxX + reach || cy < s.minY - reach || cy > s.maxY + reach) return false;
        float r2 = reach * reach;
        if (s.n == 1) {
            float dx = s.x[0] - cx, dy = s.y[0] - cy;
            return dx * dx + dy * dy <= r2;
        }
        for (int i = 1; i < s.n; i++) {
            if (Geometry.segDist2(cx, cy, s.x[i - 1], s.y[i - 1], s.x[i], s.y[i]) <= r2) return true;
        }
        return false;
    }

    /** True if the swept eraser segment (ax,ay)-(bx,by) with radius r touches the stroke. */
    public static boolean hitsSwept(Stroke s, float ax, float ay, float bx, float by, float r) {
        float len = (float) Math.hypot(bx - ax, by - ay);
        int steps = Math.max(1, (int) Math.ceil(len / Math.max(1f, r * 0.5f)));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            if (hits(s, ax + (bx - ax) * t, ay + (by - ay) * t, r)) return true;
        }
        return false;
    }

    /**
     * Cuts the part of the stroke under the eraser circle out and returns what is left as new strokes
     * (empty list = fully erased). Returns null if the circle does not touch the stroke at all.
     */
    public static List<Stroke> cut(Stroke s, float cx, float cy, float r) {
        if (!hits(s, cx, cy, r)) return null;
        Stroke dense = densify(s, Math.max(1f, r * 0.4f));
        float reach = r + dense.maxHalfWidth() * 0.35f;
        float r2 = reach * reach;
        List<Stroke> out = new ArrayList<>();
        Stroke cur = null;
        for (int i = 0; i < dense.n; i++) {
            float dx = dense.x[i] - cx, dy = dense.y[i] - cy;
            boolean erased = dx * dx + dy * dy <= r2;
            if (erased) {
                if (cur != null) {
                    out.add(cur);
                    cur = null;
                }
            } else {
                if (cur == null) cur = new Stroke(s.tool, s.color, s.width);
                cur.add(dense.x[i], dense.y[i], dense.p[i]);
            }
        }
        if (cur != null) out.add(cur);
        // If nothing was actually removed (only the padded bounds touched), report "no hit".
        int kept = 0;
        for (Stroke k : out) kept += k.n;
        if (out.size() == 1 && kept == dense.n) return null;
        return out;
    }

    /** Copy of the stroke with extra points so no segment is longer than maxSeg. */
    public static Stroke densify(Stroke s, float maxSeg) {
        Stroke d = new Stroke(s.tool, s.color, s.width, s.n * 2);
        if (s.n == 0) return d;
        d.add(s.x[0], s.y[0], s.p[0]);
        for (int i = 1; i < s.n; i++) {
            float x0 = s.x[i - 1], y0 = s.y[i - 1], p0 = s.p[i - 1];
            float x1 = s.x[i], y1 = s.y[i], p1 = s.p[i];
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            int steps = Math.max(1, (int) Math.ceil(len / maxSeg));
            for (int k = 1; k <= steps; k++) {
                float t = k / (float) steps;
                d.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, p0 + (p1 - p0) * t);
            }
        }
        return d;
    }
}

package com.ski.notes.engine;

import java.util.ArrayList;
import java.util.List;

/** Lasso selection: which strokes a hand-drawn loop encloses. */
public final class Lasso {
    private Lasso() {
    }

    /**
     * Indices of strokes with at least half of their points inside the loop (n points in poly as x,y
     * pairs). A short loop (fewer than 3 points) selects nothing.
     */
    public static List<Integer> select(List<Stroke> strokes, float[] poly, int n) {
        List<Integer> out = new ArrayList<>();
        if (n < 3) return out;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, poly[2 * i]);
            maxX = Math.max(maxX, poly[2 * i]);
            minY = Math.min(minY, poly[2 * i + 1]);
            maxY = Math.max(maxY, poly[2 * i + 1]);
        }
        for (int si = 0; si < strokes.size(); si++) {
            Stroke s = strokes.get(si);
            if (s.maxX < minX || s.minX > maxX || s.maxY < minY || s.minY > maxY) continue;
            int inside = 0;
            int step = Math.max(1, s.n / 64);
            int tested = 0;
            for (int i = 0; i < s.n; i += step) {
                tested++;
                if (Geometry.pointInPolygon(poly, n, s.x[i], s.y[i])) inside++;
            }
            if (tested > 0 && inside * 2 >= tested) out.add(si);
        }
        return out;
    }

    /** Union bounds of the given strokes: {minX, minY, maxX, maxY}, padded by pen width. */
    public static float[] bounds(List<Stroke> strokes) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (Stroke s : strokes) {
            float h = s.maxHalfWidth();
            minX = Math.min(minX, s.minX - h);
            minY = Math.min(minY, s.minY - h);
            maxX = Math.max(maxX, s.maxX + h);
            maxY = Math.max(maxY, s.maxY + h);
        }
        return new float[]{minX, minY, maxX, maxY};
    }
}

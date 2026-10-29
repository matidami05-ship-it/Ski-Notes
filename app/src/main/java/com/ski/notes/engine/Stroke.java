package com.ski.notes.engine;

import java.util.Arrays;

/** One pen stroke in page coordinates: points with pressure, plus the tool, colour and base width. */
public final class Stroke {
    public static final int FOUNTAIN = 0;
    public static final int BALLPOINT = 1;
    public static final int PENCIL = 2;
    public static final int CALLIGRAPHY = 3;
    public static final int HIGHLIGHTER = 4;
    public static final int TOOL_COUNT = 5;

    public int tool;
    public int color;
    public float width;

    public float[] x;
    public float[] y;
    public float[] p;
    public int n;

    public float minX, minY, maxX, maxY;

    public Stroke(int tool, int color, float width) {
        this(tool, color, width, 64);
    }

    public Stroke(int tool, int color, float width, int capacity) {
        this.tool = tool;
        this.color = color;
        this.width = width;
        int c = Math.max(4, capacity);
        x = new float[c];
        y = new float[c];
        p = new float[c];
        minX = minY = Float.POSITIVE_INFINITY;
        maxX = maxY = Float.NEGATIVE_INFINITY;
    }

    public void add(float px, float py, float pressure) {
        if (n == x.length) {
            int c = x.length * 2;
            x = Arrays.copyOf(x, c);
            y = Arrays.copyOf(y, c);
            p = Arrays.copyOf(p, c);
        }
        x[n] = px;
        y[n] = py;
        p[n] = clampPressure(pressure);
        n++;
        if (px < minX) minX = px;
        if (py < minY) minY = py;
        if (px > maxX) maxX = px;
        if (py > maxY) maxY = py;
    }

    public static float clampPressure(float pr) {
        if (Float.isNaN(pr) || pr <= 0f) return 0.5f;
        return pr > 1f ? 1f : pr;
    }

    public void clear() {
        n = 0;
        minX = minY = Float.POSITIVE_INFINITY;
        maxX = maxY = Float.NEGATIVE_INFINITY;
    }

    public void computeBounds() {
        minX = minY = Float.POSITIVE_INFINITY;
        maxX = maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (x[i] < minX) minX = x[i];
            if (y[i] < minY) minY = y[i];
            if (x[i] > maxX) maxX = x[i];
            if (y[i] > maxY) maxY = y[i];
        }
    }

    /** Half of the widest the stroke can get, used to pad bounds for hit-testing and redraws. */
    public float maxHalfWidth() {
        return width * (tool == FOUNTAIN || tool == CALLIGRAPHY ? 0.75f : 0.6f) + 1f;
    }

    public Stroke copy() {
        Stroke s = new Stroke(tool, color, width, Math.max(4, n));
        System.arraycopy(x, 0, s.x, 0, n);
        System.arraycopy(y, 0, s.y, 0, n);
        System.arraycopy(p, 0, s.p, 0, n);
        s.n = n;
        s.minX = minX;
        s.minY = minY;
        s.maxX = maxX;
        s.maxY = maxY;
        return s;
    }

    public void translate(float dx, float dy) {
        for (int i = 0; i < n; i++) {
            x[i] += dx;
            y[i] += dy;
        }
        minX += dx;
        maxX += dx;
        minY += dy;
        maxY += dy;
    }

    /** Scales points (and the pen width) about (cx, cy). */
    public void scale(float cx, float cy, float s) {
        for (int i = 0; i < n; i++) {
            x[i] = cx + (x[i] - cx) * s;
            y[i] = cy + (y[i] - cy) * s;
        }
        width *= s;
        computeBounds();
    }

    /**
     * Replaces the points with a straight line from (x0,y0) to (x1,y1), sampled every few units so the
     * eraser can cut it like any other stroke.
     */
    public void setLine(float x0, float y0, float x1, float y1, float pressure) {
        clear();
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        int steps = Math.max(1, (int) Math.ceil(len / 4f));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, pressure);
        }
    }

    /** Mean pressure of the stroke (used when it is turned into a straight line). */
    public float meanPressure() {
        if (n == 0) return 0.5f;
        float s = 0;
        for (int i = 0; i < n; i++) s += p[i];
        return s / n;
    }
}

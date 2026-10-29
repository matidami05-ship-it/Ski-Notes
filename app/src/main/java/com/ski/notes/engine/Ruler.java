package com.ski.notes.engine;

/**
 * An on-screen straight edge. It lives in screen coordinates (it stays put when the page is panned or
 * zoomed, like a real ruler lying on the glass). Pen strokes that start on or near one of its long edges
 * are locked to that edge, which gives perfectly straight lines.
 */
public final class Ruler {
    public float cx, cy;
    /** Rotation in radians; 0 = horizontal. */
    public float angle;
    public float length;
    public float thickness;

    public Ruler(float cx, float cy, float length, float thickness) {
        this.cx = cx;
        this.cy = cy;
        this.length = length;
        this.thickness = thickness;
    }

    /** Coordinate along the ruler, 0 at its centre. */
    public float toU(float x, float y) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return (x - cx) * c + (y - cy) * s;
    }

    /** Coordinate across the ruler, 0 on its centre line, ±thickness/2 on the edges. */
    public float toV(float x, float y) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return -(x - cx) * s + (y - cy) * c;
    }

    public float fromUx(float u, float v) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return cx + u * c - v * s;
    }

    public float fromUy(float u, float v) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return cy + u * s + v * c;
    }

    public boolean contains(float x, float y, float margin) {
        return Math.abs(toU(x, y)) <= length / 2f + margin && Math.abs(toV(x, y)) <= thickness / 2f + margin;
    }

    /**
     * Which edge a pen touching down at (x,y) should follow: +1 or -1, or 0 for none. A touch on the
     * ruler body picks the closer edge; a touch outside counts only within {@code snapDist} of an edge.
     */
    public int edgeFor(float x, float y, float snapDist) {
        float u = toU(x, y);
        if (Math.abs(u) > length / 2f + snapDist) return 0;
        float v = toV(x, y);
        float h = thickness / 2f;
        float dPlus = Math.abs(v - h), dMinus = Math.abs(v + h);
        if (Math.abs(v) <= h) return dPlus <= dMinus ? 1 : -1;
        if (dPlus <= snapDist) return 1;
        if (dMinus <= snapDist) return -1;
        return 0;
    }

    /** Projects (x,y) onto the given edge; the result is written into out[0..1]. */
    public void snap(float x, float y, int edge, float offset, float[] out) {
        float u = toU(x, y);
        float v = edge * (thickness / 2f + offset);
        out[0] = fromUx(u, v);
        out[1] = fromUy(u, v);
    }

    public void moveBy(float dx, float dy) {
        cx += dx;
        cy += dy;
    }

    /** Rotates about a pivot point (usually the midpoint of two fingers). */
    public void rotateAbout(float px, float py, float dAngle) {
        float c = (float) Math.cos(dAngle), s = (float) Math.sin(dAngle);
        float dx = cx - px, dy = cy - py;
        cx = px + dx * c - dy * s;
        cy = py + dx * s + dy * c;
        angle = normalize(angle + dAngle);
    }

    public float angleDegrees() {
        float d = (float) Math.toDegrees(angle);
        d = ((d % 180f) + 180f) % 180f; // a ruler at 190° is the same line as 10°
        return d;
    }

    /** Displayed angle, folded into 0..90 like a protractor reading. */
    public int readoutDegrees() {
        float d = angleDegrees();
        if (d > 90f) d = 180f - d;
        return Math.round(d);
    }

    /**
     * Snaps the angle to the nearest multiple of 15° if within {@code tolDeg}. Returns true if it snapped.
     */
    public boolean snapAngle(float tolDeg) {
        float d = (float) Math.toDegrees(angle);
        float nearest = Math.round(d / 15f) * 15f;
        if (Math.abs(d - nearest) <= tolDeg) {
            angle = normalize((float) Math.toRadians(nearest));
            return true;
        }
        return false;
    }

    private static float normalize(float a) {
        double twoPi = Math.PI * 2;
        double r = a % twoPi;
        if (r > Math.PI) r -= twoPi;
        if (r < -Math.PI) r += twoPi;
        return (float) r;
    }
}

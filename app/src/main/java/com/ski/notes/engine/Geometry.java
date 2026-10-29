package com.ski.notes.engine;

/** Small geometry helpers shared by the eraser, lasso and line tools. */
public final class Geometry {
    private Geometry() {
    }

    /** Squared distance from point (px,py) to the segment (ax,ay)-(bx,by). */
    public static float segDist2(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = 0f;
        if (len2 > 1e-9f) {
            t = ((px - ax) * dx + (py - ay) * dy) / len2;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
        }
        float qx = ax + t * dx - px, qy = ay + t * dy - py;
        return qx * qx + qy * qy;
    }

    /** Even-odd point-in-polygon test; poly holds n (x,y) pairs. */
    public static boolean pointInPolygon(float[] poly, int n, float x, float y) {
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = poly[2 * i], yi = poly[2 * i + 1];
            float xj = poly[2 * j], yj = poly[2 * j + 1];
            if ((yi > y) != (yj > y)) {
                float xCross = (xj - xi) * (y - yi) / (yj - yi) + xi;
                if (x < xCross) in = !in;
            }
        }
        return in;
    }

    /** Smallest angular difference between two angles in degrees, in [0, 180]. */
    public static float angleDiffDeg(float a, float b) {
        float d = Math.abs(((a - b) % 360f + 540f) % 360f - 180f);
        return d;
    }
}

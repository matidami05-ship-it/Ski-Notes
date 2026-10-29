package com.ski.notes.engine;

/** "Hold to straighten": helpers for turning a wobbly stroke into a clean straight line. */
public final class LineAssist {
    private LineAssist() {
    }

    /**
     * Returns the line end point, snapped to the nearest 45° direction from (x0,y0) when the line is within
     * {@code tolDeg} of it (so horizontals, verticals and diagonals come out exact).
     */
    public static void snapEnd(float x0, float y0, float x1, float y1, float tolDeg, float[] out) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        out[0] = x1;
        out[1] = y1;
        if (len < 1e-3f) return;
        float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float nearest = Math.round(deg / 45f) * 45f;
        if (Math.abs(deg - nearest) <= tolDeg) {
            double r = Math.toRadians(nearest);
            out[0] = x0 + (float) (Math.cos(r) * len);
            out[1] = y0 + (float) (Math.sin(r) * len);
        }
    }

    /**
     * Tracks whether the pen has been held still. Feed it every pen position with a timestamp; it reports
     * "held" once the pen has stayed within {@code radius} for {@code holdMs}.
     */
    public static final class HoldDetector {
        private final float radius;
        private final long holdMs;
        private float ax, ay;
        private long since = -1;

        public HoldDetector(float radius, long holdMs) {
            this.radius = radius;
            this.holdMs = holdMs;
        }

        public void reset() {
            since = -1;
        }

        public void feed(float x, float y, long t) {
            if (since < 0 || Math.hypot(x - ax, y - ay) > radius) {
                ax = x;
                ay = y;
                since = t;
            }
        }

        public boolean held(long now) {
            return since >= 0 && now - since >= holdMs;
        }
    }

    /**
     * True when a stroke is worth straightening: long enough that it is clearly a line attempt rather than
     * a dot or the start of a letter.
     */
    public static boolean worthStraightening(Stroke s, float minLen) {
        if (s.n < 2) return false;
        float dx = s.x[s.n - 1] - s.x[0], dy = s.y[s.n - 1] - s.y[0];
        return Math.hypot(dx, dy) >= minLen;
    }
}

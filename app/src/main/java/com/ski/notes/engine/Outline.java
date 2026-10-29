package com.ski.notes.engine;

/**
 * Turns a stroke into filled polygons whose width follows pen pressure (and, for calligraphy, the
 * direction of travel). Every polygon has the same winding direction, so filling them all with a
 * non-zero (winding) rule produces one clean, gap-free shape.
 *
 * The {@link Incremental} builder emits geometry as points arrive, so a live stroke can be painted
 * piece by piece while the pen moves; a finished stroke is built by the same code, so what you see
 * while writing is exactly what gets saved.
 */
public final class Outline {
    private Outline() {
    }

    public interface Sink {
        /** A closed polygon of n points stored as x,y pairs in pts. */
        void polygon(float[] pts, int n);
    }

    /** Nib angle for the calligraphy pen (a broad nib held at 45°). */
    private static final double NIB = Math.toRadians(-45);

    /** Half width at a point for the given tool. pressure is smoothed; dir is the travel direction. */
    public static float halfWidth(int tool, float width, float pressure, double dir) {
        float w = width * 0.5f;
        switch (tool) {
            case Stroke.FOUNTAIN:
                return w * (0.18f + 1.0f * (float) Math.pow(pressure, 0.85));
            case Stroke.BALLPOINT:
                return w * (0.8f + 0.3f * pressure);
            case Stroke.PENCIL:
                return w * (0.5f + 0.55f * pressure);
            case Stroke.CALLIGRAPHY: {
                float edge = (float) Math.abs(Math.sin(dir - NIB));
                return w * (0.12f + 0.95f * edge) * (0.65f + 0.45f * pressure);
            }
            default:
                return w;
        }
    }

    public static void build(Stroke s, Sink sink) {
        Incremental inc = new Incremental(s, sink);
        inc.finish();
    }

    /** Builds a stroke's outline progressively. */
    public static final class Incremental {
        private final Stroke s;
        private final Sink sink;
        private final float[] buf = new float[2 * 40];
        private float[] r = new float[64];
        private int rCount;          // radii computed for points [0, rCount)
        private float sp = Float.NaN; // smoothed pressure state
        private int last = -1;        // last point a segment ended on
        private double prevAng = Double.NaN;
        private boolean finished;

        public Incremental(Stroke s, Sink sink) {
            this.s = s;
            this.sink = sink;
        }

        /** Index of the last point whose geometry has been emitted (-1 if none). */
        public int lastEmitted() {
            return last;
        }

        /** Radius used at the last emitted point. */
        public float lastRadius() {
            return last >= 0 ? r[last] : 0f;
        }

        /** Current radius estimate for the newest point (for drawing the not-yet-final tail). */
        public float tailRadius() {
            if (s.n == 0) return 0f;
            float p = Float.isNaN(sp) ? s.p[s.n - 1] : sp;
            return Math.max(0.35f, halfWidth(s.tool, s.width, p, direction(s.n - 1, s.n)));
        }

        /** Emits geometry for every point whose shape can no longer change (all but the newest). */
        public void update() {
            emitUpTo(s.n - 1, s.n);
        }

        /** Emits everything, including the end cap. */
        public void finish() {
            if (finished) return;
            finished = true;
            int count = s.n;
            if (count == 0) return;
            emitUpTo(count, count);
            // End cap (a lone dot already got its start cap).
            if (last > 0) circle(s.x[last], s.y[last], r[last]);
        }

        private void computeRadius(int i, int count) {
            if (i >= r.length) {
                float[] nr = new float[Math.max(r.length * 2, i + 16)];
                System.arraycopy(r, 0, nr, 0, rCount);
                r = nr;
            }
            sp = Float.isNaN(sp) ? s.p[i] : sp + (s.p[i] - sp) * 0.35f;
            r[i] = Math.max(0.35f, halfWidth(s.tool, s.width, sp, direction(i, count)));
            rCount = i + 1;
        }

        /** Emit geometry for points [rCount, upTo), where the stroke currently has {@code count} points. */
        private void emitUpTo(int upTo, int count) {
            for (int i = rCount; i < upTo; i++) {
                computeRadius(i, count);
                if (last < 0) {
                    circle(s.x[0], s.y[0], r[0]); // start cap
                    last = 0;
                    continue;
                }
                float x0 = s.x[last], y0 = s.y[last], x1 = s.x[i], y1 = s.y[i];
                float dx = x1 - x0, dy = y1 - y0;
                float len = (float) Math.hypot(dx, dy);
                boolean isFinalPoint = i == count - 1 && finished;
                if (len < 0.25f && !isFinalPoint) continue; // skip near-duplicates
                if (len > 1e-4f) {
                    float nx = -dy / len, ny = dx / len;
                    float r0 = r[last], r1 = r[i];
                    buf[0] = x0 + nx * r0;
                    buf[1] = y0 + ny * r0;
                    buf[2] = x1 + nx * r1;
                    buf[3] = y1 + ny * r1;
                    buf[4] = x1 - nx * r1;
                    buf[5] = y1 - ny * r1;
                    buf[6] = x0 - nx * r0;
                    buf[7] = y0 - ny * r0;
                    sink.polygon(buf, 4);
                    double ang = Math.atan2(dy, dx);
                    // Round joints only where the path bends.
                    if (!Double.isNaN(prevAng) && angleBetween(ang, prevAng) > Math.toRadians(6)) {
                        circle(x0, y0, r0);
                    }
                    prevAng = ang;
                    last = i;
                }
            }
        }

        private double direction(int i, int count) {
            int a = Math.max(0, i - 1), b = Math.min(count - 1, i + 1);
            float dx = s.x[b] - s.x[a], dy = s.y[b] - s.y[a];
            if (dx == 0f && dy == 0f) return 0.0;
            return Math.atan2(dy, dx);
        }

        private void circle(float cx, float cy, float rad) {
            Outline.circle(cx, cy, rad, buf, sink);
        }
    }

    private static double angleBetween(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return d > Math.PI ? 2 * Math.PI - d : d;
    }

    /** Emits a circle as a polygon wound the same way as the segment quads. */
    static void circle(float cx, float cy, float rad, float[] buf, Sink sink) {
        int seg = Math.max(8, Math.min(36, (int) (rad * 1.5f)));
        for (int k = 0; k < seg; k++) {
            double a = -2 * Math.PI * k / seg;
            buf[2 * k] = cx + (float) (Math.cos(a) * rad);
            buf[2 * k + 1] = cy + (float) (Math.sin(a) * rad);
        }
        sink.polygon(buf, seg);
    }

    /** Signed shoelace area of a polygon (used by tests to check consistent winding). */
    public static float signedArea(float[] pts, int n) {
        double a = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += (double) pts[2 * i] * pts[2 * j + 1] - (double) pts[2 * j] * pts[2 * i + 1];
        }
        return (float) (a / 2);
    }
}

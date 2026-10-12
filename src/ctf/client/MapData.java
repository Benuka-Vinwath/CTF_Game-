package ctf.client;

/** Shared map constants. Part A (server) must use the same values and the same resolve() for movement. */
public final class MapData {
    public static final int W = 800, H = 600, R = 14;
    public static final double SPEED = 180; // px/sec
    /** x, y, w, h */
    public static final double[][] WALLS = {
        {380, 200, 40, 200}, {150, 120, 100, 24}, {550, 456, 100, 24}, {250, 380, 24, 100}, {526, 120, 24, 100}};
    /** index = team (0 red, 1 blue): x, y, radius */
    public static final double[][] BASES = {{60, 300, 45}, {740, 300, 45}};

    /** Keeps a circle of radius R inside the arena and out of walls. */
    public static void resolve(double[] p) {
        p[0] = Math.max(R, Math.min(W - R, p[0]));
        p[1] = Math.max(R, Math.min(H - R, p[1]));
        for (double[] w : WALLS) {
            double cx = Math.max(w[0], Math.min(p[0], w[0] + w[2]));
            double cy = Math.max(w[1], Math.min(p[1], w[1] + w[3]));
            double dx = p[0] - cx, dy = p[1] - cy, d = Math.hypot(dx, dy);
            if (d < R) {
                if (d == 0) p[0] = w[0] - R;
                else { p[0] = cx + dx / d * R; p[1] = cy + dy / d * R; }
            }
        }
    }
    private MapData() {}
}

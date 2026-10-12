package ctf.client;

/** Shared map constants. Part A (server) must use the same values and the same resolve() for movement. */
public final class MapData {
    public static final int W = 800, H = 600, R = 14;
    public static final double SPEED = 180; // px/sec
    /** x, y, w, h */
    public static final double[][] WALLS = {
        {380, 200, 40, 200}, {150, 120, 100, 24}, {550, 456, 100, 24}, {250, 380, 24, 100}, {526, 120, 24, 100}};
    public static final double BULLET_SPEED = 420;   // px/sec; server and client prediction must agree
    public static final double FIRE_INTERVAL = 0.25; // sec between shots; the server enforces it, the client only mirrors it
    /** index = team (0 red, 1 blue): x, y, radius */
    public static final double[][] BASES = {{60, 300, 45}, {740, 300, 45}};

    /** Protective power tent constants */
    public static final double TENT_X = 400;
    public static final double TENT_Y = 500;
    public static final double TENT_R = 32;          // radius of protection trigger zone
    public static final double SHIELD_DURATION = 5.0;// duration of invulnerability in seconds
    public static final double TENT_RECHARGE = 10.0; // cooldown in seconds before tent can be collected again

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
    /** True if the segment passes within r of (cx, cy). Use it instead of a point check so fast bullets cannot skip a target. */
    public static boolean segHits(double x0, double y0, double x1, double y1, double cx, double cy, double r) {
        double dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((cx - x0) * dx + (cy - y0) * dy) / l2));
        return Math.hypot(x0 + t * dx - cx, y0 + t * dy - cy) <= r;
    }

    /** True if (x, y) is within margin m of a wall or the arena edge (m = 0: inside one). */
    public static boolean nearSolid(double x, double y, double m) {
        if (x < m || x > W - m || y < m || y > H - m) return true;
        for (double[] w : WALLS)
            if (x >= w[0] - m && x <= w[0] + w[2] + m && y >= w[1] - m && y <= w[1] + w[3] + m) return true;
        return false;
    }
    private MapData() {}
}

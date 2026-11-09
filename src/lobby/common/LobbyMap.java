package lobby.common;

/**
 * Map constants shared between the lobby server and client.
 *
 * The lobby arena is exactly the same 1120 × 630 canvas as the main game,
 * so art assets look identical.  Movement speed is the same but bullets are
 * never spawned.
 */
public final class LobbyMap {

    private LobbyMap() {}

    public static final int W = 1120;
    public static final int H = 630;
    public static final int R = 17;              // player circle radius

    public static final double SPEED = 180.0;    // px / sec (matches the game)

    // The same walls as the main game – players can still navigate the real map.
    // Format: { x, y, width, height }
    public static final double[][] WALLS = {
        {540, 215, 40, 200},
        {230, 110, 140, 24}, {750, 496, 140, 24},
        {340, 400, 24, 110}, {756, 120, 24, 110},
        {420, 250, 24, 130}, {676, 250, 24, 130}
    };

    // Spawn positions: players start in the centre, spread out a little.
    public static final double SPAWN_X = W / 2.0;
    public static final double SPAWN_Y = H / 2.0;

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

    /** True if (x, y) is within margin m of a wall or the arena boundary. */
    public static boolean nearSolid(double x, double y, double m) {
        if (x < m || x > W - m || y < m || y > H - m) return true;
        for (double[] w : WALLS)
            if (x >= w[0] - m && x <= w[0] + w[2] + m &&
                y >= w[1] - m && y <= w[1] + w[3] + m) return true;
        return false;
    }
}


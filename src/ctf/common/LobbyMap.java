package ctf.common;

/**
 * The waiting room players walk around in before a match. It is the same 1120x630 world as the arena,
 * so the window scaling and the movement code are unchanged; only the walls and the things in it differ.
 * The server and the client both use these values, so prediction and collision agree.
 */
public final class LobbyMap {
    /** x, y, w, h: crates, the laptop desk and the wall that carries the status board. */
    public static final double[][] WALLS = {
        {110, 90, 56, 56}, {130, 480, 50, 50}, {955, 480, 56, 56},   // crates
        {935, 70, 100, 44},                                          // laptop desk
        {380, 0, 360, 84},                                           // status board wall
    };

    /** index = team (0 red, 1 blue): x, y, radius. Standing on a pad joins that team. */
    public static final double[][] PADS = {{230, 315, 72}, {890, 315, 72}};

    /** Practice targets along the bottom wall: x, y, radius. Bullets pop on them; players cannot be hurt here. */
    public static final double[][] TARGETS = {{370, 568, 18}, {480, 586, 18}, {590, 568, 18}};
    /** x, w of the practice range strip (left of centre, clear of the ready / start buttons bottom-right). */
    public static final double RANGE_X = 300, RANGE_W = 360, RANGE_Y = 530;

    /** Walk up to the laptop and press E / C to change your hat. */
    public static final double LAPTOP_X = 985, LAPTOP_Y = 92, LAPTOP_R = 75;

    public static final double SPAWN_X = 560, SPAWN_Y = 300;
    public static final int HATS = 6, HAT_COLORS = 6;   // hat 0 = none
    public static final int MAX_PLAYERS = 8, MAX_TEAM = MAX_PLAYERS / 2;

    /** The team pad (x, y) is standing on, or -1. */
    public static int padAt(double x, double y) {
        for (int t = 0; t < 2; t++) if (Math.hypot(x - PADS[t][0], y - PADS[t][1]) <= PADS[t][2]) return t;
        return -1;
    }

    public static boolean nearLaptop(double x, double y) {
        return Math.hypot(x - LAPTOP_X, y - LAPTOP_Y) <= LAPTOP_R;
    }

    /** Index of the target the bullet's path from (x0, y0) to (x1, y1) touches, or -1. */
    public static int targetHit(double x0, double y0, double x1, double y1) {
        for (int i = 0; i < TARGETS.length; i++)
            if (MapData.segHits(x0, y0, x1, y1, TARGETS[i][0], TARGETS[i][1], TARGETS[i][2])) return i;
        return -1;
    }

    /** Where a player appears in the lobby: around the centre, spread out a little by id so nobody overlaps. */
    public static double[] spawn(int id) {
        double[] p = {SPAWN_X + (id * 53 % 140) - 70, SPAWN_Y + (id * 31 % 90) - 45};
        MapData.resolve(p, WALLS);
        return p;
    }

    private LobbyMap() {}
}

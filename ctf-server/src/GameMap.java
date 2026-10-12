/** Fixed map layout. The client (Member B) must draw the same walls and bases. */
public final class GameMap {
    public static final int WIDTH = 1200, HEIGHT = 700;
    public static final double PLAYER_R = 15, FLAG_R = 12, BASE_R = 50;

    /** Base centres: index 0 = red (left), 1 = blue (right). Flags start here. */
    public static final double[][] BASES = { {100, 350}, {1100, 350} };

    /** Walls as {x, y, width, height}. The centre lane (y 280..420) is left open. */
    public static final int[][] WALLS = {
        {280, 100, 40, 180}, {280, 420, 40, 180},   // red side cover
        {880, 100, 40, 180}, {880, 420, 40, 180},   // blue side cover
        {570,   0, 60, 200}, {570, 500, 60, 200},   // centre pillars
    };

    private GameMap() {}

    /** True if a circle of radius r at (x, y) would hit a wall or leave the map. */
    public static boolean blocked(double x, double y, double r) {
        if (x - r < 0 || y - r < 0 || x + r > WIDTH || y + r > HEIGHT) return true;
        for (int[] w : WALLS) {
            double nx = Math.max(w[0], Math.min(x, w[0] + w[2]));   // nearest point on rectangle
            double ny = Math.max(w[1], Math.min(y, w[1] + w[3]));
            double dx = x - nx, dy = y - ny;
            if (dx * dx + dy * dy < r * r) return true;
        }
        return false;
    }

    public static boolean onEnemySide(Player p) {
        return p.team == 0 ? p.x > WIDTH / 2.0 : p.x < WIDTH / 2.0;
    }
}

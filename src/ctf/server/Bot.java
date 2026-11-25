package ctf.server;

import ctf.common.MapData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * A computer player. Every tick it sets the same four things a human's input sets (dx, dy, aim, shoot), so the server
 * applies exactly the same rules to it: speed, 8 directions, walls, fire rate, respawn. It only sees targets in line of
 * sight. Three playing styles:
 *   RAIDER    goes for the enemy flag and escorts a teammate who carries it
 *   GUARDIAN  stays near its own flag, stops anyone who comes close, returns a dropped flag
 *   HUNTER    hunts players, keeps its distance and strafes while shooting
 * Every type runs home with the flag, chases a thief, returns a dropped flag, takes the tent shield and dodges bullets.
 * Tuned medium: it still plays the flag smartly and leads moving targets, but reacts slowly, misses often and rarely dodges.
 */
final class Bot {
    enum Type { RAIDER, GUARDIAN, HUNTER }

    /** Which style a bot gets by its slot on the team: a lone bot attacks, a second one defends. */
    static final Type[] ORDER = {Type.RAIDER, Type.GUARDIAN, Type.HUNTER, Type.RAIDER};

    // medium: an average player's reaction, misses often at range, rarely dodges (hard was 0.18 s / 2.5 deg / 0.6)
    static final double REACTION = 0.45;                    // seconds a new target must be in sight before the first shot
    static final double AIM_ERROR = Math.toRadians(8);      // spread of the aim, one standard deviation
    static final double DODGE_CHANCE = 0.15;                // share of incoming bullets it sees in time; 1.0 is unbeatable
    static final double SIGHT = 650;                        // px
    private static final double DT = GameState.DT, REPATH = 0.35;

    final Type type;
    private final Random rng;
    private List<double[]> path = List.of();
    private int pathAt;
    private double repathIn, goalX = -1, goalY = -1;
    private int targetId = -1;
    private double targetSeen;
    private final Map<Integer, double[]> lastPos = new java.util.HashMap<>();   // for leading shots
    private final Map<double[], Boolean> dodgeRoll = new IdentityHashMap<>();   // one roll per bullet
    private double strafe = 1, strafeIn, guardIn, guardX, guardY;
    private double lastX, lastY, stuckFor, unstickIn;
    private int unstickDx, unstickDy;

    Bot(Type type, Random rng) {
        this.type = type;
        this.rng = rng;
    }

    /** Called by GameState inside its lock, once per tick, for a living bot during a match. */
    void think(Player me, Map<Integer, Player> players, Flag[] flags, List<double[]> bullets, List<double[]> grenades, double tentCooldown) {
        me.inputAge = 0;   // a bot's input is always fresh
        Player target = pickTarget(me, players, flags);
        aim(me, target);
        maybeThrow(me, players, flags);

        int[] dir = flee(me, grenades);
        if (dir == null) dir = dodge(me, bullets);
        if (dir == null) dir = unstick(me);
        if (dir == null) {
            double[] goal = goal(me, players, flags, tentCooldown, target);
            dir = goal == null ? strafeAround(me, target) : steer(me, goal[0], goal[1]);
        }
        me.dx = dir[0];
        me.dy = dir[1];

        for (Player p : players.values()) lastPos.put(p.id, new double[]{p.x, p.y});
        if (dodgeRoll.size() > 300) dodgeRoll.clear();
    }

    // ------------------------------------------------------------------ what to do

    /** Where to go this tick, or null for "stand and fight" (the hunter strafes instead). */
    private double[] goal(Player me, Map<Integer, Player> players, Flag[] flags, double tentCooldown, Player target) {
        Flag own = flags[me.team], enemy = flags[1 - me.team];
        double[] home = MapData.BASES[me.team];
        if (enemy.state == Flag.State.CARRIED && enemy.carrierId == me.id) return new double[]{home[0], home[1]};   // capture run
        if (own.state == Flag.State.DROPPED && (type != Type.RAIDER || dist(me, own.x, own.y) < 260))
            return new double[]{own.x, own.y};                                                                  // send our flag home
        if (own.state == Flag.State.CARRIED && type != Type.RAIDER) {
            Player thief = players.get(own.carrierId);
            if (thief != null) return new double[]{thief.x, thief.y};                                           // stop the thief
        }
        if (tentCooldown <= 0 && me.shieldSec <= 0 && type != Type.GUARDIAN
                && dist(me, MapData.TENT_X, MapData.TENT_Y) < 260) return new double[]{MapData.TENT_X, MapData.TENT_Y};

        switch (type) {
            case RAIDER -> {
                if (enemy.state == Flag.State.CARRIED) {   // a teammate has it: stay close and cover them
                    Player c = players.get(enemy.carrierId);
                    if (c != null) return new double[]{c.x, c.y};
                }
                return new double[]{enemy.x, enemy.y};
            }
            case GUARDIAN -> {
                Player intruder = nearestEnemy(me, players, home[0], home[1], 340);
                if (intruder != null) return new double[]{intruder.x, intruder.y};
                guardIn -= DT;
                if (guardIn <= 0) {   // a new guard spot between the flag and the middle
                    double toMid = Math.signum(MapData.W / 2.0 - home[0]);
                    guardX = home[0] + toMid * (90 + rng.nextDouble() * 110);
                    guardY = home[1] + (rng.nextDouble() - 0.5) * 220;
                    guardIn = 2 + rng.nextDouble() * 2;
                }
                return new double[]{guardX, guardY};
            }
            default -> {   // HUNTER
                if (target != null) {
                    double d = dist(me, target.x, target.y);
                    if (d > 300) return new double[]{target.x, target.y};
                    if (d < 170) return new double[]{me.x - (target.x - me.x), me.y - (target.y - me.y)};   // back off
                    return null;   // good range: strafe and shoot
                }
                Player prey = nearestEnemy(me, players, me.x, me.y, Double.MAX_VALUE);
                return prey != null ? new double[]{prey.x, prey.y} : new double[]{enemy.x, enemy.y};
            }
        }
    }

    /** The enemy to shoot: visible, in range and not shielded; a flag carrier first, then the nearest. */
    private Player pickTarget(Player me, Map<Integer, Player> players, Flag[] flags) {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (Player e : players.values()) {
            if (e.team == me.team || !e.alive || e.shieldSec > 0) continue;
            double d = dist(me, e.x, e.y);
            if (d > SIGHT || !Nav.clearShot(me.x, me.y, e.x, e.y)) continue;
            boolean carrier = flags[me.team].state == Flag.State.CARRIED && flags[me.team].carrierId == e.id;
            double score = carrier ? d - 1000 : d;
            if (score < bestScore) { bestScore = score; best = e; }
        }
        int id = best == null ? -1 : best.id;
        if (id != targetId) { targetId = id; targetSeen = 0; }
        else targetSeen += DT;
        return best;
    }

    /** Aims where the target will be when the bullet arrives, with a small human-like error; fires after the reaction time. */
    private void aim(Player me, Player target) {
        if (target == null) {
            if (me.dx != 0 || me.dy != 0) me.aim = Math.atan2(me.dy, me.dx);
            return;
        }
        double[] was = lastPos.get(target.id);
        double vx = was == null ? 0 : (target.x - was[0]) / DT, vy = was == null ? 0 : (target.y - was[1]) / DT;
        double px = target.x, py = target.y;
        for (int i = 0; i < 2; i++) {   // two refinements are plenty at these speeds
            double t = dist(me, px, py) / MapData.BULLET_SPEED;
            px = target.x + vx * t;
            py = target.y + vy * t;
        }
        me.aim = Math.atan2(py - me.y, px - me.x) + rng.nextGaussian() * AIM_ERROR;
        if (targetSeen >= REACTION) me.shootLatch = true;   // the server still limits it to one shot per 0.25 s
    }

    /**
     * A grenade is worth it at a flag thief, at two or more enemies close together, or (guardian) at anyone near our flag.
     * The throw lands where the target will be when it bursts; checked about twice a second, not every tick.
     */
    private void maybeThrow(Player me, Map<Integer, Player> players, Flag[] flags) {
        if (me.grenades <= 0 || me.throwCd > 0 || rng.nextDouble() > 2 * DT) return;
        double[] home = MapData.BASES[me.team];
        for (Player e : players.values()) {
            if (e.team == me.team || !e.alive || e.shieldSec > 0) continue;
            double d = dist(me, e.x, e.y);
            if (d < 110 || d > MapData.GRENADE_RANGE || !Nav.clearShot(me.x, me.y, e.x, e.y)) continue;   // not at our own feet
            boolean thief = flags[me.team].state == Flag.State.CARRIED && flags[me.team].carrierId == e.id;
            int near = 0;
            for (Player o : players.values()) if (o.team == e.team && o.alive && Math.hypot(o.x - e.x, o.y - e.y) < MapData.BLAST_R) near++;
            boolean intruder = type == Type.GUARDIAN && Math.hypot(e.x - home[0], e.y - home[1]) < 260;
            if (!thief && near < 2 && !intruder) continue;
            double[] was = lastPos.get(e.id);
            double t = MapData.GRENADE_FUSE * 0.6;   // they keep moving a little before it bursts
            double px = e.x + (was == null ? 0 : (e.x - was[0]) / DT * t), py = e.y + (was == null ? 0 : (e.y - was[1]) / DT * t);
            me.aim = Math.atan2(py - me.y, px - me.x) + rng.nextGaussian() * AIM_ERROR;
            me.throwDist = Math.min(MapData.GRENADE_RANGE, Math.hypot(px - me.x, py - me.y));
            return;
        }
    }

    // ------------------------------------------------------------------ how to move

    /** Runs straight away from an enemy grenade that is about to burst nearby. */
    private int[] flee(Player me, List<double[]> grenades) {
        for (double[] gr : grenades) {
            if ((int) gr[4] == me.team || gr[6] > 1.0) continue;
            double dx = me.x - gr[0], dy = me.y - gr[1];
            if (Math.hypot(dx, dy) > MapData.BLAST_R + 25) continue;
            if (dx == 0 && dy == 0) dx = 1;
            if (Nav.walkable(me.x, me.y, me.x + Math.signum(dx) * 20, me.y + Math.signum(dy) * 20)) return dir8(dx, dy);
            return dir8(dy, -dx);   // a wall behind: run sideways instead
        }
        return null;
    }

    /** Steps aside from an enemy bullet that would hit within 0.45 s (it notices only DODGE_CHANCE of them). */
    private int[] dodge(Player me, List<double[]> bullets) {
        for (double[] b : bullets) {
            if ((int) b[4] == me.team) continue;
            double rx = me.x - b[0], ry = me.y - b[1], v2 = b[2] * b[2] + b[3] * b[3];
            double t = (rx * b[2] + ry * b[3]) / v2;
            if (t <= 0 || t > 0.45) continue;
            double cx = b[0] + b[2] * t - me.x, cy = b[1] + b[3] * t - me.y;
            if (Math.hypot(cx, cy) > MapData.R + 8) continue;
            if (!dodgeRoll.computeIfAbsent(b, k -> rng.nextDouble() < DODGE_CHANCE)) continue;
            double side = (rx * b[3] - ry * b[2]) >= 0 ? 1 : -1;   // move away from the bullet's line
            double px = -b[3] * side, py = b[2] * side;
            if (Nav.walkable(me.x, me.y, me.x + px / Math.sqrt(v2) * 24, me.y + py / Math.sqrt(v2) * 24)) return dir8(px, py);
            if (Nav.walkable(me.x, me.y, me.x - px / Math.sqrt(v2) * 24, me.y - py / Math.sqrt(v2) * 24)) return dir8(-px, -py);
        }
        return null;
    }

    /** Hunter at a good range: sidestep left and right while shooting. */
    private int[] strafeAround(Player me, Player target) {
        strafeIn -= DT;
        if (strafeIn <= 0) { strafe = -strafe; strafeIn = 0.45 + rng.nextDouble() * 0.5; }
        double ax = target.x - me.x, ay = target.y - me.y;
        double sx = -ay * strafe, sy = ax * strafe, len = Math.hypot(sx, sy);
        if (len == 0) return new int[]{0, 0};
        if (!Nav.walkable(me.x, me.y, me.x + sx / len * 24, me.y + sy / len * 24)) { strafe = -strafe; sx = -sx; sy = -sy; }
        return dir8(sx, sy);
    }

    /** Follows an A* path, skipping waypoints it can already walk straight to. */
    private int[] steer(Player me, double gx, double gy) {
        if (repathIn <= 0 || Math.hypot(gx - goalX, gy - goalY) > 40 || path.isEmpty()) {
            path = Nav.path(me.x, me.y, gx, gy);
            pathAt = 0;
            goalX = gx;
            goalY = gy;
            repathIn = REPATH;
        }
        repathIn -= DT;
        while (pathAt < path.size() - 1 && Nav.walkable(me.x, me.y, path.get(pathAt + 1)[0], path.get(pathAt + 1)[1])) pathAt++;
        double[] wp = path.isEmpty() ? new double[]{gx, gy} : path.get(pathAt);
        double dx = wp[0] - me.x, dy = wp[1] - me.y;
        if (Math.hypot(dx, dy) < 4) {
            if (pathAt < path.size() - 1) pathAt++;
            return new int[]{0, 0};
        }
        return dir8(dx, dy);
    }

    /** Pressed into a corner for 0.4 s: wiggle in a random direction for a moment and plan again. */
    private int[] unstick(Player me) {
        boolean wantsToMove = me.dx != 0 || me.dy != 0;
        stuckFor = wantsToMove && Math.hypot(me.x - lastX, me.y - lastY) < 0.5 ? stuckFor + DT : 0;
        lastX = me.x;
        lastY = me.y;
        if (stuckFor > 0.4) {
            stuckFor = 0;
            unstickIn = 0.25;
            unstickDx = rng.nextInt(3) - 1;
            unstickDy = rng.nextInt(3) - 1;
            repathIn = 0;
        }
        if (unstickIn > 0) { unstickIn -= DT; return new int[]{unstickDx, unstickDy}; }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static Player nearestEnemy(Player me, Map<Integer, Player> players, double x, double y, double within) {
        Player best = null;
        double bestD = within;
        for (Player e : players.values()) {
            if (e.team == me.team || !e.alive) continue;
            double d = Math.hypot(e.x - x, e.y - y);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** The nearest of the 8 directions a player can move in (dx, dy each -1, 0 or 1). */
    static int[] dir8(double dx, double dy) {
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return new int[]{0, 0};
        double k = Math.round(Math.atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4);
        return new int[]{(int) Math.round(Math.cos(k)), (int) Math.round(Math.sin(k))};
    }

    private static double dist(Player p, double x, double y) { return Math.hypot(p.x - x, p.y - y); }

    // ================================================================== navigation

    /** A grid over the arena: a cell is free if a player's centre can stand there. A* finds the way around walls. */
    static final class Nav {
        static final int CELL = 14, COLS = (MapData.W + CELL - 1) / CELL, ROWS = (MapData.H + CELL - 1) / CELL;
        private static final boolean[] FREE = new boolean[COLS * ROWS];

        static {
            for (int r = 0; r < ROWS; r++)
                for (int c = 0; c < COLS; c++)
                    FREE[r * COLS + c] = !MapData.nearSolid(cx(c), cy(r), MapData.R + 1);
        }

        private static double cx(int c) { return c * CELL + CELL / 2.0; }
        private static double cy(int r) { return r * CELL + CELL / 2.0; }

        static boolean free(double x, double y) {
            int c = (int) (x / CELL), r = (int) (y / CELL);
            return c >= 0 && r >= 0 && c < COLS && r < ROWS && FREE[r * COLS + c];
        }

        /** A player can walk the straight line without touching a wall. */
        static boolean walkable(double x0, double y0, double x1, double y1) {
            double len = Math.hypot(x1 - x0, y1 - y0);
            int n = Math.max(1, (int) (len / 6));
            for (int i = 1; i <= n; i++) {
                double t = (double) i / n;
                if (MapData.nearSolid(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, MapData.R - 2)) return false;
            }
            return true;
        }

        /** A bullet would fly the straight line without hitting a wall. */
        static boolean clearShot(double x0, double y0, double x1, double y1) {
            double len = Math.hypot(x1 - x0, y1 - y0);
            int n = Math.max(1, (int) (len / 4));
            for (int i = 1; i < n; i++) {
                double t = (double) i / n;
                if (MapData.nearSolid(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0)) return false;
            }
            return true;
        }

        /** Waypoints from (x0, y0) to (x1, y1), ending exactly at the goal; empty if there is no way. */
        static List<double[]> path(double x0, double y0, double x1, double y1) {
            int start = nearestFree(x0, y0), goal = nearestFree(x1, y1);
            if (start < 0 || goal < 0) return List.of();
            double[] g = new double[COLS * ROWS];
            Arrays.fill(g, Double.MAX_VALUE);
            int[] from = new int[COLS * ROWS];
            Arrays.fill(from, -1);
            boolean[] done = new boolean[COLS * ROWS];
            PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            g[start] = 0;
            open.add(new double[]{h(start, goal), start});
            while (!open.isEmpty()) {
                int cur = (int) open.poll()[1];
                if (done[cur]) continue;
                done[cur] = true;
                if (cur == goal) break;
                int r = cur / COLS, c = cur % COLS;
                for (int dr = -1; dr <= 1; dr++)
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int nr = r + dr, nc = c + dc;
                        if (nr < 0 || nc < 0 || nr >= ROWS || nc >= COLS || !FREE[nr * COLS + nc]) continue;
                        if (dr != 0 && dc != 0 && (!FREE[r * COLS + nc] || !FREE[nr * COLS + c])) continue;   // no corner cutting
                        int n = nr * COLS + nc;
                        double ng = g[cur] + (dr != 0 && dc != 0 ? 1.4142 : 1);
                        if (ng < g[n]) { g[n] = ng; from[n] = cur; open.add(new double[]{ng + h(n, goal), n}); }
                    }
            }
            if (from[goal] < 0 && goal != start) return List.of();
            List<double[]> out = new ArrayList<>();
            for (int n = goal; n != start; n = from[n]) out.add(new double[]{cx(n % COLS), cy(n / COLS)});
            Collections.reverse(out);
            if (free(x1, y1)) out.add(new double[]{x1, y1});
            return out;
        }

        private static double h(int a, int b) {   // octile distance
            double dc = Math.abs(a % COLS - b % COLS), dr = Math.abs(a / COLS - b / COLS);
            return Math.max(dc, dr) + 0.4142 * Math.min(dc, dr);
        }

        /** The free cell nearest to a point (the point itself may be inside a wall's margin). */
        private static int nearestFree(double x, double y) {
            int c0 = Math.max(0, Math.min(COLS - 1, (int) (x / CELL))), r0 = Math.max(0, Math.min(ROWS - 1, (int) (y / CELL)));
            for (int rad = 0; rad < Math.max(COLS, ROWS); rad++)
                for (int r = r0 - rad; r <= r0 + rad; r++)
                    for (int c = c0 - rad; c <= c0 + rad; c++) {
                        if (r < 0 || c < 0 || r >= ROWS || c >= COLS) continue;
                        if (Math.max(Math.abs(r - r0), Math.abs(c - c0)) == rad && FREE[r * COLS + c]) return r * COLS + c;
                    }
            return -1;
        }
    }
}

package ctf.client;

import ctf.common.MapData;
import ctf.common.Snapshot;

import javax.swing.*;
import java.util.*;

/** Standalone test: a fake 30Hz "server" with 3 wandering bots, so Part B runs without Parts A/C. */
public class Demo implements NetLink {
    // shortcut: bots wander and shoot on sight but never chase flags; only "You" plays the flag. Replace with Part A's server.
    static final int ME = 0;
    final Snapshot s = new Snapshot();
    final double[][] bot = new double[4][4]; // x, y, vx, vy
    volatile int dx, dy;
    volatile double aim;
    volatile boolean shoot;
    final double[] cd = new double[4]; // shot cooldown per player
    final List<double[]> bullets = new ArrayList<>(); // x, y, vx, vy, shooter id
    final long[] respawnAt = new long[4];
    final long[] droppedAt = new long[2];
    final Random r = new Random();
    double clock = 300;
    double tentCooldown;
    double matchEndElapsedSec;
    boolean lag;

    public static void main(String[] a) {
        Demo d = new Demo();
        SwingUtilities.invokeLater(() -> {
            GamePanel gp = new GamePanel(d, ME);
            JFrame f = new JFrame("CTF client demo");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            f.add(gp); f.pack(); f.setLocationRelativeTo(null); f.setVisible(true);
            gp.requestFocusInWindow();
            d.start(gp);
        });
    }

    void start(GamePanel gp) {
        String[] names = {"You", "Ann", "Bob", "Cy"};
        s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.name = names[i]; p.team = i % 2;
            p.x = bot[i][0] = MapData.BASES[p.team][0]; p.y = bot[i][1] = MapData.BASES[p.team][1];
            bot[i][2] = r.nextDouble() * 120 - 60; bot[i][3] = r.nextDouble() * 120 - 60;
            s.players[i] = p;
        }
        s.timeLeftSec = 300;
        returnFlag(0); returnFlag(1);

        new javax.swing.Timer(33, e -> {
            double dt = 1.0 / 30;
            Snapshot.P m = s.players[ME];
            double[] me = {m.x, m.y};
            double len = Math.hypot(dx, dy);
            if (len > 0 && m.alive) { me[0] += dx / len * MapData.SPEED * dt; me[1] += dy / len * MapData.SPEED * dt; }
            MapData.resolve(me);
            m.x = me[0]; m.y = me[1]; m.aim = aim;
            stepBots(dt);
            stepFlags();
            stepTent(dt);
            stepBullets(dt);
            clock = Math.max(0, clock - dt); s.timeLeftSec = (int) clock;
            if (clock <= 0 && s.winner < 0) {
                s.winner = s.score[1] > s.score[0] ? 1 : 0;
            }
            if (s.winner >= 0) {
                matchEndElapsedSec += dt;
                s.matchEndElapsedSec = matchEndElapsedSec;
            } else {
                matchEndElapsedSec = 0;
                s.matchEndElapsedSec = 0;
            }
            gp.onSnapshot(copy(s));
        }).start();
    }

    void stepTent(double dt) {
        tentCooldown = Math.max(0, tentCooldown - dt);
        s.tentCooldown = tentCooldown;
        for (Snapshot.P p : s.players) p.shieldSec = Math.max(0, p.shieldSec - dt);
        for (Snapshot.P p : s.players) {
            if (p.alive && Math.hypot(p.x - MapData.TENT_X, p.y - MapData.TENT_Y) <= MapData.TENT_R) {
                if (tentCooldown <= 0 && p.shieldSec <= 0) {
                    p.shieldSec = MapData.SHIELD_DURATION;
                    tentCooldown = MapData.TENT_RECHARGE;
                    s.tentCooldown = tentCooldown;
                    break;
                }
            }
        }
    }

    /** Bots wander (new direction when blocked by a wall) and shoot the first enemy they can see. */
    void stepBots(double dt) {
        for (int i = 1; i < 4; i++) {
            Snapshot.P p = s.players[i];
            double[] b = bot[i];
            cd[i] -= dt;
            if (!p.alive) continue;
            double[] q = {p.x + b[2] * dt, p.y + b[3] * dt};
            MapData.resolve(q);
            boolean stuck = Math.hypot(q[0] - p.x, q[1] - p.y) < Math.hypot(b[2], b[3]) * dt / 2;
            if (stuck || r.nextInt(30) == 0) { b[2] = r.nextDouble() * 160 - 80; b[3] = r.nextDouble() * 160 - 80; }
            p.x = q[0]; p.y = q[1];
            p.aim = Math.atan2(b[3], b[2]);
            for (Snapshot.P e : s.players)
                if (e.team != p.team && e.alive && Math.hypot(e.x - p.x, e.y - p.y) < 300 && clear(p.x, p.y, e.x, e.y)) {
                    p.aim = Math.atan2(e.y - p.y, e.x - p.x);
                    if (cd[i] <= 0) { cd[i] = 0.9; fire(i, p.aim + (r.nextDouble() - 0.5) * 0.3); } // +-8 degrees so bots miss sometimes
                    break;
                }
        }
    }

    /** "You" (red) vs the blue flag: pick up at base or where dropped, blue returns it by touch or after 10s, score at own base. */
    void stepFlags() {
        Snapshot.F bf = s.flags[1];
        Snapshot.P m = s.players[ME];
        if (bf.state != Snapshot.CARRIED && m.alive && Math.hypot(m.x - bf.x, m.y - bf.y) < 25) { bf.state = Snapshot.CARRIED; bf.carrier = ME; }
        if (bf.state == Snapshot.DROPPED) {
            boolean back = System.currentTimeMillis() > droppedAt[1] + 10_000;
            for (Snapshot.P p : s.players) back |= p.team == 1 && p.alive && Math.hypot(p.x - bf.x, p.y - bf.y) < 25;
            if (back) returnFlag(1);
        }
        if (bf.state == Snapshot.CARRIED && Math.hypot(m.x - MapData.BASES[0][0], m.y - MapData.BASES[0][1]) < 45) {
            returnFlag(1); s.score[0]++; m.captures++;
            m.score = m.captures * 100 + m.tags * 25;
            if (s.score[0] >= 3) s.winner = 0;
        }
    }

    void returnFlag(int t) {
        Snapshot.F f = s.flags[t];
        f.state = Snapshot.BASE; f.carrier = -1; f.x = MapData.BASES[t][0]; f.y = MapData.BASES[t][1];
    }

    void fire(int shooter, double ang) {
        Snapshot.P p = s.players[shooter];
        bullets.add(new double[]{p.x, p.y, Math.cos(ang) * MapData.BULLET_SPEED, Math.sin(ang) * MapData.BULLET_SPEED, shooter});
    }

    void stepBullets(double dt) {
        long now = System.currentTimeMillis();
        cd[ME] -= dt;
        if (shoot && cd[ME] <= 0 && s.players[ME].alive) { cd[ME] = MapData.FIRE_INTERVAL; fire(ME, aim); }
        shoot = false; // a click is consumed per server tick; held fire re-sends true every message
        for (Iterator<double[]> it = bullets.iterator(); it.hasNext(); ) {
            double[] b = it.next();
            Snapshot.P sh = s.players[(int) b[4]];
            double ox = b[0], oy = b[1];
            b[0] += b[2] * dt; b[1] += b[3] * dt;
            boolean gone = b[0] < 0 || b[0] > MapData.W || b[1] < 0 || b[1] > MapData.H || inWall(b[0], b[1]);
            for (Snapshot.P p : s.players)
                if (p.team != sh.team && p.alive && MapData.segHits(ox, oy, b[0], b[1], p.x, p.y, MapData.R)) {
                    if (p.shieldSec > 0) {
                        gone = true; // absorbed / deflected by protective sanctuary shield!
                        break;
                    } else {
                        p.alive = false; respawnAt[p.id] = now + 3000; sh.tags++; p.deaths++; gone = true;
                        sh.score = sh.captures * 100 + sh.tags * 25;
                        p.score = p.captures * 100 + p.tags * 25;
                        for (int t = 0; t < 2; t++) { // a tagged carrier drops the flag where they fell
                            Snapshot.F f = s.flags[t];
                            if (f.state == Snapshot.CARRIED && f.carrier == p.id) { f.state = Snapshot.DROPPED; f.carrier = -1; f.x = p.x; f.y = p.y; droppedAt[t] = now; }
                        }
                        break;
                    }
                }
            if (gone) it.remove();
        }
        for (Snapshot.P p : s.players)
            if (!p.alive && now >= respawnAt[p.id]) { p.alive = true; p.x = MapData.BASES[p.team][0]; p.y = MapData.BASES[p.team][1]; }
        s.bullets = new double[bullets.size()][];
        for (int i = 0; i < s.bullets.length; i++) s.bullets[i] = new double[]{bullets.get(i)[0], bullets.get(i)[1], s.players[(int) bullets.get(i)[4]].team};
    }

    static boolean inWall(double x, double y) {
        for (double[] w : MapData.WALLS) if (x > w[0] && x < w[0] + w[2] && y > w[1] && y < w[1] + w[3]) return true;
        return false;
    }

    /** Line of sight: samples the segment every ~6px (thinnest wall is 24px). */
    static boolean clear(double x0, double y0, double x1, double y1) {
        int n = (int) (Math.hypot(x1 - x0, y1 - y0) / 6) + 1;
        for (int k = 1; k < n; k++) if (inWall(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n)) return false;
        return true;
    }

    static Snapshot copy(Snapshot o) {
        Snapshot c = new Snapshot();
        c.players = new Snapshot.P[o.players.length];
        for (int i = 0; i < c.players.length; i++) {
            Snapshot.P a = o.players[i], p = new Snapshot.P();
            p.aim = a.aim; p.id = a.id; p.name = a.name; p.team = a.team; p.x = a.x; p.y = a.y; p.alive = a.alive;
            p.shieldSec = a.shieldSec;
            p.captures = a.captures; p.tags = a.tags; p.deaths = a.deaths; p.score = a.score; p.pingMs = a.pingMs;
            c.players[i] = p;
        }
        for (int t = 0; t < 2; t++) {
            Snapshot.F f = c.flags[t]; Snapshot.F a = o.flags[t];
            f.state = a.state; f.carrier = a.carrier; f.x = a.x; f.y = a.y;
        }
        c.bullets = o.bullets.clone();
        c.score = o.score.clone(); c.timeLeftSec = o.timeLeftSec; c.winner = o.winner;
        c.tentCooldown = o.tentCooldown;
        c.matchEndElapsedSec = o.matchEndElapsedSec;
        return c;
    }

    @Override public void sendInput(int dx, int dy, double aim, boolean shoot) { this.dx = dx; this.dy = dy; this.aim = aim; this.shoot |= shoot; }
    @Override public int pingMs() { return lag ? 180 : 20; }
    @Override public void requestPlayAgain() {
        s.score = new int[2];
        s.winner = -1;
        clock = 300;
        s.timeLeftSec = 300;
        s.tentCooldown = tentCooldown = 0;
        s.matchEndElapsedSec = matchEndElapsedSec = 0;
        for (Snapshot.P p : s.players) {
            p.shieldSec = 0;
            p.tags = 0;
            p.captures = 0;
            p.deaths = 0;
            p.score = 0;
        }
        returnFlag(0);
        returnFlag(1);
    }
    @Override public void toggleLagSim() { lag = !lag; }
    @Override public boolean lagSimOn() { return lag; }
}

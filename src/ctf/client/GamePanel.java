package ctf.client;

import ctf.common.MapData;
import ctf.common.Snapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Part B: render loop, input, drawing, prediction, interpolation and effects.
 * Networking (Part C) calls onSnapshot(); this panel calls NetLink.sendInput().
 * Everything decorative (animation frames, particles, light) is derived locally from the snapshots.
 */
public class GamePanel extends JPanel {
    static final String[] TEAM_NAME = {"RED", "BLUE"};
    static final Color[] TEAM = {new Color(235, 70, 70), new Color(70, 150, 255)};
    static final long INTERP_DELAY_NS = 100_000_000L; // render 100ms in the past (~3 snapshots)
    static final double SHOOT_COOLDOWN = 0.25;
    static final int MAX_PARTICLES = 1000;

    private static final class Particle {
        double x, y, vx, vy, life, max; Color c; int size = 4; boolean soft;
    }

    private static final class FireworkRocket {
        double x, y, vx, vy, fuse; Color c; int team;
    }

    private static final class FireworkSpark {
        double x, y, vx, vy, life, max; Color c; int size;
    }

    private static final class MagicalFlame {
        double x, y, vx, vy, life, max; Color c; int size;
    }

    private final NetLink link;
    private final int myId;

    private final List<Snapshot> buf = new ArrayList<>();
    private Snapshot last;
    private Map<Integer, double[]> renderPos = new HashMap<>();
    private final Map<Integer, double[]> anim = new HashMap<>(); // id -> {phase, moving-hold, prevX, prevY}
    private final List<Particle> parts = new ArrayList<>();
    private final List<double[]> beams = new ArrayList<>();      // {x, y, team, life}
    private final List<Object[]> feed = new ArrayList<>();       // {text, expiryNs}
    private final double[][] embers = new double[72][4];         // x, y, speed, phase
    private final List<FireworkRocket> rockets = new ArrayList<>();
    private final List<FireworkSpark> sparks = new ArrayList<>();
    private final List<MagicalFlame> flames = new ArrayList<>();
    private double victoryTimer;
    private int celebrationWinner = -1;
    private final Rectangle btnPlayAgainBounds = new Rectangle();
    private boolean hoverPlayAgain;
    private boolean viewLeaderboardManual;
    private final Rectangle btnLeaderboardToggle = new Rectangle();
    private boolean hoverLeaderboardToggle;
    private final Rectangle btnLeaderboardPlayAgain = new Rectangle();
    private boolean hoverLeaderboardPlayAgain;
    private final Rectangle btnLeaderboardBack = new Rectangle();
    private boolean hoverLeaderboardBack;
    private double nextRocketTime;

    // predicted own bullets: {ox, oy, vx, vy, team, age, matchedBefore, x, y}; see reconcileGhosts()
    private final List<double[]> ghosts = new ArrayList<>();
    private final List<double[]> recentImpacts = new ArrayList<>(); // {x, y, age}: ghost impacts, so the server's copy is not shown twice
    private final Map<Integer, Double> fireT = new HashMap<>();     // player id -> seconds of muzzle flash left
    private final Set<double[]> hidden = Collections.newSetFromMap(new IdentityHashMap<>()); // server bullets drawn as ghosts instead
    private Snapshot prev;
    private boolean shootLatch, firedSinceSend, shakeOn = true;

    private final Set<Integer> keys = new HashSet<>();
    private int mouseX, mouseY;
    private boolean mouseDown, showBoard, fx = true;

    private final double[] pred = new double[2];
    private boolean predReady, wasAlive = true;

    private double shake, flash, shootCd, sendAcc, time;
    private long lastTickNs = System.nanoTime();
    private final Random rnd = new Random();
    private final Scenery scenery = new Scenery();
    // The part of the world on screen, in world units. GameView sets it so the picture fills the whole window;
    // until then (and when a test paints the panel directly) it is simply the panel's own size.
    private double viewX, viewY, viewW, viewH;
    private boolean viewSet;
    private BufferedImage vignetteImg;
    private double vigScale, vigW, vigH;
    private final List<double[]> rings = new ArrayList<>();      // {x, y, team (-1 white), age, life, radius}
    private final List<Object[]> notices = new ArrayList<>();    // {text, color, age}: big centre-screen announcements
    private final double[] scorePop = new double[2];             // 1 -> 0 after a team scores, drives the score bounce
    private final Map<Integer, Double> spawnT = new HashMap<>(); // player id -> seconds of respawn materialise left
    private double smokeT;
    private final List<Object[]> blooms = new ArrayList<>();      // {x, y, Color, age}: short light flashes at each firework burst

    public GamePanel(NetLink link, int myId) {
        this.link = link;
        this.myId = myId;
        setPreferredSize(new Dimension(MapData.W, MapData.H));
        setBackground(new Color(10, 8, 20));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false); // let TAB reach the key listener
        for (double[] e : embers) respawnEmber(e, true);
        Sfx.play(""); // loads the sound class now, not on the first shot

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                keys.add(k);
                if (k == KeyEvent.VK_TAB) {
                    if (last != null && last.winner >= 0) {
                        viewLeaderboardManual = !viewLeaderboardManual;
                    } else {
                        showBoard = true;
                    }
                }
                if (k == KeyEvent.VK_L) link.toggleLagSim();
                if (k == KeyEvent.VK_V) shakeOn = !shakeOn;
                if (k == KeyEvent.VK_F) fx = !fx; // optional effects off if they cost frames
                if ((k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) && last != null && last.winner >= 0) {
                    setCursor(Cursor.getDefaultCursor());
                    link.requestPlayAgain();
                }
            }
            @Override public void keyReleased(KeyEvent e) {
                keys.remove(e.getKeyCode());
                if (e.getKeyCode() == KeyEvent.VK_TAB && (last == null || last.winner < 0)) showBoard = false;
            }
        });
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                mouseX = e.getX(); mouseY = e.getY();
                if (last != null && last.winner >= 0) {
                    boolean autoLb = (last.matchEndElapsedSec >= 60.0) || (victoryTimer >= 60.0);
                    boolean showingLb = autoLb || viewLeaderboardManual;
                    boolean anyHover;
                    if (!showingLb) {
                        hoverPlayAgain = btnPlayAgainBounds.contains(mouseX, mouseY);
                        hoverLeaderboardToggle = btnLeaderboardToggle.contains(mouseX, mouseY);
                        anyHover = hoverPlayAgain || hoverLeaderboardToggle;
                    } else {
                        hoverLeaderboardPlayAgain = btnLeaderboardPlayAgain.contains(mouseX, mouseY);
                        hoverLeaderboardBack = btnLeaderboardBack.contains(mouseX, mouseY);
                        anyHover = hoverLeaderboardPlayAgain || hoverLeaderboardBack;
                    }
                    setCursor(Cursor.getPredefinedCursor(anyHover ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            }
            @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
            @Override public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                if (last != null && last.winner >= 0) {
                    boolean autoLb = (last.matchEndElapsedSec >= 60.0) || (victoryTimer >= 60.0);
                    boolean showingLb = autoLb || viewLeaderboardManual;
                    if (!showingLb) {
                        if (btnLeaderboardToggle.contains(e.getX(), e.getY())) {
                            viewLeaderboardManual = true;
                            return;
                        }
                        if (btnPlayAgainBounds.contains(e.getX(), e.getY())) {
                            setCursor(Cursor.getDefaultCursor());
                            link.requestPlayAgain();
                            return;
                        }
                    } else {
                        if (btnLeaderboardPlayAgain.contains(e.getX(), e.getY())) {
                            setCursor(Cursor.getDefaultCursor());
                            link.requestPlayAgain();
                            return;
                        }
                        if (btnLeaderboardBack.contains(e.getX(), e.getY())) {
                            viewLeaderboardManual = false;
                            return;
                        }
                    }
                }
                mouseMoved(e); // aim at the click point, not at the last move event
                mouseDown = shootLatch = true; // latch: a click shorter than one send interval still fires
                requestFocusInWindow();
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1) mouseDown = false; }
        };
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { keys.clear(); mouseDown = false; } // no stuck keys or auto-fire
        });
        addMouseListener(m);
        addMouseMotionListener(m);

        new javax.swing.Timer(16, e -> tick()).start();
    }

    // ---------- network entry point (any thread) ----------

    public void onSnapshot(Snapshot s) {
        s.arrivedNs = System.nanoTime();
        SwingUtilities.invokeLater(() -> accept(s));
    }

    private void accept(Snapshot s) {
        if (last != null) detectEvents(last, s);
        prev = last;
        last = s;
        buf.add(s);
        Snapshot.P me = find(s, myId);
        if (me != null) {
            if (!predReady || (me.alive && !wasAlive)) { pred[0] = me.x; pred[1] = me.y; predReady = true; }
            else if (me.alive) { // quietly pull prediction toward the authoritative position
                double ex = me.x - pred[0], ey = me.y - pred[1];
                if (Math.hypot(ex, ey) > 60) { pred[0] = me.x; pred[1] = me.y; }
                else { pred[0] += ex * 0.15; pred[1] += ey * 0.15; }
            }
            wasAlive = me.alive;
        }
    }

    // ---------- per-frame update ----------

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastTickNs) / 1e9);
        lastTickNs = now;
        time += dt;

        int dx = (down(KeyEvent.VK_D) || down(KeyEvent.VK_RIGHT) ? 1 : 0) - (down(KeyEvent.VK_A) || down(KeyEvent.VK_LEFT) ? 1 : 0);
        int dy = (down(KeyEvent.VK_S) || down(KeyEvent.VK_DOWN) ? 1 : 0) - (down(KeyEvent.VK_W) || down(KeyEvent.VK_UP) ? 1 : 0);

        // client-side prediction: move now, server corrects later
        Snapshot.P me = last == null ? null : find(last, myId);
        if (predReady && me != null && me.alive && (dx != 0 || dy != 0)) {
            double len = Math.hypot(dx, dy);
            pred[0] += dx / len * MapData.SPEED * dt;
            pred[1] += dy / len * MapData.SPEED * dt;
            MapData.resolve(pred);
        }
        double aim = predReady ? Math.atan2(mouseY - pred[1], mouseX - pred[0]) : 0;

        shootCd -= dt;
        if ((mouseDown || shootLatch) && shootCd <= 0 && me != null && me.alive) {
            shootCd = MapData.FIRE_INTERVAL; // mirrors the server's limit; no extra delay
            shootLatch = false;
            firedSinceSend = true;
            sendAcc = 1; // send this very tick instead of waiting for the next 30Hz slot
            ghosts.add(new double[]{pred[0], pred[1], Math.cos(aim) * MapData.BULLET_SPEED, Math.sin(aim) * MapData.BULLET_SPEED,
                me.team, 0, 0, pred[0], pred[1]});
            fireT.put(myId, 0.08);
            Sfx.play("shoot");
        } else if (me == null || !me.alive) shootLatch = false;

        sendAcc += dt;
        if (sendAcc >= 1.0 / 30) { // match the server tick rate
            sendAcc = 0;
            link.sendInput(dx, dy, aim, mouseDown || firedSinceSend);
            firedSinceSend = false;
        }

        // where everyone is drawn this frame, and whether they are walking
        renderPos = interpolate();
        if (me != null && me.alive && predReady) renderPos.put(myId, pred.clone());
        for (Map.Entry<Integer, double[]> e : renderPos.entrySet()) {
            double[] a = anim.computeIfAbsent(e.getKey(), k -> new double[]{0, 0, e.getValue()[0], e.getValue()[1]});
            double[] xy = e.getValue();
            a[1] = Math.hypot(xy[0] - a[2], xy[1] - a[3]) > 0.25 ? 0.15 : Math.max(0, a[1] - dt);
            if (a[1] > 0) a[0] += dt * 10;
            a[2] = xy[0]; a[3] = xy[1];
        }

        reconcileGhosts(dt);
        for (Iterator<Map.Entry<Integer, Double>> it = fireT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Double> e = it.next();
            double left = e.getValue() - dt;
            if (left <= 0) it.remove(); else e.setValue(left);
        }
        if (last != null) // a new server bullet right at someone's gun means they just fired
            for (double[] b : last.bullets) if (!hidden.contains(b))
                for (Snapshot.P p : last.players) {
                    double[] xy = renderPos.get(p.id);
                    if (xy == null || !p.alive || p.team != teamOf(b)) continue;
                    if (Math.hypot(b[0] - (xy[0] + Math.cos(p.aim) * 14), b[1] - (xy[1] + Math.sin(p.aim) * 14)) < 22) fireT.put(p.id, 0.08);
                }
        recentImpacts.removeIf(r -> (r[2] += dt) > 0.6);
        for (Iterator<Particle> it = parts.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt; p.x += p.vx * dt; p.y += p.vy * dt;
            if (p.life <= 0) it.remove();
        }
        for (Iterator<double[]> it = beams.iterator(); it.hasNext(); ) if ((it.next()[3] -= dt) <= 0) it.remove();
        for (double[] e : embers) {
            e[1] -= e[2] * dt;
            e[0] += Math.sin(time * 1.5 + e[3]) * 12 * dt;
            if (e[1] < vY0() - 5) respawnEmber(e, false);
        }
        if (last != null && last.winner >= 0) {
            victoryTimer += dt;
            stepCelebration(dt, last.winner);
        } else {
            victoryTimer = 0;
            rockets.clear();
            sparks.clear();
            flames.clear();
        }
        for (Iterator<Object[]> it = blooms.iterator(); it.hasNext(); ) { Object[] b = it.next(); b[3] = (Double) b[3] + dt; if ((Double) b[3] > 0.4) it.remove(); }
        for (Iterator<double[]> it = rings.iterator(); it.hasNext(); ) { double[] r = it.next(); if ((r[3] += dt) >= r[4]) it.remove(); }
        for (Iterator<Object[]> it = notices.iterator(); it.hasNext(); ) { Object[] n = it.next(); n[2] = (Double) n[2] + dt; if ((Double) n[2] > 2.6) it.remove(); }
        for (int t = 0; t < 2; t++) scorePop[t] = Math.max(0, scorePop[t] - 1.4 * dt);
        for (Iterator<Map.Entry<Integer, Double>> it = spawnT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Double> e = it.next();
            double left = e.getValue() - dt;
            if (left <= 0) it.remove(); else e.setValue(left);
        }
        smokeT -= dt;
        if (fx && smokeT <= 0) { // smoke and sparks rising from the braziers
            smokeT = 0.14;
            for (double[] w : MapData.WALLS) {
                double tx = w[0] + w[2] / 2, ty = w[1] - 16;
                addSoft(tx + (rnd.nextDouble() - 0.5) * 6, ty, (rnd.nextDouble() - 0.5) * 10, -22 - rnd.nextDouble() * 12, 1.1, new Color(120, 112, 150), 9);
                if (rnd.nextInt(3) == 0) addP(tx, ty + 4, (rnd.nextDouble() - 0.5) * 30, -50 - rnd.nextDouble() * 40, 0.6, new Color(255, 170, 70), 2);
            }
        }
        shake = Math.max(0, shake - 40 * dt);
        flash = Math.max(0, flash - 2.5 * dt);
        feed.removeIf(f -> (long) f[1] < now);
        repaint();
    }

    /**
     * Own shots are drawn from a local prediction so they appear on the click, while the server stays the
     * only judge of hits. A server bullet lying on a ghost's ray is that ghost's twin: it is hidden, not drawn twice.
     * A ghost the server never answers within about one round trip was rejected (cooldown etc.) and is dropped.
     * A ghost whose twin vanished (hit or wall on the server) is dropped too.
     */
    private void reconcileGhosts(double dt) {
        hidden.clear();
        boolean[] matchedNow = new boolean[ghosts.size()];
        if (last != null) {
            for (double[] b : last.bullets)
                for (int i = 0; i < ghosts.size(); i++) {
                    double[] g = ghosts.get(i);
                    double along = ((b[0] - g[0]) * g[2] + (b[1] - g[1]) * g[3]) / MapData.BULLET_SPEED;
                    double side = Math.abs((b[0] - g[0]) * g[3] - (b[1] - g[1]) * g[2]) / MapData.BULLET_SPEED;
                    if ((int) g[4] == teamOf(b) && side < 8 && along > -8 && along < MapData.BULLET_SPEED * g[5] + 14) {
                        matchedNow[i] = true;
                        hidden.add(b);
                        break;
                    }
                }
        }
        double grace = link.pingMs() / 1000.0 + 0.2;
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            double[] g = ghosts.get(i);
            g[5] += dt;
            g[7] = g[0] + g[2] * g[5];
            g[8] = g[1] + g[3] * g[5];
            if (matchedNow[i]) g[6] = 1;
            if (MapData.nearSolid(g[7], g[8], 0)) { // wall impact, shown at once on our own screen
                impact(g[7], g[8], (int) g[4]);
                Sfx.play("wall");
                recentImpacts.add(new double[]{g[7], g[8], 0});
                ghosts.remove(i);
            } else if ((g[6] == 1 && !matchedNow[i]) || (g[6] == 0 && g[5] > grace)) ghosts.remove(i);
        }
    }

    private void impact(double x, double y, int team) {
        burst(x, y, Art.FX[team], 8, 110);
        burst(x, y, new Color(150, 140, 170), 4, 50);
        addP(x, y, 0, 0, 0.1, Color.WHITE, 9);
    }

    private void respawnEmber(double[] e, boolean anywhere) {
        e[0] = vX0() + rnd.nextDouble() * vW();
        e[1] = anywhere ? vY0() + rnd.nextDouble() * vH() : vY0() + vH() + 5;
        e[2] = 15 + rnd.nextDouble() * 30;
        e[3] = rnd.nextDouble() * 6.28;
    }

    private boolean down(int k) { return keys.contains(k); }

    private static int teamOf(double[] bullet) { return Math.max(0, Math.min(1, (int) bullet[2])); }

    // ---------- events inferred from snapshot differences ----------

    private void detectEvents(Snapshot a, Snapshot b) {
        for (Snapshot.P p : b.players) {
            Snapshot.P o = find(a, p.id);
            if (o == null) { say(p.name + " joined"); continue; }
            if (o.alive && !p.alive) {
                burst(o.x, o.y, Art.FX[o.team], 28, 230);
                addP(o.x, o.y, 0, 0, 0.12, Color.WHITE, 14);
                ring(o.x, o.y, o.team, 0.55, 58);
                Sfx.play("hit");
                say(p.name + " was eliminated", TEAM[o.team]);
                if (p.id == myId) { flash = 1; shake = 12; }
            } else if (!o.alive && p.alive) {
                burst(p.x, p.y, Art.FX[p.team], 18, 120);
                beams.add(new double[]{p.x, p.y + 12, p.team, 0.6});
                ring(p.x, p.y, p.team, 0.7, 50);
                spawnT.put(p.id, 0.7);
            }
            // Shield state transitions
            if (o.shieldSec <= 0 && p.shieldSec > 0) {
                burst(p.x, p.y, new Color(170, 70, 255), 24, 160);
                burst(p.x, p.y, new Color(80, 230, 255), 16, 120);
                Sfx.play("shield");
                say(p.name + " activated MAGIC SHIELD", new Color(170, 90, 255));
                if (p.id == myId) announce("MAGIC SHIELD", new Color(170, 90, 255));
            } else if (o.shieldSec > 0 && p.shieldSec <= 0) {
                burst(p.x, p.y, new Color(170, 60, 255), 20, 140);
                Sfx.play("expire");
                if (p.id == myId) say("MAGIC SHIELD EXPIRED");
            }
        }
        for (Snapshot.P o : a.players) if (find(b, o.id) == null) say(o.name + " left");

        for (double[] o : a.bullets) { // a bullet that vanished next to a wall hit it (hits on players show as eliminations)
            boolean still = false;
            for (double[] n : b.bullets) if (Math.hypot(n[0] - o[0], n[1] - o[1]) < 30) { still = true; break; }
            boolean ours = false;
            for (double[] r : recentImpacts) if (Math.hypot(r[0] - o[0], r[1] - o[1]) < 40) ours = true;
            if (!still) {
                boolean deflected = false;
                for (Snapshot.P p : b.players) {
                    if (p.shieldSec > 0 && Math.hypot(p.x - o[0], p.y - o[1]) < MapData.R + 12) {
                        burst(p.x, p.y, new Color(90, 230, 255), 14, 160);
                        burst(p.x, p.y, new Color(200, 100, 255), 8, 120);
                        Sfx.play("deflect");
                        deflected = true;
                        break;
                    }
                }
                if (!deflected && !ours && MapData.nearSolid(o[0], o[1], 24)) impact(o[0], o[1], teamOf(o));
            }
        }

        for (int t = 0; t < 2; t++) {
            Snapshot.F o = a.flags[t], n = b.flags[t];
            if (n.state == Snapshot.CARRIED && o.state != Snapshot.CARRIED) {
                say(name(b, n.carrier) + " took the " + TEAM_NAME[t] + " flag", GOLD);
                announce(n.carrier == myId ? "YOU TOOK THE " + TEAM_NAME[t] + " FLAG" : TEAM_NAME[t] + " FLAG TAKEN", n.carrier == myId ? GOLD : TEAM[t]);
                Sfx.play("pickup");
                double[] at = flagSpot(b, n);
                burst(at[0], at[1], Art.FX[t], 22, 170);
                ring(at[0], at[1], t, 0.7, 54);
            } else if (n.state == Snapshot.DROPPED && o.state == Snapshot.CARRIED) {
                say(TEAM_NAME[t] + " flag dropped", new Color(255, 120, 90));
                announce(TEAM_NAME[t] + " FLAG DROPPED", new Color(255, 130, 100));
                Sfx.play("drop");
                ring(n.x, n.y, t, 0.6, 40);
            } else if (n.state == Snapshot.BASE && o.state == Snapshot.DROPPED) {
                say(TEAM_NAME[t] + " flag returned", Art.FX[t]);
                announce(TEAM_NAME[t] + " FLAG RETURNED", TEAM[t]);
                burst(MapData.BASES[t][0], MapData.BASES[t][1], Art.FX[t], 20, 150);
                beams.add(new double[]{MapData.BASES[t][0], MapData.BASES[t][1] + 12, t, 0.8});
                ring(MapData.BASES[t][0], MapData.BASES[t][1], t, 0.9, 70);
            }
            int other = 1 - t; // the team that scores by carrying flag t home
            if (b.score[other] > a.score[other]) {
                say(TEAM_NAME[other] + " captured the flag!", GOLD);
                announce(TEAM_NAME[other] + " CAPTURED THE FLAG!", TEAM[other]);
                scorePop[other] = 1;
                ring(MapData.BASES[other][0], MapData.BASES[other][1], other, 1.1, 120);
                Sfx.play("score");
                burst(MapData.BASES[other][0], MapData.BASES[other][1], Art.FX[other], 60, 280);
                beams.add(new double[]{MapData.BASES[other][0], MapData.BASES[other][1] + 12, other, 1.4});
                shake = Math.max(shake, 7);
            }
        }
        if (b.winner >= 0 && a.winner < 0) {
            Sfx.play("win");
            celebrationWinner = b.winner;
            victoryTimer = 0;
            triggerOpeningCelebration(b.winner);
        }
    }

    /** Where the flag was just picked up: the carrier if known, else its base. */
    private static double[] flagSpot(Snapshot s, Snapshot.F f) {
        Snapshot.P c = find(s, f.carrier);
        return c != null ? new double[]{c.x, c.y} : new double[]{f.x, f.y};
    }

    private void say(String s) { say(s, new Color(150, 140, 190)); }

    private void say(String s, Color c) {
        feed.add(new Object[]{s, System.nanoTime() + 4_000_000_000L, c});
        if (feed.size() > 5) feed.remove(0);
    }

    /** Large centre-screen message for the moments that matter. */
    private void announce(String text, Color c) {
        notices.add(new Object[]{text, c, 0.0});
        if (notices.size() > 2) notices.remove(0);
    }

    private void ring(double x, double y, int team, double life, double radius) {
        if (rings.size() < 24) rings.add(new double[]{x, y, team, 0, life, radius});
    }

    private void addP(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (parts.size() >= MAX_PARTICLES) return;
        Particle p = new Particle();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.max = p.life = life; p.c = c; p.size = size;
        parts.add(p);
    }

    /** Large, faint, growing puff (smoke). */
    private void addSoft(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (parts.size() >= MAX_PARTICLES) return;
        Particle p = new Particle();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.max = p.life = life; p.c = c; p.size = size; p.soft = true;
        parts.add(p);
    }

    private void burst(double x, double y, Color c, int n, double speed) {
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, sp = speed * (0.3 + rnd.nextDouble() * 0.7);
            addP(x, y, Math.cos(ang) * sp, Math.sin(ang) * sp, 0.3 + rnd.nextDouble() * 0.4, c, 5);
        }
    }

    // ---------- interpolation ----------

    /** Positions of every player at (now - delay), lerped between the two snapshots around that time. */
    private Map<Integer, double[]> interpolate() {
        Map<Integer, double[]> out = new HashMap<>();
        if (buf.isEmpty()) return out;
        long rt = System.nanoTime() - INTERP_DELAY_NS;
        while (buf.size() > 2 && buf.get(1).arrivedNs <= rt) buf.remove(0);
        Snapshot a = buf.get(0), b = buf.size() > 1 ? buf.get(1) : a;
        double t = 0;
        if (a != b && b.arrivedNs > a.arrivedNs) t = Math.max(0, Math.min(1, (double) (rt - a.arrivedNs) / (b.arrivedNs - a.arrivedNs)));
        for (Snapshot.P pb : b.players) {
            Snapshot.P pa = find(a, pb.id);
            if (pa == null) pa = pb;
            out.put(pb.id, new double[]{pa.x + (pb.x - pa.x) * t, pa.y + (pb.y - pa.y) * t});
        }
        return out;
    }

    // ---------- drawing ----------

    /** Tells the panel which part of the world is on screen (world units), so the picture can fill the whole window. */
    void setView(double x0, double y0, double w, double h) {
        viewX = x0; viewY = y0; viewW = w; viewH = h;
        viewSet = true;
    }

    private int ox() { return (int) Math.round(vX0()); }
    private int oy() { return (int) Math.round(vY0()); }
    private double vX0() { return viewSet ? viewX : 0; }
    private double vY0() { return viewSet ? viewY : 0; }
    private double vW() { return viewSet ? viewW : (getWidth() > 0 ? getWidth() : MapData.W); }
    private double vH() { return viewSet ? viewH : (getHeight() > 0 ? getHeight() : MapData.H); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        paintWorld(g);
        paintOverlay(g);
        g.dispose();
    }

    private double shakeX, shakeY;

    /**
     * The battlefield: scenery, fighters, bullets, particles. GameView may draw this at a lower resolution than
     * the screen when the computer is slow; nothing in here is text, so that costs no sharpness.
     */
    void paintWorld(Graphics2D g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // where the local fighter stands, -0.5..0.5 across the arena: drives the parallax layers
        double camX = predReady ? (pred[0] - MapData.W / 2.0) / MapData.W : 0, camY = predReady ? (pred[1] - MapData.H / 2.0) / MapData.H : 0;
        if (last == null) {
            scenery.drawBack(g, vX0(), vY0(), vW(), vH(), 0, 0, time, false);
            g.dispose();
            return;
        }
        Snapshot s = last;
        AffineTransform base = g.getTransform();
        double sh = shakeOn ? shake : 0;
        shakeX = (rnd.nextDouble() - 0.5) * sh; shakeY = (rnd.nextDouble() - 0.5) * sh;
        g.translate(shakeX, shakeY);

        scenery.drawBack(g, vX0(), vY0(), vW(), vH(), camX, camY, time, fx);
        drawBases(g);
        for (double[] w : MapData.WALLS) Art.wall(g, w);
        drawTorches(g);
        Art.tent(g, MapData.TENT_X, MapData.TENT_Y, s.tentCooldown <= 0 ? 1.0 : 1.0 - s.tentCooldown / MapData.TENT_RECHARGE, time, fx);
        drawEntities(g, s);
        for (double[] b : s.bullets) if (!hidden.contains(b)) drawBullet(g, b[0], b[1], bulletAngle(b), teamOf(b));
        for (double[] gh : ghosts) drawBullet(g, gh[7], gh[8], Math.atan2(gh[3], gh[2]), (int) gh[4]);
        for (Particle p : parts) {
            Composite old = g.getComposite();
            float a = (float) Math.max(0, Math.min(1, p.life / p.max));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, p.soft ? a * 0.35f : a));
            g.setColor(p.c);
            if (p.soft) {
                double r = p.size * (1.6 - a * 0.8);
                g.fill(new Ellipse2D.Double(p.x - r / 2, p.y - r / 2, r, r));
            } else g.fill(new Ellipse2D.Double(p.x - p.size / 2.0, p.y - p.size / 2.0, p.size, p.size));
            g.setComposite(old);
        }
        for (double[] r : rings) { // expanding ground ripples
            double k = r[3] / r[4], rad = r[5] * (1 - (1 - k) * (1 - k));
            Color c = r[2] < 0 ? Color.WHITE : Art.FX[(int) r[2]];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (200 * (1 - k))));
            g.setStroke(new BasicStroke((float) (3.5 * (1 - k) + 0.8)));
            g.draw(new Ellipse2D.Double(r[0] - rad, r[1] + 10 - rad * 0.5, rad * 2, rad));
        }
        g.setStroke(new BasicStroke(1));
        for (double[] b : beams) Art.beam(g, b[0], b[1], Art.FX[(int) b[2]], (float) Math.min(1, b[3]));
        if (fx) drawEmbers(g);

        // Fireworks and celebration flames in world space
        for (FireworkRocket r : rockets) {
            g.setColor(r.c);
            g.fill(new Ellipse2D.Double(r.x - 2, r.y - 4, 4, 8));
        }
        for (FireworkSpark sp : sparks) {
            Composite oldComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, sp.life / sp.max)));
            g.setColor(sp.c);
            g.setStroke(new BasicStroke(sp.size * 0.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(sp.x, sp.y, sp.x - sp.vx * 0.045, sp.y - sp.vy * 0.045)); // motion streak
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(sp.x - sp.size * 0.3, sp.y - sp.size * 0.3, sp.size * 0.6, sp.size * 0.6));
            g.setComposite(oldComp);
        }
        if (fx) for (Object[] b : blooms) Art.glow(g, (Color) b[2], (Double) b[0], (Double) b[1], 120, (float) (0.55 * (1 - (Double) b[3] / 0.4)));
        if (fx && flames.size() > 20) // the fire lights the whole room
            for (int i = 0; i < 6; i++) Art.glow(g, new Color(255, 120, 40), vX0() + vW() * (i + 0.5) / 6, vY0() + vH(), 190, (float) (0.28 + 0.08 * Math.sin(time * 7 + i)));
        g.setStroke(new BasicStroke(1));
        for (MagicalFlame fl : flames) { // layered flame tongues: flame colour outside, orange and white-hot inside
            double k = Math.max(0, fl.life / fl.max), sz = fl.size * (0.4 + 0.6 * k), h = sz * 2.0, w = sz * 1.35;
            double sway = Math.sin(time * 9 + fl.x * 0.1) * sz * 0.25;
            int al = (int) (215 * Math.min(1, k * 1.8));
            for (int layer = 0; layer < 3; layer++) {
                double sc = 1 - layer * 0.34, lh = h * sc, lw = w * sc;
                Path2D.Double p = new Path2D.Double();
                p.moveTo(fl.x - lw / 2, fl.y);
                p.curveTo(fl.x - lw / 2, fl.y - lh * 0.45, fl.x + sway - lw * 0.15, fl.y - lh * 0.72, fl.x + sway, fl.y - lh);
                p.curveTo(fl.x + sway + lw * 0.15, fl.y - lh * 0.72, fl.x + lw / 2, fl.y - lh * 0.45, fl.x + lw / 2, fl.y);
                p.closePath();
                g.setColor(layer == 0 ? new Color(fl.c.getRed(), fl.c.getGreen(), fl.c.getBlue(), al)
                    : layer == 1 ? new Color(255, 170, 50, (int) (al * 0.9)) : new Color(255, 245, 190, (int) (al * 0.85)));
                g.fill(p);
            }
        }
        scenery.drawFront(g, camX, camY, fx);
        g.setTransform(base);

        if (fx) drawVignette(g);
        int fx0 = (int) Math.floor(vX0()), fy0 = (int) Math.floor(vY0()), fw = (int) Math.ceil(vW()) + 1, fh = (int) Math.ceil(vH()) + 1;
        if (flash > 0) { g.setColor(new Color(255, 0, 0, (int) (110 * flash))); g.fillRect(fx0, fy0, fw, fh); }
        Snapshot.P me = find(s, myId);
        if (s.winner >= 0 && me != null && me.team != s.winner) { // the losing side sees the match in cold, dim light
            g.setColor(new Color(10, 12, 40, 80));
            g.fillRect(fx0, fy0, fw, fh);
        }
        g.dispose();
    }

    /**
     * Everything that is text or UI: names, HUD, scoreboard, victory and leaderboard. It is always drawn at the
     * screen's real pixel size (GameView hands in the final graphics), so glyph outlines are rasterised once, at
     * native resolution, with fractional metrics and kerning, and never resampled.
     */
    void paintOverlay(Graphics2D g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (last == null) {
            g.setFont(Type.display(24));
            Art.textCenter(g, "Waiting for server...", vX0(), vW(), vY0() + vH() / 2, Color.WHITE);
            g.dispose();
            return;
        }
        Snapshot s = last;
        AffineTransform hudBase = g.getTransform();
        g.translate(shakeX, shakeY); // names ride with the shaking battlefield, the HUD does not
        drawNames(g, s);
        g.setTransform(hudBase);
        boolean resultsUp = s.winner >= 0 && ((s.matchEndElapsedSec >= 60.0) || (victoryTimer >= 60.0) || viewLeaderboardManual);
        if (!resultsUp) drawHud(g, s); // the results table covers the HUD's place
        if (showBoard && s.winner < 0) {
            g.translate(vX0() + (vW() - MapData.W) / 2, vY0() + (vH() - MapData.H) / 2);
            drawBoard(g, s);
            g.setTransform(hudBase);
        }
        if (s.winner >= 0) {
            g.translate(vX0(), vY0()); // the end screens lay themselves out inside a (0,0,vW,vH) window
            boolean autoLb = (s.matchEndElapsedSec >= 60.0) || (victoryTimer >= 60.0);
            if (autoLb || viewLeaderboardManual) {
                drawPostMatchLeaderboard(g, s);
            } else {
                drawResponsiveVictory(g, s);
            }
            g.setTransform(hudBase);
        }
        g.dispose();
    }

    private void drawNames(Graphics2D g, Snapshot s) {
        g.setFont(Type.ui(12.5f, true));
        for (Snapshot.P p : s.players) {
            double[] xy = renderPos.get(p.id);
            if (xy == null || !p.alive) continue;
            Art.namePlate(g, p.name, xy[0], xy[1] + 50, p.team == 0 ? new Color(255, 175, 165) : new Color(185, 230, 255));
        }
    }

    /** Edge shading, made once per window size and zoom. */
    private void drawVignette(Graphics2D g) {
        double sc = g.getTransform().getScaleX();
        if (vignetteImg == null || sc != vigScale || vW() != vigW || vH() != vigH) {
            vigScale = sc; vigW = vW(); vigH = vH();
            vignetteImg = Art.vignette((int) Math.ceil(vigW * sc), (int) Math.ceil(vigH * sc));
        }
        Scenery.blit1to1(g, vignetteImg, vX0(), vY0());
    }

    /** Team light pools, rune rings and crystals at each home base. */
    private void drawBases(Graphics2D g) {
        for (int t = 0; t < 2; t++) {
            double[] b = MapData.BASES[t];
            if (fx) Art.glow(g, Art.FX[t], b[0], b[1], 130, 0.30f);
            g.setColor(new Color(Art.FX[t].getRed(), Art.FX[t].getGreen(), Art.FX[t].getBlue(), 40));
            g.fill(new Ellipse2D.Double(b[0] - b[2], b[1] - b[2], b[2] * 2, b[2] * 2));
            Stroke old = g.getStroke();
            g.setColor(Art.FX[t]);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 6}, (float) (t == 0 ? time * 12 % 14 : 14 - time * 12 % 14)));
            g.draw(new Ellipse2D.Double(b[0] - b[2], b[1] - b[2], b[2] * 2, b[2] * 2));
            g.setStroke(old);
            for (int k = -1; k <= 1; k += 2) drawCrystal(g, b[0] + (t == 0 ? 34 : -34), b[1] + k * 62, t);
        }
    }

    private void drawCrystal(Graphics2D g, double x, double y, int t) {
        double pulse = 0.7 + 0.3 * Math.sin(time * 2.5 + x + y);
        if (fx) Art.glow(g, Art.FX[t], x, y, 38, (float) (0.5 * pulse));
        Path2D.Double d = new Path2D.Double();
        d.moveTo(x, y - 14); d.lineTo(x + 8, y - 1); d.lineTo(x, y + 12); d.lineTo(x - 8, y - 1); d.closePath();
        g.setColor(new Color(20, 14, 40));
        g.fill(d);
        g.setColor(new Color(Art.FX[t].getRed(), Art.FX[t].getGreen(), Art.FX[t].getBlue(), (int) (255 * pulse)));
        g.fill(new Rectangle2D.Double(x - 3, y - 7, 6, 12));
        g.setColor(Color.WHITE);
        g.fillRect((int) x - 1, (int) y - 8, 2, 3);
        g.setColor(new Color(255, 255, 255, 90));
        g.draw(d);
    }

    /** A brazier on top of every wall block. */
    private void drawTorches(Graphics2D g) {
        for (double[] w : MapData.WALLS) Art.torchFlame(g, w[0] + w[2] / 2, w[1] + 3, time, 1.0, fx);
    }

    private void drawEmbers(Graphics2D g) {
        for (double[] e : embers) {
            g.setColor(e[0] < MapData.W / 2.0 ? new Color(255, 150, 60, 170) : new Color(110, 220, 255, 150));
            g.fill(new Ellipse2D.Double(e[0], e[1], 2.4, 2.4));
        }
    }

    private void drawEntities(Graphics2D g, Snapshot s) {
        for (int t = 0; t < 2; t++) Art.pedestal(g, MapData.BASES[t][0], MapData.BASES[t][1], t);

        List<Snapshot.P> order = new ArrayList<>(Arrays.asList(s.players)); // lower on screen draws on top
        order.removeIf(p -> renderPos.get(p.id) == null);
        order.sort(Comparator.comparingDouble(p -> renderPos.get(p.id)[1]));

        for (int t = 0; t < 2; t++) { // flags in the world, from the authoritative flag state
            Snapshot.F f = s.flags[t];
            if (f.state == Snapshot.BASE) Art.flag(g, MapData.BASES[t][0], MapData.BASES[t][1], t, time);
            else if (f.state == Snapshot.DROPPED) {
                double pulse = 0.5 + 0.5 * Math.sin(time * 5);
                Art.beam(g, f.x, f.y + 12, Art.FX[t], (float) (0.25 + 0.2 * pulse));
                g.setColor(Art.FX[t]);
                g.draw(new Ellipse2D.Double(f.x - 12 - pulse * 4, f.y + 6 - pulse * 2, 24 + pulse * 8, 12 + pulse * 4));
                Art.flag(g, f.x, f.y, t, time);
            }
        }
        for (Snapshot.P p : order) drawPlayer(g, p, renderPos.get(p.id), p.id == myId);
        for (int t = 0; t < 2; t++) { // carried flags ride above the carrier's head
            Snapshot.F f = s.flags[t];
            double[] xy = f.state == Snapshot.CARRIED ? renderPos.get(f.carrier) : null;
            if (xy == null) continue;
            if (fx) Art.glow(g, Art.FX[t], xy[0] + 8, xy[1] - 58, 42, 0.5f);
            Art.flag(g, xy[0] + 5, xy[1] - 58, t, time);
        }
    }

    /**
     * Fighters are 36x48 world pixels (the old 24x32 at 1.5x). The server's hit circle (radius 14) is centred
     * on (px, py), which is the fighter's chest: the head rises above the circle and the boots sit just below it.
     */
    private void drawPlayer(Graphics2D g, Snapshot.P p, double[] xy, boolean mine) {
        double[] a = anim.get(p.id);
        boolean moving = a != null && a[1] > 0;
        double aim = mine ? Math.atan2(mouseY - xy[1], mouseX - xy[0]) : p.aim;
        boolean over = last != null && last.winner >= 0;
        if (over && p.team == last.winner && p.alive) {
            aim = -Math.PI / 2 + Math.sin(time * 5 + p.id) * 0.18; // victorious weapon raise
            if (fx && rnd.nextInt(3) == 0) addP(xy[0] + (rnd.nextDouble() - 0.5) * 14, xy[1] + 10, (rnd.nextDouble() - 0.5) * 8, -25 - rnd.nextDouble() * 20, 0.4, new Color(180, 80, 255), 3);
        }
        double px = xy[0], py = xy[1];
        final double SH = Art.SPR_H * PX;

        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new Ellipse2D.Double(px - 22, py + 19, 44, 14)); // shadow under the boots
        if (!p.alive) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            pixelArt(g, Art.spriteCrisp(p.team, 3, spriteK(g)), px, py + 18, false);
            g.setComposite(old);
            return;
        }
        double st = spawnT.getOrDefault(p.id, 0.0);
        float alpha = st > 0 ? (float) (0.25 + 0.75 * (1 - st / 0.7)) * (((int) (time * 30) & 1) == 0 ? 1f : 0.7f) : (over && p.team != last.winner ? 0.7f : 1f);
        Composite keep = g.getComposite();

        g.setColor(new Color(Art.FX[p.team].getRed(), Art.FX[p.team].getGreen(), Art.FX[p.team].getBlue(), 36)); // team ring under the feet
        g.fill(new Ellipse2D.Double(px - 25, py + 17, 50, 17));
        g.setColor(mine ? Color.WHITE : Art.FX[p.team]);
        g.setStroke(new BasicStroke(mine ? 2 : 1.5f));
        g.draw(new Ellipse2D.Double(px - 25, py + 17, 50, 17));
        g.setStroke(new BasicStroke(1));

        int phase = a == null ? 0 : (int) a[0];
        int frame = moving ? 1 + (phase & 1) : 0;
        double bob = moving ? ((phase & 1) == 0 ? 0 : -3.5) : Math.sin(time * 3 + p.id) * 1.8;
        boolean left = Math.cos(aim) < 0;
        double cy0 = py - 5 + bob, y0 = cy0 - SH / 2;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        pixelArt(g, Art.spriteCrisp(p.team, frame, spriteK(g)), px, cy0, left);
        g.setComposite(keep);

        // Body energy cycle / magical invulnerability ring
        if (p.shieldSec > 0) {
            Art.shieldRing(g, px, py, p.shieldSec, MapData.SHIELD_DURATION, time);
        }

        double ft = fireT.getOrDefault(p.id, 0.0);
        double kick = -4 * Math.min(1, ft / 0.08); // recoil: the barrel jumps back
        AffineTransform old = g.getTransform();
        g.translate(px, py - 4 + bob);
        g.rotate(aim);
        g.scale(GUN_SCALE, GUN_SCALE);
        if (left) g.scale(1, -1); // keeps the grip underneath when aiming leftwards
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        Art.gun(g, p.team, kick, ft > 0);
        g.setComposite(keep);
        g.setTransform(old);
        if (ft > 0 && fx) Art.glow(g, Art.FX[p.team], px + Math.cos(aim) * 44, py - 4 + bob + Math.sin(aim) * 44, 38, 0.9f);

        if (mine && !isCarrier(p.id)) { // bobbing chevron marks the local fighter
            double cy = y0 - 12 + Math.sin(time * 4) * 2;
            Path2D.Double ch = new Path2D.Double();
            ch.moveTo(px - 8, cy - 8); ch.lineTo(px + 8, cy - 8); ch.lineTo(px, cy + 3); ch.closePath();
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new AffineTransform(1, 0, 0, 1, 1, 1).createTransformedShape(ch));
            g.setColor(Color.WHITE);
            g.fill(ch);
        }
    }

    /** World units per sprite pixel: fighters are 43x58 world units (the original 24x32 at 1.8x). */
    static final double PX = 2.4, GUN_SCALE = 1.2;

    /** Whole screen pixels per sprite pixel, so each one stays a crisp square. */
    private static int spriteK(Graphics2D g) { return Math.max(1, (int) Math.round(PX * g.getTransform().getScaleX())); }

    /** Copies a pre-scaled pixel-art image to the screen 1:1, centred on world point (cx, cy), optionally mirrored. */
    private static void pixelArt(Graphics2D g, BufferedImage im, double cx, double cy, boolean mirror) {
        AffineTransform at = g.getTransform();
        int w = im.getWidth(), h = im.getHeight();
        int dx = (int) Math.round(at.getTranslateX() + cx * at.getScaleX() - w / 2.0), dy = (int) Math.round(at.getTranslateY() + cy * at.getScaleY() - h / 2.0);
        g.setTransform(new AffineTransform());
        if (mirror) g.drawImage(im, dx + w, dy, -w, h, null); else g.drawImage(im, dx, dy, null);
        g.setTransform(at);
    }

    private boolean isCarrier(int id) {
        if (last == null) return false;
        for (Snapshot.F f : last.flags) if (f.state == Snapshot.CARRIED && f.carrier == id) return true;
        return false;
    }

    /** Travel direction of a server bullet, from where it was one snapshot ago; 0 if it just spawned. */
    private double bulletAngle(double[] b) {
        if (prev != null)
            for (double[] o : prev.bullets)
                if (teamOf(o) == teamOf(b) && Math.hypot(b[0] - o[0], b[1] - o[1]) < 30 && (b[0] != o[0] || b[1] != o[1]))
                    return Math.atan2(b[1] - o[1], b[0] - o[0]);
        return 0;
    }

    /** Glowing energy bolt with a fading tail; its bright core is exactly at (x, y), the collision position. */
    private void drawBullet(Graphics2D g, double x, double y, double ang, int t) {
        if (fx) Art.glow(g, Art.FX[t], x, y, 30, 0.8f);
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.rotate(ang);
        Color c = Art.FX[t];
        if (fx) {
            g.setPaint(new GradientPaint(-52, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0), 0, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 190)));
            g.fill(new Rectangle2D.Double(-52, -4, 52, 8));
        }
        double len = 21 + Math.sin(time * 40);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
        g.fill(new RoundRectangle2D.Double(-len / 2 - 5, -7, len + 6, 14, 14, 14)); // soft halo
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(-len / 2 - 3, -4.5, len, 9, 9, 9));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(-3, -2.2, 12, 4.4, 4.4, 4.4));
        g.setTransform(old);
    }

    // ---------- HUD ----------

    private static final Color GOLD = new Color(255, 205, 90);

    private void drawHud(Graphics2D g, Snapshot s) {
        Snapshot.P me = find(s, myId);
        int mt = me == null ? 0 : me.team;
        final double x0 = vX0(), y0 = vY0(), w = vW(), h = vH(), cx = x0 + w / 2;
        final double PW = 192, PH = 46, TW = 100, GAP = 8, top = y0 + 22;

        // top centre: red score | timer | blue score
        boolean low = s.timeLeftSec <= 30 && s.winner < 0;
        Color timerC = low ? new Color(255, 80, 80) : new Color(170, 150, 235);
        Art.plate(g, cx - TW / 2, top - 4, TW, PH + 8, 12, timerC, time, fx);
        g.setFont(Type.numbers(31));
        Color tc = low && ((int) (time * 3) & 1) == 0 ? new Color(255, 120, 110) : Color.WHITE;
        Art.textCenter(g, String.format("%d:%02d", s.timeLeftSec / 60, s.timeLeftSec % 60), (int) (cx - TW / 2), (int) TW, (int) (top + PH / 2 + 10), tc);

        for (int t = 0; t < 2; t++) {
            double px = t == 0 ? cx - TW / 2 - GAP - PW : cx + TW / 2 + GAP;
            Art.plate(g, px, top, PW, PH, 9, TEAM[t], time, fx);
            Snapshot.F f = s.flags[t]; // this team's own flag
            boolean home = f.state == Snapshot.BASE, taken = f.state == Snapshot.CARRIED;
            Color ic = home ? TEAM[t] : taken ? GOLD : (((int) (time * 4) & 1) == 0 ? new Color(255, 110, 80) : new Color(255, 180, 150));
            double iconX = t == 0 ? px + 24 : px + PW - 24;
            if (!home && fx) Art.glow(g, ic, iconX, top + PH / 2, 22, 0.5f);
            Art.flagIcon(g, iconX, top + PH / 2, 26, ic, time);
            String name = TEAM_NAME[t], st = home ? "FLAG SAFE" : taken ? "FLAG TAKEN" : "FLAG DROPPED";
            Color stc = home ? new Color(170, 175, 195) : taken ? GOLD : new Color(255, 130, 100);
            g.setFont(Art.title(15));
            FontMetrics fm = g.getFontMetrics();
            double nx = t == 0 ? px + 46 : px + PW - 46 - fm.getStringBounds(name, g).getWidth();
            Art.text(g, name, nx, top + 19, TEAM[t]);
            g.setFont(Type.ui(10.5f, true));
            double stw = g.getFontMetrics().getStringBounds(st, g).getWidth();
            Art.text(g, st, t == 0 ? px + 46 : px + PW - 46 - stw, top + 35, stc);

            AffineTransform keep = g.getTransform(); // score, bouncing when the team captures
            g.setFont(Type.numbers(35));
            String sc = String.valueOf(s.score[t]);
            fm = g.getFontMetrics();
            double sx = t == 0 ? px + PW - 18 - fm.getStringBounds(sc, g).getWidth() / 2.0 : px + 18 + fm.getStringBounds(sc, g).getWidth() / 2.0, sy = top + 35;
            g.translate(sx, sy - 11);
            double k = 1 + 0.5 * scorePop[t];
            g.scale(k, k);
            if (scorePop[t] > 0 && fx) Art.glow(g, TEAM[t], 0, 0, 30, (float) (0.8 * scorePop[t]));
            Art.text(g, sc, -fm.getStringBounds(sc, g).getWidth() / 2, 12, scorePop[t] > 0 ? Color.WHITE : TEAM[t].brighter());
            g.setTransform(keep);
        }

        // top left: who you are
        double redX = cx - TW / 2 - GAP - PW, iy = x0 + 14 + 196 < redX - 6 ? top : top + PH + 8; // narrow windows: tuck it under the red score
        Art.plate(g, x0 + 14, iy, 196, PH, 9, TEAM[mt], time, fx);
        g.setFont(Art.title(15));
        Art.text(g, "YOU - " + TEAM_NAME[mt] + " TEAM", (int) x0 + 28, (int) iy + 19, TEAM[mt].brighter());
        g.setFont(Type.ui(11.5f, true));
        int ping = link.pingMs();
        String stats = me == null ? "" : "  K " + me.tags + "  C " + me.captures;
        Art.text(g, "PING " + ping + "ms" + (link.lagSimOn() ? " LAG" : "") + stats, (int) x0 + 28, (int) iy + 36, ping > 150 ? Color.ORANGE : new Color(170, 175, 195));

        // bottom left: compact controls guide
        g.setFont(Type.ui(11.5f, false));
        Art.textCenter(g, "WASD move   MOUSE aim/fire   TAB scores   F11 fullscreen   F fx " + (fx ? "on" : "off") + "   V shake " + (shakeOn ? "on" : "off") + "   L lag",
            x0, w, y0 + h - 10, new Color(235, 232, 255, 200));

        // event feed
        g.setFont(Type.ui(12.5f, true));
        int n = feed.size();
        for (int i = 0; i < n; i++) {
            Object[] f = feed.get(i);
            String t = (String) f[0];
            Color fc = (Color) f[2];
            double fw = g.getFontMetrics().stringWidth(t) + 26, fy = y0 + h - 52 - (n - 1 - i) * 26;
            Path2D.Double pl = Art.chamfer(x0 + 14, fy, fw, 21, 5);
            g.setColor(new Color(6, 6, 18, 190));
            g.fill(pl);
            g.setColor(fc);
            g.fillRect((int) x0 + 14, (int) fy + 3, 3, 15);
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), 120));
            g.draw(pl);
            Art.text(g, t, (int) x0 + 24, (int) fy + 15, Color.WHITE);
        }

        // big announcements: slide in under the timer, fade out
        for (int i = 0; i < notices.size(); i++) {
            Object[] nt = notices.get(i);
            double age = (Double) nt[2];
            float a = (float) Math.min(1, Math.min(age / 0.15, (2.6 - age) / 0.6));
            if (a <= 0) continue;
            Color c = (Color) nt[1];
            double ny = top + PH + 36 + (notices.size() - 1 - i) * 40 + (1 - Math.min(1, age / 0.2)) * -10;
            g.setFont(Art.title(26));
            FontMetrics fm = g.getFontMetrics();
            double bw = fm.stringWidth((String) nt[0]) + 120;
            Composite keep = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.setPaint(new LinearGradientPaint((float) (cx - bw / 2), 0, (float) (cx + bw / 2), 0, new float[]{0f, 0.2f, 0.8f, 1f},
                new Color[]{new Color(4, 4, 16, 0), new Color(4, 4, 16, 190), new Color(4, 4, 16, 190), new Color(4, 4, 16, 0)}));
            g.fill(new Rectangle2D.Double(cx - bw / 2, ny - 27, bw, 38));
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
            g.fill(new Rectangle2D.Double(cx - bw * 0.4, ny - 28, bw * 0.8, 1.5));
            g.fill(new Rectangle2D.Double(cx - bw * 0.4, ny + 10, bw * 0.8, 1.5));
            Art.textCenter(g, (String) nt[0], (int) (cx - bw / 2), (int) bw, (int) ny, c.brighter());
            g.setComposite(keep);
        }

        // bottom centre: personal status
        String banner = null;
        for (Snapshot.F f : s.flags) if (f.state == Snapshot.CARRIED && f.carrier == myId) banner = "YOU HAVE THE FLAG - RUN HOME";
        if (me != null && !me.alive) banner = "ELIMINATED - RESPAWNING";
        if (banner != null && s.winner < 0) {
            g.setFont(Art.title(18));
            int bw = g.getFontMetrics().stringWidth(banner) + 44;
            boolean good = banner.startsWith("YOU");
            Color bc = good ? GOLD : TEAM[mt];
            if (good) bc = ((int) (time * 3) & 1) == 0 ? GOLD : new Color(255, 235, 170);
            Art.plate(g, cx - bw / 2.0, y0 + h - 78, bw, 34, 9, bc, time, fx);
            Art.textCenter(g, banner, (int) (cx - bw / 2.0), bw, (int) (y0 + h - 55), Color.WHITE);
        }
    }

    private void drawBoard(Graphics2D g, Snapshot s) {
        Art.panel(g, 150, 100, 500, 400, Art.BORDER);
        for (int t = 0; t < 2; t++) {
            int x = 170 + t * 250, y = 135;
            g.setFont(Art.font(16));
            Art.text(g, TEAM_NAME[t] + "  " + s.score[t], x, y, TEAM[t]);
            g.setFont(Art.font(12));
            for (Snapshot.P p : s.players) if (p.team == t) {
                y += 24;
                Art.text(g, p.name, x, y, Color.WHITE);
                Art.text(g, p.captures + "C " + p.tags + "T", x + 100, y, new Color(200, 205, 220));
                Art.text(g, p.pingMs + "ms", x + 170, y, new Color(200, 205, 220));
            }
        }
    }

    private void drawResponsiveVictory(Graphics2D g, Snapshot s) {
        int vw = (int) Math.round(vW());
        int vh = (int) Math.round(vH());

        // Cinematic soft translucent dark veil (lets arena, players & fireworks shine through!)
        g.setColor(new Color(4, 2, 14, 115));
        g.fillRect(0, 0, vw, vh);

        // Responsive glassy window dimensions with safe margins
        int cardW = Math.min(520, Math.max(340, vw - 48));
        int cardH = Math.min(270, Math.max(210, vh - 60));
        int cardX = (vw - cardW) / 2;
        int cardY = (vh - cardH) / 2;

        int winner = s.winner;
        Color violet = new Color(175, 75, 255);
        Color accent = winner == 1 ? new Color(60, 220, 255) : new Color(255, 65, 115);

        // 1. Expanding celestial shockwave rings
        double maxRing = Math.min(vw, vh) * 0.65;
        double ringR = (victoryTimer * 160) % maxRing;
        float ringAlpha = (float) Math.max(0, 1.0 - ringR / maxRing);
        Composite oldComp = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ringAlpha * 0.7f));
        g.setColor(violet);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new Ellipse2D.Double(vw / 2.0 - ringR, vh / 2.0 - ringR, ringR * 2, ringR * 2));
        g.setComposite(oldComp);

        // 2. Frosted transparent glassy window!
        Art.glassCard(g, cardX, cardY, cardW, cardH, accent, time, fx, hoverPlayAgain);
        btnPlayAgainBounds.setBounds(cardX + ox(), cardY + oy(), cardW, cardH);

        // 3. Decorative mystical flame braziers at the outer sides of the glass window
        Art.flameBrazier(g, cardX - 16, cardY + cardH - 14, accent, time, fx);
        Art.flameBrazier(g, cardX + cardW + 16, cardY + cardH - 14, accent, time + 1.2, fx);

        // 4. Rotating celestial aurora light rays and sacred runic halo behind "VICTORY"
        double vicCenterX = cardX + cardW / 2.0;
        double bob = Math.sin(time * 2.8) * 3;
        double vicCenterY = cardY + cardH / 2.0 + bob;

        if (fx) {
            // Rotating sacred geometry runic halo behind "VICTORY"
            Composite haloComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
            g.setColor(accent);
            Stroke haloSt = g.getStroke();
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, (float)(time * 10 % 12)));
            double haloR = cardH * 0.38;
            g.draw(new Ellipse2D.Double(vicCenterX - haloR, vicCenterY - haloR, haloR * 2, haloR * 2));
            g.setStroke(haloSt);
            g.setComposite(haloComp);

            // 12 rotating celestial aurora light rays radiating behind "VICTORY"
            Composite rayComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
            for (int i = 0; i < 12; i++) {
                double rayAng = time * 0.30 + i * (Math.PI * 2 / 12);
                double rayLen = cardW * 0.52;
                Path2D.Double ray = new Path2D.Double();
                ray.moveTo(vicCenterX, vicCenterY);
                ray.lineTo(vicCenterX + Math.cos(rayAng - 0.08) * rayLen, vicCenterY + Math.sin(rayAng - 0.08) * rayLen);
                ray.lineTo(vicCenterX + Math.cos(rayAng + 0.08) * rayLen, vicCenterY + Math.sin(rayAng + 0.08) * rayLen);
                ray.closePath();
                g.setPaint(new RadialGradientPaint(
                    new Point2D.Double(vicCenterX, vicCenterY), (float) rayLen,
                    new float[]{0f, 1f},
                    new Color[]{new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180), new Color(violet.getRed(), violet.getGreen(), violet.getBlue(), 0)}
                ));
                g.fill(ray);
            }
            g.setComposite(rayComp);

            // Multilayered atmospheric glow behind "VICTORY"
            Art.glow(g, violet, vicCenterX, vicCenterY, 90, 0.45f);
            Art.glow(g, accent, vicCenterX, vicCenterY, 52, 0.70f);
            Art.glow(g, Color.WHITE, vicCenterX, vicCenterY, 24, 0.85f);
        }

        // 5. Majestic display of the only word: "VICTORY"
        String vicText = "VICTORY";
        int fontSize = Math.min(74, Math.max(50, (int)(cardW * 0.175)));
        Font vicFont = Type.heading(fontSize);
        g.setFont(vicFont);
        FontMetrics fm = g.getFontMetrics();
        java.awt.font.TextLayout tl = new java.awt.font.TextLayout(vicText, vicFont, g.getFontRenderContext());
        int strW = (int) Math.round(tl.getAdvance());
        int strX = cardX + (cardW - strW) / 2;
        int strY = (int) vicCenterY + fm.getAscent() / 2 - 4;
        // drawn as vector outlines (kerned, letter-spaced), so the edges are smooth at any resolution
        Shape letters = tl.getOutline(AffineTransform.getTranslateInstance(strX, strY));
        g.setColor(new Color(6, 2, 14, 210)); // deep shadow
        g.fill(AffineTransform.getTranslateInstance(0, 4.5).createTransformedShape(letters));
        g.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(120, 38, 214, 215)); // violet rim
        g.draw(letters);
        g.setPaint(new GradientPaint(strX, strY - fm.getAscent(), Color.WHITE, strX, strY + 2, accent)); // iridescent face
        g.fill(letters);
        g.setStroke(new BasicStroke(0.9f));
        g.setColor(new Color(255, 255, 255, 170)); // specular edge
        g.draw(letters);
        g.setStroke(new BasicStroke(1f));

        // Layer E: Sparkling diamond star glints on the letters
        Art.starGlint(g, strX + fm.charWidth('V') * 0.25, strY - fm.getAscent() * 0.88, time, 8);
        Art.starGlint(g, strX + strW * 0.52, strY - fm.getAscent() * 0.92, time + 1.3, 9);
        Art.starGlint(g, strX + strW - fm.charWidth('Y') * 0.35, strY - fm.getAscent() * 0.88, time + 2.5, 8);

        // Sleek interactive toggle button to view Match Statistics early or show countdown to auto-transition
        int toggleW = Math.min(260, cardW - 60);
        int toggleH = 28;
        int toggleX = cardX + (cardW - toggleW) / 2;
        int toggleY = Math.min(vh - 36, cardY + cardH + 10);
        btnLeaderboardToggle.setBounds(toggleX + ox(), toggleY + oy(), toggleW, toggleH);

        double elapsed = s.matchEndElapsedSec > 0 ? s.matchEndElapsedSec : victoryTimer;
        int secRemaining = Math.max(0, (int) Math.ceil(60.0 - elapsed));
        String toggleText = "STATS [TAB] (" + secRemaining + "s)";

        Color tFill = hoverLeaderboardToggle ? new Color(255, 255, 255, 45) : new Color(20, 10, 35, 180);
        Color tBorder = hoverLeaderboardToggle ? Color.WHITE : new Color(175, 75, 255, 140);
        g.setColor(tFill);
        g.fillRoundRect(toggleX, toggleY, toggleW, toggleH, 14, 14);
        g.setColor(tBorder);
        g.setStroke(new BasicStroke(hoverLeaderboardToggle ? 1.8f : 1.0f));
        g.drawRoundRect(toggleX, toggleY, toggleW, toggleH, 14, 14);

        g.setFont(Art.font(11));
        Art.textCenter(g, toggleText, toggleX, toggleW, toggleY + 18, Color.WHITE);
    }

    private void drawPostMatchLeaderboard(Graphics2D g, Snapshot s) {
        int vw = (int) Math.round(vW());
        int vh = (int) Math.round(vH());

        // Cinematic dark backdrop so fireworks and torches shine through softly
        g.setColor(new Color(4, 2, 14, 175));
        g.fillRect(0, 0, vw, vh);

        int cardW = Math.min(740, Math.max(340, vw - 36));
        int cardH = Math.min(540, Math.max(380, vh - 36));
        int cardX = (vw - cardW) / 2;
        int cardY = (vh - cardH) / 2;

        int winner = s.winner;
        Color accent = winner == 1 ? new Color(60, 220, 255) : new Color(255, 65, 115);

        // Glassmorphic main results window
        Art.glassCard(g, cardX, cardY, cardW, cardH, accent, time, fx, false);

        // 1. Header with Golden Crown and Match Results Title
        Art.crown(g, cardX + cardW / 2.0, cardY + 22, Color.YELLOW, 22, time);

        g.setFont(Type.display(Math.min(22, (int)(cardW * 0.045))));
        String headerTitle = "MATCH RESULTS & BATTLE STATISTICS";
        FontMetrics fmH = g.getFontMetrics();
        int thX = cardX + (cardW - fmH.stringWidth(headerTitle)) / 2;
        g.setColor(new Color(6, 2, 14, 220));
        g.drawString(headerTitle, thX, cardY + 47);
        g.setColor(Color.WHITE);
        g.drawString(headerTitle, thX, cardY + 46);

        String verdict = (winner == 1 ? "BLUE" : "RED") + " TEAM TRIUMPHANT";
        g.setFont(Type.ui(13, true));
        FontMetrics fmV = g.getFontMetrics();
        g.setColor(TEAM[winner]);
        g.drawString(verdict, cardX + (cardW - fmV.stringWidth(verdict)) / 2, cardY + 64);

        // Aggregate authoritative team statistics
        int[] teamKills = new int[2], teamDeaths = new int[2], teamCaps = new int[2];
        for (Snapshot.P p : s.players) {
            if (p.team >= 0 && p.team < 2) {
                teamKills[p.team] += p.tags;
                teamDeaths[p.team] += p.deaths;
                teamCaps[p.team] += p.captures;
            }
        }

        // 2. Team Comparison Cards (Side-by-side or stacked on tiny viewports)
        boolean sideBySide = cardW >= 480;
        int teamY = cardY + 74;
        int teamH = sideBySide ? 76 : 60;
        int teamW = sideBySide ? (cardW - 32) / 2 : cardW - 24;

        for (int t = 0; t < 2; t++) {
            int tx = sideBySide ? (t == 0 ? cardX + 12 : cardX + 20 + teamW) : cardX + 12;
            int ty = sideBySide ? teamY : teamY + t * 64;

            boolean won = t == winner;
            Color tc = TEAM[t];
            Color fillC = won ? new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 35) : new Color(20, 15, 35, 120);
            Color borderC = won ? tc : new Color(80, 70, 100, 160);

            g.setColor(fillC);
            g.fillRoundRect(tx, ty, teamW, teamH, 12, 12);
            g.setColor(borderC);
            g.setStroke(new BasicStroke(won ? 2.0f : 1.0f));
            g.drawRoundRect(tx, ty, teamW, teamH, 12, 12);

            if (won) {
                Art.crown(g, tx + 18, ty + 18, Color.YELLOW, 16, time);
            }

            g.setFont(Type.ui(13, true));
            g.setColor(tc);
            g.drawString(TEAM_NAME[t] + " TEAM" + (won ? "  * WINNER" : ""), tx + (won ? 32 : 14), ty + 20);

            // Team Score badge
            String scoreStr = "SCORE: " + s.score[t];
            g.setFont(Type.ui(13, true));
            g.setColor(Color.WHITE);
            g.drawString(scoreStr, tx + teamW - g.getFontMetrics().stringWidth(scoreStr) - 14, ty + 20);

            // Metrics row: KILLS, DEATHS, CAPTURES
            g.setFont(Type.ui(11, false));
            int colW = (teamW - 24) / 3;
            String[] labels = {"KILLS", "DEATHS", "CAPTURES"};
            int[] vals = {teamKills[t], teamDeaths[t], teamCaps[t]};
            for (int m = 0; m < 3; m++) {
                int cx = tx + 12 + m * colW;
                g.setColor(new Color(160, 165, 185));
                g.drawString(labels[m], cx, ty + 42);
                g.setFont(Type.ui(15, true));
                g.setColor(Color.WHITE);
                g.drawString(String.valueOf(vals[m]), cx, ty + 62);
                g.setFont(Type.ui(11, false));
            }
        }

        // 3. Individual Player Performance Table
        int tableY = cardY + (sideBySide ? 162 : 210);
        int tableX = cardX + 12;
        int tableW = cardW - 24;

        // Header strip
        g.setColor(new Color(25, 16, 44, 210));
        g.fillRoundRect(tableX, tableY, tableW, 26, 8, 8);
        g.setColor(new Color(100, 70, 140, 150));
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(tableX, tableY, tableW, 26, 8, 8);

        g.setFont(Type.ui(11, true));
        g.setColor(new Color(210, 205, 235));
        int cRank = tableX + 8;
        int cPlayer = tableX + 64;
        int cTeam = tableX + (int)(tableW * 0.44);
        int cScore = tableX + (int)(tableW * 0.58);
        int cKills = tableX + (int)(tableW * 0.70);
        int cDeaths = tableX + (int)(tableW * 0.81);
        int cCaps = tableX + (int)(tableW * 0.91);

        g.drawString("RANK", cRank, tableY + 17);
        g.drawString("PLAYER", cPlayer, tableY + 17);
        g.drawString("TEAM", cTeam, tableY + 17);
        g.drawString("SCORE", cScore, tableY + 17);
        g.drawString("KILLS", cKills, tableY + 17);
        g.drawString("DEATHS", cDeaths, tableY + 17);
        g.drawString("CAPTURES", cCaps, tableY + 17);

        // Sort players according to strict ranking rules:
        // 1. Score descending, 2. Captures descending, 3. Kills descending, 4. Deaths ascending
        List<Snapshot.P> ranked = new ArrayList<>(Arrays.asList(s.players));
        ranked.sort((a, b) -> {
            if (b.score != a.score) return Integer.compare(b.score, a.score);
            if (b.captures != a.captures) return Integer.compare(b.captures, a.captures);
            if (b.tags != a.tags) return Integer.compare(b.tags, a.tags);
            return Integer.compare(a.deaths, b.deaths);
        });

        int rowH = 28;
        int maxRows = Math.min(ranked.size(), (cardH - (tableY - cardY) - 58) / rowH);
        for (int i = 0; i < maxRows; i++) {
            Snapshot.P p = ranked.get(i);
            int ry = tableY + 30 + i * (rowH + 2);
            int rank = i + 1;

            // Row background
            Color rowBg = (i % 2 == 0) ? new Color(20, 14, 38, 120) : new Color(30, 20, 52, 100);
            if (rank == 1) rowBg = new Color(255, 215, 0, 25);
            else if (rank == 2) rowBg = new Color(200, 220, 255, 20);
            else if (rank == 3) rowBg = new Color(205, 127, 50, 20);

            g.setColor(rowBg);
            g.fillRoundRect(tableX, ry, tableW, rowH, 6, 6);

            // Rank medal badge
            Art.medalBadge(g, cRank, ry + 3, rank);

            // Player name with (YOU) indicator
            g.setFont(Type.ui(12, true));
            boolean isMe = (p.id == myId);
            g.setColor(isMe ? Color.WHITE : new Color(220, 225, 240));
            String pName = p.name + (isMe ? " (YOU)" : "");
            g.drawString(pName, cPlayer, ry + 19);

            // Team tag
            g.setFont(Type.ui(11, true));
            g.setColor(TEAM[p.team]);
            g.drawString(p.team == 0 ? "RED" : "BLUE", cTeam, ry + 19);

            // Score
            g.setFont(Type.ui(12, true));
            g.setColor(new Color(255, 235, 140));
            g.drawString(String.valueOf(p.score), cScore, ry + 19);

            // Kills, Deaths, Captures
            g.setFont(Type.ui(12, false));
            g.setColor(Color.WHITE);
            g.drawString(String.valueOf(p.tags), cKills, ry + 19);
            g.drawString(String.valueOf(p.deaths), cDeaths, ry + 19);
            g.drawString(String.valueOf(p.captures), cCaps, ry + 19);
        }

        // 4. Action Buttons (PLAY AGAIN / REMATCH, VIEW CELEBRATION)
        int btnH = 34;
        int btnW = Math.min(200, (cardW - 40) / 2);
        int btnY = cardY + cardH - 46;

        int btn1X = cardX + (cardW - (btnW * 2 + 16)) / 2;
        int btn2X = btn1X + btnW + 16;
        btnLeaderboardPlayAgain.setBounds(btn1X + ox(), btnY + oy(), btnW, btnH);
        btnLeaderboardBack.setBounds(btn2X + ox(), btnY + oy(), btnW, btnH);

        // Button 1: Play Again
        Color b1Fill = hoverLeaderboardPlayAgain ? new Color(130, 50, 210, 230) : new Color(60, 25, 100, 200);
        Color b1Border = hoverLeaderboardPlayAgain ? Color.WHITE : new Color(180, 80, 255);
        g.setColor(b1Fill);
        g.fillRoundRect(btn1X, btnY, btnW, btnH, 16, 16);
        g.setColor(b1Border);
        g.setStroke(new BasicStroke(hoverLeaderboardPlayAgain ? 2.0f : 1.2f));
        g.drawRoundRect(btn1X, btnY, btnW, btnH, 16, 16);
        g.setFont(Type.ui(13, true));
        Art.textCenter(g, "PLAY AGAIN", btn1X, btnW, btnY + 22, Color.WHITE);

        // Button 2: View Celebration
        Color b2Fill = hoverLeaderboardBack ? new Color(255, 255, 255, 45) : new Color(255, 255, 255, 18);
        Color b2Border = hoverLeaderboardBack ? Color.WHITE : new Color(180, 180, 210, 120);
        g.setColor(b2Fill);
        g.fillRoundRect(btn2X, btnY, btnW, btnH, 16, 16);
        g.setColor(b2Border);
        g.setStroke(new BasicStroke(hoverLeaderboardBack ? 2.0f : 1.0f));
        g.drawRoundRect(btn2X, btnY, btnW, btnH, 16, 16);
        Art.textCenter(g, "< CELEBRATION", btn2X, btnW, btnY + 22, Color.WHITE);
    }

    private void triggerOpeningCelebration(int winner) {
        double wx = vX0(), wy = vY0(), vw = vW(), vh = vH();
        Color teamC = TEAM[winner];
        Color purple = new Color(170, 60, 255);
        burst(wx + vw / 2, wy + vh / 2, purple, 70, 340);
        burst(wx + vw / 2, wy + vh / 2, teamC, 60, 300);
        burst(wx + vw / 2, wy + vh / 2, new Color(255, 200, 90), 50, 380);
        ring(wx + vw / 2, wy + vh / 2 - 10, winner, 1.4, 260);
        for (int i = 0; i < 9; i++) // an opening salvo across the whole width
            launchRocket(wx + vw * (0.08 + 0.84 * i / 8.0), wy + vh, (i - 4) * 14, -470 - rnd.nextDouble() * 120, winner);
        nextRocketTime = 0.7;
    }

    private void launchRocket(double x, double y, double vx, double vy, int team) {
        FireworkRocket r = new FireworkRocket();
        r.x = x; r.y = y; r.vx = vx; r.vy = vy;
        r.fuse = 0.75 + rnd.nextDouble() * 0.5;
        r.team = team;
        double k = rnd.nextDouble();
        r.c = k < 0.34 ? new Color(170, 70, 255) : k < 0.67 ? (team == 1 ? new Color(60, 220, 255) : new Color(255, 70, 90)) : new Color(255, 190, 80);
        rockets.add(r);
    }

    /** Fireworks keep going for the whole victory screen; fire rises along the bottom and beside the card. */
    private void stepCelebration(double dt, int winner) {
        double vw = vW(), vh = vH(), wx = vX0(), wy = vY0();

        if (victoryTimer < 58.0 && victoryTimer >= nextRocketTime) {
            nextRocketTime += 0.14 + rnd.nextDouble() * 0.22;
            int n = 1 + rnd.nextInt(3);
            for (int i = 0; i < n; i++)
                launchRocket(wx + vw * (0.06 + rnd.nextDouble() * 0.88), wy + vh, (rnd.nextDouble() - 0.5) * 120, -430 - rnd.nextDouble() * 170, winner);
        }

        for (Iterator<FireworkRocket> it = rockets.iterator(); it.hasNext(); ) {
            FireworkRocket r = it.next();
            r.fuse -= dt;
            r.x += r.vx * dt;
            r.y += r.vy * dt;
            r.vy += 90 * dt;
            addSpark(r.x, r.y, (rnd.nextDouble() - 0.5) * 24, rnd.nextDouble() * 40 + 10, 0.35, new Color(255, 220, 150), 3);
            addSpark(r.x, r.y, (rnd.nextDouble() - 0.5) * 24, rnd.nextDouble() * 40 + 10, 0.25, r.c, 2);
            if (r.fuse <= 0) {
                detonateRocket(r);
                it.remove();
            }
        }

        for (Iterator<FireworkSpark> it = sparks.iterator(); it.hasNext(); ) {
            FireworkSpark s = it.next();
            s.life -= dt;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.vy += 85 * dt;
            s.vx *= 0.975;
            if (s.life <= 0) it.remove();
        }

        // a wall of fire along the bottom of the screen, in orange, red, gold and the winners' colour
        Color teamC = winner == 1 ? new Color(60, 220, 255) : new Color(255, 70, 90);
        Color[] fire = {new Color(255, 120, 30), new Color(255, 70, 30), new Color(255, 120, 30), new Color(255, 190, 60), teamC, new Color(170, 60, 255)};
        int spawn = (int) Math.ceil(dt * 200);
        for (int i = 0; i < spawn; i++)
            addFlame(wx + rnd.nextDouble() * vw, wy + vh + 6, (rnd.nextDouble() - 0.5) * 30, -90 - rnd.nextDouble() * 120,
                0.8 + rnd.nextDouble() * 1.0, fire[rnd.nextInt(fire.length)], 13 + rnd.nextInt(14));
        int cardW = (int) Math.min(520, Math.max(340, vw - 48)), cardH = (int) Math.min(380, Math.max(260, vh - 56));
        double cardX = (vw - cardW) / 2 + wx, cardY = (vh - cardH) / 2 + wy;
        for (int i = 0; i < Math.max(1, spawn / 2); i++) { // tall columns of flame on both sides of the victory panel
            double side = rnd.nextBoolean() ? cardX - 18 : cardX + cardW + 18;
            addFlame(side + (rnd.nextDouble() - 0.5) * 22, cardY + cardH - 14, (rnd.nextDouble() - 0.5) * 24, -70 - rnd.nextDouble() * 110,
                0.7 + rnd.nextDouble() * 0.8, rnd.nextBoolean() ? new Color(170, 60, 255) : teamC, 10 + rnd.nextInt(10));
        }
        // embers drifting up across the whole view
        if (rnd.nextInt(2) == 0) addSpark(wx + rnd.nextDouble() * vw, wy + vh, (rnd.nextDouble() - 0.5) * 40, -120 - rnd.nextDouble() * 140, 1.6, new Color(255, 170, 70), 3);

        for (Iterator<MagicalFlame> it = flames.iterator(); it.hasNext(); ) {
            MagicalFlame f = it.next();
            f.life -= dt;
            f.x += (f.vx + Math.sin(time * 6 + f.y * 0.05) * 14) * dt;
            f.y += f.vy * dt;
            if (f.life <= 0) it.remove();
        }
    }

    private void addSpark(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (sparks.size() >= 1500) return;
        FireworkSpark s = new FireworkSpark();
        s.x = x; s.y = y; s.vx = vx; s.vy = vy; s.life = s.max = life; s.c = c; s.size = size;
        sparks.add(s);
    }

    private void addFlame(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (flames.size() >= 420) return;
        MagicalFlame f = new MagicalFlame();
        f.x = x; f.y = y; f.vx = vx; f.vy = vy; f.life = f.max = life; f.c = c; f.size = size;
        flames.add(f);
    }

    /** Peony, ring, double ring and willow bursts, each in the winners' colours plus violet, gold and white. */
    private void detonateRocket(FireworkRocket r) {
        Sfx.play("firework");
        Color primary = r.c;
        Color secondary = r.team == 1 ? new Color(60, 220, 255) : new Color(255, 60, 100);
        Color violet = new Color(190, 70, 255), gold = new Color(255, 205, 90);
        int kind = rnd.nextInt(4);
        int count = kind == 1 ? 64 : 100 + rnd.nextInt(50);
        double speed = 160 + rnd.nextDouble() * 120;
        for (int i = 0; i < count; i++) {
            double ang = kind == 1 ? i * Math.PI * 2 / count : rnd.nextDouble() * Math.PI * 2;
            double spd = kind == 1 ? speed : 50 + rnd.nextDouble() * speed * 1.4;
            Color sc = rnd.nextInt(4) == 0 ? Color.WHITE : rnd.nextInt(3) == 0 ? gold : rnd.nextBoolean() ? primary : (rnd.nextBoolean() ? secondary : violet);
            double life = kind == 3 ? 1.3 + rnd.nextDouble() * 0.8 : 0.7 + rnd.nextDouble() * 0.7; // willow: long falling trails
            addSpark(r.x, r.y, Math.cos(ang) * spd, Math.sin(ang) * spd, life, sc, 3 + rnd.nextInt(3));
        }
        if (kind == 2) for (int i = 0; i < 40; i++) { // inner ring
            double ang = i * Math.PI * 2 / 40;
            addSpark(r.x, r.y, Math.cos(ang) * speed * 0.5, Math.sin(ang) * speed * 0.5, 0.9, gold, 4);
        }
        ring(r.x, r.y - 10, r.team, 0.5, 70);
        if (blooms.size() < 14) blooms.add(new Object[]{r.x, r.y, primary, 0.0});
    }

    private static Snapshot.P find(Snapshot s, int id) {
        for (Snapshot.P p : s.players) if (p.id == id) return p;
        return null;
    }

    private static String name(Snapshot s, int id) {
        Snapshot.P p = find(s, id);
        return p == null ? "?" : p.name;
    }
}

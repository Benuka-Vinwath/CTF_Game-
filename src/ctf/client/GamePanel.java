package ctf.client;

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
    static final int PAD = 20; // background is oversized by this much so screen shake never shows an edge
    static final int MAX_PARTICLES = 800;

    private static final class Particle {
        double x, y, vx, vy, life, max; Color c; int size = 4;
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
    private final double[][] embers = new double[40][4];         // x, y, speed, phase
    private final List<FireworkRocket> rockets = new ArrayList<>();
    private final List<FireworkSpark> sparks = new ArrayList<>();
    private final List<MagicalFlame> flames = new ArrayList<>();
    private double victoryTimer;
    private int celebrationWinner = -1;
    private final Rectangle btnPlayAgainBounds = new Rectangle();
    private boolean hoverPlayAgain;
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
    private final BufferedImage bg = loadBackground();
    private final BufferedImage vignette = Art.vignette(MapData.W, MapData.H);

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
                if (k == KeyEvent.VK_TAB) showBoard = true;
                if (k == KeyEvent.VK_L) link.toggleLagSim();
                if (k == KeyEvent.VK_V) shakeOn = !shakeOn;
                if (k == KeyEvent.VK_F) fx = !fx; // optional effects off if they cost frames
                if ((k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) && last != null && last.winner >= 0) link.requestPlayAgain();
            }
            @Override public void keyReleased(KeyEvent e) {
                keys.remove(e.getKeyCode());
                if (e.getKeyCode() == KeyEvent.VK_TAB) showBoard = false;
            }
        });
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                mouseX = e.getX(); mouseY = e.getY();
                if (last != null && last.winner >= 0) {
                    boolean prevHover = hoverPlayAgain;
                    hoverPlayAgain = btnPlayAgainBounds.contains(mouseX, mouseY);
                    if (hoverPlayAgain != prevHover) {
                        setCursor(Cursor.getPredefinedCursor(hoverPlayAgain ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    }
                }
            }
            @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
            @Override public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                if (last != null && last.winner >= 0 && btnPlayAgainBounds.contains(e.getX(), e.getY())) {
                    setCursor(Cursor.getDefaultCursor());
                    link.requestPlayAgain();
                    return;
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
        if (fx) { // short glowing trail behind every bullet
            for (double[] g : ghosts) addP(g[7], g[8], 0, 0, 0.18, Art.FX[(int) g[4]], 3);
            if (last != null) for (double[] b : last.bullets) if (!hidden.contains(b)) addP(b[0], b[1], 0, 0, 0.18, Art.FX[teamOf(b)], 3);
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
            if (e[1] < -5) respawnEmber(e, false);
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
        e[0] = rnd.nextDouble() * MapData.W;
        e[1] = anywhere ? rnd.nextDouble() * MapData.H : MapData.H + 5;
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
                Sfx.play("hit");
                say(p.name + " was eliminated");
                if (p.id == myId) { flash = 1; shake = 12; }
            } else if (!o.alive && p.alive) {
                burst(p.x, p.y, Art.FX[p.team], 18, 120);
                beams.add(new double[]{p.x, p.y + 12, p.team, 0.6});
            }
            // Shield state transitions
            if (o.shieldSec <= 0 && p.shieldSec > 0) {
                burst(p.x, p.y, new Color(170, 70, 255), 24, 160);
                burst(p.x, p.y, new Color(80, 230, 255), 16, 120);
                Sfx.play("shield");
                say(p.name + " activated MAGIC SHIELD");
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
                say(name(b, n.carrier) + " took the " + TEAM_NAME[t] + " flag");
                Sfx.play("pickup");
            } else if (n.state == Snapshot.DROPPED && o.state == Snapshot.CARRIED) {
                say(TEAM_NAME[t] + " flag dropped");
                Sfx.play("drop");
            } else if (n.state == Snapshot.BASE && o.state == Snapshot.DROPPED) {
                say(TEAM_NAME[t] + " flag returned");
                burst(MapData.BASES[t][0], MapData.BASES[t][1], Art.FX[t], 20, 150);
                beams.add(new double[]{MapData.BASES[t][0], MapData.BASES[t][1] + 12, t, 0.8});
            }
            int other = 1 - t; // the team that scores by carrying flag t home
            if (b.score[other] > a.score[other]) {
                say(TEAM_NAME[other] + " captured the flag!");
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

    private void say(String s) {
        feed.add(new Object[]{s, System.nanoTime() + 4_000_000_000L});
        if (feed.size() > 5) feed.remove(0);
    }

    private void addP(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (parts.size() >= MAX_PARTICLES) return;
        Particle p = new Particle();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.max = p.life = life; p.c = c; p.size = size;
        parts.add(p);
    }

    private void burst(double x, double y, Color c, int n, double speed) {
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, sp = speed * (0.3 + rnd.nextDouble() * 0.7);
            addP(x, y, Math.cos(ang) * sp, Math.sin(ang) * sp, 0.3 + rnd.nextDouble() * 0.4, c, 4);
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

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); // crisp pixels

        if (last == null) {
            drawBackground(g);
            g.setFont(Art.font(22));
            Art.textCenter(g, "Waiting for server...", 0, MapData.W, MapData.H / 2, Color.WHITE);
            g.dispose();
            return;
        }
        Snapshot s = last;
        AffineTransform base = g.getTransform();
        double sh = shakeOn ? shake : 0;
        g.translate((rnd.nextDouble() - 0.5) * sh, (rnd.nextDouble() - 0.5) * sh);

        drawBackground(g);
        drawBases(g);
        for (double[] w : MapData.WALLS) Art.wall(g, w);
        drawTorches(g);
        Art.tent(g, MapData.TENT_X, MapData.TENT_Y, s.tentCooldown <= 0 ? 1.0 : 1.0 - s.tentCooldown / MapData.TENT_RECHARGE, time, fx);
        drawEntities(g, s);
        for (double[] b : s.bullets) if (!hidden.contains(b)) drawBullet(g, b[0], b[1], bulletAngle(b), teamOf(b));
        for (double[] gh : ghosts) drawBullet(g, gh[7], gh[8], Math.atan2(gh[3], gh[2]), (int) gh[4]);
        for (Particle p : parts) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, p.life / p.max))));
            g.setColor(p.c);
            g.fillRect((int) p.x - p.size / 2, (int) p.y - p.size / 2, p.size, p.size);
            g.setComposite(old);
        }
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
            g.fillRect((int) sp.x - sp.size / 2, (int) sp.y - sp.size / 2, sp.size, sp.size);
            g.setComposite(oldComp);
        }
        for (MagicalFlame fl : flames) {
            Composite oldComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, fl.life / fl.max)));
            g.setColor(fl.c);
            g.fillRect((int) fl.x - fl.size / 2, (int) fl.y - fl.size / 2, fl.size, fl.size);
            g.setComposite(oldComp);
        }
        g.setTransform(base);

        if (fx) g.drawImage(vignette, 0, 0, null);
        if (flash > 0) { g.setColor(new Color(255, 0, 0, (int) (110 * flash))); g.fillRect(0, 0, MapData.W, MapData.H); }
        drawHud(g, s);
        if (showBoard) drawBoard(g, s);
        if (s.winner >= 0) drawResponsiveVictory(g, s);
        g.dispose();
    }

    private void drawBackground(Graphics2D g) {
        if (bg != null) g.drawImage(bg, -PAD, -PAD, null);
        g.setColor(new Color(6, 6, 18, 120)); // darken so players and bullets stay readable
        g.fillRect(-PAD, -PAD, MapData.W + 2 * PAD, MapData.H + 2 * PAD);
    }

    /** Team light pools, rune rings and crystals at each home base. */
    private void drawBases(Graphics2D g) {
        for (int t = 0; t < 2; t++) {
            double[] b = MapData.BASES[t];
            if (fx) Art.glow(g, Art.FX[t], b[0], b[1], 120, 0.28f);
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
        if (fx) Art.glow(g, Art.FX[t], x, y, 34, (float) (0.5 * pulse));
        Path2D.Double d = new Path2D.Double();
        d.moveTo(x, y - 11); d.lineTo(x + 6, y); d.lineTo(x, y + 9); d.lineTo(x - 6, y); d.closePath();
        g.setColor(new Color(20, 14, 40));
        g.fill(d);
        g.setColor(new Color(Art.FX[t].getRed(), Art.FX[t].getGreen(), Art.FX[t].getBlue(), (int) (255 * pulse)));
        g.fill(new Rectangle2D.Double(x - 2, y - 5, 4, 9));
        g.setColor(Color.WHITE);
        g.fillRect((int) x - 1, (int) y - 6, 2, 2);
    }

    /** A flickering torch on top of every wall block. */
    private void drawTorches(Graphics2D g) {
        for (double[] w : MapData.WALLS) {
            double x = w[0] + w[2] / 2, y = w[1] - 2;
            double f = (Math.sin(time * 13 + x) + Math.sin(time * 7.3 + y)) / 2;
            if (fx) Art.glow(g, new Color(255, 140, 40), x, y - 8, 56, (float) (0.35 + 0.1 * f));
            g.setColor(new Color(80, 60, 50));
            g.fillRect((int) x - 1, (int) y - 2, 3, 8);
            g.setColor(new Color(255, 110, 30));
            g.fillRect((int) x - 2, (int) (y - 9 - f * 1.5), 5, (int) (7 + f * 1.5));
            g.setColor(new Color(255, 215, 100));
            g.fillRect((int) x - 1, (int) (y - 7 - f), 3, (int) (4 + f));
        }
    }

    private void drawEmbers(Graphics2D g) {
        for (double[] e : embers) {
            g.setColor(e[0] < MapData.W / 2 ? new Color(255, 150, 60, 170) : new Color(110, 220, 255, 150));
            g.fillRect((int) e[0], (int) e[1], 2, 2);
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
            if (fx) Art.glow(g, Art.FX[t], xy[0] + 6, xy[1] - 34, 30, 0.45f);
            Art.flag(g, xy[0] + 4, xy[1] - 34, t, time);
        }
    }

    private void drawPlayer(Graphics2D g, Snapshot.P p, double[] xy, boolean mine) {
        double[] a = anim.get(p.id);
        boolean moving = a != null && a[1] > 0;
        double aim = mine ? Math.atan2(mouseY - xy[1], mouseX - xy[0]) : p.aim;
        if (last != null && last.winner >= 0 && p.team == last.winner && p.alive) {
            aim = -Math.PI / 2 + Math.sin(time * 5 + p.id) * 0.18; // victorious weapon raise
            if (fx && rnd.nextInt(3) == 0) addP(xy[0] + (rnd.nextDouble() - 0.5) * 14, xy[1] + 6, (rnd.nextDouble() - 0.5) * 8, -25 - rnd.nextDouble() * 20, 0.4, new Color(180, 80, 255), 3);
        }
        double px = xy[0], py = xy[1];

        g.setColor(new Color(0, 0, 0, 100));
        g.fill(new Ellipse2D.Double(px - 11, py + 9, 22, 7)); // shadow
        if (!p.alive) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            g.drawImage(Art.sprite(p.team, 3), (int) px - 12, (int) py + 2, 24, 10, null);
            g.setComposite(old);
            return;
        }
        g.setColor(mine ? Color.WHITE : Art.FX[p.team]); // team ring under the feet
        g.setStroke(new BasicStroke(mine ? 2 : 1));
        g.draw(new Ellipse2D.Double(px - 13, py + 7, 26, 9));
        g.setStroke(new BasicStroke(1));

        int phase = a == null ? 0 : (int) a[0];
        int frame = moving ? 1 + (phase & 1) : 0;
        double bob = moving ? ((phase & 1) == 0 ? 0 : -2) : Math.sin(time * 3 + p.id) * 1.2;
        boolean left = Math.cos(aim) < 0;
        int x0 = (int) px - 12, y0 = (int) (py - 20 + bob);
        g.drawImage(Art.sprite(p.team, frame), left ? x0 + 24 : x0, y0, left ? -24 : 24, 32, null);

        // Body energy cycle / magical invulnerability ring
        if (p.shieldSec > 0) {
            Art.shieldRing(g, px, py, p.shieldSec, MapData.SHIELD_DURATION, time);
        }

        double ft = fireT.getOrDefault(p.id, 0.0);
        int kick = (int) (-3 * Math.min(1, ft / 0.08)); // recoil: the barrel jumps back
        AffineTransform old = g.getTransform(); // gun
        g.translate(px, py + 2 + bob);
        g.rotate(aim);
        g.setColor(new Color(60, 64, 80));
        g.fillRect(4 + kick, -3, 20, 6);
        g.setColor(new Color(140, 145, 165));
        g.fillRect(4 + kick, -3, 20, 1);
        g.setColor(new Color(40, 42, 54));
        g.fillRect(4 + kick, 2, 6, 4); // grip
        g.setColor(Art.FX[p.team]);
        g.fillRect(21 + kick, -3, 3, 6);
        if (ft > 0) { // muzzle flash along the barrel
            g.setColor(Art.FX[p.team]);
            g.fillRect(25, -2, 10, 4);
            g.setColor(Color.WHITE);
            g.fillRect(25, -1, 6, 2);
            g.fillRect(24, -4, 3, 8);
        }
        g.setTransform(old);
        if (ft > 0 && fx) Art.glow(g, Art.FX[p.team], px + Math.cos(aim) * 28, py + 2 + bob + Math.sin(aim) * 28, 24, 0.9f);

        g.setFont(Art.font(10));
        Art.textCenter(g, p.name, (int) px - 40, 80, y0 - 4, p.team == 0 ? new Color(255, 160, 150) : new Color(170, 225, 255));
    }

    /** Travel direction of a server bullet, from where it was one snapshot ago; 0 if it just spawned. */
    private double bulletAngle(double[] b) {
        if (prev != null)
            for (double[] o : prev.bullets)
                if (teamOf(o) == teamOf(b) && Math.hypot(b[0] - o[0], b[1] - o[1]) < 30 && (b[0] != o[0] || b[1] != o[1]))
                    return Math.atan2(b[1] - o[1], b[0] - o[0]);
        return 0;
    }

    /** Glowing energy bolt; its bright core is exactly at (x, y), the collision position. */
    private void drawBullet(Graphics2D g, double x, double y, double ang, int t) {
        if (fx) Art.glow(g, Art.FX[t], x, y, 16, 0.7f);
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.rotate(ang);
        int len = 12 + (int) Math.round(Math.sin(time * 40));
        g.setColor(Art.FX[t]);
        g.fillRect(-len / 2 - 2, -2, len, 5);
        g.setColor(Color.WHITE);
        g.fillRect(-2, -1, 6, 3);
        g.setTransform(old);
    }

    // ---------- HUD ----------

    private void drawHud(Graphics2D g, Snapshot s) {
        Snapshot.P me = find(s, myId);
        int mt = me == null ? 0 : me.team;

        g.setFont(Art.font(20));
        Art.panel(g, 240, 8, 130, 34, TEAM[0]);
        Art.textCenter(g, "RED  " + s.score[0], 240, 130, 32, TEAM[0]);
        Art.panel(g, 430, 8, 130, 34, TEAM[1]);
        Art.textCenter(g, s.score[1] + "  BLUE", 430, 130, 32, TEAM[1]);
        Art.panel(g, 372, 8, 56, 34, Art.BORDER);
        g.setFont(Art.font(15));
        Art.textCenter(g, String.format("%d:%02d", s.timeLeftSec / 60, s.timeLeftSec % 60), 372, 56, 30, Color.WHITE);

        g.setFont(Art.font(11)); // real flag state for each team
        for (int t = 0; t < 2; t++) {
            Snapshot.F f = s.flags[t];
            String st = f.state == Snapshot.BASE ? "flag: home" : f.state == Snapshot.CARRIED ? "flag: taken" : "flag: dropped";
            Color c = f.state == Snapshot.BASE ? new Color(170, 175, 195) : f.state == Snapshot.CARRIED ? new Color(255, 200, 80) : new Color(255, 120, 90);
            Art.panel(g, t == 0 ? 240 : 430, 46, 130, 20, TEAM[t]);
            Art.textCenter(g, st, t == 0 ? 240 : 430, 130, 60, c);
        }

        Art.panel(g, 8, 8, 200, 48, TEAM[mt]);
        g.setFont(Art.font(13));
        Art.text(g, "YOU: " + TEAM_NAME[mt] + " TEAM", 18, 28, TEAM[mt]);
        g.setFont(Art.font(11));
        int ping = link.pingMs();
        Art.text(g, "PING " + ping + "ms" + (link.lagSimOn() ? "  LAG SIM" : ""), 18, 46, ping > 150 ? Color.ORANGE : new Color(170, 175, 195));

        g.setFont(Art.font(11));
        Art.text(g, "WASD move  MOUSE aim/shoot  TAB board  F fx " + (fx ? "on" : "off") + "  V shake " + (shakeOn ? "on" : "off") + "  L lag", 10, MapData.H - 10, new Color(255, 255, 255, 120));

        int y = MapData.H - 34 - feed.size() * 24;
        for (Object[] f : feed) {
            String t = (String) f[0];
            int w = g.getFontMetrics().stringWidth(t) + 16;
            Art.panel(g, 8, y += 24, w, 20, Art.BORDER);
            Art.text(g, t, 16, y + 14, Color.WHITE);
        }

        String banner = null;
        for (Snapshot.F f : s.flags) if (f.state == Snapshot.CARRIED && f.carrier == myId) banner = "YOU HAVE THE FLAG - RUN HOME";
        if (me != null && !me.alive) banner = "ELIMINATED - RESPAWNING";
        if (banner != null) {
            g.setFont(Art.font(16));
            int w = g.getFontMetrics().stringWidth(banner) + 28;
            Art.panel(g, (MapData.W - w) / 2, MapData.H - 86, w, 30, banner.startsWith("YOU") ? new Color(255, 200, 80) : TEAM[mt]);
            Art.textCenter(g, banner, (MapData.W - w) / 2, w, MapData.H - 66, Color.WHITE);
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
        int vw = getWidth() > 0 ? getWidth() : MapData.W;
        int vh = getHeight() > 0 ? getHeight() : MapData.H;

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
        btnPlayAgainBounds.setBounds(cardX, cardY, cardW, cardH);

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
        Font vicFont = new Font(Font.SERIF, Font.BOLD, fontSize);
        g.setFont(vicFont);
        FontMetrics fm = g.getFontMetrics();
        int strW = fm.stringWidth(vicText);
        int strX = cardX + (cardW - strW) / 2;
        int strY = (int) vicCenterY + fm.getAscent() / 2 - 4;

        // Layer A: Deep shadow
        g.setColor(new Color(6, 2, 14, 230));
        g.drawString(vicText, strX, strY + 5);

        // Layer B: Violet ambient rim shadow
        g.setColor(new Color(110, 30, 200, 190));
        g.drawString(vicText, strX, strY + 2);
        g.drawString(vicText, strX + 1, strY + 1);

        // Layer C: Crystalline iridescent vertical gradient
        Paint oldPaint = g.getPaint();
        GradientPaint vicGrad = new GradientPaint(
            strX, strY - fm.getAscent(), Color.WHITE,
            strX, strY + 2, accent
        );
        g.setPaint(vicGrad);
        g.drawString(vicText, strX, strY);

        // Layer D: Crisp top bevel specular sheen
        g.setColor(new Color(255, 255, 255, 210));
        g.drawString(vicText, strX, strY - 1);
        g.setPaint(oldPaint);

        // Layer E: Sparkling diamond star glints on the letters
        Art.starGlint(g, strX + fm.charWidth('V') * 0.25, strY - fm.getAscent() * 0.88, time, 8);
        Art.starGlint(g, strX + strW * 0.52, strY - fm.getAscent() * 0.92, time + 1.3, 9);
        Art.starGlint(g, strX + strW - fm.charWidth('Y') * 0.35, strY - fm.getAscent() * 0.88, time + 2.5, 8);
    }

    private void triggerOpeningCelebration(int winner) {
        int vw = getWidth() > 0 ? getWidth() : MapData.W;
        int vh = getHeight() > 0 ? getHeight() : MapData.H;
        Color teamC = TEAM[winner];
        Color purple = new Color(170, 60, 255);
        burst(vw / 2.0, vh / 2.0, purple, 45, 260);
        burst(vw / 2.0, vh / 2.0, teamC, 35, 220);
        launchRocket(vw * 0.25, vh, -25, -360, winner);
        launchRocket(vw * 0.75, vh, 25, -360, winner);
        launchRocket(vw * 0.50, vh, 0, -390, winner);
        nextRocketTime = 0.5;
    }

    private void launchRocket(double x, double y, double vx, double vy, int team) {
        FireworkRocket r = new FireworkRocket();
        r.x = x; r.y = y; r.vx = vx; r.vy = vy;
        r.fuse = 0.7 + rnd.nextDouble() * 0.4;
        r.team = team;
        r.c = team == 1 ? (rnd.nextBoolean() ? new Color(60, 220, 255) : new Color(170, 70, 255))
                        : (rnd.nextBoolean() ? new Color(255, 70, 90) : new Color(170, 70, 255));
        rockets.add(r);
    }

    private void stepCelebration(double dt, int winner) {
        int vw = getWidth() > 0 ? getWidth() : MapData.W;
        int vh = getHeight() > 0 ? getHeight() : MapData.H;

        if (victoryTimer < 7.0 && victoryTimer >= nextRocketTime) {
            nextRocketTime += 0.55 + rnd.nextDouble() * 0.4;
            double rx = vw * (0.15 + rnd.nextDouble() * 0.7);
            double rvx = (rnd.nextDouble() - 0.5) * 80;
            double rvy = -340 - rnd.nextDouble() * 100;
            launchRocket(rx, vh, rvx, rvy, winner);
            if (rnd.nextDouble() < 0.45) {
                double rx2 = vw * (0.15 + rnd.nextDouble() * 0.7);
                launchRocket(rx2, vh, (rnd.nextDouble() - 0.5) * 80, -340 - rnd.nextDouble() * 100, winner);
            }
        }

        for (Iterator<FireworkRocket> it = rockets.iterator(); it.hasNext(); ) {
            FireworkRocket r = it.next();
            r.fuse -= dt;
            r.x += r.vx * dt;
            r.y += r.vy * dt;
            r.vy += 90 * dt;
            addSpark(r.x, r.y, (rnd.nextDouble() - 0.5) * 20, rnd.nextDouble() * 30 + 10, 0.25, new Color(255, 220, 150), 2);
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
            s.vx *= 0.97;
            if (s.life <= 0) it.remove();
        }

        int cardW = Math.min(520, Math.max(340, vw - 48));
        int cardH = Math.min(380, Math.max(260, vh - 56));
        int cardX = (vw - cardW) / 2;
        int cardY = (vh - cardH) / 2;

        Color flameC = winner == 1 ? new Color(60, 220, 255) : new Color(255, 70, 90);
        Color purpleC = new Color(170, 60, 255);
        if (flames.size() < 120 && rnd.nextInt(2) == 0) {
            addFlame(cardX + 32 + (rnd.nextDouble() - 0.5) * 12, cardY + cardH - 18, (rnd.nextDouble() - 0.5) * 18, -40 - rnd.nextDouble() * 40, 0.5 + rnd.nextDouble() * 0.4, rnd.nextBoolean() ? purpleC : flameC, 3);
            addFlame(cardX + cardW - 32 + (rnd.nextDouble() - 0.5) * 12, cardY + cardH - 18, (rnd.nextDouble() - 0.5) * 18, -40 - rnd.nextDouble() * 40, 0.5 + rnd.nextDouble() * 0.4, rnd.nextBoolean() ? purpleC : flameC, 3);
            addFlame(cardX + cardW * (0.2 + rnd.nextDouble() * 0.6), cardY + cardH - 30, (rnd.nextDouble() - 0.5) * 14, -25 - rnd.nextDouble() * 30, 0.8 + rnd.nextDouble() * 0.6, purpleC, 2);
        }

        for (Iterator<MagicalFlame> it = flames.iterator(); it.hasNext(); ) {
            MagicalFlame f = it.next();
            f.life -= dt;
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            if (f.life <= 0) it.remove();
        }
    }

    private void addSpark(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (sparks.size() >= 400) return;
        FireworkSpark s = new FireworkSpark();
        s.x = x; s.y = y; s.vx = vx; s.vy = vy; s.life = s.max = life; s.c = c; s.size = size;
        sparks.add(s);
    }

    private void addFlame(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (flames.size() >= 200) return;
        MagicalFlame f = new MagicalFlame();
        f.x = x; f.y = y; f.vx = vx; f.vy = vy; f.life = f.max = life; f.c = c; f.size = size;
        flames.add(f);
    }

    private void detonateRocket(FireworkRocket r) {
        Sfx.play("firework");
        int count = 35 + rnd.nextInt(20);
        Color primary = r.c;
        Color secondary = r.team == 1 ? new Color(60, 220, 255) : new Color(255, 60, 100);
        Color violet = new Color(190, 70, 255);
        for (int i = 0; i < count; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double spd = 60 + rnd.nextDouble() * 180;
            Color sc = rnd.nextInt(3) == 0 ? Color.WHITE : rnd.nextBoolean() ? primary : (rnd.nextBoolean() ? secondary : violet);
            addSpark(r.x, r.y, Math.cos(ang) * spd, Math.sin(ang) * spd, 0.5 + rnd.nextDouble() * 0.6, sc, rnd.nextInt(3) + 2);
        }
    }

    /** Dark-fantasy ruins picture, scaled to cover the arena. Falls back to a plain color if the file is missing. */
    private static BufferedImage loadBackground() {
        try {
            BufferedImage src = javax.imageio.ImageIO.read(new java.io.File("assets/background.png"));
            int w = MapData.W + 2 * PAD, h = MapData.H + 2 * PAD;
            double k = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int dw = (int) Math.ceil(src.getWidth() * k), dh = (int) Math.ceil(src.getHeight() * k);
            g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
            g.dispose();
            return out;
        } catch (Exception e) {
            return null;
        }
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

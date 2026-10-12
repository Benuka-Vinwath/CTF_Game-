package ctf.client;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * Part B: render loop, input, drawing, prediction, interpolation and effects.
 * Networking (Part C) calls onSnapshot(); this panel calls NetLink.sendInput().
 */
public class GamePanel extends JPanel {
    static final Color[] TEAM = {new Color(235, 70, 70), new Color(70, 130, 240)};
    static final String[] TEAM_NAME = {"RED", "BLUE"};
    static final long INTERP_DELAY_NS = 100_000_000L; // render 100ms in the past (~3 snapshots)
    static final double SHOOT_COOLDOWN = 0.25;

    private static final class Particle {
        double x, y, vx, vy, life, max; Color c;
    }

    private final NetLink link;
    private final int myId;

    private final List<Snapshot> buf = new ArrayList<>();
    private Snapshot last;
    private final List<Particle> parts = new ArrayList<>();
    private final List<Object[]> feed = new ArrayList<>(); // {text, expiryNs}

    private final Set<Integer> keys = new HashSet<>();
    private int mouseX, mouseY;
    private boolean mouseDown, showBoard;

    private final double[] pred = new double[2];
    private boolean predReady, wasAlive = true;

    private double shake, flash, shootCd, sendAcc;
    private long lastTickNs = System.nanoTime();
    private final Random rnd = new Random();
    private final java.awt.image.BufferedImage bg = loadBackground();

    public GamePanel(NetLink link, int myId) {
        this.link = link;
        this.myId = myId;
        setPreferredSize(new Dimension(MapData.W, MapData.H));
        setBackground(new Color(24, 26, 32));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false); // let TAB reach the key listener

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                keys.add(k);
                if (k == KeyEvent.VK_TAB) showBoard = true;
                if (k == KeyEvent.VK_L) link.toggleLagSim();
                if (k == KeyEvent.VK_ENTER && last != null && last.winner >= 0) link.requestPlayAgain();
            }
            @Override public void keyReleased(KeyEvent e) {
                keys.remove(e.getKeyCode());
                if (e.getKeyCode() == KeyEvent.VK_TAB) showBoard = false;
            }
        });
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); }
            @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
            @Override public void mousePressed(MouseEvent e) { mouseDown = true; requestFocusInWindow(); }
            @Override public void mouseReleased(MouseEvent e) { mouseDown = false; }
        };
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
        if (mouseDown && shootCd <= 0 && me != null && me.alive) {
            shootCd = SHOOT_COOLDOWN;
            Sfx.play("shoot");
            burst(pred[0] + Math.cos(aim) * 18, pred[1] + Math.sin(aim) * 18, TEAM[me.team], 4, 120);
        }

        sendAcc += dt;
        if (sendAcc >= 1.0 / 30) { // match the server tick rate
            sendAcc = 0;
            link.sendInput(dx, dy, aim, mouseDown);
        }

        for (Iterator<Particle> it = parts.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt; p.x += p.vx * dt; p.y += p.vy * dt;
            if (p.life <= 0) it.remove();
        }
        shake = Math.max(0, shake - 40 * dt);
        flash = Math.max(0, flash - 2.5 * dt);
        feed.removeIf(f -> (long) f[1] < now);
        repaint();
    }

    private boolean down(int k) { return keys.contains(k); }

    // ---------- events inferred from snapshot differences ----------

    private void detectEvents(Snapshot a, Snapshot b) {
        for (Snapshot.P p : b.players) {
            Snapshot.P o = find(a, p.id);
            if (o == null) { say(p.name + " joined"); continue; }
            if (o.alive && !p.alive) {
                burst(o.x, o.y, TEAM[o.team], 24, 220);
                Sfx.play("hit");
                say(p.name + " was tagged");
                if (p.id == myId) { flash = 1; shake = 12; }
            }
        }
        for (Snapshot.P o : a.players) if (find(b, o.id) == null) say(o.name + " left");

        for (int t = 0; t < 2; t++) {
            Snapshot.F o = a.flags[t], n = b.flags[t];
            if (n.state == Snapshot.CARRIED && o.state != Snapshot.CARRIED) {
                say(name(b, n.carrier) + " picked up the " + TEAM_NAME[t] + " flag");
                Sfx.play("pickup");
            } else if (n.state == Snapshot.DROPPED && o.state == Snapshot.CARRIED) {
                say(TEAM_NAME[t] + " flag dropped");
                Sfx.play("drop");
            } else if (n.state == Snapshot.BASE && o.state == Snapshot.DROPPED) {
                say(TEAM_NAME[t] + " flag returned");
            }
            int other = 1 - t; // the team that scores by carrying flag t home
            if (b.score[other] > a.score[other]) {
                say(TEAM_NAME[other] + " scored!");
                Sfx.play("score");
                burst(MapData.BASES[other][0], MapData.BASES[other][1], TEAM[other], 40, 260);
                shake = Math.max(shake, 7);
            }
        }
        if (b.winner >= 0 && a.winner < 0) Sfx.play("win");
    }

    private void say(String s) {
        feed.add(new Object[]{s, System.nanoTime() + 4_000_000_000L});
        if (feed.size() > 5) feed.remove(0);
    }

    private void burst(double x, double y, Color c, int n, double speed) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double ang = rnd.nextDouble() * Math.PI * 2, sp = speed * (0.3 + rnd.nextDouble() * 0.7);
            p.x = x; p.y = y; p.vx = Math.cos(ang) * sp; p.vy = Math.sin(ang) * sp;
            p.max = p.life = 0.3 + rnd.nextDouble() * 0.4; p.c = c;
            parts.add(p);
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
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (last == null) {
            drawCentered(g, "Waiting for server...", MapData.H / 2, 24, Color.WHITE);
            g.dispose();
            return;
        }
        Snapshot s = last;
        Map<Integer, double[]> pos = interpolate();
        Snapshot.P me = find(s, myId);
        if (me != null && me.alive && predReady) pos.put(myId, pred.clone());

        AffineTransform base = g.getTransform();
        g.translate((rnd.nextDouble() - 0.5) * shake, (rnd.nextDouble() - 0.5) * shake);
        drawArena(g);

        for (Snapshot.P p : s.players) {
            double[] xy = pos.get(p.id);
            if (xy == null) continue;
            drawPlayer(g, p, xy, p.id == myId);
        }
        for (int t = 0; t < 2; t++) { // flags at base / on the ground
            Snapshot.F f = s.flags[t];
            if (f.state == Snapshot.BASE) drawFlag(g, MapData.BASES[t][0], MapData.BASES[t][1], t);
            else if (f.state == Snapshot.DROPPED) drawFlag(g, f.x, f.y, t);
        }
        for (int t = 0; t < 2; t++) { // carried flags float above the carrier
            Snapshot.F f = s.flags[t];
            double[] xy = f.state == Snapshot.CARRIED ? pos.get(f.carrier) : null;
            if (xy != null) drawFlag(g, xy[0], xy[1] - 32, t);
        }
        for (double[] b : s.bullets) {
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(b[0] - 3, b[1] - 3, 6, 6));
            g.setColor(new Color(TEAM[(int) b[2]].getRed(), TEAM[(int) b[2]].getGreen(), TEAM[(int) b[2]].getBlue(), 120));
            g.fill(new Ellipse2D.Double(b[0] - 5, b[1] - 5, 10, 10));
        }
        for (Particle p : parts) {
            g.setColor(new Color(p.c.getRed(), p.c.getGreen(), p.c.getBlue(), (int) (255 * Math.max(0, p.life / p.max))));
            g.fill(new Ellipse2D.Double(p.x - 2, p.y - 2, 4, 4));
        }
        g.setTransform(base);

        if (flash > 0) { g.setColor(new Color(255, 0, 0, (int) (110 * flash))); g.fillRect(0, 0, MapData.W, MapData.H); }
        drawHud(g, s);
        if (showBoard) drawBoard(g, s);
        if (s.winner >= 0) {
            g.setColor(new Color(0, 0, 0, 170)); g.fillRect(0, 0, MapData.W, MapData.H);
            drawCentered(g, TEAM_NAME[s.winner] + " TEAM WINS", 280, 48, TEAM[s.winner]);
            drawCentered(g, "Press ENTER to play again", 330, 20, Color.WHITE);
        }
        g.dispose();
    }

    private void drawArena(Graphics2D g) {
        if (bg != null) g.drawImage(bg, -PAD, -PAD, null); // oversized so screen shake never shows an edge
        g.setColor(new Color(6, 6, 18, 120)); // darken so players and bullets stay readable
        g.fillRect(-PAD, -PAD, MapData.W + 2 * PAD, MapData.H + 2 * PAD);
        for (int t = 0; t < 2; t++) {
            double[] b = MapData.BASES[t];
            g.setColor(new Color(TEAM[t].getRed(), TEAM[t].getGreen(), TEAM[t].getBlue(), 45));
            g.fill(new Ellipse2D.Double(b[0] - b[2], b[1] - b[2], b[2] * 2, b[2] * 2));
            g.setColor(TEAM[t]);
            g.draw(new Ellipse2D.Double(b[0] - b[2], b[1] - b[2], b[2] * 2, b[2] * 2));
        }
        for (double[] w : MapData.WALLS) {
            Rectangle2D r = new Rectangle2D.Double(w[0], w[1], w[2], w[3]);
            g.setColor(new Color(58, 50, 74));
            g.fill(r);
            g.setColor(new Color(150, 125, 185));
            g.setStroke(new BasicStroke(2));
            g.draw(r);
            g.setStroke(new BasicStroke(1));
        }
    }

    private static final int PAD = 20;

    /** Dark-fantasy ruins picture, scaled to cover the arena. Falls back to a plain color if the file is missing. */
    private static java.awt.image.BufferedImage loadBackground() {
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.File("assets/background.png"));
            int w = MapData.W + 2 * PAD, h = MapData.H + 2 * PAD;
            double k = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
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

    private void drawFlag(Graphics2D g, double x, double y, int team) {
        g.setColor(Color.LIGHT_GRAY);
        g.fill(new Rectangle2D.Double(x - 1, y - 12, 2, 24));
        g.setColor(TEAM[team]);
        g.fill(new Polygon(new int[]{(int) x + 1, (int) x + 15, (int) x + 1}, new int[]{(int) y - 12, (int) y - 6, (int) y}, 3));
    }

    private void drawPlayer(Graphics2D g, Snapshot.P p, double[] xy, boolean mine) {
        Color c = TEAM[p.team];
        if (!p.alive) c = new Color(c.getRed(), c.getGreen(), c.getBlue(), 70);
        g.setColor(c);
        g.fill(new Ellipse2D.Double(xy[0] - MapData.R, xy[1] - MapData.R, MapData.R * 2, MapData.R * 2));
        double aim = mine ? Math.atan2(mouseY - xy[1], mouseX - xy[0]) : p.aim;
        if (p.alive) {
            AffineTransform old = g.getTransform();
            g.translate(xy[0], xy[1]);
            g.rotate(aim);
            g.setColor(new Color(200, 205, 220));
            g.fill(new RoundRectangle2D.Double(5, -3, 22, 6, 3, 3)); // barrel
            g.setColor(new Color(120, 125, 140));
            g.fill(new Rectangle2D.Double(3, -4, 8, 8));             // grip
            g.setTransform(old);
        }
        if (mine && p.alive) {
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2));
            g.draw(new Ellipse2D.Double(xy[0] - MapData.R, xy[1] - MapData.R, MapData.R * 2, MapData.R * 2));
            g.setStroke(new BasicStroke(1));
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(p.alive ? Color.WHITE : Color.GRAY);
        int w = g.getFontMetrics().stringWidth(p.name);
        g.drawString(p.name, (int) xy[0] - w / 2, (int) xy[1] - MapData.R - 4);
    }

    private void drawHud(Graphics2D g, Snapshot s) {
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(TEAM[0]); g.drawString("RED " + s.score[0], 330, 28);
        g.setColor(TEAM[1]); g.drawString(s.score[1] + " BLUE", 440, 28);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(Color.WHITE);
        String tm = String.format("%d:%02d", s.timeLeftSec / 60, s.timeLeftSec % 60);
        g.drawString(tm, 388, 48);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(link.pingMs() > 150 ? Color.ORANGE : Color.LIGHT_GRAY);
        g.drawString("Ping: " + link.pingMs() + " ms" + (link.lagSimOn() ? "  [LAG SIM ON]" : "") + "   (L = lag sim)", 10, 18);
        g.setColor(new Color(255, 255, 255, 140));
        g.drawString("WASD move  |  mouse aim/shoot  |  TAB scoreboard", 10, MapData.H - 10);
        int y = MapData.H - 30 - feed.size() * 16;
        for (Object[] f : feed) { g.setColor(Color.WHITE); g.drawString((String) f[0], 10, y += 16); }
    }

    private void drawBoard(Graphics2D g, Snapshot s) {
        g.setColor(new Color(0, 0, 0, 200)); g.fillRoundRect(150, 100, 500, 400, 16, 16);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        for (int t = 0; t < 2; t++) {
            int x = 170 + t * 250, y = 130;
            g.setColor(TEAM[t]); g.drawString(TEAM_NAME[t] + "  " + s.score[t], x, y);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.setColor(Color.WHITE);
            for (Snapshot.P p : s.players) if (p.team == t) {
                y += 22;
                g.drawString(p.name, x, y);
                g.drawString(p.captures + "C " + p.tags + "T", x + 100, y);
                g.drawString(p.pingMs + "ms", x + 170, y);
            }
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
        }
    }

    private void drawCentered(Graphics2D g, String s, int y, int size, Color c) {
        g.setFont(new Font("SansSerif", Font.BOLD, size));
        g.setColor(c);
        g.drawString(s, (MapData.W - g.getFontMetrics().stringWidth(s)) / 2, y);
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

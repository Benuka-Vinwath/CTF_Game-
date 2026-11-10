package ctf.client;

import ctf.common.LobbyMap;
import ctf.common.MapData;
import ctf.common.Protocol;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Draws the waiting room (geometry in ctf.common.LobbyMap): floor, crates, laptop desk, status board,
 * team pads with display flags, practice targets and braziers. The still parts are drawn once per zoom
 * into an image and copied each frame, like Scenery does for the arena.
 * World drawing (drawWorld) goes into GameView's world image; text (drawLabels) into the sharp overlay.
 */
final class LobbyRoom {
    static final Color[] HAT_COLORS = {new Color(255, 205, 90), new Color(240, 240, 250), new Color(255, 120, 190),
        new Color(110, 230, 120), new Color(175, 110, 255), new Color(90, 225, 235)};
    static final String[] HAT_NAMES = {"No hat", "Crown", "Top hat", "Horns", "Halo", "Cap"};

    private static final Color OUTSIDE = new Color(9, 7, 18);
    private BufferedImage still;
    private double stillScale;
    private final double[] targetFlash = new double[LobbyMap.TARGETS.length];   // 1 -> 0 after a hit

    void hitTarget(int i) { targetFlash[i] = 1; }

    void step(double dt) {
        for (int i = 0; i < targetFlash.length; i++) targetFlash[i] = Math.max(0, targetFlash[i] - 2.5 * dt);
    }

    // ------------------------------------------------------------------ world

    void drawWorld(Graphics2D g, double vx, double vy, double vw, double vh, double time, boolean fx) {
        if (vx < -24 || vy < -24 || vx + vw > MapData.W + 24 || vy + vh > MapData.H + 24) {   // very wide or tall window:
            g.setColor(OUTSIDE);                                                             // fill what the room picture
            g.fill(new Rectangle2D.Double(vx, vy, vw, vh));                                  // does not cover
        }
        double sc = g.getTransform().getScaleX();
        if (still == null || sc != stillScale) { still = buildStill(sc); stillScale = sc; }
        Scenery.blit1to1(g, still, -24, -24);

        for (int t = 0; t < 2; t++) {   // team pads: the glow and disc are in the still picture; only the dashed ring turns
            double[] p = LobbyMap.PADS[t];
            Stroke old = g.getStroke();
            g.setColor(Art.FX[t]);
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{14, 9}, (float) (time * 20 % 23)));
            g.draw(new Ellipse2D.Double(p[0] - p[2], p[1] - p[2], p[2] * 2, p[2] * 2));
            g.setStroke(old);
            double fx0 = p[0] + (t == 0 ? -p[2] - 22 : p[2] + 22), fy0 = p[1] - 14;   // display flag beside the pad
            Art.pedestal(g, fx0, fy0, t);
            Art.flag(g, fx0, fy0, t, time + t);
        }

        for (int i = 0; i < LobbyMap.TARGETS.length; i++) {   // practice targets on posts
            double[] tg = LobbyMap.TARGETS[i];
            double k = targetFlash[i], wob = Math.sin(time * 40) * 3 * k;
            double x = tg[0] + wob, y = tg[1], r = tg[2];
            g.setColor(new Color(60, 46, 34));
            g.fill(new Rectangle2D.Double(x - 3, y, 6, MapData.H - y));
            for (int ring = 0; ring < 3; ring++) {
                double rr = r * (1 - ring / 3.0);
                g.setColor(ring % 2 == 0 ? new Color(220, 60, 60) : new Color(240, 235, 225));
                g.fill(new Ellipse2D.Double(x - rr, y - rr, rr * 2, rr * 2));
            }
            g.setColor(new Color(30, 20, 20));
            g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            if (k > 0) {
                if (fx) Art.glow(g, new Color(255, 230, 150), x, y, 40, (float) (0.8 * k));
                g.setColor(new Color(255, 255, 255, (int) (200 * k)));
                g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            }
        }

        double lx = LobbyMap.WALLS[3][0] + 32, ly = LobbyMap.WALLS[3][1] + 4;   // laptop on the desk, screen glowing
        double flick = 0.85 + 0.15 * Math.sin(time * 9);
        if (fx) Art.glow(g, new Color(90, 200, 255), lx + 18, ly + 12, 48, (float) (0.45 * flick));
        g.setColor(new Color(40, 44, 58));
        g.fill(new RoundRectangle2D.Double(lx, ly, 36, 24, 4, 4));
        g.setColor(new Color((int) (70 * flick), (int) (190 * flick), (int) (255 * flick)));
        g.fill(new Rectangle2D.Double(lx + 3, ly + 3, 30, 18));
        g.setColor(new Color(30, 32, 44));
        g.fill(new RoundRectangle2D.Double(lx - 4, ly + 24, 44, 8, 3, 3));
        for (int i = 0; i < 3; i++) {   // scrolling "code" lines on the screen
            double w = 8 + (Math.sin(time * 3 + i * 2) + 1) * 8;
            g.setColor(new Color(220, 250, 255, 170));
            g.fill(new Rectangle2D.Double(lx + 6, ly + 6 + i * 5, w, 2));
        }

        double[] bw = LobbyMap.WALLS[4];   // status board screen on the wall
        g.setPaint(new GradientPaint(0, (float) bw[1], new Color(14, 18, 40), 0, (float) (bw[1] + bw[3]), new Color(6, 8, 22)));
        g.fill(new RoundRectangle2D.Double(bw[0] + 10, bw[1] + 8, bw[2] - 20, bw[3] - 18, 8, 8));
        g.setColor(new Color(120, 110, 200, (int) (150 + 60 * Math.sin(time * 2))));
        g.setStroke(new BasicStroke(2));
        g.draw(new RoundRectangle2D.Double(bw[0] + 10, bw[1] + 8, bw[2] - 20, bw[3] - 18, 8, 8));
        g.setStroke(new BasicStroke(1));

        for (int i = 0; i < 3; i++) {   // braziers on the crates
            double[] c = LobbyMap.WALLS[i];
            Art.torchFlame(g, c[0] + c[2] / 2, c[1] + 3, time + i, 1.0, fx);
        }
    }

    /** Floor, room edge, crates and desk: everything that never moves, at the screen's pixel size. */
    private BufferedImage buildStill(double sc) {
        int w = (int) Math.ceil((MapData.W + 48) * sc), h = (int) Math.ceil((MapData.H + 48) * sc);
        // Opaque on purpose: a picture with no see-through pixels is a plain copy each frame, several times cheaper to draw
        BufferedImage im = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(OUTSIDE);
        g.fillRect(0, 0, im.getWidth(), im.getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(sc, sc);
        g.translate(24, 24);

        g.setColor(new Color(34, 28, 50));   // the outer wall band around the floor
        g.fill(new RoundRectangle2D.Double(-22, -22, MapData.W + 44, MapData.H + 44, 18, 18));
        g.setColor(new Color(58, 50, 82));
        g.setStroke(new BasicStroke(3));
        g.draw(new RoundRectangle2D.Double(-20, -20, MapData.W + 40, MapData.H + 40, 16, 16));

        int tile = 48;   // checkered floor tiles with a little per-tile variation
        for (int ty = 0; ty < MapData.H; ty += tile)
            for (int tx = 0; tx < MapData.W; tx += tile) {
                int v = ((tx * 7 + ty * 13) / tile & 7) - 4;
                boolean dark = ((tx + ty) / tile & 1) == 0;
                g.setColor(dark ? new Color(40 + v, 36 + v, 60 + v) : new Color(48 + v, 43 + v, 70 + v));
                g.fillRect(tx, ty, Math.min(tile, MapData.W - tx), Math.min(tile, MapData.H - ty));
                g.setColor(new Color(0, 0, 0, 50));
                g.drawRect(tx, ty, tile, tile);
            }
        g.setPaint(new RadialGradientPaint(new Point2D.Double(MapData.W / 2.0, MapData.H / 2.0), MapData.W * 0.62f,
            new float[]{0f, 1f}, new Color[]{new Color(255, 240, 220, 26), new Color(0, 0, 10, 120)}));
        g.fillRect(0, 0, MapData.W, MapData.H);
        g.setPaint(null);

        g.setColor(new Color(120, 110, 200, 60));   // spawn rune in the middle
        g.setStroke(new BasicStroke(2));
        g.draw(new Ellipse2D.Double(LobbyMap.SPAWN_X - 80, LobbyMap.SPAWN_Y - 50, 160, 100));
        g.draw(new Ellipse2D.Double(LobbyMap.SPAWN_X - 60, LobbyMap.SPAWN_Y - 36, 120, 72));
        g.setColor(new Color(220, 60, 60, 40));   // practice range strip at the bottom
        g.fill(new Rectangle2D.Double(LobbyMap.RANGE_X, LobbyMap.RANGE_Y, LobbyMap.RANGE_W, MapData.H - LobbyMap.RANGE_Y));
        g.setColor(new Color(220, 60, 60, 90));
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{10, 8}, 0));
        g.draw(new Line2D.Double(LobbyMap.RANGE_X, LobbyMap.RANGE_Y, LobbyMap.RANGE_X + LobbyMap.RANGE_W, LobbyMap.RANGE_Y));

        for (int t = 0; t < 2; t++) {   // team pads: soft light, tinted disc and inner ring
            double[] p = LobbyMap.PADS[t];
            Color c = Art.FX[t];
            Art.glow(g, c, p[0], p[1], (int) (p[2] * 1.6), 0.35f);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
            g.fill(new Ellipse2D.Double(p[0] - p[2], p[1] - p[2], p[2] * 2, p[2] * 2));
            g.setColor(c);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(p[0] - p[2] + 10, p[1] - p[2] + 10, p[2] * 2 - 20, p[2] * 2 - 20));
        }
        g.setStroke(new BasicStroke(1));
        for (int i = 0; i < 3; i++) crate(g, LobbyMap.WALLS[i]);
        desk(g, LobbyMap.WALLS[3]);
        Art.wall(g, LobbyMap.WALLS[4]);
        g.dispose();
        return im;
    }

    private static void crate(Graphics2D g, double[] w) {
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Rectangle2D.Double(w[0] + 5, w[1] + 8, w[2], w[3]));
        g.setColor(new Color(120, 82, 48));
        g.fill(new Rectangle2D.Double(w[0], w[1], w[2], w[3]));
        g.setColor(new Color(150, 106, 64));
        g.fill(new Rectangle2D.Double(w[0], w[1], w[2], 8));
        g.setColor(new Color(78, 52, 30));
        g.setStroke(new BasicStroke(4));
        g.draw(new Rectangle2D.Double(w[0] + 2, w[1] + 2, w[2] - 4, w[3] - 4));
        g.draw(new Line2D.Double(w[0] + 4, w[1] + 4, w[0] + w[2] - 4, w[1] + w[3] - 4));
        g.draw(new Line2D.Double(w[0] + w[2] - 4, w[1] + 4, w[0] + 4, w[1] + w[3] - 4));
        g.setStroke(new BasicStroke(1));
    }

    private static void desk(Graphics2D g, double[] w) {
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Rectangle2D.Double(w[0] + 5, w[1] + 8, w[2], w[3]));
        g.setColor(new Color(92, 64, 44));
        g.fill(new Rectangle2D.Double(w[0], w[1], w[2], w[3]));
        g.setColor(new Color(128, 92, 62));
        g.fill(new Rectangle2D.Double(w[0], w[1], w[2], 6));
        g.setColor(new Color(50, 34, 22));
        g.draw(new Rectangle2D.Double(w[0], w[1], w[2], w[3]));
    }

    // ------------------------------------------------------------------ text (overlay)

    /** Pad names with team counts, the status board, the practice range and the laptop hint. */
    void drawLabels(Graphics2D g, Protocol.Lobby lobby, boolean nearLaptop, double countdown, double time) {
        int[] count = new int[2];
        int ready = 0, total = 0, best = 0;
        String bestName = null, host = "?";
        if (lobby != null) for (Protocol.LobbyEntry e : lobby.entries()) {
            count[Math.max(0, Math.min(1, e.team()))]++;
            total++;
            if (e.ready()) ready++;
            if (e.targetHits() > best) { best = e.targetHits(); bestName = e.name(); }
            if (e.id() == lobby.hostId()) host = e.name();
        }
        int min = lobby == null ? 2 : lobby.minPlayers();

        g.setFont(Art.title(17));
        for (int t = 0; t < 2; t++) {
            double[] p = LobbyMap.PADS[t];
            Art.textCenter(g, GamePanel.TEAM_NAME[t] + " TEAM", p[0] - 100, 200, p[1] - p[2] - 14, GamePanel.TEAM[t].brighter());
            g.setFont(Type.ui(12, true));
            Art.textCenter(g, count[t] + " / " + LobbyMap.MAX_TEAM + "   stand here to join", p[0] - 100, 200, p[1] + p[2] + 22, new Color(220, 215, 240));
            g.setFont(Art.title(17));
        }

        double[] bw = LobbyMap.WALLS[4];
        double bx = bw[0] + 10, bwid = bw[2] - 20;
        g.setFont(Type.ui(13, true));
        Art.textCenter(g, "PLAYERS " + total + "/" + LobbyMap.MAX_PLAYERS + "     RED " + count[0] + "  vs  BLUE " + count[1],
            bx, bwid, bw[1] + 28, new Color(225, 225, 255));
        Art.textCenter(g, "READY " + ready + "/" + total + (total < min ? "     need " + min + " players" : ""),
            bx, bwid, bw[1] + 46, ready == total && total >= min ? new Color(120, 240, 140) : new Color(200, 195, 230));
        String status = countdown > 0 ? "MATCH STARTS IN " + (int) Math.ceil(countdown)
            : ready == total && total >= min ? "Waiting for host " + host + " to start" : "Host: " + host;
        Art.textCenter(g, status, bx, bwid, bw[1] + 64, countdown > 0 ? new Color(255, 205, 90) : new Color(170, 165, 210));

        g.setFont(Type.ui(11.5f, true));
        Art.textCenter(g, "PRACTICE RANGE" + (bestName != null ? "   best: " + bestName + " " + best : ""),
            LobbyMap.RANGE_X, LobbyMap.RANGE_W, LobbyMap.RANGE_Y - 7, new Color(255, 170, 160));

        double[] d = LobbyMap.WALLS[3];   // labels sit above the desk, so they never cover the player standing below it
        if (nearLaptop) {
            g.setFont(Type.ui(12.5f, true));
            double pulse = 0.6 + 0.4 * Math.sin(time * 5);
            Art.namePlate(g, "E  hat      C  colour", d[0] + d[2] / 2, d[1] - 14, new Color(150, 230, 255, (int) (255 * pulse)));
        } else {
            g.setFont(Type.ui(11, true));
            Art.textCenter(g, "WARDROBE", d[0], d[2], d[1] - 14, new Color(150, 220, 255));
        }
    }

    // ------------------------------------------------------------------ hats

    /** A hat sitting on a fighter's head; (cx, top) is the top centre of the helmet. Drawn in world units. */
    static void hat(Graphics2D g, int hat, int color, double cx, double top) {
        if (hat <= 0) return;
        Color c = HAT_COLORS[Math.floorMod(color, HAT_COLORS.length)], dark = c.darker().darker();
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1.4f));
        switch (hat) {
            case 1 -> {   // crown
                Path2D.Double p = new Path2D.Double();
                p.moveTo(cx - 12, top + 2); p.lineTo(cx - 12, top - 10); p.lineTo(cx - 6, top - 4); p.lineTo(cx, top - 13);
                p.lineTo(cx + 6, top - 4); p.lineTo(cx + 12, top - 10); p.lineTo(cx + 12, top + 2); p.closePath();
                g.setColor(c); g.fill(p); g.setColor(dark); g.draw(p);
                g.setColor(new Color(255, 80, 120));
                g.fill(new Ellipse2D.Double(cx - 2.5, top - 4, 5, 5));
            }
            case 2 -> {   // top hat
                g.setColor(dark);
                g.fill(new RoundRectangle2D.Double(cx - 15, top - 1, 30, 5, 3, 3));
                g.fill(new Rectangle2D.Double(cx - 9, top - 20, 18, 20));
                g.setColor(c);
                g.fill(new Rectangle2D.Double(cx - 9, top - 7, 18, 4));
            }
            case 3 -> {   // horns
                for (int s = -1; s <= 1; s += 2) {
                    Path2D.Double p = new Path2D.Double();
                    p.moveTo(cx + s * 6, top + 4);
                    p.curveTo(cx + s * 16, top + 2, cx + s * 20, top - 8, cx + s * 15, top - 16);
                    p.curveTo(cx + s * 14, top - 8, cx + s * 10, top - 4, cx + s * 4, top - 2);
                    p.closePath();
                    g.setColor(c); g.fill(p); g.setColor(dark); g.draw(p);
                }
            }
            case 4 -> {   // halo
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 70));
                g.setStroke(new BasicStroke(6));
                g.draw(new Ellipse2D.Double(cx - 13, top - 16, 26, 8));
                g.setColor(c);
                g.setStroke(new BasicStroke(2.5f));
                g.draw(new Ellipse2D.Double(cx - 13, top - 16, 26, 8));
            }
            default -> {  // cap with a peak
                g.setColor(c);
                g.fill(new Arc2D.Double(cx - 12, top - 9, 24, 20, 0, 180, Arc2D.CHORD));
                g.fill(new RoundRectangle2D.Double(cx - 2, top - 1, 18, 4, 3, 3));
                g.setColor(dark);
                g.draw(new Arc2D.Double(cx - 12, top - 9, 24, 20, 0, 180, Arc2D.CHORD));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(cx - 2, top - 11, 4, 4));
            }
        }
        g.setStroke(old);
    }
}

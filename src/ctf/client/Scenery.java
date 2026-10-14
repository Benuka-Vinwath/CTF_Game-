package ctf.client;

import ctf.common.MapData;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Everything around the 800x600 arena that is pure decoration: the painted ruins, flagstone floor, the
 * parapet that marks the edge of the playfield, the courtyard and pillars that fill the screen when the
 * window is wider than the arena, and the parallax layers (mountains, mist, near silhouettes).
 *
 * Static art is painted once per (zoom, window shape) into cached images; each frame only blits them and
 * animates flames, fog and light. Nothing here touches gameplay: the arena itself stays exactly where the
 * server's collision code says it is.
 */
final class Scenery {
    /** World units of extra picture around the view, so screen shake and parallax never show an edge. */
    static final int PAD = 40;
    /** Thickness of the stone parapet around the arena. */
    static final int RIM = 22;

    private final BufferedImage photo = load();
    private BufferedImage back, far, near;
    private double cSc = -1, cVw, cVh, cVx, cVy;
    private double rx, ry, rw, rh;                          // world rectangle covered by the cached images
    private final List<double[]> braziers = new ArrayList<>(); // x, y of each decorative fire

    private static BufferedImage load() {
        try {
            return javax.imageio.ImageIO.read(new java.io.File("assets/background.png"));
        } catch (Exception e) {
            return null; // falls back to a painted gradient
        }
    }

    // ---------------------------------------------------------------- per-frame drawing

    /** Backdrop, floor, parapet, pillars, far mountains, fire, fog and light. Draw this first. */
    void drawBack(Graphics2D g, double vx0, double vy0, double vw, double vh, double camX, double camY, double t, boolean fx) {
        ensure(g.getTransform().getScaleX(), vx0, vy0, vw, vh);
        blit(g, back, 0, 0);
        if (fx) blitStrips(g, far, -camX * 14, -camY * 8);
        for (double[] b : braziers) // fires outside the window are skipped
            if (b[0] > vx0 - 60 && b[0] < vx0 + vw + 60 && b[1] > vy0 - 80 && b[1] < vy0 + vh + 40) Art.torchFlame(g, b[0], b[1], t, 1.3, fx);
    }

    /** Dark near-camera silhouettes. They are clipped to the area outside the arena, so they never cover play. Draw last. */
    void drawFront(Graphics2D g, double camX, double camY, boolean fx) {
        if (fx && near != null) blitStrips(g, near, camX * 34, camY * 18);
    }

    private void blit(Graphics2D g, BufferedImage im, double dx, double dy) { blit1to1(g, im, rx + dx, ry + dy); }

    /**
     * The far and near layers only contain anything outside the arena, and parallax moves them by at most 17 units,
     * so only the four strips around the arena (plus that slack) are copied; the big empty middle is skipped.
     */
    private void blitStrips(Graphics2D g, BufferedImage im, double dx, double dy) {
        double k = im.getWidth() / rw, slack = 20;
        int w = im.getWidth(), h = im.getHeight();
        int l = clamp((-RIM - rx + slack) * k, w), r = clamp((MapData.W + RIM - rx - slack) * k, w);
        int t = clamp((-RIM - ry + slack) * k, h), b = clamp((MapData.H + RIM - ry - slack) * k, h);
        AffineTransform at = g.getTransform();
        int ox = (int) Math.round(at.getTranslateX() + (rx + dx) * at.getScaleX()), oy = (int) Math.round(at.getTranslateY() + (ry + dy) * at.getScaleY());
        g.setTransform(new AffineTransform());
        g.drawImage(im, ox, oy, ox + l, oy + h, 0, 0, l, h, null);                       // left
        g.drawImage(im, ox + r, oy, ox + w, oy + h, r, 0, w, h, null);                   // right
        if (r > l) {
            g.drawImage(im, ox + l, oy, ox + r, oy + t, l, 0, r, t, null);               // top
            g.drawImage(im, ox + l, oy + b, ox + r, oy + h, l, b, r, h, null);           // bottom
        }
        g.setTransform(at);
    }

    private static int clamp(double v, int max) { return Math.max(0, Math.min(max, (int) Math.round(v))); }

    /** Draws im with its top-left at world (wx, wy), one image pixel per screen pixel: a plain copy, no scaling. */
    static void blit1to1(Graphics2D g, BufferedImage im, double wx, double wy) {
        AffineTransform at = g.getTransform();
        int dx = (int) Math.round(at.getTranslateX() + wx * at.getScaleX());
        int dy = (int) Math.round(at.getTranslateY() + wy * at.getScaleY());
        g.setTransform(new AffineTransform());
        g.drawImage(im, dx, dy, null);
        g.setTransform(at);
    }

    // ---------------------------------------------------------------- cache

    private void ensure(double sc, double vx0, double vy0, double vw, double vh) {
        if (back != null && sc == cSc && vw == cVw && vh == cVh && vx0 == cVx && vy0 == cVy) return;
        cSc = sc; cVw = vw; cVh = vh; cVx = vx0; cVy = vy0;
        rx = vx0 - PAD; ry = vy0 - PAD; rw = vw + 2 * PAD; rh = vh + 2 * PAD;
        braziers.clear();
        back = buildBack(sc);
        far = buildFar(sc);
        near = buildNear(sc);
    }

    private BufferedImage image(double sc, int type) {
        return new BufferedImage(Math.max(1, (int) Math.ceil(rw * sc)), Math.max(1, (int) Math.ceil(rh * sc)), type);
    }

    private Graphics2D canvas(BufferedImage im, double sc) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.scale(im.getWidth() / rw, im.getHeight() / rh);
        g.translate(-rx, -ry);
        return g;
    }

    private Area outsideArena() {
        Area a = new Area(new Rectangle2D.Double(rx, ry, rw, rh));
        a.subtract(new Area(new Rectangle2D.Double(-RIM, -RIM, MapData.W + 2 * RIM, MapData.H + 2 * RIM)));
        return a;
    }

    // ---------------------------------------------------------------- static layers

    private BufferedImage buildBack(double sc) {
        BufferedImage im = image(sc, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(im, sc);
        Rectangle2D region = new Rectangle2D.Double(rx, ry, rw, rh);
        if (photo != null) {
            double k = Math.max(rw / photo.getWidth(), rh / photo.getHeight());
            double dw = photo.getWidth() * k, dh = photo.getHeight() * k;
            g.drawImage(photo, (int) Math.round(rx + (rw - dw) / 2), (int) Math.round(ry + (rh - dh) / 2),
                (int) Math.ceil(dw), (int) Math.ceil(dh), null);
        } else {
            g.setPaint(new GradientPaint(0, (float) ry, new Color(22, 24, 64), 0, (float) (ry + rh), new Color(10, 8, 24)));
            g.fill(region);
        }
        // grade the picture into the navy / charcoal / muted purple palette
        g.setPaint(new GradientPaint(0, (float) ry, new Color(4, 6, 28, 170), 0, (float) (ry + rh), new Color(22, 10, 36, 120)));
        g.fill(region);

        flagstones(g, region, 7, 46, new Color(34, 32, 58), 118);                                 // courtyard outside the arena
        Rectangle2D arena = new Rectangle2D.Double(0, 0, MapData.W, MapData.H);
        flagstones(g, arena, 11, 34, new Color(34, 32, 56), 214);                                 // the playfield floor
        g.setPaint(new RadialGradientPaint(new Point2D.Double(MapData.W / 2.0, MapData.H / 2.0), (float) (MapData.W * 0.66),
            new float[]{0f, 1f}, new Color[]{new Color(130, 120, 210, 30), new Color(0, 0, 12, 80)}));
        g.fill(arena);

        for (double[] b : MapData.BASES) { // carved rune circle under each home base
            g.setColor(new Color(255, 255, 255, 16));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(b[0] - b[2] - 8, b[1] - b[2] - 8, (b[2] + 8) * 2, (b[2] + 8) * 2));
            g.draw(new Ellipse2D.Double(b[0] - b[2] - 14, b[1] - b[2] - 14, (b[2] + 14) * 2, (b[2] + 14) * 2));
        }

        rim(g);
        pillars(g);
        g.dispose();
        return im;
    }

    private static int cl(int v) { return Math.max(0, Math.min(255, v)); }

    /** Irregular flagstone paving with mortar, worn edges, cracks and moss. Deterministic per seed. */
    private static void flagstones(Graphics2D g, Rectangle2D area, long seed, int sz, Color tone, int alpha) {
        Shape old = g.getClip();
        g.clip(area);
        Random r = new Random(seed);
        g.setColor(new Color(5, 4, 12, Math.min(255, alpha)));
        g.fill(area);
        double y = area.getMinY() - r.nextInt(30);
        while (y < area.getMaxY()) {
            double h = sz * 0.75 + r.nextInt(sz / 3);
            double x = area.getMinX() - r.nextInt(60);
            while (x < area.getMaxX()) {
                double w = sz + r.nextInt(sz);
                int v = r.nextInt(13) - 6;
                g.setColor(new Color(cl(tone.getRed() + v), cl(tone.getGreen() + v), cl(tone.getBlue() + v + r.nextInt(6)), alpha));
                g.fill(new RoundRectangle2D.Double(x + 1, y + 1, w - 2, h - 2, 10, 10));
                g.setColor(new Color(255, 255, 255, 10));
                g.draw(new Line2D.Double(x + 6, y + 2.2, x + w - 7, y + 2.2));   // lit top edge
                g.setColor(new Color(0, 0, 0, 46));
                g.draw(new Line2D.Double(x + 6, y + h - 2.2, x + w - 7, y + h - 2.2));
                if (r.nextInt(5) == 0) { // hairline crack
                    g.setColor(new Color(0, 0, 0, 80));
                    double cx = x + 8 + r.nextInt((int) w - 16), cy = y + 4;
                    Path2D.Double p = new Path2D.Double();
                    p.moveTo(cx, cy);
                    for (int k = 0; k < 4; k++) p.lineTo(cx += r.nextInt(9) - 4, cy += (h - 8) / 4);
                    g.draw(p);
                }
                if (r.nextInt(8) == 0) { // moss in the seams
                    g.setColor(new Color(40, 96, 84, 62));
                    g.fill(new Ellipse2D.Double(x + 4 + r.nextInt((int) w - 20), y + h - 9, 12 + r.nextInt(14), 6));
                }
                x += w;
            }
            y += h;
        }
        g.setClip(old);
    }

    /** Brick course used for the parapet and the corner towers. */
    private static void bricks(Graphics2D g, Rectangle2D r, long seed) {
        Shape old = g.getClip();
        g.clip(r);
        g.setColor(new Color(24, 21, 38));
        g.fill(r);
        Random rnd = new Random(seed);
        int row = 0;
        for (double yy = r.getMinY(); yy < r.getMaxY(); yy += 11, row++) {
            for (double xx = r.getMinX() - (row % 2) * 12; xx < r.getMaxX(); xx += 24) {
                int v = rnd.nextInt(18) - 9;
                g.setColor(new Color(cl(50 + v), cl(45 + v), cl(72 + v)));
                g.fill(new Rectangle2D.Double(xx + 1, yy + 1, 22, 9));
                g.setColor(new Color(255, 255, 255, 20));
                g.fill(new Rectangle2D.Double(xx + 1, yy + 1, 22, 1.5));
            }
        }
        g.setClip(old);
    }

    /** The parapet that shows where the playfield ends, with an inner shadow and a lit lip. */
    private void rim(Graphics2D g) {
        final int W = MapData.W, H = MapData.H, T = RIM;
        // inner shadow on the floor
        Color dark = new Color(2, 0, 10, 130), none = new Color(2, 0, 10, 0);
        g.setPaint(new GradientPaint(0, 0, dark, 0, 22, none)); g.fill(new Rectangle2D.Double(0, 0, W, 22));
        g.setPaint(new GradientPaint(0, H, dark, 0, H - 22, none)); g.fill(new Rectangle2D.Double(0, H - 22, W, 22));
        g.setPaint(new GradientPaint(0, 0, dark, 22, 0, none)); g.fill(new Rectangle2D.Double(0, 0, 22, H));
        g.setPaint(new GradientPaint(W, 0, dark, W - 22, 0, none)); g.fill(new Rectangle2D.Double(W - 22, 0, 22, H));

        bricks(g, new Rectangle2D.Double(-T, -T, W + 2 * T, T), 1);
        bricks(g, new Rectangle2D.Double(-T, H, W + 2 * T, T), 2);
        bricks(g, new Rectangle2D.Double(-T, 0, T, H), 3);
        bricks(g, new Rectangle2D.Double(W, 0, T, H), 4);
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(new Color(130, 160, 255, 110));                       // cool light on the lip facing the arena
        g.draw(new Rectangle2D.Double(0, 0, W, H));
        g.setColor(new Color(0, 0, 0, 190));
        g.draw(new Rectangle2D.Double(-T, -T, W + 2 * T, H + 2 * T));

        for (int c = 0; c < 4; c++) { // corner towers with a brazier on top
            double tx = (c % 2 == 0 ? -T - 20 : W - 12), ty = (c < 2 ? -T - 20 : H - 12);
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new Ellipse2D.Double(tx - 4, ty + 50, 74, 18));
            Rectangle2D.Double tower = new Rectangle2D.Double(tx, ty, 62, 62);
            bricks(g, tower, 20 + c);
            g.setColor(new Color(10, 8, 20));
            g.setStroke(new BasicStroke(2f));
            g.draw(tower);
            for (int m = 0; m < 4; m++) bricks(g, new Rectangle2D.Double(tx + 3 + m * 15, ty - 7, 10, 8), 40 + m + c);
            g.setColor(new Color(120, 150, 255, 90));
            g.draw(new Line2D.Double(tx + 1, ty + 1, tx + 61, ty + 1));
            braziers.add(new double[]{tx + 31, ty + 14});
        }
    }

    /** Broken columns along the courtyard; only visible when the window is wider or taller than the arena. */
    private void pillars(Graphics2D g) {
        final int W = MapData.W;
        double[][] spots ={{-54, 150, 70, 0}, {-54, 330, 84, 0}, {-54, 510, 62, 1}, {-128, 230, 120, 1}, {-128, 430, 96, 0},
                            {W + 54, 150, 70, 0}, {W + 54, 330, 84, 0}, {W + 54, 510, 62, 1}, {W + 128, 230, 120, 1}, {W + 128, 430, 96, 0}};
        for (double[] s : spots) {
            if (s[0] < rx - 20 || s[0] > rx + rw + 20) continue;
            pillar(g, s[0], s[1], s[2], 26, s[3] == 1);
            if (s[3] == 0) braziers.add(new double[]{s[0], s[1] - s[2] - 10});
        }
    }

    private static void pillar(Graphics2D g, double cx, double baseY, double h, double w, boolean broken) {
        g.setColor(new Color(0, 0, 0, 100));
        g.fill(new Ellipse2D.Double(cx - w, baseY - 8, w * 2, 18));
        Rectangle2D.Double plinth = new Rectangle2D.Double(cx - w * 0.78, baseY - 14, w * 1.56, 16);
        bricks(g, plinth, (long) (cx + baseY));
        g.setPaint(new GradientPaint((float) (cx - w / 2), 0, new Color(86, 82, 120), (float) (cx + w / 2), 0, new Color(24, 22, 40)));
        Path2D.Double shaft = new Path2D.Double();
        shaft.moveTo(cx - w / 2, baseY - 14);
        shaft.lineTo(cx - w / 2, baseY - h);
        if (broken) { // jagged fracture
            shaft.lineTo(cx - w * 0.2, baseY - h + 9); shaft.lineTo(cx, baseY - h - 4); shaft.lineTo(cx + w * 0.25, baseY - h + 12); shaft.lineTo(cx + w / 2, baseY - h + 2);
        } else shaft.lineTo(cx + w / 2, baseY - h);
        shaft.lineTo(cx + w / 2, baseY - 14);
        shaft.closePath();
        g.fill(shaft);
        g.setColor(new Color(0, 0, 0, 70));
        for (int i = 1; i < 4; i++) g.draw(new Line2D.Double(cx - w / 2 + i * w / 4, baseY - h + 14, cx - w / 2 + i * w / 4, baseY - 16)); // flutes
        g.setColor(new Color(160, 175, 255, 70));
        g.draw(new Line2D.Double(cx - w / 2 + 1, baseY - h + 4, cx - w / 2 + 1, baseY - 15));
        if (!broken) {
            Rectangle2D.Double cap = new Rectangle2D.Double(cx - w * 0.7, baseY - h - 10, w * 1.4, 12);
            bricks(g, cap, (long) (cx * 3 + baseY));
        }
    }

    private BufferedImage buildFar(double sc) {
        BufferedImage im = image(sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(im, sc);
        for (int i = 0; i < 3; i++) { // cold moonlight shafts across the whole view
            double x0 = cVx + cVw * (0.12 + 0.34 * i);
            Path2D.Double p = new Path2D.Double();
            p.moveTo(x0, ry); p.lineTo(x0 + 70, ry); p.lineTo(x0 + 70 + 220, ry + rh); p.lineTo(x0 + 220, ry + rh); p.closePath();
            g.setPaint(new GradientPaint(0, (float) ry, new Color(150, 175, 255, 26), 0, (float) (ry + rh), new Color(150, 175, 255, 0)));
            g.fill(p);
        }
        for (int i = 0; i < 6; i++) { // faint drifting-looking ground mist
            double x = cVx + cVw * ((i * 0.21) % 1.1), y = cVy + cVh * (0.40 + 0.55 * ((i * 0.377) % 1));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 60, new float[]{0f, 1f},
                new Color[]{i % 2 == 0 ? new Color(120, 130, 210, 26) : new Color(150, 110, 190, 24), new Color(120, 130, 210, 0)}));
            AffineTransform keep = g.getTransform();
            g.translate(x, y);
            g.scale(3.2, 0.55);
            g.fill(new Rectangle2D.Double(-60, -60, 120, 120));
            g.setTransform(keep);
        }
        g.clip(outsideArena());
        Random r = new Random(5);
        for (int layer = 0; layer < 3; layer++) { // far to near mountain ridges
            double baseY = ry + rh * (0.42 + 0.09 * layer);
            Path2D.Double p = new Path2D.Double();
            p.moveTo(rx, ry + rh);
            double x = rx - 10;
            boolean peak = true;
            while (x <= rx + rw + 30) {
                p.lineTo(x, baseY - (peak ? 30 + r.nextInt(80) - layer * 18 : r.nextInt(18)));
                peak = !peak;
                x += 20 + r.nextInt(46);
            }
            p.lineTo(rx + rw + 30, ry + rh);
            p.closePath();
            g.setColor(new Color(16 + layer * 7, 18 + layer * 6, 54 + layer * 12, 105 + layer * 38));
            g.fill(p);
        }
        g.dispose();
        return im;
    }

    private BufferedImage buildNear(double sc) {
        BufferedImage im = image(sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(im, sc);
        g.clip(outsideArena());
        Random r = new Random(9);
        Color ink = new Color(4, 4, 14, 238);
        for (int side = -1; side <= 1; side += 2) { // ruined columns framing both ends of the screen
            double edge = side < 0 ? rx : rx + rw;
            for (int i = 0; i < 3; i++) {
                double w = 30 + r.nextInt(26), x = edge - side * (i * 34 + r.nextInt(16)) - (side < 0 ? 0 : w);
                double top = ry + rh * (0.30 + r.nextDouble() * 0.45);
                Path2D.Double p = new Path2D.Double();
                p.moveTo(x, ry + rh); p.lineTo(x, top + 8); p.lineTo(x + w * 0.3, top); p.lineTo(x + w * 0.55, top + 14); p.lineTo(x + w, top + 4); p.lineTo(x + w, ry + rh);
                p.closePath();
                g.setColor(ink);
                g.fill(p);
                g.setColor(new Color(110, 140, 255, 60)); // rim light on the arena-facing edge
                g.setStroke(new BasicStroke(1.5f));
                double rimX = side < 0 ? x + w : x;
                g.draw(new Line2D.Double(rimX, top + 6, rimX, ry + rh));
            }
        }
        g.setColor(ink); // stalactites hanging from the top
        for (double x = rx; x < rx + rw; x += 22 + r.nextInt(34)) {
            double len = 14 + r.nextInt(46), w = 8 + r.nextInt(14);
            Path2D.Double p = new Path2D.Double();
            p.moveTo(x, ry); p.lineTo(x + w, ry); p.lineTo(x + w / 2, ry + len); p.closePath();
            g.fill(p);
        }
        g.dispose();
        return im;
    }
}

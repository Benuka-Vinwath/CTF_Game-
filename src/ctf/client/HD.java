package ctf.client;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * High-detail art, drawn as smooth vector shapes so it stays sharp at any resolution (full screen).
 * Everything that is expensive (walls, background, fog) is made once per screen size and reused.
 * Coordinates are world units; every method assumes the caller has already applied the world scale.
 * Team index everywhere: 0 = red, 1 = blue.
 */
final class HD {
    private HD() {}

    // ------------------------------------------------------------------ colours

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }

    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t), (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    static Color lighter(Color c, double t) { return mix(c, Color.WHITE, t); }

    static Color darker(Color c, double t) { return mix(c, Color.BLACK, t); }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // 0 cloak shadow, 1 cloak, 2 cloak light, 3 hood shadow, 4 hood rim, 5 trim, 6 eyes, 7 energy
    private static final Color[][] PALETTE = {
        {new Color(78, 16, 28), new Color(128, 28, 42), new Color(190, 56, 66), new Color(52, 12, 22), new Color(176, 54, 64),
            new Color(255, 96, 80), new Color(255, 196, 90), new Color(255, 100, 56)},
        {new Color(22, 36, 96), new Color(40, 66, 150), new Color(82, 126, 220), new Color(18, 24, 66), new Color(76, 120, 214),
            new Color(96, 180, 255), new Color(150, 244, 255), new Color(70, 205, 255)}};
    private static final Color BOOT = new Color(34, 30, 46);

    // ------------------------------------------------------------------ characters

    /**
     * A hooded fighter. (cx, cy) is the centre of the body: the head is about 20 units above and the boots
     * about 11 below. frame: 0 standing, 1 and 2 the two strides. left mirrors it. seed varies the blinking.
     */
    static void character(Graphics2D g, int team, int frame, double cx, double cy, boolean left, double time, int seed) {
        Color[] c = PALETTE[team];
        AffineTransform base = g.getTransform();
        smooth(g);
        g.translate(cx, cy);
        if (left) g.scale(-1, 1);

        double[][] boots = frame == 0 ? new double[][]{{-4.8, 6.2}, {4.8, 6.2}}
                : frame == 1 ? new double[][]{{-7.6, 6.8}, {3.4, 4.9}} : new double[][]{{-3.0, 5.0}, {6.6, 6.8}};
        for (double[] b : boots) {
            g.setPaint(new GradientPaint(0, (float) b[1], new Color(58, 52, 76), 0, (float) (b[1] + 5.4f), BOOT));
            g.fill(new RoundRectangle2D.Double(b[0] - 3.3, b[1], 7, 5.4, 3.2, 3.2));
            g.setColor(new Color(255, 255, 255, 46));
            g.fill(new RoundRectangle2D.Double(b[0] - 2.4, b[1] + 0.7, 4.4, 1.2, 1, 1));
        }

        Path2D.Double cloak = new Path2D.Double();
        cloak.moveTo(-6.0, -7.5);
        cloak.curveTo(-8.8, -3, -11, 3, -10, 9.2);
        cloak.quadTo(-7, 11.4, -4.5, 9.4);
        cloak.quadTo(-2, 11.8, 0.5, 9.8);
        cloak.quadTo(3.5, 12, 6, 9.6);
        cloak.quadTo(8.5, 11.2, 10, 9.2);
        cloak.curveTo(11, 3, 8.8, -3, 6.0, -7.5);
        cloak.closePath();
        g.setPaint(new LinearGradientPaint(-10.5f, 0, 10.5f, 0, new float[]{0f, 0.34f, 0.7f, 1f},
                new Color[]{c[0], c[2], c[1], c[0]}));
        g.fill(cloak);
        g.setColor(alpha(c[0], 120));
        g.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(-2.6, -3.5, -5.0, 9.6));
        g.draw(new Line2D.Double(2.0, -3.5, 3.3, 10.2));
        g.draw(new Line2D.Double(-0.2, -1.0, -0.9, 9.8));
        Path2D.Double hem = new Path2D.Double();
        hem.moveTo(-10, 9.2);
        hem.quadTo(-7, 11.4, -4.5, 9.4);
        hem.quadTo(-2, 11.8, 0.5, 9.8);
        hem.quadTo(3.5, 12, 6, 9.6);
        hem.quadTo(8.5, 11.2, 10, 9.2);
        g.setColor(alpha(c[5], 210));
        g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(hem);
        g.setColor(alpha(Color.WHITE, 38));
        g.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(-9.6, -7.6, 19, 22, 118, 40, Arc2D.OPEN));

        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, -3.4), 3.4f, new float[]{0f, 1f}, new Color[]{c[6], alpha(c[5], 0)}));
        g.fill(new Ellipse2D.Double(-3.6, -7, 7.2, 7.2));
        g.setColor(c[5]);
        g.fill(new Ellipse2D.Double(-1.5, -4.9, 3, 3));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(-0.7, -4.4, 1, 1));

        g.setPaint(new RadialGradientPaint(new Point2D.Double(-2.5, -17.2), 12f, new float[]{0f, 1f}, new Color[]{c[4], c[3]}));
        g.fill(new Ellipse2D.Double(-8.7, -21.2, 17.4, 17.4));
        g.setColor(alpha(lighter(c[4], 0.5), 170));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(-8.2, -20.7, 16.4, 16.4, 98, 80, Arc2D.OPEN));

        double fx = 1.5;   // the face looks the way the player is aiming
        g.setPaint(new RadialGradientPaint(new Point2D.Double(fx, -13.2), 6.4f, new float[]{0f, 1f},
                new Color[]{new Color(4, 3, 8), new Color(20, 14, 30)}));
        g.fill(new Ellipse2D.Double(-5.1 + fx, -17.6, 10.4, 9.2));
        boolean blink = ((time + seed * 1.7) % 4.2) < 0.13;
        for (int i = 0; i < 2; i++) {
            double ex = fx - 3.0 + i * 3.8, ey = -13.6;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(ex + 1.1, ey + 0.8), 4.4f, new float[]{0f, 1f},
                    new Color[]{alpha(c[6], 150), alpha(c[6], 0)}));
            g.fill(new Ellipse2D.Double(ex - 3.3, ey - 3.6, 8.8, 8.8));
            g.setColor(c[6]);
            if (blink) g.fill(new Rectangle2D.Double(ex, ey + 0.7, 2.3, 0.5));
            else g.fill(new Ellipse2D.Double(ex, ey, 2.3, 1.7));
        }
        g.setTransform(base);
    }

    /** A defeated fighter lying on the ground; (cx, cy) is where the body centre was. */
    static void fallen(Graphics2D g, int team, double cx, double cy) {
        Color[] c = PALETTE[team];
        AffineTransform base = g.getTransform();
        smooth(g);
        g.translate(cx, cy + 6);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(-14, 0, 28, 8));
        g.setPaint(new LinearGradientPaint(-11, 0, 11, 0, new float[]{0f, 0.5f, 1f}, new Color[]{c[0], c[1], c[0]}));
        g.fill(new Ellipse2D.Double(-11, -3, 22, 9));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-9, -2), 7f, new float[]{0f, 1f}, new Color[]{c[4], c[3]}));
        g.fill(new Ellipse2D.Double(-13, -6, 9, 9));
        g.setColor(alpha(c[6], 220));
        g.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(-10.5, -3.5, -8.7, -1.7));
        g.draw(new Line2D.Double(-8.7, -3.5, -10.5, -1.7));
        g.setPaint(BOOT);
        g.fill(new RoundRectangle2D.Double(8, -1.5, 5, 3.2, 2, 2));
        g.setTransform(base);
    }

    /**
     * The energy rifle. The caller has translated to the shoulder and rotated to the aim; flip is true when
     * the character aims left, so the grip stays underneath. kick is the recoil, flash the muzzle flash.
     */
    static void gun(Graphics2D g, int team, double kick, boolean flash, boolean flip) {
        Color e = PALETTE[team][7];
        AffineTransform base = g.getTransform();
        smooth(g);
        if (flip) g.scale(1, -1);
        g.setPaint(new LinearGradientPaint(0, -3.4f, 0, 3.4f, new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(168, 174, 196), new Color(92, 98, 120), new Color(42, 46, 60)}));
        g.fill(new RoundRectangle2D.Double(4 + kick, -3.4, 20.5, 6.8, 3.4, 3.4));
        g.setColor(new Color(255, 255, 255, 120));
        g.fill(new RoundRectangle2D.Double(5.5 + kick, -2.9, 16.5, 1.1, 1, 1));
        g.setPaint(new GradientPaint(0, 2.4f, new Color(58, 62, 80), 0, 8.4f, new Color(30, 32, 44)));
        g.fill(new RoundRectangle2D.Double(5 + kick, 2.4, 5.2, 6.2, 2, 2));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(13.5 + kick, 0), 5f, new float[]{0f, 1f}, new Color[]{alpha(e, 230), alpha(e, 0)}));
        g.fill(new Ellipse2D.Double(8.5 + kick, -5, 10, 10));
        g.setColor(lighter(e, 0.55));
        g.fill(new RoundRectangle2D.Double(10.5 + kick, -1.5, 6, 3, 1.5, 1.5));
        g.setColor(e);
        g.fill(new RoundRectangle2D.Double(21.5 + kick, -3.9, 3.6, 7.8, 2, 2));
        if (flash) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(30, 0), 12f, new float[]{0f, 0.35f, 1f},
                    new Color[]{Color.WHITE, alpha(e, 230), alpha(e, 0)}));
            g.fill(new Ellipse2D.Double(18, -12, 24, 24));
            g.setColor(alpha(Color.WHITE, 230));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(26, 0, 40, 0));
            g.draw(new Line2D.Double(26, 0, 36, -5));
            g.draw(new Line2D.Double(26, 0, 36, 5));
        }
        g.setTransform(base);
    }

    /** A plasma bolt: a bright core with a fading comet tail. The core is exactly at (x, y), the collision point. */
    static void bolt(Graphics2D g, double x, double y, double ang, int team, double time) {
        Color e = PALETTE[team][7];
        AffineTransform base = g.getTransform();
        smooth(g);
        g.translate(x, y);
        g.rotate(ang);
        double tail = 30 + Math.sin(time * 40) * 2;
        Path2D.Double comet = new Path2D.Double();
        comet.moveTo(2, -3.2);
        comet.quadTo(-tail * 0.45, -2.2, -tail, 0);
        comet.quadTo(-tail * 0.45, 2.2, 2, 3.2);
        comet.closePath();
        g.setPaint(new LinearGradientPaint((float) -tail, 0, 2, 0, new float[]{0f, 0.6f, 1f},
                new Color[]{alpha(e, 0), alpha(e, 150), alpha(lighter(e, 0.4), 235)}));
        g.fill(comet);
        g.setColor(e);
        g.fill(new Ellipse2D.Double(-5.5, -3.6, 11.5, 7.2));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(-3.5, -2.1, 8, 4.2));
        g.setTransform(base);
    }

    // ------------------------------------------------------------------ flag, torch, crystal, base

    /** Waving cloth on a metal pole; (x, y) is the middle of the pole. */
    static void flag(Graphics2D g, double x, double y, int team, double t) {
        Color[] c = PALETTE[team];
        AffineTransform base = g.getTransform();
        smooth(g);
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Ellipse2D.Double(x - 6, y + 11, 12, 4));
        g.setPaint(new LinearGradientPaint((float) (x - 1.6), 0, (float) (x + 1.6), 0, new float[]{0f, 0.4f, 1f},
                new Color[]{new Color(120, 120, 140), new Color(236, 236, 248), new Color(110, 110, 130)}));
        g.fill(new RoundRectangle2D.Double(x - 1.5, y - 17, 3, 32, 1.5, 1.5));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x - 0.5, y - 18.6), 2.6f, new float[]{0f, 1f},
                new Color[]{lighter(c[5], 0.6), c[1]}));
        g.fill(new Ellipse2D.Double(x - 2.6, y - 20.4, 5.2, 5.2));

        int n = 16;
        double len = 22, h = 13;
        double[] top = new double[n + 1], bot = new double[n + 1], slope = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double amp = 0.4 + u * 2.6;
            double w = Math.sin(t * 6 - u * 7.0);
            top[i] = y - 16 + w * amp;
            bot[i] = top[i] + h * (1 - 0.05 * u);
            slope[i] = Math.cos(t * 6 - u * 7.0);
        }
        for (int i = 0; i < n; i++) {
            double x0 = x + 1.5 + len * i / n, x1 = x + 1.5 + len * (i + 1) / n;
            double shade = 0.5 + 0.5 * slope[i];
            Color col = mix(c[1], lighter(c[2], 0.25), shade);
            Path2D.Double q = new Path2D.Double();
            q.moveTo(x0, top[i]);
            q.lineTo(x1 + 0.35, top[i + 1]);
            q.lineTo(x1 + 0.35, bot[i + 1]);
            q.lineTo(x0, bot[i]);
            q.closePath();
            g.setColor(col);
            g.fill(q);
        }
        Path2D.Double edge = new Path2D.Double();
        edge.moveTo(x + 1.5, top[0]);
        for (int i = 1; i <= n; i++) edge.lineTo(x + 1.5 + len * i / n, top[i]);
        g.setColor(alpha(lighter(c[5], 0.5), 200));
        g.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(edge);
        Path2D.Double lower = new Path2D.Double();
        lower.moveTo(x + 1.5, bot[0]);
        for (int i = 1; i <= n; i++) lower.lineTo(x + 1.5 + len * i / n, bot[i]);
        g.setColor(alpha(c[0], 220));
        g.draw(lower);
        int mid = n / 2;
        double mx = x + 1.5 + len * mid / n, my = (top[mid] + bot[mid]) / 2;
        g.setColor(alpha(Color.WHITE, 215));
        Path2D.Double dia = new Path2D.Double();
        dia.moveTo(mx, my - 3.4);
        dia.lineTo(mx + 2.6, my);
        dia.lineTo(mx, my + 3.4);
        dia.lineTo(mx - 2.6, my);
        dia.closePath();
        g.fill(dia);
        g.setTransform(base);
    }

    /** A flickering torch flame on a metal bracket; (x, y) is the top of the wall. f is the flicker, about -1..1. */
    static void torch(Graphics2D g, double x, double y, double f, double time) {
        AffineTransform base = g.getTransform();
        smooth(g);
        Path2D.Double cup = new Path2D.Double();
        cup.moveTo(x - 3.8, y - 4);
        cup.lineTo(x + 3.8, y - 4);
        cup.lineTo(x + 2.2, y + 0.5);
        cup.lineTo(x - 2.2, y + 0.5);
        cup.closePath();
        g.setPaint(new LinearGradientPaint((float) (x - 3.8), 0, (float) (x + 3.8), 0, new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(60, 52, 70), new Color(150, 140, 160), new Color(50, 44, 60)}));
        g.fill(cup);
        g.setColor(new Color(60, 50, 64));
        g.fill(new RoundRectangle2D.Double(x - 1.4, y + 0.3, 2.8, 6.5, 1.4, 1.4));
        double sway = Math.sin(time * 9 + x) * 0.9;
        double[][] layers = {{12 + f * 2.8, 3.9}, {8.6 + f * 2.0, 2.8}, {5 + f * 1.2, 1.7}};
        Color[][] cols = {{new Color(255, 214, 90, 235), new Color(255, 84, 22, 220)},
            {new Color(255, 240, 160, 245), new Color(255, 150, 40, 235)}, {new Color(255, 255, 235, 255), new Color(255, 230, 150, 255)}};
        for (int i = 0; i < 3; i++) {
            double hh = layers[i][0], ww = layers[i][1];
            Path2D.Double fl = new Path2D.Double();
            fl.moveTo(x + sway * (hh / 12), y - 4 - hh);
            fl.curveTo(x + ww * 1.1, y - 4 - hh * 0.5, x + ww, y - 4.2, x, y - 3.4);
            fl.curveTo(x - ww, y - 4.2, x - ww * 1.1, y - 4 - hh * 0.5, x + sway * (hh / 12), y - 4 - hh);
            fl.closePath();
            g.setPaint(new GradientPaint(0, (float) (y - 4 - hh), cols[i][0], 0, (float) (y - 3.4), cols[i][1]));
            g.fill(fl);
        }
        g.setTransform(base);
    }

    /** A faceted energy crystal; (x, y) is its centre. */
    static void crystal(Graphics2D g, double x, double y, int team, double pulse) {
        Color e = PALETTE[team][7];
        AffineTransform base = g.getTransform();
        smooth(g);
        double[][] pts = {{x, y - 11.5}, {x + 6.8, y - 0.5}, {x, y + 10.5}, {x - 6.8, y - 0.5}};
        Color[] face = {lighter(e, 0.35 * pulse + 0.1), lighter(e, 0.65 * pulse), darker(e, 0.35), mix(e, darker(e, 0.5), 0.3)};
        for (int i = 0; i < 4; i++) {
            Path2D.Double t = new Path2D.Double();
            t.moveTo(x, y - 0.5);
            t.lineTo(pts[i][0], pts[i][1]);
            t.lineTo(pts[(i + 1) % 4][0], pts[(i + 1) % 4][1]);
            t.closePath();
            g.setColor(face[i]);
            g.fill(t);
        }
        Path2D.Double outline = new Path2D.Double();
        outline.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < 4; i++) outline.lineTo(pts[i][0], pts[i][1]);
        outline.closePath();
        g.setColor(new Color(12, 8, 26));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setColor(alpha(Color.WHITE, (int) (150 + 100 * pulse)));
        g.fill(new Ellipse2D.Double(x - 2.6, y - 7, 2.6, 3.6));
        g.setTransform(base);
    }

    /** The stone disc a flag stands on. */
    static void pedestal(Graphics2D g, double x, double y, int team) {
        AffineTransform base = g.getTransform();
        smooth(g);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x - 3, y + 11), 15f, new float[]{0f, 1f},
                new Color[]{new Color(78, 70, 100), new Color(26, 22, 38)}));
        g.fill(new Ellipse2D.Double(x - 14.5, y + 5.5, 29, 13));
        g.setColor(alpha(PALETTE[team][5], 230));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Ellipse2D.Double(x - 14.5, y + 5.5, 29, 13));
        g.setColor(alpha(PALETTE[team][5], 90));
        g.setStroke(new BasicStroke(0.8f));
        g.draw(new Ellipse2D.Double(x - 9.5, y + 8, 19, 8));
        g.setTransform(base);
    }

    /** The glowing disc of a home base, brighter in the middle. */
    static void baseDisc(Graphics2D g, double cx, double cy, double r, int team) {
        Color e = PALETTE[team][7];
        smooth(g);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new float[]{0f, 0.7f, 1f},
                new Color[]{alpha(e, 70), alpha(e, 34), alpha(e, 8)}));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    // ------------------------------------------------------------------ stone walls (made once per screen size)

    private static final Map<String, BufferedImage> WALLS = new HashMap<>();
    private static final int WM = 14;   // margin around a block, room for its soft shadow

    /** Carved stone block, drawn exactly on the collision rectangle, plus a soft shadow around it. */
    static void wall(Graphics2D g, double[] w) {
        double sc = g.getTransform().getScaleX();
        int x = (int) w[0], y = (int) w[1], ww = (int) w[2], hh = (int) w[3];
        g.drawImage(wallImage(x, y, ww, hh, sc), x - WM, y - WM, ww + 2 * WM, hh + 2 * WM, null);
    }

    private static synchronized BufferedImage wallImage(int x, int y, int ww, int hh, double sc) {
        int iw = Math.max(1, (int) Math.round((ww + 2 * WM) * sc)), ih = Math.max(1, (int) Math.round((hh + 2 * WM) * sc));
        return WALLS.computeIfAbsent(x + "," + y + "," + ww + "x" + hh + "@" + iw + "x" + ih, k -> paintWall(x, y, ww, hh, iw, ih));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static BufferedImage paintWall(int x, int y, int ww, int hh, int iw, int ih) {
        BufferedImage im = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        smooth(g);
        g.scale(iw / (double) (ww + 2 * WM), ih / (double) (hh + 2 * WM));
        g.translate(WM, WM);

        for (int i = WM - 1; i >= 0; i--) {   // soft contact shadow, a little to the lower right
            double f = 1 - i / (double) WM;
            g.setColor(new Color(0, 0, 12, (int) (44 * f * f)));
            g.fill(new RoundRectangle2D.Double(-i + 2, -i + 4, ww + 2 * i, hh + 2 * i, 6 + i, 6 + i));
        }
        g.setPaint(new LinearGradientPaint(0, 0, Math.max(1, ww), 0, new float[]{0f, 1f},
                new Color[]{new Color(72, 64, 96), new Color(44, 38, 64)}));
        g.fill(new Rectangle2D.Double(0, 0, ww, hh));

        Random rnd = new Random(x * 31L + y * 17L + ww);
        int rowH = 10, row = 0;
        for (int by = 0; by < hh; by += rowH, row++) {
            int bh = Math.min(rowH, hh - by);
            int bx = -(row % 2) * 10;
            for (; bx < ww; bx += 20) {
                int x0 = Math.max(0, bx), x1 = Math.min(ww, bx + 20);
                if (x1 - x0 < 2) continue;
                int tone = rnd.nextInt(19) - 9;
                g.setColor(new Color(clamp(86 + tone), clamp(77 + tone), clamp(112 + tone), 175));
                g.fill(new RoundRectangle2D.Double(x0 + 0.7, by + 0.7, x1 - x0 - 1.4, bh - 1.4, 2.2, 2.2));
                g.setColor(new Color(255, 255, 255, 40));
                g.fill(new Rectangle2D.Double(x0 + 1.3, by + 0.9, x1 - x0 - 2.6, 1));
                g.setColor(new Color(0, 0, 12, 80));
                g.fill(new Rectangle2D.Double(x0 + 1.3, by + bh - 1.9, x1 - x0 - 2.6, 1));
            }
        }
        g.setPaint(new GradientPaint(0, hh - 16, new Color(0, 0, 12, 0), 0, hh, new Color(0, 0, 12, 110)));
        g.fill(new Rectangle2D.Double(0, hh - 16, ww, 16));
        g.setPaint(new LinearGradientPaint(0, 0, 0, 5, new float[]{0f, 1f}, new Color[]{new Color(168, 156, 208), new Color(96, 86, 134)}));
        g.fill(new RoundRectangle2D.Double(-0.6, 0, ww + 1.2, 4.6, 2, 2));
        g.setColor(new Color(255, 255, 255, 90));
        g.fill(new Rectangle2D.Double(0.6, 0.4, ww - 1.2, 0.9));
        g.setColor(new Color(255, 255, 255, 44));
        g.fill(new Rectangle2D.Double(0, 4.6, 1.5, hh - 4.6));
        g.setColor(new Color(0, 0, 12, 70));
        g.fill(new Rectangle2D.Double(ww - 1.8, 4.6, 1.8, hh - 4.6));
        for (int i = 0; i < ww / 10; i++) {   // a little moss along the top edge
            if (rnd.nextInt(3) != 0) continue;
            g.setColor(new Color(58, 126, 112, 80));
            g.fill(new Ellipse2D.Double(rnd.nextInt(Math.max(1, ww - 6)), 3.4, 3 + rnd.nextInt(5), 2 + rnd.nextInt(3)));
        }
        g.setColor(new Color(10, 8, 20));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Rectangle2D.Double(0.6, 0.6, ww - 1.2, hh - 1.2));
        g.dispose();
        return im;
    }

    // ------------------------------------------------------------------ background

    /** Sharpens the stretched picture, bakes in soft light shafts and adds fine film grain to hide softness. */
    static BufferedImage enhanceBackground(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        float a = 0.5f;
        float[] k = {0, -a, 0, -a, 1 + 4 * a, -a, 0, -a, 0};
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(in, out);

        Graphics2D g = out.createGraphics();
        smooth(g);
        double[][] shafts = {{0.16, 0.10}, {0.50, 0.14}, {0.82, 0.09}};
        for (double[] s : shafts) {
            double top = s[0] * w, wid = s[1] * w;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(top, 0);
            p.lineTo(top + wid, 0);
            p.lineTo(top + wid * 2.4 + h * 0.18, h * 0.8);
            p.lineTo(top - wid * 0.4 + h * 0.18, h * 0.8);
            p.closePath();
            g.setPaint(new GradientPaint(0, 0, new Color(190, 180, 255, 30), 0, (float) (h * 0.8), new Color(190, 180, 255, 0)));
            g.fill(p);
        }
        g.dispose();

        int[] px = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int n = r.nextInt(9) - 4;
            int p = px[i];
            px[i] = (clamp(((p >> 16) & 255) + n) << 16) | (clamp(((p >> 8) & 255) + n) << 8) | clamp((p & 255) + n);
        }
        return out;
    }

    /**
     * Ground fog: a soft noise texture that wraps sideways, so it can drift forever. w x h pixels,
     * clearest at the bottom and fading out towards the top.
     */
    static BufferedImage fogTexture(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) im.getRaster().getDataBuffer()).getData();
        int[][] cells = {{6, 3}, {14, 5}, {30, 9}};
        double[] weights = {0.55, 0.3, 0.15};
        double[][][] grids = new double[3][][];
        Random r = new Random(11);
        for (int o = 0; o < 3; o++) {
            grids[o] = new double[cells[o][1] + 2][cells[o][0]];
            for (double[] row : grids[o]) for (int i = 0; i < row.length; i++) row[i] = r.nextDouble();
        }
        for (int y = 0; y < h; y++) {
            double fade = Math.pow(y / (double) h, 1.4);
            for (int x = 0; x < w; x++) {
                double v = 0;
                for (int o = 0; o < 3; o++) {
                    double gx = x / (double) w * cells[o][0], gy = y / (double) h * cells[o][1];
                    int x0 = (int) gx, y0 = (int) gy;
                    double fx = gx - x0, fy = gy - y0;
                    fx = fx * fx * (3 - 2 * fx);
                    fy = fy * fy * (3 - 2 * fy);
                    double[][] gr = grids[o];
                    int xa = x0 % cells[o][0], xb = (x0 + 1) % cells[o][0];
                    double top = gr[y0][xa] + (gr[y0][xb] - gr[y0][xa]) * fx;
                    double bot = gr[y0 + 1][xa] + (gr[y0 + 1][xb] - gr[y0 + 1][xa]) * fx;
                    v += weights[o] * (top + (bot - top) * fy);
                }
                int a = (int) (Math.max(0, v - 0.28) * 1.4 * 62 * fade);
                px[y * w + x] = (Math.min(a, 70) << 24) | (150 << 16) | (138 << 8) | 205;
            }
        }
        return im;
    }

    // ------------------------------------------------------------------ interface pieces

    private static final String FAMILY = pickFamily();

    private static String pickFamily() {
        String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String want : new String[]{"Consolas", "DejaVu Sans Mono", "Menlo"}) {
            for (String n : names) if (n.equals(want)) return want;
        }
        return Font.MONOSPACED;
    }

    static Font font(int size) { return new Font(FAMILY, Font.BOLD, size); }

    /** A rounded glass-like panel with a glowing border. */
    static void panel(Graphics2D g, int x, int y, int w, int h, Color border) {
        smooth(g);
        g.setPaint(new GradientPaint(0, y, new Color(26, 22, 48, 215), 0, y + h, new Color(6, 5, 16, 215)));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(new Color(255, 255, 255, 22));
        g.fill(new RoundRectangle2D.Double(x + 2, y + 2, w - 4, Math.max(2, h / 2.0 - 2), 8, 8));
        g.setColor(alpha(border, 60));
        g.setStroke(new BasicStroke(4f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(border);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setStroke(new BasicStroke(1));
    }

    /** A soft round spark instead of a square. */
    static void spark(Graphics2D g, double x, double y, double size, Color c) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x - size / 2, y - size / 2, size, size));
    }

    static AlphaComposite alphaComposite(float a) {
        return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, a)));
    }
}

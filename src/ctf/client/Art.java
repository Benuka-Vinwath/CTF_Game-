package ctf.client;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Procedural pixel art: nothing here is loaded from disk, so there are no asset paths to break.
 * Team index everywhere: 0 = red (villains), 1 = blue (heroes).
 */
final class Art {
    static final Color[] FX = {new Color(255, 90, 50), new Color(60, 200, 255)}; // glows, bullets, rings
    static final Color BORDER = new Color(110, 95, 160);

    // hood-dark, hood-rim, face, eyes, cloak, trim
    private static final String KEYS = "hHseba";
    private static final Color[][] PAL = {
        {new Color(60, 16, 26), new Color(150, 36, 50), new Color(14, 8, 12), new Color(255, 170, 70), new Color(110, 24, 38), new Color(235, 70, 70)},
        {new Color(26, 32, 78), new Color(52, 86, 170), new Color(8, 10, 20), new Color(110, 235, 255), new Color(34, 58, 130), new Color(70, 160, 255)}};
    private static final Color BOOT = new Color(30, 28, 40);

    private static final String[] TOP = {
        "....hhhh....", "...hHHHHh...", "..hHssssHh..", "..hsseessh..", "..hhsssshh..", "...hbbbbh...",
        "..hbbaabbh..", ".hbbbaabbbh.", ".hbbbbbbbbh.", "..hbbbbbbh..", "...bbbbbb..."};
    private static final String[][] LEGS = {
        {"...bb..bb...", "...bb..bb...", "...ll..ll...", "..lll..lll..", "............"},   // stand
        {"..bbb..bbb..", ".bb......bb.", ".ll......ll.", "............", "............"},   // run A
        {"....bbbb....", "....bbbb....", "....llll....", "............", "............"}};  // run B
    private static final String[] DEAD = {
        "...hhhh.....", ".hHssebbbbb.", "hhsseebaabbl", ".hbbbbbbbbll", "..lll..lll.."};

    /** [team][0 idle, 1 run A, 2 run B, 3 dead] */
    private static final BufferedImage[][] SPRITES = new BufferedImage[2][4];
    private static final Map<String, BufferedImage> GLOWS = new HashMap<>();
    private static final Map<Integer, Font> FONTS = new HashMap<>();

    static {
        for (int t = 0; t < 2; t++) for (int f = 0; f < 4; f++) SPRITES[t][f] = build(t, f);
    }

    private static BufferedImage build(int team, int frame) {
        String[] rows;
        if (frame == 3) rows = DEAD;
        else {
            rows = new String[16];
            System.arraycopy(TOP, 0, rows, 0, TOP.length);
            System.arraycopy(LEGS[frame], 0, rows, TOP.length, 5);
        }
        BufferedImage im = new BufferedImage(12, rows.length, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < 12; x++) {
                char c = rows[y].charAt(x);
                if (c == '.') continue;
                im.setRGB(x, y, (c == 'l' ? BOOT : PAL[team][KEYS.indexOf(c)]).getRGB());
            }
        return im;
    }

    static BufferedImage sprite(int team, int frame) { return SPRITES[team][frame]; }

    /** Soft radial light, cached per color and radius; draw it with an alpha composite. */
    static BufferedImage glow(Color c, int r) {
        return GLOWS.computeIfAbsent(c.getRGB() + ":" + r, k -> {
            BufferedImage im = new BufferedImage(2 * r, 2 * r, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = im.createGraphics();
            g.setPaint(new RadialGradientPaint(new Point2D.Float(r, r), r, new float[]{0f, 1f},
                new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 255), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fillRect(0, 0, 2 * r, 2 * r);
            g.dispose();
            return im;
        });
    }

    static void glow(Graphics2D g, Color c, double x, double y, int r, float alpha) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(glow(c, r), (int) x - r, (int) y - r, null);
        g.setComposite(old);
    }

    static BufferedImage vignette(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, h / 2f), w * 0.62f, new float[]{0f, 0.55f, 1f},
            new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(2, 0, 12, 200)}));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return im;
    }

    /** Carved stone block. Drawn exactly on the collision rectangle. */
    static void wall(Graphics2D g, double[] w) {
        int x = (int) w[0], y = (int) w[1], ww = (int) w[2], h = (int) w[3];
        g.setColor(new Color(52, 45, 70));
        g.fillRect(x, y, ww, h);
        g.setColor(new Color(30, 25, 45));
        int row = 0;
        for (int yy = y; yy < y + h; yy += 10, row++) {
            g.drawLine(x, yy, x + ww, yy);
            for (int xx = x + (row % 2) * 10; xx < x + ww; xx += 20) g.drawLine(xx, yy, xx, Math.min(yy + 10, y + h));
        }
        g.setColor(new Color(120, 105, 160));
        g.fillRect(x, y, ww, 3);
        g.setColor(new Color(15, 12, 24));
        g.drawRect(x, y, ww, h);
    }

    static void pedestal(Graphics2D g, double x, double y, int team) {
        g.setColor(new Color(34, 30, 46));
        g.fill(new Ellipse2D.Double(x - 14, y + 6, 28, 12));
        g.setColor(FX[team]);
        g.draw(new Ellipse2D.Double(x - 14, y + 6, 28, 12));
    }

    /** Waving cloth on a pole; (x, y) is the middle of the pole. */
    static void flag(Graphics2D g, double x, double y, int team, double t) {
        g.setColor(new Color(190, 185, 200));
        g.fill(new Rectangle2D.Double(x - 1, y - 16, 2, 30));
        int n = 6;
        int[] xs = new int[2 * n], ys = new int[2 * n];
        for (int i = 0; i < n; i++) {
            double off = Math.sin(t * 6 - i * 0.9) * (0.5 + i * 0.5);
            xs[i] = (int) (x + 1 + i * 3);
            ys[i] = (int) (y - 16 + off);
            xs[2 * n - 1 - i] = xs[i];
            ys[2 * n - 1 - i] = (int) (y - 16 + off + 11);
        }
        g.setColor(PAL[team][5]);
        g.fillPolygon(xs, ys, 2 * n);
        g.setColor(PAL[team][0]);
        g.drawPolygon(xs, ys, 2 * n);
        g.setColor(FX[team]);
        g.fillRect(xs[2] + 1, ys[2] + 4, 4, 3);
    }

    /** Vertical light column rising from (x, y), brightest at the bottom. */
    static void beam(Graphics2D g, double x, double y, Color c, float a) {
        Paint old = g.getPaint();
        g.setPaint(new GradientPaint((float) x, (float) (y - 150), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0),
            (float) x, (float) y, new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * Math.max(0, Math.min(1, a))))));
        g.fill(new Rectangle2D.Double(x - 7, y - 150, 14, 150));
        g.setPaint(old);
    }

    static void panel(Graphics2D g, int x, int y, int w, int h, Color border) {
        g.setColor(new Color(8, 8, 18, 190));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(border);
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x, y, w, h, 8, 8);
        g.setColor(new Color(border.getRed(), border.getGreen(), border.getBlue(), 70));
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(x + 3, y + 3, w - 6, h - 6, 5, 5);
    }

    static Font font(int size) { return FONTS.computeIfAbsent(size, s -> new Font(Font.MONOSPACED, Font.BOLD, s)); }

    static void text(Graphics2D g, String s, int x, int y, Color c) {
        g.setColor(new Color(0, 0, 0, 200));
        g.drawString(s, x + 1, y + 1);
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static void textCenter(Graphics2D g, String s, int x, int w, int y, Color c) {
        text(g, s, x + (w - g.getFontMetrics().stringWidth(s)) / 2, y, c);
    }

    /** Protective magical tent / sanctuary on the ground. */
    static void tent(Graphics2D g, double x, double y, double rechargeFrac, double time, boolean fx) {
        boolean ready = rechargeFrac >= 1.0;
        double pulse = 0.7 + 0.3 * Math.sin(time * 3.2);

        // 1. Ground platform / sanctuary dais
        if (fx) {
            Color glowC = ready ? new Color(130, 60, 240) : new Color(80, 40, 140);
            glow(g, glowC, x, y + 6, 70, (float) (ready ? 0.45 * pulse : 0.2));
            if (ready) glow(g, new Color(60, 220, 255), x, y + 6, 36, (float) (0.35 * pulse));
        }

        // Circular stone dais
        g.setColor(new Color(28, 22, 42));
        g.fill(new Ellipse2D.Double(x - 34, y - 6, 68, 26));
        g.setColor(ready ? new Color(150, 70, 255) : new Color(80, 55, 110));
        g.setStroke(new BasicStroke(ready ? 2 : 1));
        g.draw(new Ellipse2D.Double(x - 34, y - 6, 68, 26));

        // Inner runic circle
        Stroke oldSt = g.getStroke();
        g.setColor(ready ? new Color(60, 220, 255, (int)(220 * pulse)) : new Color(100, 70, 140, 120));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 4}, (float)(time * 8 % 9)));
        g.draw(new Ellipse2D.Double(x - 24, y - 2, 48, 18));
        g.setStroke(oldSt);

        // 2. Dark purple fabric canopy and wooden frame
        g.setColor(new Color(45, 32, 28));
        g.fillRect((int) x - 26, (int) y - 22, 4, 24);
        g.fillRect((int) x + 22, (int) y - 22, 4, 24);

        Path2D.Double canopy = new Path2D.Double();
        canopy.moveTo(x - 30, y - 4);
        canopy.curveTo(x - 28, y - 24, x - 15, y - 38, x, y - 44);
        canopy.curveTo(x + 15, y - 38, x + 28, y - 24, x + 30, y - 4);
        canopy.lineTo(x + 16, y - 10);
        canopy.lineTo(x, y - 14);
        canopy.lineTo(x - 16, y - 10);
        canopy.closePath();

        g.setColor(ready ? new Color(62, 18, 92) : new Color(42, 14, 64));
        g.fill(canopy);

        Path2D.Double highlight = new Path2D.Double();
        highlight.moveTo(x - 20, y - 8);
        highlight.lineTo(x, y - 44);
        highlight.lineTo(x + 2, y - 44);
        highlight.lineTo(x - 10, y - 10);
        highlight.closePath();
        g.setColor(ready ? new Color(100, 35, 145) : new Color(65, 22, 95));
        g.fill(highlight);

        g.setColor(ready ? new Color(80, 230, 255) : new Color(120, 80, 160));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(canopy);
        g.setStroke(oldSt);

        g.setColor(ready ? new Color(200, 100, 255, (int)(240 * pulse)) : new Color(120, 70, 160, 100));
        g.fillRect((int) x - 18, (int) y - 24, 3, 3);
        g.fillRect((int) x - 8, (int) y - 30, 3, 3);
        g.fillRect((int) x + 6, (int) y - 30, 3, 3);
        g.fillRect((int) x + 16, (int) y - 24, 3, 3);

        Path2D.Double door = new Path2D.Double();
        door.moveTo(x - 12, y + 2);
        door.lineTo(x - 12, y - 14);
        door.curveTo(x - 8, y - 20, x + 8, y - 20, x + 12, y - 14);
        door.lineTo(x + 12, y + 2);
        door.closePath();
        g.setColor(new Color(12, 6, 22));
        g.fill(door);
        if (ready) {
            g.setColor(new Color(70, 220, 255, (int)(160 * pulse)));
            g.draw(door);
        }

        // 3. Floating Power-up Symbol (Shield Crest) above entrance
        double bob = Math.sin(time * 3.5) * 3;
        double sx = x, sy = y - 50 + bob;
        if (fx && ready) glow(g, new Color(70, 230, 255), sx, sy, 28, (float)(0.6 * pulse));

        Path2D.Double shield = new Path2D.Double();
        shield.moveTo(sx - 9, sy - 8);
        shield.lineTo(sx + 9, sy - 8);
        shield.lineTo(sx + 9, sy + 1);
        shield.curveTo(sx + 7, sy + 8, sx + 2, sy + 12, sx, sy + 14);
        shield.curveTo(sx - 2, sy + 12, sx - 7, sy + 8, sx - 9, sy + 1);
        shield.closePath();

        g.setColor(new Color(24, 14, 42));
        g.fill(shield);
        g.setColor(ready ? new Color(80, 230, 255) : new Color(120, 95, 150));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(shield);
        g.setStroke(oldSt);

        g.setColor(ready ? Color.WHITE : new Color(150, 140, 170));
        g.fillRect((int) sx - 1, (int) sy - 5, 3, 12);
        g.fillRect((int) sx - 5, (int) sy - 2, 11, 3);

        if (!ready) {
            g.setColor(new Color(60, 220, 255, 190));
            g.setStroke(new BasicStroke(2));
            g.draw(new Arc2D.Double(sx - 12, sy - 12, 24, 24, 90, -rechargeFrac * 360, Arc2D.OPEN));
            g.setStroke(oldSt);
        }
    }

    /** Animated protective energy cycle around player body. */
    static void shieldRing(Graphics2D g, double px, double py, double shieldSec, double maxDuration, double time) {
        double frac = Math.max(0, Math.min(1.0, shieldSec / maxDuration));
        if (frac <= 0) return;

        float alpha = 1.0f;
        if (shieldSec < 1.5) {
            boolean blink = ((int) (time * 20)) % 2 == 0;
            alpha = blink ? 0.35f : 1.0f;
        }

        Composite oldComp = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        int r = 21;
        glow(g, new Color(160, 50, 255), px, py, 34, 0.45f * alpha);
        glow(g, new Color(70, 230, 255), px, py, 22, 0.35f * alpha);

        Stroke oldSt = g.getStroke();
        g.setColor(new Color(130, 45, 220, 90));
        g.setStroke(new BasicStroke(3));
        g.draw(new Ellipse2D.Double(px - r, py - r, r * 2, r * 2));

        int startAngle = (int) ((time * 180) % 360);
        int extent = (int) Math.round(360 * frac);

        g.setColor(new Color(180, 60, 255));
        g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(px - r, py - r, r * 2, r * 2, startAngle, extent, Arc2D.OPEN));

        g.setColor(new Color(90, 240, 255));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(px - r, py - r, r * 2, r * 2, startAngle, extent, Arc2D.OPEN));

        int numMotes = 3;
        for (int i = 0; i < numMotes; i++) {
            double ang = Math.toRadians(startAngle + (extent * i) / (double) Math.max(1, numMotes));
            double mx = px + Math.cos(ang) * r;
            double my = py - Math.sin(ang) * r;
            g.setColor(Color.WHITE);
            g.fillRect((int) mx - 1, (int) my - 1, 3, 3);
            g.setColor(new Color(80, 230, 255, 180));
            g.fillRect((int) mx - 2, (int) my - 2, 5, 5);
        }

        g.setStroke(oldSt);
        g.setComposite(oldComp);
    }

    /** Celebration decorative flame brazier / pillar. */
    static void flameBrazier(Graphics2D g, double x, double y, Color flameColor, double time, boolean fx) {
        g.setColor(new Color(35, 30, 48));
        g.fillRoundRect((int) x - 10, (int) y - 4, 20, 10, 4, 4);
        g.setColor(new Color(80, 65, 110));
        g.drawRoundRect((int) x - 10, (int) y - 4, 20, 10, 4, 4);

        if (fx) glow(g, flameColor, x, y - 16, 42, 0.5f);

        for (int k = 0; k < 3; k++) {
            double shift = Math.sin(time * 9 + k * 2.1) * 3;
            double height = 18 + Math.cos(time * 11 + k * 1.5) * 6;
            Path2D.Double flame = new Path2D.Double();
            flame.moveTo(x - 7 + k * 4, y - 4);
            flame.curveTo(x - 5 + shift, y - height * 0.5, x + shift * 1.5, y - height * 0.8, x + shift, y - height);
            flame.curveTo(x + 2, y - height * 0.6, x + 5, y - 4, x + 7 - k * 3, y - 4);
            flame.closePath();

            g.setColor(k == 0 ? new Color(140, 40, 220, 180) : k == 1 ? flameColor : Color.WHITE);
            g.fill(flame);
        }
    }

    private Art() {}
}

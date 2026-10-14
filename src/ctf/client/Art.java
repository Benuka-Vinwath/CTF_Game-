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

    // o outline, h/H helmet dark/light, p/P crest, v visor, e eyes, a/A armor dark/light, t trim, s/S steel, b belt, l/L leg, k boot, g gem
    private static final String KEYS = "ohHpPveaAtsSblLkg";
    private static final Color[][] PAL = {
        palette(new Color(60, 54, 72), new Color(132, 126, 152), new Color(210, 60, 62), new Color(255, 140, 110), new Color(255, 190, 80),
            new Color(112, 26, 42), new Color(208, 54, 66), new Color(255, 120, 90), new Color(24, 8, 14), new Color(255, 236, 190)),
        palette(new Color(54, 60, 84), new Color(126, 140, 170), new Color(60, 140, 235), new Color(150, 225, 255), new Color(140, 245, 255),
            new Color(30, 54, 138), new Color(72, 130, 236), new Color(120, 215, 255), new Color(6, 10, 26), new Color(225, 250, 255))};

    private static Color[] palette(Color h, Color hl, Color crest, Color crestHi, Color eye, Color armor, Color armorHi, Color trim, Color line, Color gem) {
        Color steel = new Color(104, 104, 128), steelHi = new Color(176, 178, 200);
        Color[] p = new Color[KEYS.length()];
        p[0] = line; p[1] = h; p[2] = hl; p[3] = crest; p[4] = crestHi; p[5] = new Color(10, 8, 16); p[6] = eye;
        p[7] = armor; p[8] = armorHi; p[9] = trim; p[10] = steel; p[11] = steelHi; p[12] = new Color(52, 36, 30);
        p[13] = new Color(armor.getRed() / 2 + 18, armor.getGreen() / 2 + 14, armor.getBlue() / 2 + 20);
        p[14] = new Color(Math.min(255, armor.getRed() + 40), Math.min(255, armor.getGreen() + 34), Math.min(255, armor.getBlue() + 46));
        p[15] = new Color(34, 30, 44); p[16] = gem;
        return p;
    }

    // Left half of the 18x24 picture; the right half is its mirror image.
    private static final String[] TOP = {
        ".......oP", "......oPP", ".....ooHH", "....ohhHH", "....ohhhh", "....ohvvv", "....oheev", "....ohhss",
        ".ooSSSsaa", "oSSSsaAAt", "oSSsoaAAg", "oSSsoaaAA", "oSssoaaAA", ".ossobbtt", "..ooaaaaa", "...oaaAAa"};
    private static final String[] LEG_DOWN = {"...olLLlo", "...olLLlo", "...olLLlo", "...olLLlo", "..okkkkko", "..okkkkko", "..ooooooo", "........."};
    private static final String[] LEG_LIFT = {"...olLLlo", "...olLLlo", "..okkkkko", "..okkkkko", "..ooooooo", ".........", ".........", "........."};

    /** Character picture size in sprite pixels; GamePanel draws it at 2 world pixels per sprite pixel (36x48). */
    static final int SPR_W = 18, SPR_H = 24;

    /** [team][0 idle, 1 run A, 2 run B, 3 fallen] */
    private static final BufferedImage[][] SPRITES = new BufferedImage[2][4];
    private static final Map<String, BufferedImage> GLOWS = new HashMap<>();
    private static final Map<Integer, Font> FONTS = new HashMap<>();

    static {
        for (int t = 0; t < 2; t++) for (int f = 0; f < 3; f++) SPRITES[t][f] = build(t, f);
        for (int t = 0; t < 2; t++) { // a fallen fighter is the standing one tipped onto its side
            BufferedImage s = SPRITES[t][0], r = new BufferedImage(SPR_H, SPR_W, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SPR_H; y++) for (int x = 0; x < SPR_W; x++) r.setRGB(SPR_H - 1 - y, x, s.getRGB(x, y));
            SPRITES[t][3] = r;
        }
    }

    private static BufferedImage build(int team, int frame) {
        String[] left = new String[SPR_H], right = new String[SPR_H];
        for (int i = 0; i < TOP.length; i++) left[i] = right[i] = TOP[i];
        String[] l = frame == 1 ? LEG_LIFT : LEG_DOWN, r = frame == 2 ? LEG_LIFT : LEG_DOWN;
        for (int i = 0; i < 8; i++) { left[TOP.length + i] = l[i]; right[TOP.length + i] = r[i]; }
        BufferedImage im = new BufferedImage(SPR_W, SPR_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SPR_H; y++) {
            String row = left[y] + new StringBuilder(right[y]).reverse();
            for (int x = 0; x < SPR_W; x++) {
                char c = row.charAt(x);
                if (c != '.') im.setRGB(x, y, PAL[team][KEYS.indexOf(c)].getRGB());
            }
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
        double sc = g.getTransform().getScaleX();
        if (sc > 1.25) {   // drawn bigger (full screen): use a light made at the real size, not an enlarged small one
            Scenery.blit1to1(g, glowScaled(c, r, sc), x - r, y - r);   // exact-size light, copied pixel for pixel
        } else {
            g.drawImage(glow(c, r), (int) x - r, (int) y - r, null);
        }
        g.setComposite(old);
    }

    // ---- smooth versions for big screens. The originals stay for the normal size. -------------------

    private static final Map<String, BufferedImage> SCALED = new HashMap<>();

    /** The same radial light, drawn at (2r * scale) pixels so it is smooth instead of an enlarged small bitmap. */
    private static synchronized BufferedImage glowScaled(Color c, int r, double scale) {
        int size = Math.max(2, (int) Math.round(2 * r * scale));
        return SCALED.computeIfAbsent("g:" + c.getRGB() + ":" + r + ":" + size, k -> {
            BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = im.createGraphics();
            g.setPaint(new RadialGradientPaint(new Point2D.Float(size / 2f, size / 2f), size / 2f, new float[]{0f, 1f},
                new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 255), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fillRect(0, 0, size, size);
            g.dispose();
            return im;
        });
    }

    /**
     * Scale2x (also called AdvMAME2x): doubles a pixel-art picture and, where two neighbouring pixels agree
     * on a diagonal, fills the new corner with their colour instead of a block. This rounds off the
     * stair-steps without blurring the flat colours. Pixels outside the picture count as transparent.
     */
    private static BufferedImage scale2x(BufferedImage in) {
        int w = in.getWidth(), h = in.getHeight();
        int[] px = new int[w * h];
        in.getRGB(0, 0, w, h, px, 0, w);
        for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) == 0) px[i] = 0;   // all transparent pixels are equal
        int[] out = new int[w * 2 * h * 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int a = y > 0 ? px[(y - 1) * w + x] : 0;        // above
                int d = y < h - 1 ? px[(y + 1) * w + x] : 0;    // below
                int c = x > 0 ? px[y * w + x - 1] : 0;          // left
                int b = x < w - 1 ? px[y * w + x + 1] : 0;      // right
                int e0 = (c == a && c != d && a != b) ? a : p;
                int e1 = (a == b && a != c && b != d) ? b : p;
                int e2 = (d == c && d != b && c != a) ? c : p;
                int e3 = (b == d && b != a && d != c) ? d : p;
                int o = (y * 2) * (w * 2) + x * 2;
                out[o] = e0;
                out[o + 1] = e1;
                out[o + w * 2] = e2;
                out[o + w * 2 + 1] = e3;
            }
        }
        BufferedImage im = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
        im.setRGB(0, 0, w * 2, h * 2, out, 0, w * 2);
        return im;
    }

    /**
     * A character sprite for a big screen, made once per size: the 12-pixel picture is blown up 8x with
     * Scale2x (so diagonal edges are rounded, not stair-stepped) and then smoothed down to the exact size
     * needed, which gives anti-aliased edges instead of blocks of uneven width. worldW and worldH are the
     * size it is drawn at.
     */
    static synchronized BufferedImage spriteScaled(int team, int frame, double scale, int worldW, int worldH) {
        int w = Math.max(1, (int) Math.round(worldW * scale)), h = Math.max(1, (int) Math.round(worldH * scale));
        return SCALED.computeIfAbsent("s:" + team + ":" + frame + ":" + w + "x" + h, k -> {
            BufferedImage big = SPRITES[team][frame];
            for (int pass = 0; pass < 3; pass++) big = scale2x(big);   // 8x, with the stair-steps on diagonals rounded off
            BufferedImage half = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = half.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(big, 0, 0, w * 2, h * 2, null);
            g.dispose();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(half, 0, 0, w, h, null);
            g.dispose();
            return out;
        });
    }

    /** Dark edges, elliptical so it fits any window shape. */
    static BufferedImage vignette(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.translate(w / 2.0, h / 2.0);
        g.scale(1, (double) h / w);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(0, 0), w * 0.66f, new float[]{0f, 0.55f, 1f},
            new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(2, 0, 12, 200)}));
        g.fill(new Rectangle2D.Double(-w / 2.0, -w / 2.0, w, w));
        g.dispose();
        return im;
    }

    /** Carved stone tower block with a lit capstone, brick courses and a cast shadow. Drawn exactly on the collision rectangle. */
    static void wall(Graphics2D g, double[] w) {
        int x = (int) w[0], y = (int) w[1], ww = (int) w[2], h = (int) w[3];
        g.setColor(new Color(0, 0, 0, 60)); // soft shadow thrown to the lower right
        g.fill(new RoundRectangle2D.Double(x + 4, y + 8, ww + 6, h + 4, 8, 8));
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Double(x + 2, y + 4, ww + 3, h + 2, 5, 5));

        g.setColor(new Color(24, 20, 38));
        g.fillRect(x, y, ww, h);
        for (int yy = y + 10, row = 0; yy < y + h; yy += 12, row++) { // brick courses under the cap
            for (int xx = x - (row % 2) * 12; xx < x + ww; xx += 24) {
                int bx = Math.max(xx, x), bw = Math.min(xx + 24, x + ww) - bx, bh = Math.min(12, y + h - yy);
                if (bw <= 1) continue;
                int v = ((xx * 7 + yy * 13) & 15) - 8;
                g.setColor(new Color(Math.max(0, 92 + v), Math.max(0, 84 + v), Math.max(0, 120 + v)));
                g.fillRect(bx + 1, yy + 1, bw - 2, bh - 2);
                g.setColor(new Color(255, 255, 255, 22));
                g.fillRect(bx + 1, yy + 1, bw - 2, 1);
                g.setColor(new Color(0, 0, 0, 55));
                g.fillRect(bx + 1, yy + bh - 2, bw - 2, 1);
            }
        }
        g.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 30), x + ww, y, new Color(0, 0, 10, 70))); // light from the left
        g.fillRect(x, y + 10, ww, h - 10);
        g.setPaint(null);

        g.setColor(new Color(150, 142, 190)); // capstone: the lit top face
        g.fillRect(x, y, ww, 10);
        g.setColor(new Color(205, 198, 240));
        g.fillRect(x, y, ww, 2);
        g.setColor(new Color(70, 64, 104)); // crenellation notches
        if (ww >= h) for (int xx = x + 4; xx + 8 <= x + ww - 2; xx += 16) g.fillRect(xx, y + 3, 8, 5);
        else g.fillRect(x + ww / 2 - 4, y + 3, 8, 5);
        g.setColor(new Color(0, 0, 0, 60));
        g.fillRect(x, y + 10, ww, 2);
        g.setColor(new Color(100, 160, 255, 70)); // cold rim light on the left edge
        g.fillRect(x, y, 1, h);
        g.setColor(new Color(10, 8, 18));
        g.drawRect(x, y, ww, h);
    }

    static void pedestal(Graphics2D g, double x, double y, int team) {
        g.setColor(new Color(34, 30, 46));
        g.fill(new Ellipse2D.Double(x - 18, y + 8, 36, 15));
        g.setColor(new Color(70, 64, 98));
        g.fill(new Ellipse2D.Double(x - 14, y + 8, 28, 10));
        g.setColor(FX[team]);
        g.draw(new Ellipse2D.Double(x - 18, y + 8, 36, 15));
    }

    /** Waving banner on a pole; (x, y) is the middle of the pole. Drawn 1.25x so it reads next to the larger fighters. */
    static void flag(Graphics2D g, double x, double y, int team, double t) {
        AffineTransform keep = g.getTransform();
        g.translate(x, y);
        g.scale(1.25, 1.25);
        g.setColor(new Color(190, 185, 200));
        g.fill(new Rectangle2D.Double(-1, -16, 2, 30));
        g.setColor(new Color(240, 235, 250));
        g.fill(new Ellipse2D.Double(-2.5, -19, 5, 5)); // finial
        int n = 7;
        int[] xs = new int[2 * n], ys = new int[2 * n];
        for (int i = 0; i < n; i++) {
            double off = Math.sin(t * 6 - i * 0.9) * (0.5 + i * 0.5);
            xs[i] = (int) (1 + i * 3);
            ys[i] = (int) (-16 + off + i * 0.4);
            xs[2 * n - 1 - i] = xs[i];
            ys[2 * n - 1 - i] = (int) (-16 + off + 12 - i * 0.9);
        }
        g.setColor(PAL[team][9]);
        g.fillPolygon(xs, ys, 2 * n);
        g.setColor(PAL[team][7]);
        g.fillPolygon(new int[]{xs[0], xs[2], xs[2], xs[0]}, new int[]{ys[0], ys[2], ys[2 * n - 3], ys[2 * n - 1]}, 4);
        g.setColor(PAL[team][0]);
        g.drawPolygon(xs, ys, 2 * n);
        g.setColor(PAL[team][16]);
        g.fillRect(xs[3], ys[3] + 3, 3, 3);
        g.setTransform(keep);
    }

    /** A flickering flame with a stone bowl; (x, y) is the base of the fire. s scales it. */
    static void torchFlame(Graphics2D g, double x, double y, double t, double s, boolean fx) {
        double f = (Math.sin(t * 13 + x) + Math.sin(t * 7.3 + y)) / 2;
        if (fx) glow(g, new Color(255, 140, 40), x, y - 12 * s, (int) (64 * s), (float) (0.34 + 0.1 * f));
        g.setColor(new Color(36, 30, 44));
        g.fill(new RoundRectangle2D.Double(x - 6 * s, y - 1 * s, 12 * s, 6 * s, 4, 4));
        g.setColor(new Color(96, 84, 126));
        g.fill(new Rectangle2D.Double(x - 6 * s, y - 1 * s, 12 * s, 1.5 * s));
        Color[] c = {new Color(255, 84, 20, 235), new Color(255, 160, 50), new Color(255, 236, 160)};
        for (int k = 0; k < 3; k++) {
            double h = (24 - k * 7 + f * 3) * s, w = (7 - k * 2) * s, sway = Math.sin(t * 9 + k * 1.7 + x) * 1.8 * s;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(x - w, y);
            p.curveTo(x - w, y - h * 0.45, x + sway - w * 0.3, y - h * 0.7, x + sway, y - h);
            p.curveTo(x + sway + w * 0.3, y - h * 0.7, x + w, y - h * 0.45, x + w, y);
            p.closePath();
            g.setColor(c[k]);
            g.fill(p);
        }
    }

    /** The blaster, drawn along +x from the wielder's centre; the muzzle ends about 31 units out. kick pulls it back. */
    static void gun(Graphics2D g, int team, double kick, boolean flash) {
        g.setColor(new Color(30, 26, 40));
        g.fill(new RoundRectangle2D.Double(7 + kick, 2, 6, 8, 2, 2));                     // grip
        g.setColor(new Color(64, 68, 92));
        g.fill(new RoundRectangle2D.Double(4 + kick, -4, 19, 8, 3, 3));                    // body
        g.setColor(new Color(150, 156, 184));
        g.fill(new Rectangle2D.Double(6 + kick, -4, 15, 1.5));                             // top highlight
        g.setColor(new Color(44, 46, 62));
        g.fill(new Rectangle2D.Double(21 + kick, -2.5, 9, 5));                             // barrel
        g.setColor(FX[team]);
        for (int i = 0; i < 3; i++) g.fill(new Rectangle2D.Double(9 + i * 4 + kick, -5, 2, 10)); // energy coils
        g.fill(new Rectangle2D.Double(28 + kick, -3.5, 3, 7));                             // emitter
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(29 + kick, -1, 1.5, 2));
        if (flash) { // star-shaped muzzle flash
            Path2D.Double p = new Path2D.Double();
            double cx = 36, r1 = 12, r2 = 3.5;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4, ra = i % 2 == 0 ? r1 : r2;
                if (i == 0) p.moveTo(cx + Math.cos(a) * ra, Math.sin(a) * ra); else p.lineTo(cx + Math.cos(a) * ra, Math.sin(a) * ra);
            }
            p.closePath();
            g.setColor(FX[team]);
            g.fill(p);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(cx - 4, -4, 8, 8));
        }
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

    private static final Map<Integer, Font> TITLES = new HashMap<>();
    /** Display face for scores and announcements. */
    static Font title(int size) { return TITLES.computeIfAbsent(size, s -> new Font(Font.SERIF, Font.BOLD, s)); }

    /** Rectangle with all four corners cut off. */
    static Path2D.Double chamfer(double x, double y, double w, double h, double c) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x + c, y); p.lineTo(x + w - c, y); p.lineTo(x + w, y + c); p.lineTo(x + w, y + h - c);
        p.lineTo(x + w - c, y + h); p.lineTo(x + c, y + h); p.lineTo(x, y + h - c); p.lineTo(x, y + c);
        p.closePath();
        return p;
    }

    /** HUD frame: cut corners, accent border, soft outer glow and a slow light sweep along the top edge. */
    static void plate(Graphics2D g, double x, double y, double w, double h, double cut, Color accent, double time, boolean fx) {
        Path2D.Double p = chamfer(x, y, w, h, cut);
        if (fx) {
            g.setStroke(new BasicStroke(6f));
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 34));
            g.draw(p);
        }
        g.setPaint(new GradientPaint(0, (float) y, new Color(16, 13, 38, 224), 0, (float) (y + h), new Color(5, 5, 16, 224)));
        g.fill(p);
        g.setStroke(new BasicStroke(2f));
        g.setColor(accent);
        g.draw(p);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 64));
        g.draw(chamfer(x + 3.5, y + 3.5, w - 7, h - 7, Math.max(1, cut - 2)));
        Shape old = g.getClip();
        g.clip(p);
        double sx = x + ((time * 0.3 + x * 0.01) % 1.5 - 0.25) * w;
        g.setPaint(new GradientPaint((float) sx, 0, new Color(255, 255, 255, 0), (float) (sx + 30), 0,
            new Color(Math.min(255, accent.getRed() + 60), Math.min(255, accent.getGreen() + 60), Math.min(255, accent.getBlue() + 60), 170)));
        g.fill(new Rectangle2D.Double(sx, y, 30, 2.5));
        g.setClip(old);
    }

    /** Small pennant icon for flag status. */
    static void flagIcon(Graphics2D g, double x, double y, double size, Color c, double t) {
        double k = size / 20.0;
        g.setColor(new Color(215, 210, 228));
        g.fill(new Rectangle2D.Double(x - 9 * k, y - 10 * k, 2 * k, 20 * k));
        Path2D.Double f = new Path2D.Double();
        double w1 = Math.sin(t * 6) * 1.2 * k, w2 = Math.sin(t * 6 - 1.3) * 1.2 * k;
        f.moveTo(x - 7 * k, y - 10 * k);
        f.curveTo(x - 2 * k, y - 12 * k + w1, x + 3 * k, y - 8 * k + w2, x + 9 * k, y - 10 * k + w1);
        f.lineTo(x + 5 * k, y - 4 * k + w2);
        f.lineTo(x + 9 * k, y + 1 * k + w1);
        f.curveTo(x + 3 * k, y + 3 * k + w2, x - 2 * k, y - 1 * k + w1, x - 7 * k, y + 1 * k);
        f.closePath();
        g.setColor(c);
        g.fill(f);
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke(1f));
        g.draw(f);
    }

    /** Name tag on a dark pill, centred on cx with its baseline at y. */
    static void namePlate(Graphics2D g, String s, double cx, double y, Color c) {
        FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(s) + 12, h = fm.getAscent() + 5;
        g.setColor(new Color(4, 4, 14, 150));
        g.fill(new RoundRectangle2D.Double(cx - w / 2, y - fm.getAscent() - 1, w, h, h, h));
        text(g, s, (int) (cx - w / 2 + 6), (int) y, c);
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

        int r = 28;
        glow(g, new Color(160, 50, 255), px, py, 44, 0.45f * alpha);
        glow(g, new Color(70, 230, 255), px, py, 30, 0.35f * alpha);

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

    /** Glassmorphic frosted transparent window with bevel highlights, chromatic sheen, and gemstone corners. */
    static void glassCard(Graphics2D g, int x, int y, int w, int h, Color accent, double time, boolean fx, boolean hovered) {
        // 1. Soft atmospheric colored back-glow around and behind the glass
        if (fx) {
            glow(g, accent, x + w / 2.0, y + h / 2.0, Math.max(w, h) / 2 + 40, hovered ? 0.45f : 0.30f);
            glow(g, new Color(155, 55, 255), x + w / 2.0, y + h / 2.0, Math.max(w, h) / 2 + 65, 0.22f);
        }

        Composite oldComp = g.getComposite();
        Paint oldPaint = g.getPaint();
        Stroke oldSt = g.getStroke();

        // 2. Deep frosted drop shadow underneath the glass window
        g.setColor(new Color(2, 1, 8, 160));
        g.fillRoundRect(x + 4, y + 8, w, h, 24, 24);

        // 3. Translucent frosted glass body (crystalline dark-violet tint allowing fireworks & arena through)
        GradientPaint glassTint = new GradientPaint(
            x, y, new Color(24, 14, 44, hovered ? 135 : 115),
            x + w, y + h, new Color(10, 6, 22, hovered ? 165 : 145)
        );
        g.setPaint(glassTint);
        g.fillRoundRect(x, y, w, h, 20, 20);

        // 4. Subtle inner stardust motes drifting inside the glass
        if (fx) {
            for (int i = 0; i < 7; i++) {
                double mx = x + 24 + ((i * 73 + (int)(time * 15)) % (w - 48));
                double my = y + h - 16 - ((i * 47 + (int)(time * 28)) % (h - 32));
                double mp = 0.5 + 0.5 * Math.sin(time * 3.0 + i * 1.7);
                g.setColor(new Color(255, 255, 255, (int)(140 * mp)));
                g.fillRect((int) mx, (int) my, 2, 2);
            }
        }

        // 5. Specular diagonal glass sheen across the upper section
        Path2D.Double sheen = new Path2D.Double();
        sheen.moveTo(x + 3, y + 3);
        sheen.lineTo(x + w - 3, y + 3);
        sheen.lineTo(x + w - 3, y + (int)(h * 0.46));
        sheen.lineTo(x + 3, y + (int)(h * 0.18));
        sheen.closePath();

        GradientPaint sheenPaint = new GradientPaint(
            x, y, new Color(255, 255, 255, hovered ? 52 : 40),
            x, y + (int)(h * 0.46), new Color(255, 255, 255, 0)
        );
        g.setPaint(sheenPaint);
        g.fill(sheen);

        // Razor-sharp curved top bevel highlight hairline
        g.setColor(new Color(255, 255, 255, hovered ? 180 : 130));
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(x + 18, y + 2, x + w - 18, y + 2);

        // 6. Frosted glass prismatic bevel rim (shimmering dynamic gradient)
        g.setStroke(new BasicStroke(hovered ? 2.4f : 1.8f));
        double shift = Math.sin(time * 2.8);
        Color topRim = new Color(255, 255, 255, (int)(160 + shift * 35));
        Color botRim = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int)(130 - shift * 35));
        GradientPaint rimPaint = new GradientPaint(x, y, topRim, x + w, y + h, botRim);
        g.setPaint(rimPaint);
        g.drawRoundRect(x, y, w, h, 20, 20);

        // Inner glowing accent line
        g.setStroke(new BasicStroke(1.0f));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), hovered ? 110 : 65));
        g.drawRoundRect(x + 3, y + 3, w - 6, h - 6, 16, 16);

        // 7. Ornate Celestial Gemstone Corner Brackets
        Color gemRim = hovered ? Color.WHITE : new Color(220, 225, 255);
        g.setColor(gemRim);
        g.setStroke(new BasicStroke(1.5f));
        // Top-left
        g.drawLine(x + 4, y + 14, x + 4, y + 4); g.drawLine(x + 4, y + 4, x + 14, y + 4);
        // Top-right
        g.drawLine(x + w - 14, y + 4, x + w - 4, y + 4); g.drawLine(x + w - 4, y + 4, x + w - 4, y + 14);
        // Bottom-left
        g.drawLine(x + 4, y + h - 14, x + 4, y + h - 4); g.drawLine(x + 4, y + h - 4, x + 14, y + h - 4);
        // Bottom-right
        g.drawLine(x + w - 14, y + h - 4, x + w - 4, y + h - 4); g.drawLine(x + w - 4, y + h - 4, x + w - 4, y + h - 14);

        // Glowing corner jewel facets
        g.setColor(accent);
        g.fillRect(x + 7, y + 7, 3, 3);
        g.fillRect(x + w - 10, y + 7, 3, 3);
        g.fillRect(x + 7, y + h - 10, 3, 3);
        g.fillRect(x + w - 10, y + h - 10, 3, 3);

        // 8. Sparkling diamond star glints around the frame
        starGlint(g, x + 8, y + 8, time, 7);
        starGlint(g, x + w - 8, y + 8, time + 1.1, 7);
        starGlint(g, x + 8, y + h - 8, time + 2.2, 6);
        starGlint(g, x + w - 8, y + h - 8, time + 3.3, 6);
        starGlint(g, x + w / 2.0, y + 1, time * 1.5 + 0.5, 5);

        g.setStroke(oldSt);
        g.setPaint(oldPaint);
        g.setComposite(oldComp);
    }

    /** Sparkling 8-point diamond celestial light glint with cross flares. */
    static void starGlint(Graphics2D g, double x, double y, double t, int size) {
        double pulse = Math.max(0, Math.sin(t * 3.5));
        if (pulse < 0.15) return;
        int s = (int) Math.round(size * pulse);
        int alpha = (int)(255 * pulse);
        // Primary cross rays
        g.setColor(new Color(255, 255, 255, alpha));
        g.fillRect((int) x - s, (int) y - 1, s * 2 + 1, 2);
        g.fillRect((int) x - 1, (int) y - s, 2, s * 2 + 1);
        // Diagonal diamond rays
        int ds = Math.max(1, s / 2);
        for (int d = -ds; d <= ds; d++) {
            g.fillRect((int) x + d, (int) y + d, 1, 1);
            g.fillRect((int) x + d, (int) y - d, 1, 1);
        }
        // Blinding white core
        g.setColor(Color.WHITE);
        g.fillRect((int) x - 1, (int) y - 1, 2, 2);
    }

    /** Pixel-art regal golden victory crown with gemstone insets and specular shine. */
    static void crown(Graphics2D g, double x, double y, Color gold, double size, double time) {
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        double s = size / 24.0;
        g.scale(s, s);

        // Crown silhouette / base
        Path2D.Double p = new Path2D.Double();
        p.moveTo(-12, 6);
        p.lineTo(-14, -6);
        p.lineTo(-6, -1);
        p.lineTo(0, -9);
        p.lineTo(6, -1);
        p.lineTo(14, -6);
        p.lineTo(12, 6);
        p.closePath();

        // Shadow
        g.setColor(new Color(20, 10, 30, 200));
        g.translate(1, 2);
        g.fill(p);
        g.translate(-1, -2);

        // Gold gradient body
        GradientPaint gp = new GradientPaint(0, -9, new Color(255, 235, 120), 0, 6, new Color(210, 140, 20));
        g.setPaint(gp);
        g.fill(p);

        // Bevel highlight edge
        g.setColor(new Color(255, 255, 220, 220));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(p);

        // Gemstone insets
        g.setColor(new Color(255, 40, 90)); // Center Ruby
        g.fillRect(-2, 0, 4, 4);
        g.setColor(new Color(50, 220, 255)); // Side Sapphires
        g.fillRect(-9, 1, 3, 3);
        g.fillRect(6, 1, 3, 3);

        // Top pearl points
        g.setColor(Color.WHITE);
        g.fillRect(-15, -8, 3, 3);
        g.fillRect(-1, -11, 3, 3);
        g.fillRect(13, -8, 3, 3);

        starGlint(g, 0, -9, time * 2.0, 5);
        g.setTransform(old);
    }

    /** Gold, Silver, Bronze rank medals for top performers. */
    static void medalBadge(Graphics2D g, int x, int y, int rank) {
        Color main, border, glowC;
        String text = String.valueOf(rank);
        if (rank == 1) {
            main = new Color(245, 195, 35);
            border = new Color(255, 240, 150);
            glowC = new Color(255, 215, 0, 120);
        } else if (rank == 2) {
            main = new Color(185, 195, 210);
            border = new Color(240, 245, 255);
            glowC = new Color(200, 215, 235, 120);
        } else if (rank == 3) {
            main = new Color(200, 125, 60);
            border = new Color(245, 185, 130);
            glowC = new Color(205, 127, 50, 120);
        } else {
            main = new Color(60, 50, 80);
            border = new Color(110, 100, 135);
            glowC = new Color(0, 0, 0, 0);
        }

        // Circular medal disc
        int d = 22;
        g.setColor(glowC);
        g.fillOval(x - 2, y - 2, d + 4, d + 4);
        g.setColor(main);
        g.fillOval(x, y, d, d);
        g.setColor(border);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(x, y, d, d);

        // Text inside medal
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(rank <= 3 ? new Color(20, 10, 30) : Color.WHITE);
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (d - fm.stringWidth(text)) / 2;
        int ty = y + (d + fm.getAscent() - 4) / 2;
        g.drawString(text, tx, ty);
    }

    private Art() {}
}

package ctf.client;

import ctf.common.MapData;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Random;

/**
 * Grenade art, drawn as smooth vector shapes so it stays sharp at any size: the grenade in flight, the blast-radius
 * warning, the explosion (flash, fireball, flame tongues, shockwave, light), the scorch mark it leaves, the
 * emoji-style bomb icon for the HUD, and the armory crate. Smoke, sparks and debris are GamePanel's particles.
 */
final class Blast {
    static final double BOOM_LIFE = 1.0, SCORCH_LIFE = 8.0;
    private static final Color[] FIRE = {new Color(255, 255, 240), new Color(255, 236, 140), new Color(255, 160, 40),
        new Color(230, 70, 20), new Color(120, 20, 10)};

    private Blast() {}

    private static float clamp01(double v) { return (float) Math.max(0, Math.min(1, v)); }

    private static Color alpha(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * clamp01(a)));
    }

    private static void fade(Graphics2D g, double a) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(a)));
    }

    // ------------------------------------------------------------------ the grenade

    /**
     * A grenade at (x, y) on the ground, raised by z while it flies (with its shadow left on the ground). fuseLeft drives
     * the blinking light: slow at first, frantic just before it bursts. The team band says whose it is.
     */
    static void grenade(Graphics2D g, double x, double y, double z, int team, double fuseLeft, double spin, double time, boolean fx) {
        Composite keepC = g.getComposite();
        g.setColor(new Color(0, 0, 0, 70));   // the shadow shrinks as it rises
        double sw = 16 - z * 0.15;
        g.fill(new Ellipse2D.Double(x - sw / 2, y + 7 - sw * 0.2, sw, sw * 0.42));

        AffineTransform keep = g.getTransform();
        g.translate(x, y - z);
        g.rotate(spin);
        Color team1 = Art.FX[team];
        if (fx) Art.glow(g, team1, 0, 0, 22, 0.35f);

        Ellipse2D body = new Ellipse2D.Double(-8, -7, 16, 17);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-3, -3), 13, new float[]{0f, 0.55f, 1f},
            new Color[]{new Color(140, 165, 80), new Color(78, 98, 38), new Color(30, 40, 14)}));
        g.fill(body);
        g.setStroke(new BasicStroke(1.1f));   // the pineapple grooves
        g.setColor(new Color(20, 28, 8, 170));
        for (double gx = -4; gx <= 4; gx += 4) g.draw(new Arc2D.Double(gx - 2.5, -7, 5, 17, 90, 180, Arc2D.OPEN));
        for (double gy = -2; gy <= 6; gy += 4) g.draw(new Arc2D.Double(-8, gy - 1.5, 16, 3, 180, 180, Arc2D.OPEN));
        g.setColor(alpha(team1, 0.9));   // the team band
        g.fill(new Rectangle2D.Double(-8, 1, 16, 2.6));
        g.setColor(new Color(255, 255, 255, 120));   // a glint
        g.fill(new Ellipse2D.Double(-5, -5, 4, 3));

        g.setPaint(new GradientPaint(0, -11, new Color(225, 228, 236), 0, -6, new Color(110, 114, 128)));   // the steel cap
        g.fill(new RoundRectangle2D.Double(-3.5, -11, 7, 5, 2, 2));
        Path2D lever = new Path2D.Double();   // the spoon along the side
        lever.moveTo(2.5, -10); lever.quadTo(8, -9, 8.5, -2); lever.lineTo(7, -2); lever.quadTo(6.5, -7.5, 2.5, -8);
        g.fill(lever);
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(255, 205, 90));   // the pin ring
        g.draw(new Ellipse2D.Double(-8, -14, 5, 5));

        double rate = 0.07 + 0.33 * Math.max(0, Math.min(1, fuseLeft / MapData.GRENADE_FUSE));   // blinks faster near the end
        boolean on = ((int) (time / rate) & 1) == 0;
        if (on) {
            Color light = fuseLeft < 0.6 ? new Color(255, 60, 40) : new Color(255, 200, 80);
            if (fx) Art.glow(g, light, 0, -12, 10, 0.9f);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(-1.5, -13.5, 3, 3));
        }
        g.setTransform(keep);
        g.setComposite(keepC);
    }

    /** The true blast radius on the ground during the last second: a pulsing red ring that says "run". */
    static void warning(Graphics2D g, double x, double y, double fuseLeft, double time) {
        if (fuseLeft > 1.0) return;
        double k = 1 - fuseLeft;   // 0 -> 1 as it gets closer
        double pulse = 0.5 + 0.5 * Math.sin(time * (10 + 18 * k));
        double r = MapData.BLAST_R;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) r, new float[]{0f, 0.75f, 1f},
            new Color[]{new Color(255, 60, 30, 0), new Color(255, 60, 30, (int) (25 + 35 * k)), new Color(255, 60, 30, (int) (70 + 60 * k * pulse))}));
        g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        g.setStroke(new BasicStroke(2f + (float) k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{10, 8}, (float) (time * 40)));
        g.setColor(new Color(255, 90, 60, (int) (120 + 120 * pulse * k)));
        g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        g.setStroke(new BasicStroke(1));
    }

    // ------------------------------------------------------------------ the explosion

    /** The burst itself, age 0..BOOM_LIFE seconds. seed varies the flame tongues so no two blasts look the same. */
    static void fireball(Graphics2D g, double x, double y, double age, long seed, boolean fx) {
        if (age < 0 || age > BOOM_LIFE) return;
        Composite keep = g.getComposite();
        double R = MapData.BLAST_R;

        if (fx) Art.glow(g, new Color(255, 150, 60), x, y, 260, (float) (0.9 * (1 - age / BOOM_LIFE)));   // it lights up the ground

        double grow = 1 - Math.pow(1 - Math.min(1, age / 0.22), 3);   // fast at first, then settles
        double r = R * (0.3 + 0.6 * grow) * (age > 0.45 ? 1 + (age - 0.45) * 0.5 : 1);
        double heat = 1 - clamp01((age - 0.15) / 0.7);   // white-hot to dark red
        fade(g, 1 - clamp01((age - 0.35) / 0.6));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y - r * 0.08), (float) r, new float[]{0f, 0.25f, 0.55f, 0.8f, 1f},
            new Color[]{mix(FIRE[0], FIRE[2], 1 - heat), mix(FIRE[1], FIRE[3], 1 - heat), mix(FIRE[2], FIRE[4], 1 - heat),
                alpha(FIRE[3], 0.85), alpha(FIRE[4], 0)}));
        g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));

        Random rnd = new Random(seed);   // flame tongues licking outward
        int n = 13;
        Path2D tongues = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double a0 = (i + rnd.nextDouble() * 0.3) / n * Math.PI * 2, half = Math.PI / n * (0.55 + rnd.nextDouble() * 0.3);
            double len = r * (1.15 + rnd.nextDouble() * 0.45) * (1 - clamp01((age - 0.2) / 0.5) * 0.6);
            tongues.moveTo(x + Math.cos(a0 - half) * r * 0.55, y + Math.sin(a0 - half) * r * 0.55);
            tongues.quadTo(x + Math.cos(a0) * len * 0.8, y + Math.sin(a0) * len * 0.8 - 4, x + Math.cos(a0) * len, y + Math.sin(a0) * len);
            tongues.quadTo(x + Math.cos(a0) * len * 0.8, y + Math.sin(a0) * len * 0.8 + 4, x + Math.cos(a0 + half) * r * 0.55, y + Math.sin(a0 + half) * r * 0.55);
            tongues.closePath();
        }
        fade(g, 0.85 * (1 - clamp01((age - 0.1) / 0.45)));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r * 1.6), new float[]{0f, 0.5f, 1f},
            new Color[]{FIRE[1], FIRE[2], alpha(FIRE[3], 0)}));
        g.fill(tongues);

        if (age < 0.12) {   // the first instant: a white-hot flash
            fade(g, 1 - age / 0.12);
            double fr = 30 + age * 500;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) fr, new float[]{0f, 1f},
                new Color[]{Color.WHITE, new Color(255, 255, 220, 0)}));
            g.fill(new Ellipse2D.Double(x - fr, y - fr, fr * 2, fr * 2));
        }

        if (age < 0.45) {   // the shockwave ring racing out past the blast radius
            double k = age / 0.45, sr = 16 + R * 1.45 * (1 - Math.pow(1 - k, 2.4));
            g.setComposite(keep);
            g.setStroke(new BasicStroke((float) (11 * (1 - k) + 1)));
            g.setColor(new Color(255, 245, 220, (int) (210 * (1 - k))));
            g.draw(new Ellipse2D.Double(x - sr, y - sr, sr * 2, sr * 2));
            g.setStroke(new BasicStroke((float) (3 * (1 - k) + 0.5)));
            g.setColor(new Color(255, 180, 90, (int) (160 * (1 - k))));
            g.draw(new Ellipse2D.Double(x - sr * 0.82, y - sr * 0.82, sr * 1.64, sr * 1.64));
            g.setStroke(new BasicStroke(1));
        }
        g.setComposite(keep);
    }

    /** The burnt patch left on the ground; embers glow in it for a moment, then it fades away. */
    static void scorch(Graphics2D g, double x, double y, double age, long seed) {
        if (age < 0 || age > SCORCH_LIFE) return;
        Composite keep = g.getComposite();
        fade(g, 1 - clamp01((age - SCORCH_LIFE + 3) / 3));
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        Path2D blot = new Path2D.Double();
        int n = 16;
        for (int i = 0; i <= n; i++) {
            double a = (double) i / n * Math.PI * 2, rr = 34 + rnd.nextDouble() * 18;
            if (i == 0) blot.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr * 0.8);
            else blot.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr * 0.8);
        }
        blot.closePath();
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), 54, new float[]{0f, 0.6f, 1f},
            new Color[]{new Color(10, 6, 4, 190), new Color(25, 14, 8, 120), new Color(30, 18, 10, 0)}));
        g.fill(blot);
        if (age < 2.5) {   // glowing embers
            for (int i = 0; i < 9; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * 32;
                double ex = x + Math.cos(a) * d, ey = y + Math.sin(a) * d * 0.8;
                float glow = (float) ((1 - age / 2.5) * (0.6 + 0.4 * Math.sin(age * 9 + i)));
                g.setColor(new Color(255, 140 + rnd.nextInt(80), 40, (int) (220 * clamp01(glow))));
                g.fill(new Ellipse2D.Double(ex - 1.6, ey - 1.6, 3.2, 3.2));
            }
        }
        g.setComposite(keep);
    }

    private static Color mix(Color a, Color b, double t) {
        double k = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * k), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * k),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * k), (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * k));
    }

    // ------------------------------------------------------------------ emoji-style bomb icon and the armory crate

    /**
     * The bomb emoji, drawn in vector so it is sharp at any size: a glossy black ball, a steel cap, a curling fuse and a
     * sparking flame. used = false draws it greyed out (an empty slot). centre (cx, cy), about `size` tall.
     */
    static void icon(Graphics2D g, double cx, double cy, double size, boolean full, double time) {
        AffineTransform keep = g.getTransform();
        Composite keepC = g.getComposite();
        g.translate(cx, cy);
        g.scale(size / 40.0, size / 40.0);   // drawn in a 40 x 40 box
        if (!full) fade(g, 0.28);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-6, -2), 20, new float[]{0f, 0.5f, 1f},
            new Color[]{new Color(95, 100, 120), new Color(38, 40, 52), new Color(8, 8, 12)}));
        g.fill(new Ellipse2D.Double(-15, -9, 30, 30));
        g.setColor(new Color(255, 255, 255, 170));   // the glossy highlight
        g.fill(new Ellipse2D.Double(-9, -4, 8, 6));
        g.setColor(new Color(255, 255, 255, 80));
        g.fill(new Ellipse2D.Double(-11, 3, 3, 3));
        g.setPaint(new GradientPaint(0, -14, new Color(200, 204, 216), 0, -8, new Color(90, 94, 110)));   // the cap
        g.fill(new RoundRectangle2D.Double(-5, -14, 10, 7, 3, 3));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));   // the fuse
        g.setColor(new Color(190, 150, 90));
        Path2D fuse = new Path2D.Double();
        fuse.moveTo(1, -14); fuse.curveTo(3, -20, 9, -18, 10, -22);
        g.draw(fuse);
        if (full) {   // the sparking flame: a little star that flickers
            double fl = 0.8 + 0.2 * Math.sin(time * 23) + 0.15 * Math.sin(time * 37);
            g.translate(11, -23);
            g.scale(fl, fl);
            Art.glow(g, new Color(255, 170, 40), 0, 0, 9, 0.9f);
            Path2D star = new Path2D.Double();
            for (int i = 0; i < 10; i++) {
                double a = i * Math.PI / 5 - Math.PI / 2, rr = i % 2 == 0 ? 6 : 2.4;
                if (i == 0) star.moveTo(Math.cos(a) * rr, Math.sin(a) * rr); else star.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
            }
            star.closePath();
            g.setColor(new Color(255, 210, 60));
            g.fill(star);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(-1.6, -1.6, 3.2, 3.2));
        }
        g.setTransform(keep);
        g.setComposite(keepC);
    }

    /** The armory crate: a wooden ammo box with the bomb emblem. ready 0..1 fills its recharge ring; 1 = take a grenade. */
    static void crate(Graphics2D g, double x, double y, double ready, double time, boolean fx) {
        boolean on = ready >= 1;
        if (on && fx) Art.glow(g, new Color(255, 190, 80), x, y, 56, (float) (0.45 + 0.15 * Math.sin(time * 3)));
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(new Ellipse2D.Double(x - 26, y + 12, 52, 14));
        RoundRectangle2D box = new RoundRectangle2D.Double(x - 22, y - 16, 44, 32, 5, 5);
        g.setPaint(new GradientPaint(0, (float) (y - 16), new Color(170, 118, 62), 0, (float) (y + 16), new Color(98, 62, 28)));
        g.fill(box);
        g.setColor(new Color(60, 36, 14));   // planks
        g.setStroke(new BasicStroke(1.2f));
        for (double py = y - 6; py < y + 16; py += 10) g.draw(new java.awt.geom.Line2D.Double(x - 22, py, x + 22, py));
        g.setColor(new Color(150, 152, 165));   // steel corners
        for (double[] c : new double[][]{{x - 22, y - 16}, {x + 16, y - 16}, {x - 22, y + 10}, {x + 16, y + 10}})
            g.fill(new RoundRectangle2D.Double(c[0], c[1], 6, 6, 2, 2));
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(40, 24, 10));
        g.draw(box);
        icon(g, x, y + 1, 22, on, time);
        double rr = 30;   // the recharge ring
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(40, 30, 20, 140));
        g.draw(new Ellipse2D.Double(x - rr, y - rr, rr * 2, rr * 2));
        g.setColor(on ? new Color(255, 205, 90) : new Color(255, 205, 90, 170));
        g.draw(new Arc2D.Double(x - rr, y - rr, rr * 2, rr * 2, 90, -360 * Math.max(0, Math.min(1, ready)), Arc2D.OPEN));
        g.setStroke(new BasicStroke(1));
    }
}

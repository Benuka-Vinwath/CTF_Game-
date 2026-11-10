package ctf.client;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The game's typography: scalable TrueType faces (installed with Windows, so nothing to bundle) with kerning
 * and a logical-font fallback, cached per size. Sizes are in world units; the Graphics2D transform scales the
 * outlines to the real screen resolution, so text is drawn at native pixel size, never enlarged from a bitmap.
 *  display: headings, team names, VICTORY   ui: labels, feed, body text   numbers: scores and the timer
 */
final class Type {
    private static final Set<String> INSTALLED = new HashSet<>(
        java.util.Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    private static final String DISPLAY = pick("Palatino Linotype", "Book Antiqua", "Georgia", Font.SERIF);
    private static final String UI = pick("Segoe UI", "Trebuchet MS", "Tahoma", Font.SANS_SERIF);
    private static final String NUMBERS = pick("Bahnschrift", "Segoe UI", Font.SANS_SERIF);
    private static final Map<String, Font> CACHE = new HashMap<>();

    private static String pick(String... names) {
        for (String n : names) if (INSTALLED.contains(n)) return n;
        return names[names.length - 1]; // the last entry is always a logical font
    }

    static Font display(float size) { return make(DISPLAY, Font.BOLD, size, 0.04f); }

    /** Small labels and body text; bold for status text on busy backgrounds. */
    static Font ui(float size, boolean bold) { return make(UI, bold ? Font.BOLD : Font.PLAIN, size, 0.02f); }

    static Font numbers(float size) { return make(NUMBERS, Font.BOLD, size, 0f); }

    /** Display face with extra letter spacing, for the VICTORY heading. */
    static Font heading(float size) { return make(DISPLAY, Font.BOLD, size, 0.12f); }

    // The ThroneFall brand faces (SIL Open Font License, shipped in assets/fonts). If a file is missing the
    // matching system face above is used, so the game never fails to start over a font.
    private static final Font LOGO = load("CinzelDecorative-Black.ttf");
    private static final Font ROYAL = load("Cinzel-Black.ttf"), ROYAL_BOLD = load("Cinzel-Bold.ttf");
    private static final Font HUD = load("Rajdhani-Bold.ttf"), HUD_SEMI = load("Rajdhani-SemiBold.ttf");

    /** The "ThroneFall" logo. */
    static Font logo(float size) { return derive(LOGO, size, 0.02f, () -> display(size)); }

    /** Menu items, screen titles and buttons; black = heaviest weight. */
    static Font royal(float size, boolean black, float tracking) {
        return derive(black ? ROYAL : ROYAL_BOLD, size, tracking, () -> make(DISPLAY, Font.BOLD, size, tracking));
    }

    /** Hints, fields, addresses and numbers in the menu. */
    static Font hud(float size, boolean bold, float tracking) {
        return derive(bold ? HUD : HUD_SEMI, size, tracking, () -> make(UI, Font.BOLD, size, tracking));
    }

    private static Font load(String file) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new java.io.File("assets/fonts", file));
        } catch (Exception e) {
            return null;   // missing or unreadable: fall back to a system face
        }
    }

    private static synchronized Font derive(Font base, float size, float tracking, java.util.function.Supplier<Font> fallback) {
        if (base == null) return fallback.get();
        return CACHE.computeIfAbsent(base.getFontName() + ":" + size + ":" + tracking, k -> {
            Map<TextAttribute, Object> a = new HashMap<>();
            a.put(TextAttribute.SIZE, size);
            a.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
            a.put(TextAttribute.TRACKING, tracking);
            return base.deriveFont(a);
        });
    }

    private static synchronized Font make(String family, int style, float size, float tracking) {
        return CACHE.computeIfAbsent(family + style + ":" + size + ":" + tracking, k -> {
            Map<TextAttribute, Object> a = new HashMap<>();
            a.put(TextAttribute.FAMILY, family);
            a.put(TextAttribute.WEIGHT, style == Font.BOLD ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
            a.put(TextAttribute.SIZE, size);
            a.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
            a.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
            a.put(TextAttribute.TRACKING, tracking);
            return new Font(a);
        });
    }

    private Type() {}
}

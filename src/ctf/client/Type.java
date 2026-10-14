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

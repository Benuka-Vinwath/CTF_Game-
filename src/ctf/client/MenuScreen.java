package ctf.client;

import ctf.common.LobbyMap;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.prefs.Preferences;

/**
 * The ThroneFall title screen ("Throne room" design, docs/menu-design.html) and its pages: Play, How to play,
 * Settings and Credits. Everything is laid out on a 1600x900 canvas that is scaled to the window; the scene behind
 * it fills the whole window. Still parts are drawn once into images per window size, so a frame only draws what moves.
 * Choices (name, hat, last address, settings) are remembered between runs with java.util.prefs.
 */
final class MenuScreen extends JComponent {
    /** What the menu asks the game window to do. */
    interface Actions {
        void host();
        void join(String address);
        void quit();
        void toggleFullscreen();
        boolean fullscreen();
    }

    static final int VW = 1600, VH = 900;
    private enum Page { MAIN, PLAY, PROFILE, HOWTO, SETTINGS, CREDITS }
    private static final String[] ITEMS = {"PLAY", "PROFILE", "HOW TO PLAY", "SETTINGS", "CREDITS", "QUIT"};
    private static final String[] NUMERALS = {"I", "II", "III", "IV", "V", "VI"};
    private static final String[] COLOR_NAMES = {"GOLD", "SILVER", "ROSE", "JADE", "VIOLET", "CYAN"};

    static final Color GOLD = new Color(255, 205, 90), GOLD_HI = new Color(255, 241, 196), GOLD_LO = new Color(185, 130, 42),
        GOLD_DK = new Color(94, 61, 12), VIOLET = new Color(170, 150, 235), STONE = new Color(110, 95, 160),
        SOFT = new Color(200, 196, 225), MUTED = new Color(143, 138, 168), RED_FX = new Color(255, 90, 50), BLUE_FX = new Color(60, 200, 255);

    private final Actions actions;
    private final Profile profile;        // who you are (name, looks, title, team) and your match history
    private Profile.Stats stats;          // what the Profile page shows; reloaded when the page opens
    private int title, team;
    private double savedAt = -10, resetArmedAt = -10;   // "SAVED" flash; RESET needs a second click within 3 s
    private final Preferences prefs = Preferences.userNodeForPackage(MenuScreen.class);
    private Page page = Page.MAIN;
    private int sel;                      // selected main-menu item
    private int tile;                     // Play page: 0 host, 1 join
    private boolean editName;             // Play page: typing goes into the name instead of the address
    private final StringBuilder name = new StringBuilder(), address = new StringBuilder();
    private int hat, hatColor;
    private boolean fx, shake;
    private float volume;
    private int lagDelay, lagLoss;
    private String status = "";
    private boolean statusError, busy;
    private String lanAddress = "...";
    private final String onlineServer = readOnlineServer(java.nio.file.Path.of("online-server.txt"));
    private int dragSlider = -1;

    private final Timer timer = new Timer(16, e -> repaint());
    private double time;
    private long lastNs = System.nanoTime();
    private final double[][] embers = new double[80][5];   // x, y (0..1), speed, phase, warm?
    private final Random rnd = new Random(7);

    // hit boxes on the 1600x900 canvas, refilled every frame by the drawing code
    private final List<Object[]> hits = new ArrayList<>();   // {Shape, Runnable}
    private final AffineTransform toCanvas = new AffineTransform();
    private Point2D mouse = new Point2D.Double(-1, -1);

    private BufferedImage back, backPage, backPlay, fogImg, logoImg;   // cached still pictures (see ensureCache)
    private double logoX, logoY;
    private double cacheScale;
    private int cacheW, cacheH;
    private Shape logoShape;

    MenuScreen(Actions actions, Profile profile) {
        this.actions = actions;
        this.profile = profile;
        name.append(profile.name());
        address.append(prefs.get("address", "localhost"));
        hat = profile.hat();
        hatColor = profile.hatColor();
        title = profile.title();
        team = profile.favouriteTeam();
        fx = prefs.getBoolean("fx", true);
        shake = prefs.getBoolean("shake", true);
        volume = prefs.getFloat("volume", 0.8f);
        lagDelay = prefs.getInt("lagDelay", 0);
        lagLoss = prefs.getInt("lagLoss", 0);
        Sfx.volume = volume;
        Music.setVolume(volume);
        for (double[] e : embers) spawn(e, true);
        setPreferredSize(new Dimension(1280, 720));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setBackground(Color.BLACK);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { key(e); }
        });
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { mouse = canvas(e); hover(); }
            @Override public void mouseDragged(MouseEvent e) { mouse = canvas(e); drag(); }
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); mouse = canvas(e); click(); }
            @Override public void mouseReleased(MouseEvent e) { dragSlider = -1; save(); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        new Thread(() -> lanAddress = findLanAddress(), "lan-address").start();
    }

    // ------------------------------------------------------------------ what the game window reads

    String playerName() { return ctf.common.Protocol.cleanName(name.toString()); }
    int hat() { return hat; }
    int hatColor() { return hatColor; }
    boolean effects() { return fx; }
    boolean shake() { return shake; }
    int lagDelay() { return lagDelay; }
    int lagLoss() { return lagLoss; }

    void setAddress(String a) { address.setLength(0); address.append(a); }

    void start() {
        fx = prefs.getBoolean("fx", fx);   // the in-game menu may have changed these
        shake = prefs.getBoolean("shake", shake);
        volume = prefs.getFloat("volume", volume);
        timer.start();
        requestFocusInWindow();
    }
    void stop() { timer.stop(); }

    /** Shown under the Play tiles: "Connecting..." while busy, or why it failed in red. */
    void setStatus(String text, boolean error, boolean busy) {
        status = text;
        statusError = error;
        this.busy = busy;
        if (!text.isEmpty()) page = Page.PLAY;   // messages belong under the Play tiles (also after a disconnect)
    }

    // ------------------------------------------------------------------ input

    private Point2D canvas(MouseEvent e) {
        try { return toCanvas.inverseTransform(new Point2D.Double(e.getX(), e.getY()), null); }
        catch (NoninvertibleTransformException ex) { return new Point2D.Double(-1, -1); }
    }

    private void hover() {
        if (page == Page.CREDITS) {
            boolean over = false;
            for (Object[] h : hits) if (((Shape) h[0]).contains(mouse)) over = true;
            setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            return;
        }
        if (page != Page.MAIN && page != Page.PLAY) return;
        for (Object[] h : hits) if (((Shape) h[0]).contains(mouse) && h.length > 2) {
            if (page == Page.MAIN) sel = (Integer) h[2]; else tile = (Integer) h[2];
        }
        boolean over = false;
        for (Object[] h : hits) if (((Shape) h[0]).contains(mouse)) over = true;
        setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** The last thing drawn is on top, so it gets the click: a button inside a card wins over the card. */
    private void click() {
        List<Object[]> snapshot = new ArrayList<>(hits);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Object[] h = snapshot.get(i);
            if (((Shape) h[0]).contains(mouse)) { ((Runnable) h[1]).run(); repaint(); return; }
        }
    }

    private void drag() {
        if (dragSlider < 0) return;
        double k = Math.max(0, Math.min(1, (mouse.getX() - sliderX) / SLIDER_W));
        setSlider(dragSlider, k);
    }

    private void key(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_F11) { actions.toggleFullscreen(); return; }
        if (confirmQuit) {   // the "leave the throne room?" message has the keyboard
            if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_N || k == KeyEvent.VK_BACK_SPACE) confirmQuit = false;
            else if ((k == KeyEvent.VK_ENTER || k == KeyEvent.VK_Y) && time - quitAsked > 0.35) actions.quit();   // a double tap on ENTER cannot quit by accident
            return;
        }
        if (k == KeyEvent.VK_M && page != Page.PLAY && page != Page.PROFILE) { Music.toggle(); return; }   // M is a letter on the pages that type
        boolean typing = page == Page.PLAY || page == Page.PROFILE;   // the Profile page types into the name only
        if (typing && (k == KeyEvent.VK_BACK_SPACE || (e.getKeyChar() >= ' ' && e.getKeyChar() < 127 && !e.isControlDown()))) {
            if (page == Page.PROFILE) editName = true;
            StringBuilder f = editName ? name : address;
            if (k == KeyEvent.VK_BACK_SPACE) { if (f.length() > 0) f.setLength(f.length() - 1); }
            else if (f.length() < (editName ? 12 : 60)) f.append(e.getKeyChar());
            save();
            return;
        }
        switch (page) {
            case MAIN -> {
                if (k == KeyEvent.VK_UP || k == KeyEvent.VK_W) sel = (sel + ITEMS.length - 1) % ITEMS.length;
                if (k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S) sel = (sel + 1) % ITEMS.length;
                if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) open(sel);
                if (k == KeyEvent.VK_ESCAPE) sel = ITEMS.length - 1;
            }
            case PLAY -> {
                if (k == KeyEvent.VK_LEFT) tile = 0;
                if (k == KeyEvent.VK_RIGHT) tile = 1;
                if (k == KeyEvent.VK_TAB) editName = !editName;
                if (k == KeyEvent.VK_ENTER) go(tile);
                if (k == KeyEvent.VK_ESCAPE) back();
            }
            default -> { if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_BACK_SPACE) back(); }
        }
    }

    private void open(int item) {
        switch (item) {
            case 0 -> { page = Page.PLAY; status = ""; statusError = false; }
            case 1 -> { page = Page.PROFILE; stats = profile.stats(); editName = true; }
            case 2 -> page = Page.HOWTO;
            case 3 -> page = Page.SETTINGS;
            case 4 -> { page = Page.CREDITS; creditsStart = time; }
            default -> { confirmQuit = true; quitAsked = time; }   // QUIT asks first
        }
    }

    private void back() { if (!busy) page = Page.MAIN; }

    private void go(int which) {
        if (busy) return;
        if (playerName().isBlank()) { setStatus("Type a name first.", true, false); return; }
        save();
        if (which == 0) actions.host();
        else if (address.toString().isBlank()) setStatus("Type the host's address first.", true, false);
        else {
            prefs.put("lastJoined", address.toString().trim());
            actions.join(address.toString().trim());
        }
    }

    private void save() {
        profile.save(playerName(), hat, hatColor, title, team);   // name and looks live in the profile database
        prefs.put("address", address.toString());
        prefs.putBoolean("fx", fx);
        prefs.putBoolean("shake", shake);
        prefs.putFloat("volume", volume);
        prefs.putInt("lagDelay", lagDelay);
        prefs.putInt("lagLoss", lagLoss);
    }

    // ------------------------------------------------------------------ frame

    @Override protected void paintComponent(Graphics g0) {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNs) / 1e9);
        lastNs = now;
        time += dt;
        for (double[] e : embers) {
            e[1] -= e[2] * dt;
            e[3] += dt * 1.6;
            if (e[1] < -0.03) spawn(e, false);
        }

        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double s = Math.min(getWidth() / (double) VW, getHeight() / (double) VH);
        double ox = (getWidth() - VW * s) / 2, oy = (getHeight() - VH * s) / 2;
        g.translate(ox, oy);
        g.scale(s, s);
        toCanvas.setTransform(AffineTransform.getTranslateInstance(ox, oy));
        toCanvas.scale(s, s);
        hits.clear();

        double vx = -ox / s, vy = -oy / s, vw = getWidth() / s, vh = getHeight() / s;   // the whole window, in canvas units
        ensureCache(g, vx, vy, vw, vh);
        drawScene(g, vx, vy, vw, vh, page);
        switch (page) {
            case MAIN -> drawMain(g);
            case PLAY -> drawPlay(g);
            case PROFILE -> drawProfilePage(g);
            case HOWTO -> drawHowTo(g);
            case SETTINGS -> drawSettings(g);
            case CREDITS -> drawCredits(g);
        }
        if (confirmQuit) drawQuitConfirm(g, vx, vy, vw, vh);
        g.dispose();
    }

    private void spawn(double[] e, boolean anywhere) {
        e[0] = rnd.nextDouble();
        e[1] = anywhere ? rnd.nextDouble() : 1.03;
        e[2] = 0.02 + rnd.nextDouble() * 0.05;
        e[3] = rnd.nextDouble() * 6.28;
        e[4] = rnd.nextDouble() < 0.7 ? 1 : 0;
    }

    // ------------------------------------------------------------------ the throne room scene

    /** Art space: the throne-room drawing uses a 640x540 space placed at the right of the canvas (as in the design). */
    private static final double AX = 544, AY = 36, AS = 1.6;

    private void ensureCache(Graphics2D g, double vx, double vy, double vw, double vh) {
        double sc = g.getTransform().getScaleX();
        int w = (int) Math.ceil(vw * sc), h = (int) Math.ceil(vh * sc);
        if (back != null && sc == cacheScale && w == cacheW && h == cacheH) return;
        cacheScale = sc; cacheW = w; cacheH = h;

        // Everything that does not move is baked into one opaque picture per page type: a plain copy each frame.
        // (Blending full-screen see-through layers every frame cost about 45 ms on a 2560x1600 screen.)
        Font lf = Type.logo(72);
        logoShape = new TextLayout("THRONEFALL", lf, new java.awt.font.FontRenderContext(null, true, true))
            .getOutline(AffineTransform.getTranslateInstance(92, 190));
        back = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D b = layer(back, sc, vx, vy);
        paintRoom(b, vx, vy, vw, vh, true);
        b.dispose();
        backPage = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        b = layer(backPage, sc, vx, vy);
        paintRoom(b, vx, vy, vw, vh, false);
        b.dispose();
        backPlay = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);   // the throne room, dimmed, behind the Play cards
        b = layer(backPlay, sc, vx, vy);
        paintThroneRoom(b, vx, vy, vw, vh, false);
        b.setPaint(new RadialGradientPaint(new Point2D.Double(800, 504), 900, new float[]{0f, .7f, 1f},
            new Color[]{new Color(8, 4, 18, 117), new Color(5, 3, 12, 77), new Color(5, 3, 12, 140)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        b.setPaint(new LinearGradientPaint(0, 0, 0, 900, new float[]{0f, .3f, .8f, 1f},
            new Color[]{new Color(6, 3, 14, 140), new Color(6, 3, 14, 26), new Color(6, 3, 14, 46), new Color(6, 3, 14, 128)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        b.dispose();

        Rectangle2D lb = logoShape.getBounds2D();   // the logo, with its shadow, as a small cached picture
        logoX = lb.getMinX() - 4; logoY = lb.getMinY() - 4;
        logoImg = new BufferedImage((int) Math.ceil((lb.getWidth() + 8) * sc), (int) Math.ceil((lb.getHeight() + 14) * sc), BufferedImage.TYPE_INT_ARGB);
        Graphics2D l = layer(logoImg, sc, logoX, logoY);
        AffineTransform keep = l.getTransform();
        l.translate(0, 4);
        l.setColor(GOLD_DK);
        l.fill(logoShape);
        l.setTransform(keep);
        l.setPaint(new LinearGradientPaint(0, (float) lb.getMinY(), 0, (float) lb.getMaxY(), new float[]{0f, .22f, .48f, .72f, 1f},
            new Color[]{new Color(255, 251, 232), GOLD_HI, GOLD, GOLD_LO, new Color(243, 195, 92)}));
        l.fill(logoShape);
        l.dispose();

        fogImg = new BufferedImage((int) Math.ceil(VW * 2 * sc), (int) Math.ceil(260 * sc), BufferedImage.TYPE_INT_ARGB);
        Graphics2D fo = fogImg.createGraphics();
        fo.scale(sc, sc);
        for (int i = 0; i < 9; i++) {   // soft violet fog banks, tiled twice so the strip can scroll without a seam
            double cx = (i % 4.5) * 360 + 120 + (i >= 5 ? VW : 0), rx = 260 + (i * 37 % 90), ry = 70 + (i * 23 % 30);
            for (int copy = 0; copy < 2; copy++) {
                double x = cx + copy * VW - (i >= 5 ? VW : 0);
                fo.setPaint(new RadialGradientPaint(new Point2D.Double(x, 150), (float) rx, new float[]{0f, 1f},
                    new Color[]{new Color(150, 130, 220, 60), new Color(150, 130, 220, 0)}));
                AffineTransform k2 = fo.getTransform();
                fo.translate(x, 150); fo.scale(1, ry / rx); fo.translate(-x, -150);
                fo.fill(new Ellipse2D.Double(x - rx, 150 - rx, rx * 2, rx * 2));
                fo.setTransform(k2);
            }
        }
        fo.dispose();
    }

    /** The still room. The title screen gets the lit throne room; the other pages keep the calmer wall and window. */
    private void paintRoom(Graphics2D b, double vx, double vy, double vw, double vh, boolean throneRoom) {
        if (throneRoom) { paintThroneRoom(b, vx, vy, vw, vh, true); return; }
        b.setPaint(new LinearGradientPaint(0, (float) vy, 0, (float) (vy + vh), new float[]{0f, .6f, 1f},
            new Color[]{new Color(18, 12, 34), new Color(11, 7, 22), new Color(6, 4, 12)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        b.setPaint(new RadialGradientPaint(new Point2D.Double(1150, 380), 560, new float[]{0f, 1f},
            new Color[]{new Color(120, 90, 200, 72), new Color(120, 90, 200, 0)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        for (double y = Math.floor(vy / 38) * 38; y < vy + vh; y += 38) {   // stone courses, fading out to the left
            int row = (int) Math.round(y / 38);
            for (double x = Math.floor(vx / 80) * 80 - (row & 1) * 40; x < vx + vw; x += 80) {
                double fade = Math.max(0, Math.min(1, (x - 300) / 700));
                b.setColor(new Color(0, 0, 0, (int) (40 + 70 * fade)));
                b.fill(new Rectangle2D.Double(x, y, 80, 2));
                b.fill(new Rectangle2D.Double(x, y, 2, 38));
            }
        }
        AffineTransform base = b.getTransform();
        b.translate(AX, AY);
        b.scale(AS, AS);
        Path2D win = new Path2D.Double();   // gothic window with moonlit glass
        win.moveTo(300, 40); win.quadTo(300, -10, 345, -10); win.quadTo(390, -10, 390, 40); win.lineTo(390, 170); win.lineTo(300, 170); win.closePath();
        b.setColor(new Color(27, 20, 54)); b.fill(win);
        b.setStroke(new BasicStroke(6)); b.setColor(new Color(58, 45, 98)); b.draw(win);
        Path2D glass = new Path2D.Double();
        glass.moveTo(312, 46); glass.quadTo(312, 6, 345, 6); glass.quadTo(378, 6, 378, 46); glass.lineTo(378, 162); glass.lineTo(312, 162); glass.closePath();
        b.setColor(new Color(142, 124, 240, 140)); b.fill(glass);
        b.setStroke(new BasicStroke(5)); b.setColor(new Color(42, 32, 72));
        b.draw(new Line2D.Double(345, 6, 345, 162)); b.draw(new Line2D.Double(312, 84, 378, 84));
        b.setTransform(base);
        b.setPaint(new RadialGradientPaint(new Point2D.Double(960, 405), 1050, new float[]{0f, .4f, 1f},   // vignette
            new Color[]{new Color(2, 1, 6, 0), new Color(2, 1, 6, 0), new Color(2, 1, 6, 220)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        b.setColor(new Color(4, 3, 10, 120));   // a calmer, darker room so the panels read clearly
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
    }

    // ---- the lit throne room of the main menu, drawn in the 1671 x 941 units of the design picture ----

    private static final double RS = 1600 / 1671.0;   // design units -> canvas units
    private static final Color STONE_LINE = new Color(21, 14, 42), GOLD_EDGE = new Color(240, 170, 46);

    private static void edged(Graphics2D g, Shape s, Paint fill, float width) {   // fill with a dark outline
        g.setPaint(fill); g.fill(s);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(INK); g.draw(s);
    }

    private void paintThroneRoom(Graphics2D b, double vx, double vy, double vw, double vh, boolean menuSide) {
        b.setPaint(new LinearGradientPaint(0, 0, (float) (1671 * RS), 0, new float[]{0f, .42f, 1f},
            new Color[]{new Color(9, 5, 19), new Color(21, 15, 44), new Color(42, 32, 80)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        AffineTransform base = b.getTransform();
        b.scale(RS, RS);
        double top = vy / RS, right = (vx + vw) / RS, bottom = (vy + vh) / RS;

        // stone wall behind the throne
        b.setColor(new Color(34, 26, 62));
        b.fill(new Rectangle2D.Double(640, top, right - 640, 800 - top));
        b.setStroke(new BasicStroke(2));
        for (double y = Math.floor(top / 24) * 24; y < 800; y += 24) {
            int row = (int) Math.round(y / 24);
            b.setColor(new Color(21, 14, 42));
            b.draw(new Line2D.Double(640, y, right, y));
            for (double x = 640 + ((row & 1) == 0 ? 0 : 48); x < right; x += 96) b.draw(new Line2D.Double(x, y, x, y + 24));
        }
        b.setPaint(new LinearGradientPaint(0, 0, (float) (1671), 0, new float[]{0f, .42f, 1f},   // the wall fades darker toward the menu
            new Color[]{new Color(9, 5, 19, 200), new Color(21, 15, 44, 60), new Color(42, 32, 80, 0)}));
        b.fill(new Rectangle2D.Double(640, top, right - 640, 800 - top));

        // pillars
        Paint pillar = new LinearGradientPaint(662, 0, 802, 0, new float[]{0f, .5f, 1f}, new Color[]{new Color(44, 34, 80), new Color(59, 47, 104), new Color(42, 32, 72)});
        b.setPaint(pillar); b.fill(new Rectangle2D.Double(662, top, 140, 770 - top));
        b.setStroke(new BasicStroke(3)); b.setColor(STONE_LINE);
        b.draw(new Line2D.Double(662, top, 662, 770)); b.draw(new Line2D.Double(802, top, 802, 770));
        b.setColor(new Color(74, 61, 124, 100)); b.setStroke(new BasicStroke(2));
        b.draw(new Line2D.Double(690, 60, 690, 740)); b.draw(new Line2D.Double(772, 60, 772, 740));
        edged(b, new Rectangle2D.Double(650, 632, 165, 140), new Color(47, 37, 86), 3); b.setColor(STONE_LINE); b.draw(new Rectangle2D.Double(650, 632, 165, 140));
        b.setPaint(new LinearGradientPaint(1552, 0, 1671, 0, new float[]{0f, .5f, 1f}, new Color[]{new Color(44, 34, 80), new Color(59, 47, 104), new Color(42, 32, 72)}));
        b.fill(new Rectangle2D.Double(1552, top, right - 1552, 770 - top));
        b.setStroke(new BasicStroke(3)); b.setColor(STONE_LINE); b.draw(new Line2D.Double(1552, top, 1552, 770));
        b.setColor(new Color(47, 37, 86)); b.fill(new Rectangle2D.Double(1534, 624, right - 1534, 148)); b.setColor(STONE_LINE); b.draw(new Rectangle2D.Double(1534, 624, right - 1534, 148));

        // arched window with the castle at dusk
        Path2D outer = new Path2D.Double();
        outer.moveTo(1030, 450); outer.lineTo(1030, 158); outer.quadTo(1030, 14, 1140, 14); outer.quadTo(1250, 14, 1250, 158); outer.lineTo(1250, 450); outer.closePath();
        b.setColor(new Color(42, 32, 82)); b.fill(outer);
        b.setStroke(new BasicStroke(10)); b.setColor(new Color(90, 74, 146)); b.draw(outer);
        Path2D glass = new Path2D.Double();
        glass.moveTo(1056, 440); glass.lineTo(1056, 158); glass.quadTo(1056, 40, 1140, 40); glass.quadTo(1224, 40, 1224, 158); glass.lineTo(1224, 440); glass.closePath();
        b.setPaint(new LinearGradientPaint(0, 40, 0, 440, new float[]{0f, .5f, 1f}, new Color[]{new Color(63, 70, 196), new Color(138, 98, 198), new Color(233, 154, 98)}));
        b.fill(glass);
        Shape oldClip = b.getClip();
        b.clip(glass);
        b.setColor(new Color(231, 165, 107, 46)); b.fill(new Ellipse2D.Double(970, 120, 260, 260));
        b.setColor(new Color(45, 34, 96));
        b.fill(poly(1056, 440, 1056, 330, 1074, 330, 1074, 300, 1084, 262, 1094, 300, 1094, 330, 1112, 330, 1112, 270, 1124, 270, 1124, 232, 1136, 192, 1148, 232, 1148, 270,
            1160, 270, 1160, 200, 1172, 180, 1184, 140, 1196, 180, 1206, 200, 1206, 330, 1224, 330, 1224, 440));
        b.setColor(new Color(36, 26, 82));
        b.fill(new Rectangle2D.Double(1068, 360, 32, 80)); b.fill(new Rectangle2D.Double(1130, 320, 40, 120)); b.fill(new Rectangle2D.Double(1176, 280, 36, 160));
        b.setStroke(new BasicStroke(3)); b.setColor(new Color(45, 34, 96));
        b.draw(new Line2D.Double(1084, 262, 1084, 240)); b.draw(new Line2D.Double(1136, 192, 1136, 166)); b.draw(new Line2D.Double(1184, 140, 1184, 112));
        b.setColor(new Color(224, 56, 79));
        b.fill(poly(1084, 240, 1106, 247, 1084, 254)); b.fill(poly(1136, 168, 1160, 175, 1136, 182)); b.fill(poly(1184, 114, 1208, 122, 1184, 130));
        b.setColor(new Color(255, 179, 71));
        b.fill(new RoundRectangle2D.Double(1100, 372, 8, 18, 6, 6)); b.fill(new RoundRectangle2D.Double(1176, 344, 8, 18, 6, 6)); b.fill(new RoundRectangle2D.Double(1203, 372, 8, 16, 6, 6));
        b.setStroke(new BasicStroke(7)); b.setColor(new Color(42, 32, 82)); b.draw(new Line2D.Double(1138, 40, 1138, 440));
        b.setClip(oldClip);
        b.setStroke(new BasicStroke(5)); b.setColor(new Color(58, 45, 108)); b.draw(glass);
        edged(b, new Rectangle2D.Double(1018, 440, 244, 26), new Color(58, 46, 104), 3);

        // light falling from the window
        b.setPaint(new LinearGradientPaint(0, 440, 0, 760, new float[]{0f, 1f}, new Color[]{new Color(207, 198, 255, 70), new Color(207, 198, 255, 8)}));
        b.fill(poly(1056, 440, 1224, 440, 1470, 760, 832, 760));

        // floor, carpet and stairs
        b.setPaint(new LinearGradientPaint(0, 740, 0, (float) Math.max(941, bottom), new float[]{0f, .25f, 1f},
            new Color[]{new Color(42, 32, 72, 0), new Color(45, 34, 80, 230), new Color(13, 9, 25)}));
        b.fill(new Rectangle2D.Double(640, 740, right - 640, Math.max(941, bottom) - 740));
        b.setPaint(new GradientPaint(0, 758, new Color(142, 20, 36), 0, 896, new Color(196, 31, 48)));
        b.fill(poly(978, 758, 1316, 758, 1452, 896, 842, 896));
        b.setStroke(new BasicStroke(9)); b.setColor(GOLD_EDGE);
        b.draw(new Line2D.Double(978, 758, 842, 896)); b.draw(new Line2D.Double(1316, 758, 1452, 896));
        b.setStroke(new BasicStroke(3)); b.setColor(new Color(77, 50, 6, 150));
        b.draw(new Line2D.Double(966, 758, 830, 896)); b.draw(new Line2D.Double(1328, 758, 1464, 896));
        lion(b, 1108, 788, 0.78);
        double[][] stairs = {{835, 760, 1452, 726, 852, 1436, 0x2b2150}, {895, 726, 1392, 696, 910, 1378, 0x33285c}, {962, 696, 1330, 666, 974, 1318, 0x3a2f66}};
        for (double[] s : stairs) {
            Path2D p = poly(s[0], s[1], s[2], s[1], s[5], s[3], s[4], s[3]);
            b.setColor(new Color((int) s[6])); b.fill(p);
            b.setStroke(new BasicStroke(3)); b.setColor(STONE_LINE); b.draw(p);
            b.setStroke(new BasicStroke(2)); b.setColor(new Color(106, 88, 168, 150)); b.draw(new Line2D.Double(s[0], s[1], s[2], s[1]));
        }

        // the throne
        edged(b, poly(1052, 666, 1052, 430, 1147, 356, 1242, 430, 1242, 666), GOLD_EDGE, 4);
        edged(b, poly(1066, 654, 1066, 440, 1147, 376, 1228, 440, 1228, 654), new Color(20, 16, 31), 3);
        edged(b, poly(1096, 612, 1096, 506, 1118, 480, 1176, 480, 1198, 506, 1198, 612), new Color(142, 20, 36), 3);
        b.setColor(new Color(255, 255, 255, 18)); b.fill(poly(1100, 604, 1100, 510, 1120, 486, 1138, 486, 1138, 604));
        edged(b, new RoundRectangle2D.Double(1015, 560, 58, 40, 6, 6), GOLD_EDGE, 3);
        edged(b, new RoundRectangle2D.Double(1221, 560, 58, 40, 6, 6), GOLD_EDGE, 3);
        edged(b, new Rectangle2D.Double(1023, 598, 14, 68), new Color(225, 154, 38), 3);
        edged(b, new Rectangle2D.Double(1257, 598, 14, 68), new Color(225, 154, 38), 3);
        edged(b, new RoundRectangle2D.Double(1063, 596, 168, 44, 6, 6), new Color(196, 31, 48), 3);
        edged(b, new Rectangle2D.Double(1063, 634, 168, 10), GOLD_EDGE, 2);
        edged(b, new Rectangle2D.Double(1060, 644, 174, 22), new Color(26, 20, 40), 3);
        crown(b, 1147, 466, 1.05);

        // torch stands (the flames are drawn live)
        for (double x : new double[]{829, 1501}) {
            b.setColor(new Color(23, 18, 31)); b.fill(poly(x - 3, 546, x + 3, 546, x + 7, 750, x - 7, 750));
            b.setColor(new Color(18, 13, 28)); b.fill(new Ellipse2D.Double(x - 30, 744, 60, 16));
            gilded(b, poly(x - 23, 540, x + 23, 540, x + 13, 562, x - 13, 562), 540, 562);
            gilded(b, new RoundRectangle2D.Double(x - 27, 534, 54, 9, 8, 8), 534, 543);
        }

        b.setTransform(base);
        if (menuSide) {
            b.setPaint(new LinearGradientPaint((float) vx, 0, (float) (720 * RS), 0, new float[]{0f, .8f, 1f},   // keeps the menu side dark and readable
                new Color[]{new Color(7, 4, 15, 245), new Color(7, 4, 15, 224), new Color(7, 4, 15, 0)}));
            b.fill(new Rectangle2D.Double(vx, vy, 720 * RS - vx, vh));
            Rectangle2D lb = logoShape.getBounds2D();
            Art.glow(b, new Color(255, 190, 80), lb.getCenterX(), lb.getCenterY(), 300, 0.10f);
        }
        b.scale(RS, RS);
        // gold frame along the bottom, over the shade
        b.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); b.setColor(GOLD_EDGE);
        b.draw(new Line2D.Double(60, 910, 776, 910)); b.draw(new Line2D.Double(894, 910, 1611, 910));
        Path2D cl = new Path2D.Double(); cl.moveTo(20, 884); cl.lineTo(20, 908); cl.quadTo(20, 912, 24, 912); cl.lineTo(48, 912);
        Path2D cr = new Path2D.Double(); cr.moveTo(1651, 884); cr.lineTo(1651, 908); cr.quadTo(1651, 912, 1647, 912); cr.lineTo(1623, 912);
        b.draw(cl); b.draw(cr);
        b.setStroke(new BasicStroke(1)); b.setColor(new Color(255, 241, 196, 120));
        b.draw(new Line2D.Double(60, 908, 776, 908)); b.draw(new Line2D.Double(894, 908, 1611, 908));
        Path2D gem = poly(835, 891, 858, 912, 835, 933, 812, 912);
        b.setPaint(new LinearGradientPaint(0, 891, 0, 933, new float[]{0f, .5f, 1f}, GOLD_RAMP)); b.fill(gem);
        b.setStroke(new BasicStroke(3)); b.setColor(INK); b.draw(gem);
        b.setTransform(base);
    }

    /** Banners and torch flames, drawn live on top of the still throne room. */
    private void drawThroneLife(Graphics2D g) {
        AffineTransform keep = g.getTransform();
        g.scale(RS, RS);
        menuBanner(g, 924, new Color(179, 32, 44), new Color(108, 15, 30), 0);
        menuBanner(g, 1368, new Color(42, 79, 191), new Color(20, 35, 106), 2.4);
        for (double[] t : new double[][]{{829, 538, 0}, {1501, 538, 2.1}}) {
            double flick = 1 + 0.06 * Math.sin(time * 11 + t[2]) + 0.04 * Math.sin(time * 23 + t[2] * 2);
            if (fx) Art.glow(g, new Color(255, 179, 71), t[0], 500, 92, (float) (0.55 + 0.12 * Math.sin(time * 5.7 + t[2])));
            AffineTransform k = g.getTransform();
            g.translate(t[0], t[1]);
            g.scale(1 / flick, flick);   // taller and a touch thinner when it leaps
            g.setColor(new Color(255, 106, 20)); g.fill(path("M 0 0 C -24 -10 -27 -42 -11 -76 C -9 -58 1 -58 3 -82 C 23 -60 27 -18 0 0 Z"));
            g.setColor(new Color(255, 176, 46)); g.fill(path("M 0 -2 C -15 -10 -16 -32 -6 -52 C -4 -40 3 -40 4 -56 C 17 -40 17 -12 0 -2 Z"));
            g.setColor(new Color(255, 242, 168)); g.fill(path("M 0 -4 C -8 -10 -8 -20 -2 -30 C 0 -22 6 -22 6 -14 C 6 -8 3 -5 0 -4 Z"));
            g.setTransform(k);
        }
        g.setTransform(keep);
    }

    /** A hanging banner with the gold lion, centred on cx (design units), swaying a little. */
    private void menuBanner(Graphics2D g, double cx, Color top, Color bottom, double phase) {
        AffineTransform keep = g.getTransform();
        g.translate(cx, 118);
        g.shear(0.028 * (0.5 + 0.5 * Math.sin(time * 1.14 + phase)), 0);
        g.translate(-cx, -118);
        gilded(g, new RoundRectangle2D.Double(cx - 82, 116, 164, 14, 12, 12), 116, 130);
        gilded(g, new Ellipse2D.Double(cx - 85, 114, 18, 18), 114, 132);
        gilded(g, new Ellipse2D.Double(cx + 67, 114, 18, 18), 114, 132);
        Path2D cloth = poly(cx - 62, 130, cx + 62, 130, cx + 62, 440, cx, 488, cx - 62, 440);
        g.setPaint(new GradientPaint(0, 130, top, 0, 488, bottom)); g.fill(cloth);
        g.setStroke(new BasicStroke(15, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)); g.setColor(INK); g.draw(cloth);
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)); g.setColor(GOLD_EDGE); g.draw(cloth);
        lion(g, cx - 52, 210, 1.0);
        g.setTransform(keep);
    }

    private static Graphics2D layer(BufferedImage im, double sc, double vx, double vy) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(sc, sc);
        g.translate(-vx, -vy);
        return g;
    }

    /** Pages that sit on the dimmed throne room. */
    private static boolean dimRoom(Page p) { return p == Page.PLAY || p == Page.HOWTO || p == Page.SETTINGS; }

    private void drawScene(Graphics2D g, double vx, double vy, double vw, double vh, Page pg) {
        boolean withThrone = pg == Page.MAIN || dimRoom(pg);   // the lit throne room (dimmed on the other pages)
        Scenery.blit1to1(g, pg == Page.MAIN ? back : dimRoom(pg) ? backPlay : backPage, vx, vy);
        if (withThrone) {
            Composite keepC = g.getComposite();
            if (pg != Page.MAIN) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            drawThroneLife(g);
            g.setComposite(keepC);
        }
        if (!withThrone) Scenery.blit1to1(g, fogImg, -(time * 16 % VW), 640);   // fog drifts slowly to the left (not over the lit throne room)
        for (double[] e : embers) {
            double a = Math.max(0, Math.min(1, e[1] * 2)) * (0.5 + 0.5 * Math.sin(e[3] * 2));   // fades out near the top
            g.setColor(e[4] > 0 ? new Color(255, 160, 70, (int) (215 * a)) : new Color(200, 180, 255, (int) (200 * a)));
            double x = vx + (e[0] + Math.sin(e[3]) * 0.006) * vw, y = vy + e[1] * vh;
            g.fill(new Ellipse2D.Double(x - 1.6, y - 1.6, 3.2, 3.2));
        }
    }

    private static Path2D poly(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }

    /** The gold crown; (cx, base) is the middle of its band. */
    static void crown(Graphics2D g, double cx, double base, double k) {
        AffineTransform keep = g.getTransform();
        g.translate(cx, base);
        g.scale(k, k);
        Paint gold = new LinearGradientPaint(0, -46, 0, 10, new float[]{0f, .45f, 1f}, new Color[]{GOLD_HI, GOLD, GOLD_LO});
        Path2D top = poly(-32, 0, -36, -36, -20, -22, 0, -46, 20, -22, 36, -36, 32, 0);
        g.setPaint(gold); g.fill(top);
        g.setStroke(new BasicStroke(2.5f)); g.setColor(GOLD_DK); g.draw(top);
        RoundRectangle2D band = new RoundRectangle2D.Double(-34, -2, 68, 11, 4, 4);
        g.setPaint(gold); g.fill(band);
        g.setColor(GOLD_DK); g.draw(band);
        g.setColor(new Color(235, 70, 70)); g.fill(new Ellipse2D.Double(-5, -23, 10, 10));
        g.setColor(new Color(70, 150, 255));
        g.fill(new Ellipse2D.Double(-23.4, -13.4, 6.8, 6.8));
        g.fill(new Ellipse2D.Double(16.6, -13.4, 6.8, 6.8));
        g.setTransform(keep);
    }

    // ------------------------------------------------------------------ MAIN

    private void drawMain(Graphics2D g) {
        // title block: crown, THE between two gold lines, the logo, and the tagline
        crown(g, 380, 99 + Math.sin(time * 1.4) * 1.5, 1.2);
        g.setFont(Type.royal(24, true, 0.5f));
        double tw = g.getFontMetrics().getStringBounds("THE", g).getWidth();
        g.setPaint(new GradientPaint(113, 0, new Color(255, 205, 90, 0), 212, 0, new Color(255, 205, 90, 240)));
        g.fill(new Rectangle2D.Double(113, 114, 99, 2));
        Art.text(g, "THE", 226, 123, new Color(243, 194, 93));
        g.setPaint(new GradientPaint((float) (226 + tw + 6), 0, new Color(255, 205, 90, 240), (float) (226 + tw + 112), 0, new Color(255, 205, 90, 0)));
        g.fill(new Rectangle2D.Double(226 + tw + 6, 114, 106, 2));
        drawLogo(g);
        g.setFont(Type.royal(19, true, 0.34f));
        Art.textCenter(g, "CAPTURE  ·  HOLD  ·  CONQUER", 73, 612, 244, new Color(185, 177, 230));

        double y = 299;   // six rows, the chosen one on a gold band with a bar and a glow at its left end
        for (int i = 0; i < ITEMS.length; i++) {
            boolean on = i == sel;
            double h = 73;
            Rectangle2D box = new Rectangle2D.Double(100, y, 580, h);
            final int item = i;
            hits.add(new Object[]{box, (Runnable) () -> open(item), i});
            if (on) {
                g.setPaint(new LinearGradientPaint(107, 0, 663, 0, new float[]{0f, .46f, 1f},
                    new Color[]{new Color(214, 150, 40, 158), new Color(150, 98, 24, 97), new Color(60, 40, 10, 0)}));
                g.fill(new Rectangle2D.Double(107, y, 556, h));
                g.setColor(new Color(255, 205, 90, 115)); g.fill(new Rectangle2D.Double(107, y, 556, 1));
                g.setColor(new Color(255, 205, 90, 50)); g.fill(new Rectangle2D.Double(107, y + h - 1, 556, 1));
                if (fx) Art.glow(g, new Color(255, 190, 70), 110, y + h / 2, 62, 0.75f);
                g.setPaint(new GradientPaint(0, (float) y, GOLD_HI, 0, (float) (y + h), GOLD_LO));
                g.fill(new Rectangle2D.Double(107, y, 8, h));
            }
            g.setFont(Type.royal(24, true, 0.05f));
            Art.text(g, NUMERALS[i], 121, y + h / 2 + 9, on ? new Color(255, 211, 106) : new Color(138, 118, 221));
            g.setFont(Type.royal(40, true, 0.2f));
            Art.text(g, ITEMS[i], 180, y + h / 2 + 14, on ? new Color(255, 246, 220) : new Color(189, 184, 220));
            y += 70;
        }

        drawProfile(g);
        drawFooter(g, true);
    }

    private void drawLogo(Graphics2D g) {
        Rectangle2D b = logoShape.getBounds2D();
        Scenery.blit1to1(g, logoImg, logoX, logoY);   // gold letters and shadow, drawn once in ensureCache
        double cycle = time % 5.5 / 5.5;   // a light sweep crosses the letters every few seconds
        if (cycle > 0.55 && cycle < 0.85) {
            double sx = b.getMinX() - 120 + (cycle - 0.55) / 0.3 * (b.getWidth() + 240);
            Shape oldClip = g.getClip();
            g.clip(logoShape);
            g.setPaint(new LinearGradientPaint((float) (sx - 60), 0, (float) (sx + 60), 0, new float[]{0f, .5f, 1f},
                new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 230), new Color(255, 255, 255, 0)}));
            g.fill(new Rectangle2D.Double(sx - 60, b.getMinY(), 120, b.getHeight()));
            g.setClip(oldClip);
        }
    }

    private void eyebrow(Graphics2D g, String text, double x, double y) {
        g.setFont(Type.royal(18, false, 0.6f));
        double w = g.getFontMetrics().getStringBounds(text, g).getWidth();
        g.setPaint(new GradientPaint((float) x, 0, new Color(255, 205, 90, 0), (float) (x + 54), 0, GOLD));
        g.fill(new Rectangle2D.Double(x, y - 6, 54, 1.5));
        Art.text(g, text, x + 68, y, SOFT);
        g.setPaint(new GradientPaint((float) (x + 82 + w), 0, GOLD, (float) (x + 136 + w), 0, new Color(255, 205, 90, 0)));
        g.fill(new Rectangle2D.Double(x + 82 + w, y - 6, 54, 1.5));
    }

    private void drawProfile(Graphics2D g) {
        double x = 1229, y = 40, w = 345, h = 74;
        RoundRectangle2D card = new RoundRectangle2D.Double(x, y, w, h, 14, 14);
        g.setPaint(new GradientPaint(0, (float) y, new Color(26, 18, 48, 238), 0, (float) (y + h), new Color(10, 6, 22, 244)));
        g.fill(card);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(240, 181, 58));
        g.draw(card);
        // your knight's head and shoulders, cut out of the knight picture (blue team = the blue knight)
        BufferedImage a = knightArt((int) Math.round(120 * g.getTransform().getScaleY()), team == 1);
        if (a != null) {
            double cropH = a.getHeight() * 0.52, cropW = cropH * 56 / 62, cropX = a.getWidth() * 0.563 - cropW / 2;
            Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(a, (int) (x + 16), (int) (y + 6), (int) (x + 72), (int) (y + 68),
                (int) cropX, 0, (int) (cropX + cropW), (int) cropH, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        } else {   // assets/knight.png missing: the old pixel knight in its frame
            g.setColor(new Color(11, 7, 24));
            g.fill(new Rectangle2D.Double(x + 14, y + 10, 54, 54));
            g.setColor(GOLD);
            g.draw(new Rectangle2D.Double(x + 14, y + 10, 54, 54));
            knight(g, x + 41, y + 13, 2.0, 0);
        }
        g.setFont(Type.royal(26, true, 0.1f));
        Art.text(g, playerName().toUpperCase(), x + 92, y + 34, new Color(244, 236, 255));
        g.setFont(Type.hud(15, true, 0.02f));
        Art.text(g, LobbyRoom.HAT_NAMES[hat] + "  |  Last server: " + address, x + 92, y + 58, new Color(211, 207, 232));
    }

    /** The red knight sprite with the chosen hat; (cx, top) is the top centre of the sprite, k = canvas units per sprite pixel. */
    private void knight(Graphics2D g, double cx, double top, double k, double bobPhase) {
        double bob = bobPhase == 0 ? 0 : Math.sin(time * 2.1 + bobPhase) * k * 1.2;
        BufferedImage art = knightArt((int) Math.round(Art.SPR_H * k * g.getTransform().getScaleY()), team == 1);   // blue team = the blue knight; red and "any" = red
        if (art != null) {
            double h = Art.SPR_H * k, w = h * art.getWidth() / art.getHeight();
            Object keepHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            AffineTransform at = new AffineTransform();
            at.translate(cx - w / 2, top + bob);
            at.scale(w / art.getWidth(), h / art.getHeight());
            g.drawImage(art, at, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, keepHint);
            AffineTransform keep = g.getTransform();   // the hats are drawn in the picture's own pixels (KNIGHT_W x KNIGHT_H)
            g.translate(cx - w / 2, top + bob);
            g.scale(w / KNIGHT_W, h / KNIGHT_H);
            knightHat(g, hat, LobbyRoom.HAT_COLORS[Math.floorMod(hatColor, LobbyRoom.HAT_COLORS.length)]);
            g.setTransform(keep);
            return;
        }
        BufferedImage im = Art.spriteCrisp(0, 0, 1);   // assets/knight.png missing: the old pixel sprite and its small hats
        Object keepHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(im, (int) Math.round(cx - Art.SPR_W * k / 2), (int) Math.round(top + bob), (int) Math.round(Art.SPR_W * k), (int) Math.round(Art.SPR_H * k), null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, keepHint);
        AffineTransform keep = g.getTransform();
        g.translate(cx, top + bob);
        g.scale(k / GamePanel.PX, k / GamePanel.PX);   // LobbyRoom.hat works in world units (2.4 per sprite pixel)
        LobbyRoom.hat(g, hat, hatColor, 0, 6);
        g.setTransform(keep);
    }

    // ---- hats for the big knight picture: same thick dark outline as the picture, helmet top centre at x = 497 ----

    static final int KNIGHT_W = 882, KNIGHT_H = 1138;
    private static final Color OUTLINE = new Color(13, 9, 18);
    private static final BasicStroke OUT = new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    /** Absolute SVG-style path: letters M L Q C Z, every token separated by a space. */
    private static Path2D.Double path(String d) {
        Path2D.Double p = new Path2D.Double();
        String[] t = d.trim().split("\s+");
        for (int i = 0; i < t.length; ) {
            char c = t[i++].charAt(0);
            double[] v = new double[6];
            int n = c == 'C' ? 6 : c == 'Q' ? 4 : c == 'Z' ? 0 : 2;
            for (int j = 0; j < n; j++) v[j] = Double.parseDouble(t[i++]);
            switch (c) {
                case 'M' -> p.moveTo(v[0], v[1]);
                case 'L' -> p.lineTo(v[0], v[1]);
                case 'Q' -> p.quadTo(v[0], v[1], v[2], v[3]);
                case 'C' -> p.curveTo(v[0], v[1], v[2], v[3], v[4], v[5]);
                default -> p.closePath();
            }
        }
        return p;
    }

    private static void solid(Graphics2D g, Shape s, Color fill) {
        g.setColor(fill); g.fill(s);
        g.setStroke(OUT); g.setColor(OUTLINE); g.draw(s);
    }

    private static void shade(Graphics2D g, Shape s, Color c) { g.setColor(c); g.fill(s); }

    private static void line(Graphics2D g, Shape s, Color c, float w) {
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(c); g.draw(s);
    }

    static void knightHat(Graphics2D g, int hat, Color c) { knightHat(g, hat, c, -1); }

    /** time >= 0 makes the halo float and the wings flap; -1 keeps them still. */
    static void knightHat(Graphics2D g, int hat, Color c, double time) {
        Color white = new Color(255, 255, 255, 105);
        switch (hat) {
            case 1 -> {   // crown
                solid(g, path("M 360 206 L 350 104 L 420 158 L 497 72 L 574 158 L 644 104 L 634 206 Q 497 234 360 206 Z"), c);
                Path2D.Double band = path("M 360 206 Q 497 234 634 206 L 632 236 Q 497 266 362 236 Z");
                solid(g, band, c); shade(g, band, new Color(0, 0, 0, 70));
                shade(g, path("M 372 198 L 364 132 L 412 168 Z"), new Color(255, 255, 255, 100));
                Ellipse2D.Double ruby = new Ellipse2D.Double(481, 178, 32, 32);
                solid(g, ruby, new Color(255, 77, 109));
                shade(g, new Ellipse2D.Double(486, 183, 10, 10), Color.WHITE);
                for (double[] pr : new double[][]{{350, 104, 11}, {497, 72, 12}, {644, 104, 11}})
                    solid(g, new Ellipse2D.Double(pr[0] - pr[2], pr[1] - pr[2], 2 * pr[2], 2 * pr[2]), new Color(255, 244, 194));
            }
            case 3 -> mirrored(g, () -> {   // horns
                solid(g, path("M 330 312 C 214 318 150 250 168 98 C 206 188 286 220 370 232 Z"), c);
                line(g, path("M 190 134 C 206 200 262 234 330 250"), new Color(255, 255, 255, 115), 10);
                line(g, path("M 300 300 C 250 296 224 270 210 240"), new Color(0, 0, 0, 46), 8);
            });
            case 4 -> {   // halo
                AffineTransform haloKeep = g.getTransform();
                if (time >= 0) g.translate(0, -14 * (0.5 - 0.5 * Math.cos(2 * Math.PI * time / 3)));
                Ellipse2D.Double ring = new Ellipse2D.Double(347, 82, 300, 68);
                for (int i = 0; i < 4; i++) line(g, ring, new Color(c.getRed(), c.getGreen(), c.getBlue(), 28), 56 - i * 8);   // soft glow
                line(g, ring, OUTLINE, 34);
                line(g, ring, c, 18);
                line(g, path("M 366 104 Q 420 78 497 78"), new Color(255, 255, 255, 180), 6);
                g.setTransform(haloKeep);
            }
            case 5 -> mirrored(g, () -> {   // wings
                AffineTransform wingKeep = g.getTransform();
                if (time >= 0) {   // flap about the point where the wing meets the helmet
                    double u = (time % 1.4) / 1.4, ang = u < .25 ? -7 * u / .25 : u < .5 ? -7 + 7 * (u - .25) / .25 : u < .75 ? 4 * (u - .5) / .25 : 4 - 4 * (u - .75) / .25;
                    g.rotate(Math.toRadians(ang), 340, 290);
                }
                solid(g, path("M 330 318 C 240 330 140 292 80 186 C 136 206 170 196 200 158 C 212 204 262 230 350 244 Z"), c);
                line(g, path("M 326 298 C 252 306 180 280 136 226"), new Color(13, 9, 18, 140), 6);
                line(g, path("M 318 270 C 258 274 208 250 182 212"), new Color(13, 9, 18, 140), 6);
                line(g, path("M 98 192 C 148 216 188 208 208 176"), new Color(255, 255, 255, 125), 8);
                g.setTransform(wingKeep);
            });
            default -> { }   // no hat; 2 (top hat) was removed
        }
    }

    /** Draws the left-hand piece, then its mirror image on the right of the helmet (centre line x = 497). */
    private static void mirrored(Graphics2D g, Runnable leftSide) {
        leftSide.run();
        AffineTransform keep = g.getTransform();
        g.translate(994, 0);
        g.scale(-1, 1);
        leftSide.run();
        g.setTransform(keep);
    }

    private static final BufferedImage[] KNIGHT_FULL = new BufferedImage[2];   // [0] red (assets/knight.png), [1] blue (assets/knight_blue.png)
    private static final java.util.Map<Integer, BufferedImage> KNIGHT_SIZES = new java.util.HashMap<>();

    /** The team's knight picture shrunk to the given pixel height in halving steps, so it stays smooth; null if the file is missing. */
    static synchronized BufferedImage knightArt(int px, boolean blue) {
        int t = blue ? 1 : 0;
        if (KNIGHT_FULL[t] == null) {
            try { KNIGHT_FULL[t] = javax.imageio.ImageIO.read(new java.io.File(blue ? "assets/knight_blue.png" : "assets/knight.png")); } catch (Exception e) { return null; }
        }
        BufferedImage full = KNIGHT_FULL[t];
        if (full == null || px <= 0) return full;
        return KNIGHT_SIZES.computeIfAbsent(px * 2 + t, key -> {
            BufferedImage cur = full;
            while (cur.getHeight() / 2 >= px) cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
            return cur.getHeight() > px ? resize(cur, Math.max(1, cur.getWidth() * px / cur.getHeight()), px) : cur;
        });
    }

    static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private void drawFooter(Graphics2D g, boolean main) {
        double x = 100, y = dimRoom(page) ? 836 : 862;   // the Play page has the gold frame below the footer
        g.setFont(Type.hud(17, true, 0.08f));
        if (main) {
            x = keycap(g, "↑", x, y); x = keycap(g, "↓", x - 6, y); x = label(g, "CHOOSE", x, y);
            x = keycap(g, "ENTER", x + 18, y); x = label(g, "SELECT", x, y);
            x = keycap(g, "F11", x + 18, y); x = label(g, "FULLSCREEN", x, y);
            x = keycap(g, "M", x + 18, y); label(g, "MUSIC " + (Music.isOn() ? "ON" : "OFF"), x, y);
        } else {
            g.setFont(Type.ui(17, true));   // the menu fonts have no arrow symbols
            Art.text(g, "←", x, y - 1, SOFT);
            g.setFont(Type.hud(17, true, 0.08f));
            Art.text(g, "BACK", x + 24, y, SOFT);
            Rectangle2D r = new Rectangle2D.Double(x - 10, y - 26, 200, 38);
            hits.add(new Object[]{r, (Runnable) this::back});
            keycap(g, "ESC", x + 92, y);
        }
        if (main || dimRoom(page)) {
            g.setColor(new Color(10, 6, 22, 185));
            g.fill(new RoundRectangle2D.Double(1288, y - 27, 282, 38, 12, 12));
        }
        g.setColor(new Color(110, 230, 130));
        if (fx) Art.glow(g, new Color(110, 230, 130), 1314, y - 6, 12, 0.7f);
        g.fill(new Ellipse2D.Double(1308, y - 12, 11, 11));
        g.setFont(Type.hud(17, true, 0.08f));
        Art.text(g, "LAN " + lanAddress + "   ·   v" + ctf.common.Protocol.VERSION, 1330, y, MUTED);
    }

    private double keycap(Graphics2D g, String k, double x, double y) {
        boolean symbol = k.chars().anyMatch(c -> c > 127);   // arrows: the menu fonts have none, the system UI font does
        g.setFont(symbol ? Type.ui(15, true) : Type.hud(15, true, 0.04f));
        double w = Math.max(30, g.getFontMetrics().stringWidth(k) + 16);
        RoundRectangle2D r = new RoundRectangle2D.Double(x, y - 21, w, 28, 5, 5);
        g.setColor(new Color(20, 14, 40, 170)); g.fill(r);
        g.setStroke(new BasicStroke(1.3f)); g.setColor(new Color(170, 150, 235, 150)); g.draw(r);
        Art.textCenter(g, k, x, w, y - 2, SOFT);
        return x + w + 8;
    }

    private double label(Graphics2D g, String s, double x, double y) {
        g.setFont(Type.hud(17, true, 0.08f));
        Art.text(g, s, x, y - 2, MUTED);
        return x + g.getFontMetrics().stringWidth(s) + 8;
    }

    // ------------------------------------------------------------------ shared page pieces

    private void pageTitle(Graphics2D g, String crumb, String title) {
        g.setFont(Type.hud(18, true, 0.3f));
        Art.text(g, "MAIN MENU   /   ", 100, 66, MUTED);
        Art.text(g, crumb, 100 + g.getFontMetrics().stringWidth("MAIN MENU   /   "), 66, GOLD);
        Shape t = new TextLayout(title, Type.logo(58), new java.awt.font.FontRenderContext(null, true, true))
            .getOutline(AffineTransform.getTranslateInstance(100, 138));
        AffineTransform keep = g.getTransform();
        g.translate(0, 3);
        g.setColor(GOLD_DK);
        g.fill(t);
        g.setTransform(keep);
        Rectangle2D b = t.getBounds2D();
        g.setPaint(new LinearGradientPaint(0, (float) b.getMinY(), 0, (float) b.getMaxY(), new float[]{0f, .5f, 1f}, new Color[]{GOLD_HI, GOLD, GOLD_LO}));
        g.fill(t);
    }

    /** A dark panel with cut corners and a thin border (the design's cards). */
    private static Path2D panel(Graphics2D g, double x, double y, double w, double h, double cut, Color border, Paint fill) {
        Path2D p = poly(x, y, x + w - cut, y, x + w, y + cut, x + w, y + h, x + cut, y + h, x, y + h - cut);
        g.setPaint(fill != null ? fill : new GradientPaint(0, (float) y, new Color(20, 14, 40, 200), 0, (float) (y + h), new Color(8, 5, 18, 235)));
        g.fill(p);
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(border);
        g.draw(p);
        return p;
    }

    private static void wrap(Graphics2D g, String text, double x, double y, double w, double lineH, Color c) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && fm.stringWidth(line + " " + word) > w) {
                Art.text(g, line.toString(), x, y, c);
                y += lineH;
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) Art.text(g, line.toString(), x, y, c);
    }

    /** A gold (or blue) slanted action button. */
    private void button(Graphics2D g, double x, double y, double w, String text, boolean blue, Runnable action) {
        button(g, x, y, w, 46, text, blue, action);
    }

    private void button(Graphics2D g, double x, double y, double w, double h, String text, boolean blue, Runnable action) {
        button(g, x, y, w, h, text, blue ? 1 : 0, action);
    }

    /** style: 0 gold, 1 blue, 2 red. */
    private void button(Graphics2D g, double x, double y, double w, double h, String text, int style, Runnable action) {
        Path2D p = poly(x + 12, y, x + w, y, x + w - 12, y + h, x, y + h);
        g.setPaint(style == 1 ? new GradientPaint(0, (float) y, new Color(201, 240, 255), 0, (float) (y + h), new Color(31, 111, 176))
            : style == 2 ? new LinearGradientPaint(0, (float) y, 0, (float) (y + h), new float[]{0f, .5f, 1f}, new Color[]{new Color(255, 184, 170), new Color(224, 69, 58), new Color(138, 20, 32)})
            : new LinearGradientPaint(0, (float) y, 0, (float) (y + h), new float[]{0f, .5f, 1f}, new Color[]{GOLD_HI, GOLD, GOLD_LO}));
        g.fill(p);
        g.setFont(Type.royal((float) (19 * h / 46), true, 0.25f));
        Art.textCenter(g, text, x, w, y + h * 0.675, style == 1 ? new Color(3, 18, 38) : style == 2 ? Color.WHITE : new Color(26, 15, 2));
        hits.add(new Object[]{p, action});
    }

    // ------------------------------------------------------------------ the "leave the throne room?" message

    private boolean confirmQuit;   // true while the message is on screen
    private double quitAsked;      // menu time when it opened

    /** A dim layer over the whole window and a gold-framed question; clicks outside the box do nothing. */
    private void drawQuitConfirm(Graphics2D g, double vx, double vy, double vw, double vh) {
        g.setColor(new Color(5, 3, 12, 189));
        g.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        hits.add(new Object[]{new Rectangle2D.Double(vx, vy, vw, vh), (Runnable) () -> { }});   // swallows the clicks outside the box
        double w = 640, h = 340, x = (VW - w) / 2, y = (VH - h) / 2;
        playCard(g, x, y, w, h, new Color(29, 20, 54), new Color(11, 7, 22), false, GOLD);
        cardInset(g, x, y, w, h);
        if (fx) Art.glow(g, GOLD, VW / 2.0, y + 62, 70, 0.30f);
        crown(g, VW / 2.0, y + 64, 1.3);
        shineText(g, "LEAVE THE THRONE ROOM?", Type.royal(29, true, 0.12f), VW / 2.0, y + 150, RETURN_RAMP, new Color(0, 0, 0, 0), -1);
        g.setFont(Type.hud(23, true, 0.02f));
        Art.textCenter(g, "Are you sure you want to quit the game?", x, w, y + 207, new Color(217, 211, 238));
        button(g, x + 92, y + 240, 210, 54, "STAY", 0, () -> confirmQuit = false);
        button(g, x + 338, y + 240, 210, 54, "QUIT", 2, () -> actions.quit());
        g.setFont(Type.hud(14, true, 0.14f));
        double hx = x + w / 2 - 118;
        hx = keycap(g, "ESC", hx, y + h - 20);
        hx = label(g, "STAY", hx, y + h - 20);
        hx = keycap(g, "ENTER", hx + 14, y + h - 20);
        label(g, "QUIT", hx, y + h - 20);
    }

    // ------------------------------------------------------------------ PLAY (three cards in the throne-room theme)

    // Flame shapes (control points of three curves): the base shape, leaning right and tall, leaning left. The flame morphs between them.
    private static final double[][] FLAME_OUT = {
        {-14, -6, -16, -26, -6, -46, -4, -34, 2, -34, 4, -52, 16, -36, 18, -8, 0, 0},
        {-10, -8, -12, -28, -2, -50, 0, -38, 6, -36, 9, -58, 20, -38, 16, -10, 0, 0},
        {-18, -6, -20, -24, -10, -44, -8, -32, -2, -32, 0, -50, 14, -36, 16, -10, 0, 0}};
    private static final double[][] FLAME_MID = {
        {-8, -6, -9, -20, -3, -32, -1, -24, 3, -24, 3, -34, 10, -24, 10, -8, 0, -2},
        {-5, -7, -6, -22, 0, -34, 2, -26, 6, -26, 7, -38, 12, -26, 9, -8, 0, -2},
        {-11, -6, -12, -18, -7, -30, -5, -22, -1, -22, 0, -32, 8, -24, 9, -8, 0, -2}};
    private static final double[][] FLAME_IN = {
        {-4, -7, -4, -13, -1, -18, 0, -14, 4, -13, 3, -8, 3, -6, 1, -4, 0, -4},
        {-3, -7, -3, -14, 1, -20, 2, -15, 5, -14, 4, -8, 3, -6, 1, -4, 0, -4},
        {-5, -7, -5, -12, -3, -17, -1, -13, 2, -13, 2, -8, 2, -6, 1, -4, 0, -4}};

    /** One flame layer at phase 0..1 of its cycle: base -> right -> base -> left -> base, eased. */
    private static Path2D flamePath(double[][] k, double phase, double startY) {
        double u = (phase - Math.floor(phase)) * 4, e = 0.5 - 0.5 * Math.cos(Math.PI * (u - Math.floor(u)));
        int seg = (int) Math.floor(u) & 3;
        double[][] from = {k[0], k[1], k[0], k[2]}, to = {k[1], k[0], k[2], k[0]};
        double[] a = from[seg], b = to[seg];
        double[] v = new double[18];
        for (int i = 0; i < 18; i++) v[i] = a[i] + (b[i] - a[i]) * e;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, startY);
        p.curveTo(v[0], v[1], v[2], v[3], v[4], v[5]);
        p.curveTo(v[6], v[7], v[8], v[9], v[10], v[11]);
        p.curveTo(v[12], v[13], v[14], v[15], v[16], v[17]);
        p.closePath();
        return p;
    }

    /** The beacon flame at (x, y): it leans from side to side, its tongues change shape, and sparks rise from it. */
    private void waveFlame(Graphics2D g, double x, double y) {
        AffineTransform keep = g.getTransform();
        g.translate(x, y);
        g.rotate(Math.toRadians(-5 * Math.cos(time * 2 * Math.PI / 1.7)));
        g.setColor(new Color(255, 106, 20)); g.fill(flamePath(FLAME_OUT, time / 1.1, 0));
        g.setColor(new Color(255, 176, 46)); g.fill(flamePath(FLAME_MID, time / 0.9, -2));
        g.setColor(new Color(255, 242, 168)); g.fill(flamePath(FLAME_IN, time / 0.7, -4));
        g.setTransform(keep);
        double[][] sparks = {{2.1, 0, 2, -30, -92, 2, 14}, {2.7, -0.9, 1.6, -34, -100, -3, -16}, {1.8, -1.3, 1.4, -26, -84, 0, 8}};
        for (double[] s : sparks) {
            double u = ((time + s[1]) % s[0] + s[0]) % s[0] / s[0];
            g.setColor(new Color(255, 193, 90, (int) (255 * Math.sin(Math.PI * u))));
            double r = s[2];
            g.fill(new Ellipse2D.Double(x + s[5] + (s[6] - s[5]) * u - r, y + s[3] + (s[4] - s[3]) * u - r, 2 * r, 2 * r));
        }
    }

    // The footpath on the Join card, in the card's 454 x 232 art space, and points along it for the walking light.
    private static final Path2D TRAIL = new Path2D.Double();
    private static final double[] TRAIL_X, TRAIL_Y, TRAIL_L;
    static {
        TRAIL.moveTo(227, 150);
        TRAIL.curveTo(214, 164, 242, 172, 228, 186);
        TRAIL.curveTo(216, 198, 238, 206, 228, 222);
        java.util.List<double[]> pts = new ArrayList<>();
        double[] c = new double[6];
        double len = 0, lx = 0, ly = 0;
        for (java.awt.geom.PathIterator it = new java.awt.geom.FlatteningPathIterator(TRAIL.getPathIterator(null), 0.4); !it.isDone(); it.next()) {
            it.currentSegment(c);
            if (!pts.isEmpty()) len += Math.hypot(c[0] - lx, c[1] - ly);
            pts.add(new double[]{c[0], c[1], len});
            lx = c[0]; ly = c[1];
        }
        TRAIL_X = new double[pts.size()]; TRAIL_Y = new double[pts.size()]; TRAIL_L = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) { TRAIL_X[i] = pts.get(i)[0]; TRAIL_Y[i] = pts.get(i)[1]; TRAIL_L[i] = pts.get(i)[2]; }
    }

    /** The point a fraction u (0 = at the gate, 1 = at the bottom) of the way along the footpath. */
    private static Point2D trailPoint(double u) {
        double d = u * TRAIL_L[TRAIL_L.length - 1];
        int i = 1;
        while (i < TRAIL_L.length - 1 && TRAIL_L[i] < d) i++;
        double t = (d - TRAIL_L[i - 1]) / Math.max(1e-9, TRAIL_L[i] - TRAIL_L[i - 1]);
        return new Point2D.Double(TRAIL_X[i - 1] + (TRAIL_X[i] - TRAIL_X[i - 1]) * t, TRAIL_Y[i - 1] + (TRAIL_Y[i] - TRAIL_Y[i - 1]) * t);
    }

    private void playHeader(Graphics2D g, String crumb, String title) {
        g.setFont(Type.hud(19, true, 0.42f));
        Art.text(g, "MAIN MENU   /   ", 80, 56, new Color(163, 159, 196));
        Art.text(g, crumb, 80 + g.getFontMetrics().stringWidth("MAIN MENU   /   "), 56, GOLD);
        Shape t = new java.awt.font.TextLayout(title, Type.logo(62), FRC).getOutline(AffineTransform.getTranslateInstance(76, 140));
        AffineTransform keep = g.getTransform();
        g.translate(0, 4);
        g.setColor(GOLD_DK);
        g.fill(t);
        g.setTransform(keep);
        Rectangle2D b = t.getBounds2D();
        g.setPaint(new LinearGradientPaint(0, (float) b.getMinY(), 0, (float) b.getMaxY(), new float[]{0f, .42f, .72f, 1f},
            new Color[]{new Color(255, 243, 200), new Color(255, 211, 106), new Color(217, 150, 45), new Color(180, 116, 29)}));
        g.fill(t);
        g.setPaint(new LinearGradientPaint(80, 0, 840, 0, new float[]{0f, .08f, .6f, 1f},   // gold line with a diamond at its start
            new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 215), new Color(255, 205, 90, 90), new Color(255, 205, 90, 0)}));
        g.fill(new Rectangle2D.Double(80, 169, 760, 1.6));
        gilded(g, poly(94, 170, 103, 163, 112, 170, 103, 178), 163, 178);
    }

    /** A card frame: gold edge, a dark body that fades from top to bottom, and (drawn by the caller after the art) a thin inner line. Returns the body outline. */
    private Path2D playCard(Graphics2D g, double x, double y, double w, double h, Color top, Color bottom, boolean lit, Color glow) {
        if (lit && fx) Art.glow(g, glow, x + w / 2, y + h / 2, 300, 0.20f);
        Path2D outer = poly(x, y, x + w - 30, y, x + w, y + 30, x + w, y + h, x + 30, y + h, x, y + h - 30);
        g.setPaint(new LinearGradientPaint(0, (float) y, 0, (float) (y + h), new float[]{0f, .4f, 1f},
            new Color[]{new Color(255, 229, 138), new Color(240, 170, 46), new Color(138, 90, 20)}));
        g.fill(outer);
        Path2D body = poly(x + 2, y + 2, x + w - 30, y + 2, x + w - 2, y + 30, x + w - 2, y + h - 2, x + 30, y + h - 2, x + 2, y + h - 30);
        g.setPaint(new GradientPaint(0, (float) y, top, 0, (float) (y + h * 0.62), bottom));
        g.fill(body);
        return outer;
    }

    private static void cardInset(Graphics2D g, double x, double y, double w, double h) {
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 205, 90, 56));
        g.draw(poly(x + 9, y + 9, x + w - 33, y + 9, x + w - 9, y + 33, x + w - 9, y + h - 9, x + 33, y + h - 9, x + 9, y + h - 33));
    }

    private void drawPlay(Graphics2D g) {
        playHeader(g, "PLAY", "CHOOSE YOUR PATH");
        double ty = 196, tw = 458, th = 570;
        playTile(g, 0, 80, ty, tw, th);
        playTile(g, 1, 570, ty, tw, th);
        playKnightCard(g, 1060, ty, tw, th);
        if (!status.isEmpty()) {
            g.setFont(Type.hud(19, true, 0.04f));
            Art.text(g, status, 80, 800, statusError ? new Color(255, 130, 110) : GOLD_HI);
        }
        drawFooter(g, false);
    }

    private void playTile(Graphics2D g, int which, double x, double y, double w, double h) {
        boolean on = tile == which, host = which == 0;
        y += on ? -6 : 0;
        Path2D outer = playCard(g, x, y, w, h, host ? new Color(42, 15, 28) : new Color(11, 32, 70), host ? new Color(13, 7, 19) : new Color(8, 13, 30),
            on, host ? new Color(255, 90, 50) : new Color(70, 160, 255));
        hits.add(new Object[]{outer, (Runnable) () -> tile = which, which});
        double bx = x + 2, by = y + 2;

        Shape oldClip = g.getClip();
        g.clip(poly(x + 2, y + 2, x + w - 30, y + 2, x + w - 2, y + 30, x + w - 2, y + h - 2, x + 30, y + h - 2, x + 2, y + h - 30));
        AffineTransform keep = g.getTransform();
        g.translate(bx, by);
        if (host) beaconArt(g); else gateArt(g);
        g.setTransform(keep);
        g.setClip(oldClip);
        cardInset(g, x, y, w, h);

        shineTextLeft(g, host ? "HOST A GAME" : "JOIN A GAME", Type.royal(40, true, 0.14f), bx + 40, by + 300,
            host ? new Color[]{new Color(255, 217, 207), new Color(255, 154, 130), new Color(216, 106, 86)}
                 : new Color[]{new Color(230, 244, 255), new Color(159, 210, 255), new Color(90, 166, 236)});
        g.setFont(Type.hud(19, true, 0.01f));
        wrap(g, host ? "Light the beacon. Run the game on this computer and pick 1v1 to 4v4 in the lobby."
            : "March to a friend's castle. Type the address shown on their Play screen.", bx + 40, by + 342, w - 80, 28, new Color(213, 207, 230));

        if (host) {
            g.setFont(Type.hud(19, true, 0.12f));
            double lw = g.getFontMetrics().stringWidth("YOUR ADDRESS");
            g.setFont(Type.hud(19, true, 0.06f));
            double aw = g.getFontMetrics().stringWidth(lanAddress), pw = lw + 14 + aw + 36;
            RoundRectangle2D pill = new RoundRectangle2D.Double(bx + 40, by + 396, pw, 40, 5, 5);
            g.setColor(new Color(10, 6, 20, 180)); g.fill(pill);
            g.setStroke(new BasicStroke(2f)); g.setColor(new Color(255, 205, 90, 190)); g.draw(pill);
            g.setFont(Type.hud(19, true, 0.12f));
            Art.text(g, "YOUR ADDRESS", bx + 58, by + 423, new Color(255, 242, 200));
            g.setFont(Type.hud(19, true, 0.06f));
            Art.text(g, lanAddress, bx + 58 + lw + 14, by + 423, Color.WHITE);
            button(g, bx + 40, by + 498, 170, 54, busy && tile == 0 ? "..." : "HOST", false, () -> go(0));
        } else {
            playField(g, bx + 40, by + 394, 378, 54, address.toString(), !editName, false, () -> editName = false);
            g.setFont(Type.hud(16, true, 0.05f));
            double rx = bx + 40;
            String last = prefs.get("lastJoined", "");
            for (String r : last.isEmpty() || last.equals("localhost") ? new String[]{"localhost"} : new String[]{"localhost", last}) {
                double rw = g.getFontMetrics().stringWidth(r) + 24;
                Rectangle2D rr = new Rectangle2D.Double(rx, by + 458, rw, 28);
                g.setColor(new Color(10, 20, 40, 180)); g.fill(rr);
                g.setStroke(new BasicStroke(1.2f)); g.setColor(new Color(150, 200, 255, 130)); g.draw(rr);
                Art.text(g, r, rx + 12, by + 478, new Color(185, 217, 255));
                final String pick = r;
                hits.add(new Object[]{rr, (Runnable) () -> { address.setLength(0); address.append(pick); editName = false; }});
                rx += rw + 8;
            }
            button(g, bx + 40, by + 498, 156, 54, busy && tile == 1 ? "..." : "JOIN", true, () -> go(1));
            if (onlineServer != null)   // the cloud server from online-server.txt: play with anyone, anywhere
                button(g, bx + 208, by + 498, 190, 54, "ONLINE", false, () -> {
                    address.setLength(0);
                    address.append(onlineServer);
                    editName = false;
                    go(1);
                });
        }
    }

    private void playKnightCard(Graphics2D g, double x, double y, double w, double h) {
        playCard(g, x, y, w, h, new Color(28, 20, 52), new Color(10, 7, 22), false, GOLD);
        double bx = x + 2, by = y + 2;
        cardInset(g, x, y, w, h);
        g.setFont(Type.hud(17, true, 0.5f));
        Art.textCenter(g, "YOUR KNIGHT", x, w, by + 48, new Color(157, 139, 230));
        playKnight(g, bx + 108, by + 50);
        Color hc = LobbyRoom.HAT_COLORS[Math.floorMod(hatColor, LobbyRoom.HAT_COLORS.length)];
        playPicker(g, bx + 40, by + 366, 378, LobbyRoom.HAT_NAMES[hat].toUpperCase(), null, () -> stepHat(-1), () -> stepHat(1));
        playPicker(g, bx + 40, by + 424, 378, COLOR_NAMES[hatColor], hc, () -> hatColor = (hatColor + COLOR_NAMES.length - 1) % COLOR_NAMES.length,
            () -> hatColor = (hatColor + 1) % COLOR_NAMES.length);
        playField(g, bx + 40, by + 494, 378, 54, name.toString(), editName, true, () -> editName = true);
    }

    /** Your knight on a stone slab (x0, y0 = top-left of its 238 x 308 area): he breathes and sways, his eyes glow and blink, the halo floats and the wings flap. */
    private void playKnight(Graphics2D g, double x0, double y0) {
        AffineTransform keep = g.getTransform();
        double devH = keep.getScaleY();
        g.translate(x0, y0);
        g.scale(238 / 300.0, 308 / 388.0);   // the design's 300 x 388 drawing space
        if (fx) {   // the golden glow on the floor breathes
            AffineTransform k = g.getTransform();
            g.translate(150, 352);
            g.scale(1, 26 / 150.0);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 150, new float[]{0f, 1f},
                new Color[]{new Color(246, 198, 74, (int) (95 + 45 * Math.sin(time * 2.1))), new Color(246, 198, 74, 0)}));
            g.fill(new Ellipse2D.Double(-150, -150, 300, 300));
            g.setTransform(k);
        }
        Path2D slab = poly(58, 346, 242, 346, 226, 376, 74, 376);
        g.setPaint(new GradientPaint(0, 346, new Color(74, 66, 88), 0, 376, new Color(36, 28, 50))); g.fill(slab);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(new Color(14, 9, 18)); g.draw(slab);
        Path2D top = poly(50, 336, 250, 336, 242, 350, 58, 350);
        g.setColor(new Color(90, 80, 112)); g.fill(top); g.setColor(new Color(14, 9, 18)); g.draw(top);

        BufferedImage a = knightArt((int) Math.round(359 * (308 / 388.0) * devH), team == 1);
        if (a == null) { g.setTransform(keep); return; }
        double u = (time % 3.6) / 3.6;   // idle: bob, lean and breathe
        double ty = keyed(u, 0, -4, -3, 0), rot = keyed(u, 0, 0.9, -0.9, 0), sy = keyed(u, 1, 1.012, 1.01, 1);
        g.translate(163, 379 + ty);
        g.rotate(Math.toRadians(rot));
        g.scale(1, sy);
        g.translate(-163, -379);
        g.translate(24, 20);
        AffineTransform pic = g.getTransform();
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.scale(278.1 / a.getWidth(), 358.8 / a.getHeight());
        g.drawImage(a, 0, 0, null);
        if (hint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        g.setTransform(pic);
        g.scale(0.3153, 0.3153);   // from here on: the knight picture's own pixels (882 x 1138)
        if (fx) {
            double ga = 0.45 + 0.5 * (0.5 + 0.5 * Math.sin(time * 2 * Math.PI / 2.2));
            for (double[] e : new double[][]{{468, 411, 74}, {601, 411, 64}}) {
                g.setPaint(new RadialGradientPaint(new Point2D.Double(e[0], e[1]), (float) e[2], new float[]{0f, 1f},
                    new Color[]{new Color(255, 210, 60, (int) (190 * ga)), new Color(255, 210, 60, 0)}));
                g.fill(new Ellipse2D.Double(e[0] - e[2], e[1] - e[2], 2 * e[2], 2 * e[2]));
            }
        }
        double ph = (time % 4.8) / 4.8, lid = ph < 0.92 ? 0 : ph < 0.94 ? (ph - 0.92) / 0.02 : ph < 0.97 ? 1 : ph < 0.99 ? 1 - (ph - 0.97) / 0.02 : 0;
        if (lid > 0) {   // a blink: the visor closes over the eyes for a moment
            g.setColor(new Color(11, 7, 16, (int) (255 * lid)));
            g.fill(new RoundRectangle2D.Double(418, 390, 102, 42, 8, 8));
            g.fill(new RoundRectangle2D.Double(558, 390, 86, 42, 8, 8));
        }
        knightHat(g, hat, LobbyRoom.HAT_COLORS[Math.floorMod(hatColor, LobbyRoom.HAT_COLORS.length)], time);
        g.setTransform(keep);
    }

    /** Smooth value at u (0..1) through four key values at 0, .3, .7 and 1. */
    private static double keyed(double u, double v0, double v1, double v2, double v3) {
        double[] t = {0, 0.3, 0.7, 1}, v = {v0, v1, v2, v3};
        int i = u < 0.3 ? 0 : u < 0.7 ? 1 : 2;
        double f = (u - t[i]) / (t[i + 1] - t[i]), e = 0.5 - 0.5 * Math.cos(Math.PI * f);
        return v[i] + (v[i + 1] - v[i]) * e;
    }

    /** A text box with a gold frame; the caret blinks while it has the keyboard. */
    private void playField(Graphics2D g, double x, double y, double w, double h, String text, boolean focused, boolean centred, Runnable onClick) {
        Rectangle2D r = new Rectangle2D.Double(x, y, w, h);
        g.setColor(new Color(4, 3, 10)); g.fill(r);
        if (focused && fx) Art.glow(g, new Color(255, 205, 90), x + w / 2, y + h / 2, 150, 0.16f);
        g.setStroke(new BasicStroke(2f));
        g.setColor(focused ? new Color(255, 229, 138) : new Color(255, 205, 90, 205)); g.draw(r);
        g.setFont(centred ? Type.royal(26, true, 0.18f) : Type.hud(24, true, 0.06f));
        FontMetrics fm = g.getFontMetrics();
        String shown = centred ? text.toUpperCase() : text;
        double tx = centred ? x + (w - fm.stringWidth(shown)) / 2 : x + 16;
        Art.text(g, shown, tx, y + h / 2 + 9, Color.WHITE);
        if (focused && ((int) (time * 2) & 1) == 0) {
            g.setColor(GOLD);
            g.fill(new Rectangle2D.Double(tx + fm.stringWidth(shown) + 3, y + 13, 2, h - 26));
        }
        hits.add(new Object[]{r, onClick});
    }

    private void playPicker(Graphics2D g, double x, double y, double w, String value, Color dot, Runnable prev, Runnable next) {
        for (int side = 0; side < 2; side++) {
            Rectangle2D a = new Rectangle2D.Double(side == 0 ? x : x + w - 50, y, 50, 50);
            g.setColor(new Color(10, 6, 20, 180)); g.fill(a);
            g.setStroke(new BasicStroke(1.5f)); g.setColor(new Color(255, 205, 90, 180)); g.draw(a);
            g.setFont(Type.royal(26, true, 0f));
            Art.textCenter(g, side == 0 ? "‹" : "›", a.getX(), 50, y + 34, GOLD);
            final Runnable r = side == 0 ? prev : next;
            hits.add(new Object[]{a, (Runnable) () -> { r.run(); save(); }});
        }
        g.setFont(Type.royal(25, true, 0.2f));
        FontMetrics fm = g.getFontMetrics();
        double tw = fm.stringWidth(value), tx = x + (w - tw) / 2 + (dot != null ? 15 : 0);
        Art.text(g, value, tx, y + 34, Color.WHITE);
        if (dot != null) {
            Ellipse2D d = new Ellipse2D.Double(tx - 30, y + 16, 18, 18);
            if (fx) Art.glow(g, dot, tx - 21, y + 25, 16, 0.5f);
            g.setColor(dot); g.fill(d);
            g.setStroke(new BasicStroke(2f)); g.setColor(Color.WHITE); g.draw(d);
        }
    }

    /** Left-aligned text in a vertical colour ramp (the card titles). */
    private static void shineTextLeft(Graphics2D g, String text, Font font, double x, double base, Color[] ramp) {
        double adv = new java.awt.font.TextLayout(text, font, FRC).getAdvance();
        shineText(g, text, font, x + adv / 2, base, ramp, new Color(0, 0, 0, 0), -1);
    }

    /**
     * The Host card's picture: a beacon tower on a hill at dusk. 454 x 232, origin at the top-left of the card body.
     * The flame waves, and sparks rise from it.
     */
    private void beaconArt(Graphics2D g) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, 232, new float[]{0f, .6f, 1f}, new Color[]{new Color(58, 16, 32), new Color(138, 42, 36), new Color(216, 105, 58)}));
        g.fill(new Rectangle2D.Double(0, 0, 454, 232));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(227, 96), 120, new float[]{0f, 1f},
            new Color[]{new Color(255, 179, 71, (int) (190 + 30 * Math.sin(time * 5.7))), new Color(255, 122, 26, 0)}));
        g.fill(new Rectangle2D.Double(107, 0, 240, 232));
        Path2D far = new Path2D.Double();
        far.moveTo(0, 176); far.quadTo(80, 140, 150, 160); far.quadTo(225, 180, 300, 150); far.quadTo(380, 120, 454, 168); far.lineTo(454, 232); far.lineTo(0, 232); far.closePath();
        g.setColor(new Color(26, 10, 20)); g.fill(far);
        Path2D near = new Path2D.Double();
        near.moveTo(0, 200); near.quadTo(120, 176, 240, 192); near.quadTo(360, 208, 454, 190); near.lineTo(454, 232); near.lineTo(0, 232); near.closePath();
        g.setColor(new Color(18, 8, 16)); g.fill(near);
        AffineTransform keep = g.getTransform();   // the tower
        g.translate(227, 70);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Rectangle2D shaft = new Rectangle2D.Double(-34, 40, 68, 72);
        g.setColor(new Color(44, 22, 34)); g.fill(shaft); g.setColor(new Color(13, 5, 16)); g.draw(shaft);
        Path2D crown = poly(-44, 40, -44, 22, -30, 22, -30, 30, -12, 30, -12, 22, 12, 22, 12, 30, 30, 30, 30, 22, 44, 22, 44, 40);
        g.setColor(new Color(58, 29, 44)); g.fill(crown); g.setColor(new Color(13, 5, 16)); g.draw(crown);
        Path2D door = new Path2D.Double();
        door.moveTo(-12, 112); door.lineTo(-12, 80); door.quadTo(0, 68, 12, 80); door.lineTo(12, 112); door.closePath();
        g.setColor(new Color(7, 3, 8)); g.fill(door);
        gilded(g, new RoundRectangle2D.Double(-14, 26, 28, 8, 4, 4), 26, 34);
        g.setTransform(keep);
        waveFlame(g, 227, 98);
        g.setColor(new Color(202, 160, 68)); g.fill(new RoundRectangle2D.Double(34, 22, 38, 5, 4, 4));   // a small red banner on the left
        Path2D cloth = poly(36, 27, 70, 27, 70, 92, 53, 106, 36, 92);
        g.setColor(new Color(168, 34, 46)); g.fill(cloth);
        g.setStroke(new BasicStroke(3)); g.setColor(GOLD_EDGE); g.draw(cloth);
        g.setColor(GOLD_EDGE); g.fill(new Ellipse2D.Double(46, 49, 14, 14));
    }

    /** The Join card's picture: a castle gate at night with a footpath that marches toward it and a light that walks up it. */
    private void gateArt(Graphics2D g) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, 232, new float[]{0f, .65f, 1f}, new Color[]{new Color(10, 28, 74), new Color(22, 64, 138), new Color(47, 120, 200)}));
        g.fill(new Rectangle2D.Double(0, 0, 454, 232));
        g.setColor(new Color(223, 234, 255, 230)); g.fill(new Ellipse2D.Double(300, 32, 36, 36));   // a crescent moon
        g.setColor(new Color(22, 64, 138)); g.fill(new Ellipse2D.Double(310, 31, 30, 30));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(227, 96), 110, new float[]{0f, 1f},
            new Color[]{new Color(127, 208, 255, (int) (125 + 25 * Math.sin(time * 5.7))), new Color(127, 208, 255, 0)}));
        g.fill(new Rectangle2D.Double(107, 0, 240, 232));
        Path2D far = new Path2D.Double();
        far.moveTo(0, 168); far.quadTo(110, 126, 215, 150); far.quadTo(330, 174, 454, 138); far.lineTo(454, 232); far.lineTo(0, 232); far.closePath();
        g.setColor(new Color(7, 16, 38)); g.fill(far);
        Path2D near = new Path2D.Double();
        near.moveTo(0, 204); near.quadTo(140, 178, 260, 194); near.quadTo(380, 210, 454, 186); near.lineTo(454, 232); near.lineTo(0, 232); near.closePath();
        g.setColor(new Color(5, 10, 26)); g.fill(near);
        AffineTransform keep = g.getTransform();   // the gate
        g.translate(227, 40);
        Path2D outer = new Path2D.Double();
        outer.moveTo(-48, 108); outer.lineTo(-48, 50); outer.quadTo(-48, 8, 0, 8); outer.quadTo(48, 8, 48, 50); outer.lineTo(48, 108); outer.closePath();
        g.setColor(new Color(13, 26, 62)); g.fill(outer);
        g.setStroke(new BasicStroke(3)); g.setColor(new Color(10, 16, 48)); g.draw(outer);
        Path2D arch = new Path2D.Double();
        arch.moveTo(-34, 100); arch.lineTo(-34, 54); arch.quadTo(-34, 24, 0, 24); arch.quadTo(34, 24, 34, 54); arch.lineTo(34, 100); arch.closePath();
        g.setColor(new Color(18, 48, 94)); g.fill(arch);
        g.setColor(new Color(127, 208, 255, (int) (40 + 25 * Math.sin(time * 2.5)))); g.fill(arch);
        g.setColor(new Color(127, 208, 255)); g.draw(arch);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new Line2D.Double(0, 24, 0, 100)); g.draw(new Line2D.Double(-34, 62, 34, 62));
        g.setColor(new Color(10, 16, 48));
        g.fill(poly(-52, 108, -52, 96, -36, 96, -36, 108)); g.fill(poly(36, 108, 36, 96, 52, 96, 52, 108));
        g.setTransform(keep);
        g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{3, 9}, (float) (time * 17 % 12)));   // the dots march toward the gate
        g.setColor(new Color(127, 208, 255));
        g.draw(TRAIL);
        Point2D p = trailPoint(1 - (time % 3.4) / 3.4);   // a light walks up the path
        g.setPaint(new RadialGradientPaint(p, 9, new float[]{0f, 1f}, new Color[]{new Color(127, 208, 255, 190), new Color(127, 208, 255, 0)}));
        g.fill(new Ellipse2D.Double(p.getX() - 9, p.getY() - 9, 18, 18));
        g.setColor(new Color(230, 246, 255)); g.fill(new Ellipse2D.Double(p.getX() - 3.2, p.getY() - 3.2, 6.4, 6.4));
        g.setColor(new Color(202, 160, 68)); g.fill(new RoundRectangle2D.Double(396, 22, 38, 5, 4, 4));   // the blue banner on the right
        Path2D cloth = poly(398, 27, 432, 27, 432, 92, 415, 106, 398, 92);
        g.setColor(new Color(42, 79, 191)); g.fill(cloth);
        g.setStroke(new BasicStroke(3)); g.setColor(GOLD_EDGE); g.draw(cloth);
        g.setColor(GOLD_EDGE); g.fill(new Ellipse2D.Double(408, 49, 14, 14));
    }

    /**
     * The cloud server's address, from online-server.txt next to the game (one line, e.g. 140.238.1.2:5000).
     * Empty or missing: there is no online server yet, and the ONLINE button stays hidden.
     */
    static String readOnlineServer(java.nio.file.Path file) {
        try {
            for (String line : java.nio.file.Files.readAllLines(file)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) return s;
            }
        } catch (java.io.IOException ignored) {
            // no file: no online server
        }
        return null;
    }

    /** A text box; the caret blinks while it has the keyboard. */
    private void field(Graphics2D g, double x, double y, double w, String text, boolean focused, boolean centred, Runnable onClick) {
        Rectangle2D r = new Rectangle2D.Double(x, y, w, 42);
        g.setColor(new Color(0, 0, 0, 130)); g.fill(r);
        g.setStroke(new BasicStroke(focused ? 1.8f : 1.2f));
        g.setColor(focused ? (centred ? GOLD : BLUE_FX) : new Color(170, 150, 235, 110)); g.draw(r);
        g.setFont(centred ? Type.royal(24, true, 0.12f) : Type.hud(22, true, 0.06f));
        FontMetrics fm = g.getFontMetrics();
        String shown = centred ? text.toUpperCase() : text;
        double tx = centred ? x + (w - fm.stringWidth(shown)) / 2 : x + 14;
        Art.text(g, shown, tx, y + 29, Color.WHITE);
        if (focused && ((int) (time * 2) & 1) == 0) {
            g.setColor(centred ? GOLD : BLUE_FX);
            g.fill(new Rectangle2D.Double(tx + fm.stringWidth(shown) + 3, y + 10, 2, 22));
        }
        hits.add(new Object[]{r, onClick});
    }

    private void picker(Graphics2D g, double x, double y, double w, String value, Runnable prev, Runnable next) {
        for (int side = 0; side < 2; side++) {
            Rectangle2D a = new Rectangle2D.Double(side == 0 ? x : x + w - 40, y, 40, 40);
            g.setColor(new Color(255, 205, 90, 130)); g.setStroke(new BasicStroke(1.2f)); g.draw(a);
            g.setFont(Type.royal(22, true, 0f));
            Art.textCenter(g, side == 0 ? "‹" : "›", a.getX(), 40, y + 28, GOLD);
            final Runnable r = side == 0 ? prev : next;
            hits.add(new Object[]{a, (Runnable) () -> { r.run(); save(); }});
        }
        g.setFont(Type.royal(21, true, 0.14f));
        Art.textCenter(g, value, x, w, y + 28, Color.WHITE);
    }


    // ------------------------------------------------------------------ HOW TO PLAY

    private static final Color[] STEAL_RAMP = {new Color(255, 217, 207), new Color(255, 154, 130), new Color(216, 106, 86)},
        RETURN_RAMP = {new Color(255, 241, 196), new Color(255, 205, 90), new Color(216, 154, 42)},
        DEFEND_RAMP = {new Color(230, 244, 255), new Color(159, 210, 255), new Color(90, 166, 236)};

    private void drawHowTo(Graphics2D g) {
        playHeader(g, "HOW TO PLAY", "HOW TO PLAY");
        String[] titles = {"STEAL", "RETURN", "DEFEND"};
        String[] text = {"Run into the enemy base and touch their flag to pick it up.",
            "Carry it to your own base while your flag is safe at home. First to 3 captures wins.",
            "Shoot the carrier to drop the flag. Touch your own dropped flag to send it home."};
        Color[][] ramp = {STEAL_RAMP, RETURN_RAMP, DEFEND_RAMP};
        Color[][] body = {{new Color(42, 15, 28), new Color(13, 7, 19)}, {new Color(31, 21, 56), new Color(12, 8, 23)}, {new Color(11, 32, 70), new Color(8, 13, 30)}};
        double[] xs = {80, 570, 1060};
        for (int i = 0; i < 3; i++) {
            double x = xs[i], y = 196, w = 458, h = 456;
            playCard(g, x, y, w, h, body[i][0], body[i][1], false, GOLD);
            Shape oldClip = g.getClip();
            g.clip(poly(x + 2, y + 2, x + w - 30, y + 2, x + w - 2, y + 30, x + w - 2, y + h - 2, x + 30, y + h - 2, x + 2, y + h - 30));
            AffineTransform keep = g.getTransform();
            g.translate(x + 2, y + 2);
            if (i == 0) stealArt(g); else if (i == 1) returnArt(g); else defendArt(g);
            g.setTransform(keep);
            g.setClip(oldClip);
            cardInset(g, x, y, w, h);
            Font tf = Type.royal(40, true, 0.14f);
            shineTextLeft(g, titles[i], tf, x + 42, y + 292, ramp[i]);
            g.setFont(Type.hud(19, true, 0.01f));
            wrap(g, text[i], x + 42, y + 336, w - 80, 28, new Color(213, 207, 230));
        }

        // the controls, in one gold strip
        double sx = 80, sy = 672, sw = 1440, sh = 72, cut = 26;
        g.setPaint(new LinearGradientPaint(0, (float) sy, 0, (float) (sy + sh), new float[]{0f, .5f, 1f},
            new Color[]{new Color(255, 229, 138), new Color(201, 138, 34), new Color(106, 69, 16)}));
        g.fill(poly(sx, sy, sx + sw - cut, sy, sx + sw, sy + cut, sx + sw, sy + sh, sx + cut, sy + sh, sx, sy + sh - cut));
        g.setPaint(new GradientPaint(0, (float) sy, new Color(23, 16, 40), 0, (float) (sy + sh), new Color(11, 7, 22)));
        g.fill(poly(sx + 2, sy + 2, sx + sw - cut, sy + 2, sx + sw - 2, sy + cut, sx + sw - 2, sy + sh - 2, sx + cut, sy + sh - 2, sx + 2, sy + sh - cut));
        double x = sx + 40;
        String[][] keys = {{"WASD", "move"}, {"MOUSE", "aim and fire"}, {"TAB", "scores"}, {"ENTER", "chat"}, {"R", "ready (lobby)"},
            {"E / C", "wardrobe"}, {"F11", "fullscreen"}};
        for (String[] k : keys) {
            g.setFont(Type.hud(16, true, 0.04f));
            double kw = g.getFontMetrics().stringWidth(k[0]) + 22;
            RoundRectangle2D cap = new RoundRectangle2D.Double(x, sy + sh / 2 - 15, kw, 30, 7, 7);
            g.setColor(new Color(255, 205, 90, 18)); g.fill(cap);
            g.setStroke(new BasicStroke(1.5f)); g.setColor(new Color(255, 205, 90, 185)); g.draw(cap);
            Art.textCenter(g, k[0], x, kw, sy + sh / 2 + 6, new Color(255, 233, 176));
            g.setFont(Type.hud(21, true, 0.03f));
            Art.text(g, k[1], x + kw + 10, sy + sh / 2 + 7, new Color(228, 224, 246));
            x += kw + 10 + g.getFontMetrics().stringWidth(k[1]) + 30;
        }

        // the tent tip
        double ty = 760;
        Path2D tent = poly(102, ty, 120, ty + 32, 84, ty + 32);
        g.setColor(new Color(90, 42, 160)); g.fill(tent);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(new Color(212, 178, 255)); g.draw(tent);
        g.setColor(new Color(26, 12, 56)); g.fill(poly(102, ty + 12, 110, ty + 32, 94, ty + 32));
        g.setColor(new Color(233, 210, 255, (int) (160 + 95 * Math.sin(time * 4.5)))); g.fill(new Ellipse2D.Double(99, ty + 1, 6, 6));
        g.setFont(Type.hud(21, true, 0.02f));
        Art.text(g, "The purple tent gives a 5 second shield.", 134, ty + 25, new Color(201, 168, 255));
        drawFooter(g, false);
    }

    private static void dimGround(Graphics2D g, Color a, Color b, Color c, Color d) {   // sky of a card picture (454 x 230)
        g.setPaint(new LinearGradientPaint(0, 0, 0, 230, new float[]{0f, .6f, 1f}, new Color[]{a, b, c}));
        g.fill(new Rectangle2D.Double(0, 0, 454, 230));
    }

    private static void hills(Graphics2D g, Color far, Color near) {
        Path2D f = new Path2D.Double();
        f.moveTo(0, 176); f.quadTo(90, 146, 170, 164); f.quadTo(250, 182, 330, 156); f.quadTo(400, 134, 454, 172); f.lineTo(454, 230); f.lineTo(0, 230); f.closePath();
        g.setColor(far); g.fill(f);
        Path2D n = new Path2D.Double();
        n.moveTo(0, 204); n.quadTo(130, 184, 250, 196); n.quadTo(360, 208, 454, 194); n.lineTo(454, 230); n.lineTo(0, 230); n.closePath();
        g.setColor(near); g.fill(n);
    }

    /** STEAL: the enemy flag waving on its base. */
    private void stealArt(Graphics2D g) {
        dimGround(g, new Color(54, 16, 42), new Color(122, 36, 56), new Color(200, 96, 58), null);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(227, 100), 120, new float[]{0f, 1f},
            new Color[]{new Color(255, 179, 71, (int) (150 + 25 * Math.sin(time * 5.7))), new Color(255, 122, 26, 0)}));
        g.fill(new Rectangle2D.Double(107, 0, 240, 230));
        hills(g, new Color(26, 10, 20), new Color(18, 8, 16));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{10, 14}, (float) (24 - time * 24 % 24)));
        g.setColor(new Color(56, 200, 255));
        g.draw(new Ellipse2D.Double(139, 164, 176, 44));
        AffineTransform flagKeep = g.getTransform();   // the flag, a little smaller, standing on the middle of the ring
        g.translate(227, 188);
        g.scale(0.78, 0.78);
        g.translate(-227, -188);
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(214, 210, 230)); g.fill(new Rectangle2D.Double(224, 62, 7, 126));
        g.setColor(new Color(13, 5, 16)); g.draw(new Rectangle2D.Double(224, 62, 7, 126));
        g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(219.5, 50, 16, 16));
        g.setColor(new Color(13, 5, 16)); g.draw(new Ellipse2D.Double(219.5, 50, 16, 16));
        double k = 0.5 - 0.5 * Math.cos(2 * Math.PI * time / 1.6);   // the cloth ripples between two shapes
        Path2D cloth = new Path2D.Double();
        cloth.moveTo(231, 66); cloth.curveTo(262, 52 + 28 * k, 290, 82 - 30 * k, 322, 68); cloth.lineTo(312, 96); cloth.lineTo(322, 124);
        cloth.curveTo(290, 138 - 30 * k, 262, 108 + 30 * k, 231, 122); cloth.closePath();
        g.setColor(new Color(42, 79, 191)); g.fill(cloth);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(new Color(13, 5, 16)); g.draw(cloth);
        Path2D shine = new Path2D.Double();
        shine.moveTo(236, 74); shine.curveTo(262, 62 + 26 * k, 284, 86 - 24 * k, 310, 76);
        g.setColor(new Color(159, 224, 255, 190)); g.draw(shine);
        g.setTransform(flagKeep);
    }

    /** RETURN: the crown carried home along a dashed arrow, to a glowing base. */
    private void returnArt(Graphics2D g) {
        dimGround(g, new Color(26, 18, 56), new Color(74, 36, 96), new Color(160, 64, 88), null);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(227, 186), 140, new float[]{0f, 1f},
            new Color[]{new Color(255, 106, 60, (int) (150 + 25 * Math.sin(time * 5.7))), new Color(255, 106, 60, 0)}));
        g.fill(new Rectangle2D.Double(87, 120, 280, 110));
        Path2D hill = new Path2D.Double();
        hill.moveTo(0, 192); hill.quadTo(130, 172, 250, 186); hill.quadTo(370, 200, 454, 180); hill.lineTo(454, 230); hill.lineTo(0, 230); hill.closePath();
        g.setColor(new Color(18, 8, 16)); g.fill(hill);
        double pulse = 1 + 0.06 * (0.5 - 0.5 * Math.cos(2 * Math.PI * time / 1.8));
        Ellipse2D base = new Ellipse2D.Double(227 - 98 * pulse, 190 - 24 * pulse, 196 * pulse, 48 * pulse);
        g.setColor(new Color(74, 20, 38)); g.fill(base);
        g.setStroke(new BasicStroke(3.5f)); g.setColor(new Color(255, 90, 60)); g.draw(base);
        crown(g, 227, 100 - 6 * (0.5 - 0.5 * Math.cos(2 * Math.PI * time / 2.6)), 1.15);
        g.setStroke(new BasicStroke(6, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{12, 12}, (float) (24 - time * 24 % 24)));   // the dashes run toward the arrow head
        g.setColor(new Color(255, 211, 106));
        g.draw(new Line2D.Double(132, 134, 300, 134));
        Path2D head = poly(300, 116, 336, 134, 300, 152);
        g.setColor(new Color(255, 211, 106)); g.fill(head);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(new Color(106, 69, 16)); g.draw(head);
    }

    /** DEFEND: a crosshair, its rings turning slowly. */
    private void defendArt(Graphics2D g) {
        dimGround(g, new Color(10, 28, 74), new Color(22, 64, 138), new Color(47, 120, 200), null);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(227, 108), 120, new float[]{0f, 1f},
            new Color[]{new Color(127, 208, 255, (int) (125 + 25 * Math.sin(time * 5.7))), new Color(127, 208, 255, 0)}));
        g.fill(new Rectangle2D.Double(107, 0, 240, 230));
        Path2D hill = new Path2D.Double();
        hill.moveTo(0, 196); hill.quadTo(130, 174, 250, 188); hill.quadTo(370, 202, 454, 180); hill.lineTo(454, 230); hill.lineTo(0, 230); hill.closePath();
        g.setColor(new Color(5, 10, 26)); g.fill(hill);
        AffineTransform keep = g.getTransform();
        g.translate(227, 108);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{46, 14}, 0));
        g.setColor(new Color(56, 200, 255, 140));
        g.rotate(time * 2 * Math.PI / 14);
        g.draw(new Ellipse2D.Double(-82, -82, 164, 164));
        g.setTransform(keep);
        g.translate(227, 108);
        g.setStroke(new BasicStroke(4.5f)); g.setColor(new Color(56, 200, 255));
        g.draw(new Ellipse2D.Double(-68, -68, 136, 136));
        g.rotate(-time * 2 * Math.PI / 9);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{14, 8}, 0));
        g.setColor(new Color(127, 224, 255));
        g.draw(new Ellipse2D.Double(-26, -26, 52, 52));
        g.rotate(time * 2 * Math.PI / 9);
        g.setStroke(new BasicStroke(4)); g.setColor(new Color(56, 200, 255));
        g.draw(new Ellipse2D.Double(-22, -22, 44, 44));
        g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(0, -96, 0, -48)); g.draw(new Line2D.Double(0, 48, 0, 96));
        g.draw(new Line2D.Double(-96, 0, -48, 0)); g.draw(new Line2D.Double(48, 0, 96, 0));
        g.setColor(new Color(255, 90, 60, (int) (140 + 115 * (0.5 + 0.5 * Math.cos(2 * Math.PI * time)))));
        g.fill(new Ellipse2D.Double(-7, -7, 14, 14));
        g.setTransform(keep);
    }

    // ------------------------------------------------------------------ SETTINGS

    private static final double SLIDER_W = 280;
    private double sliderX;

    private void drawSettings(Graphics2D g) {
        playHeader(g, "SETTINGS", "SETTINGS");
        Color top = new Color(23, 16, 40), bottom = new Color(11, 7, 22);
        double y = 196, h = 560;

        playCard(g, 80, y, 700, h, top, bottom, false, GOLD);
        cardInset(g, 80, y, 700, h);
        double bx = 82, by = y + 2;
        shineTextLeft(g, "DISPLAY", Type.royal(32, true, 0.16f), bx + 44, by + 66, new Color[]{new Color(255, 241, 196), new Color(255, 205, 90), new Color(216, 154, 42)});
        sliderX = 0;
        settingsToggle(g, bx + 44, by + 104, 608, "Fullscreen", actions.fullscreen(), actions::toggleFullscreen);
        settingsToggle(g, bx + 44, by + 188, 608, "Visual effects", fx, () -> fx = !fx);
        settingsToggle(g, bx + 44, by + 272, 608, "Screen shake", shake, () -> shake = !shake);
        g.setFont(Type.hud(19, true, 0.02f));
        wrap(g, "Effects are the glows, sparks and fireworks. The game turns them off by itself if your computer is too slow.",
            bx + 44, by + 410, 608, 30, new Color(169, 163, 198));

        playCard(g, 810, y, 710, h, top, bottom, false, GOLD);
        cardInset(g, 810, y, 710, h);
        bx = 812;
        shineTextLeft(g, "SOUND AND NETWORK", Type.royal(32, true, 0.16f), bx + 44, by + 66, new Color[]{new Color(255, 241, 196), new Color(255, 205, 90), new Color(216, 154, 42)});
        sliderX = bx + 706 - 44 - SLIDER_W;
        settingsSlider(g, 0, bx + 44, by + 104, 618, "Sound volume", volume, Math.round(volume * 100) + "%");
        settingsSlider(g, 1, bx + 44, by + 188, 618, "Test delay", lagDelay / 300.0, lagDelay + " ms");
        settingsSlider(g, 2, bx + 44, by + 272, 618, "Test packet loss", lagLoss / 30.0, lagLoss + "%");
        g.setFont(Type.hud(19, true, 0.02f));
        wrap(g, "The two test sliders fake a bad network for demos: extra delay each way and lost packets. Both apply to the next game you join.",
            bx + 44, by + 410, 618, 30, new Color(169, 163, 198));
        drawFooter(g, false);
    }

    private void settingsToggle(Graphics2D g, double x, double y, double w, String label, boolean on, Runnable flip) {
        g.setFont(Type.hud(26, true, 0.03f));
        Art.text(g, label, x, y + 51, new Color(239, 234, 255));
        RoundRectangle2D t = new RoundRectangle2D.Double(x + w - 62, y + 26, 62, 32, 32, 32);
        if (on && fx) Art.glow(g, new Color(91, 229, 138), x + w - 31, y + 42, 40, 0.30f);
        g.setColor(on ? new Color(29, 90, 54) : new Color(36, 29, 60)); g.fill(t);
        g.setStroke(new BasicStroke(1.8f));
        g.setColor(on ? new Color(91, 229, 138) : new Color(170, 150, 235, 153)); g.draw(t);
        g.setColor(on ? new Color(125, 255, 158) : new Color(182, 174, 220));
        g.fill(new Ellipse2D.Double(on ? x + w - 30 : x + w - 58, y + 30, 24, 24));
        g.setColor(new Color(255, 205, 90, 41));
        g.fill(new Rectangle2D.Double(x, y + 83, w, 1));
        hits.add(new Object[]{new Rectangle2D.Double(x, y, w, 84), (Runnable) () -> { flip.run(); save(); }});
    }

    private void settingsSlider(Graphics2D g, int id, double x, double y, double w, String label, double k, String value) {
        g.setFont(Type.hud(26, true, 0.03f));
        Art.text(g, label, x, y + 51, new Color(239, 234, 255));
        g.setFont(Type.hud(22, true, 0.03f));
        Art.textCenter(g, value, sliderX - 100, 80, y + 50, Color.WHITE);
        double ty = y + 42;
        g.setColor(new Color(44, 37, 72));
        g.fill(new RoundRectangle2D.Double(sliderX, ty - 2.5, SLIDER_W, 5, 5, 5));
        g.setPaint(new GradientPaint((float) sliderX, 0, new Color(255, 205, 90), (float) (sliderX + SLIDER_W), 0, new Color(255, 224, 138)));
        g.fill(new RoundRectangle2D.Double(sliderX, ty - 2.5, Math.max(5, SLIDER_W * k), 5, 5, 5));
        double kx = sliderX + SLIDER_W * k;
        if (fx) Art.glow(g, new Color(255, 200, 90), kx, ty, 18, 0.5f);
        g.setColor(new Color(255, 240, 196)); g.fill(new Ellipse2D.Double(kx - 11, ty - 11, 22, 22));
        g.setStroke(new BasicStroke(2.5f)); g.setColor(new Color(240, 170, 46)); g.draw(new Ellipse2D.Double(kx - 11, ty - 11, 22, 22));
        g.setColor(new Color(255, 205, 90, 41));
        g.fill(new Rectangle2D.Double(x, y + 83, w, 1));
        hits.add(new Object[]{new Rectangle2D.Double(sliderX - 14, y, SLIDER_W + 28, 84), (Runnable) () -> {
            dragSlider = id;
            setSlider(id, Math.max(0, Math.min(1, (mouse.getX() - sliderX) / SLIDER_W)));
        }});
    }

    private void setSlider(int id, double k) {
        switch (id) {
            case 0 -> { volume = (float) (Math.round(k * 20) / 20.0); Sfx.volume = volume; Music.setVolume(volume); }   // the music follows the volume too
            case 1 -> lagDelay = (int) Math.round(k * 30) * 10;
            default -> lagLoss = (int) Math.round(k * 30);
        }
    }

    // ------------------------------------------------------------------ CREDITS (empty for now, as asked)

    // ------------------------------------------------------------------ PROFILE (docs/profile-design.html)

    /** Next or previous hat this player has unlocked; the crown and top hat are always free. */
    private void stepHat(int dir) {
        do hat = Math.floorMod(hat + dir, LobbyMap.HATS); while (!profile.hatUnlocked(hat));
    }

    private void stepTitle(int dir) {
        do title = Math.floorMod(title + dir, Profile.TITLES.length); while (!profile.titleUnlocked(title));
    }

    private void drawProfilePage(Graphics2D g) {
        if (stats == null) stats = profile.stats();
        pageTitle(g, "PROFILE", "Your legend");
        Color line = new Color(255, 205, 90, 80);

        // left: who you are (you edit this)
        double lx = 100, ly = 176, lw = 400, lh = 660, lcx = lx + lw / 2;
        panel(g, lx, ly, lw, lh, 26, line, null);
        g.setFont(Type.hud(15, true, 0.3f));
        Art.textCenter(g, "YOUR KNIGHT", lx, lw, ly + 40, VIOLET);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(lcx, ly + 246), 100, new float[]{0f, 1f},
            new Color[]{new Color(255, 205, 90, 110), new Color(255, 205, 90, 0)}));
        g.fill(new Ellipse2D.Double(lcx - 100, ly + 234, 200, 24));
        knight(g, lcx, ly + 86, 7, 1);
        field(g, lx + 40, ly + 272, lw - 80, name.toString(), true, true, () -> editName = true);
        g.setFont(Type.hud(17, true, 0.14f));
        Art.textCenter(g, Profile.TITLES[title], lx, lw, ly + 344, GOLD_HI);

        double rx = lx + 26, rw = lw - 52;
        fieldLabel(g, "HAT", rx, ly + 392);
        picker(g, rx + 86, ly + 366, rw - 86, LobbyRoom.HAT_NAMES[hat].toUpperCase(), () -> stepHat(-1), () -> stepHat(1));
        fieldLabel(g, "COLOUR", rx, ly + 446);
        for (int i = 0; i < LobbyRoom.HAT_COLORS.length; i++) {   // colour swatches
            double sx = rx + 104 + i * 42, sy = ly + 438;
            Ellipse2D.Double sw = new Ellipse2D.Double(sx - 14, sy - 14, 28, 28);
            g.setColor(LobbyRoom.HAT_COLORS[i]);
            g.fill(sw);
            if (i == hatColor) { g.setColor(Color.WHITE); g.setStroke(new BasicStroke(2.2f)); g.draw(new Ellipse2D.Double(sx - 18, sy - 18, 36, 36)); g.setStroke(new BasicStroke(1)); }
            final int c = i;
            hits.add(new Object[]{sw.getBounds2D(), (Runnable) () -> { hatColor = c; save(); }});
        }
        fieldLabel(g, "TITLE", rx, ly + 502);
        picker(g, rx + 86, ly + 476, rw - 86, Profile.TITLES[title], () -> stepTitle(-1), () -> stepTitle(1));
        fieldLabel(g, "TEAM", rx, ly + 556);
        String[] teams = {"RED", "BLUE", "ANY"};
        Color[] tc = {new Color(255, 155, 140), new Color(156, 202, 255), SOFT};
        for (int i = 0; i < 3; i++) {
            int value = i == 2 ? -1 : i;
            Rectangle2D.Double b = new Rectangle2D.Double(rx + 96 + i * 92, ly + 532, 82, 34);
            boolean on = team == value;
            g.setColor(on ? new Color(tc[i].getRed(), tc[i].getGreen(), tc[i].getBlue(), 40) : new Color(0, 0, 0, 0));
            g.fill(b);
            g.setColor(on ? tc[i] : new Color(170, 150, 235, 90));
            g.draw(b);
            g.setFont(Type.royal(15, true, 0.14f));
            Art.textCenter(g, teams[i], b.x, b.width, b.y + 23, on ? tc[i] : MUTED);
            hits.add(new Object[]{b, (Runnable) () -> { team = value; save(); }});
        }
        button(g, lx + 30, ly + lh - 72, lw - 60, "SAVE", false, () -> { save(); savedAt = time; stats = profile.stats(); });
        if (time - savedAt < 2) {
            g.setFont(Type.hud(16, true, 0.2f));
            Art.textCenter(g, "SAVED", lx, lw, ly + lh - 84, new Color(110, 230, 130));
        }

        // right: what the game fills in
        double px = 528, pw = 972;
        panel(g, px, 176, pw, 118, 22, line, null);
        double bcx = px + 70, bcy = 235;   // level badge
        g.setPaint(new LinearGradientPaint(0, (float) (bcy - 40), 0, (float) (bcy + 40), new float[]{0f, .5f, 1f}, new Color[]{GOLD_HI, GOLD, GOLD_LO}));
        Path2D hex = poly(bcx, bcy - 42, bcx + 37, bcy - 21, bcx + 37, bcy + 21, bcx, bcy + 42, bcx - 37, bcy + 21, bcx - 37, bcy - 21);
        g.fill(hex);
        g.setColor(GOLD_DK); g.setStroke(new BasicStroke(3)); g.draw(hex); g.setStroke(new BasicStroke(1));
        g.setFont(Type.hud(36, true, 0f));
        Art.textCenter(g, String.valueOf(stats.level), bcx - 40, 80, bcy + 13, new Color(26, 15, 2));
        double bx = px + 140, bw = pw - 180;
        g.setFont(Type.royal(24, true, 0.1f));
        Art.text(g, "LEVEL " + stats.level, bx, 222, Color.WHITE);
        g.setFont(Type.hud(18, true, 0.1f));
        String xpText = stats.xpInto + " / " + stats.xpNeed + " XP";
        Art.text(g, xpText, bx + bw - g.getFontMetrics().stringWidth(xpText), 220, SOFT);
        g.setColor(new Color(255, 255, 255, 20));
        g.fill(new Rectangle2D.Double(bx, 234, bw, 14));
        double frac = stats.xpNeed == 0 ? 0 : (double) stats.xpInto / stats.xpNeed;
        g.setPaint(new LinearGradientPaint((float) bx, 0, (float) (bx + bw), 0, new float[]{0f, .6f, 1f}, new Color[]{GOLD_LO, GOLD, GOLD_HI}));
        g.fill(new Rectangle2D.Double(bx, 234, bw * frac, 14));
        g.setColor(new Color(255, 205, 90, 90));
        g.draw(new Rectangle2D.Double(bx, 234, bw, 14));
        String reward = stats.level < 3 ? " · Level 3 unlocks the KNIGHT title" : stats.level < 5 ? " · Level 5 unlocks the CHAMPION title"
            : stats.level < 10 ? " · Level 10 unlocks the LEGEND title" : "";
        g.setFont(Type.hud(16, false, 0.04f));
        Art.text(g, (stats.xpNeed - stats.xpInto) + " XP to Level " + (stats.level + 1) + reward, bx, 274, MUTED);

        String[] keys = {"MATCHES", "WINS", "WIN RATE", "CAPTURES", "TAGS", "K / D", "MVP"};
        String[] vals = {stats.matches + "", stats.wins + "", stats.matches == 0 ? "-" : Math.round(100.0 * stats.wins / stats.matches) + "%",
            stats.captures + "", stats.tags + "", stats.matches == 0 ? "-" : String.format("%.1f", (double) stats.tags / Math.max(1, stats.deaths)), stats.mvps + ""};
        double tw = (pw - 6 * 13) / 7;
        for (int i = 0; i < 7; i++) {
            double tx = px + i * (tw + 13);
            g.setColor(new Color(20, 14, 40, 190));
            g.fill(new Rectangle2D.Double(tx, 314, tw, 88));
            g.setColor(new Color(170, 150, 235, 80));
            g.draw(new Rectangle2D.Double(tx, 314, tw, 88));
            g.setFont(Type.hud(36, true, 0f));
            Art.textCenter(g, vals[i], tx, tw, 362, i == 2 || i == 6 ? GOLD : Color.WHITE);
            g.setFont(Type.hud(13, true, 0.2f));
            Art.textCenter(g, keys[i], tx, tw, 388, MUTED);
        }

        // recent matches
        double hx = px, hy = 426, hw = 528, hh = 410;
        panel(g, hx, hy, hw, hh, 22, line, null);
        g.setFont(Type.hud(15, true, 0.3f));
        Art.text(g, "RECENT MATCHES", hx + 26, hy + 36, VIOLET);
        if (!profile.ok()) {
            g.setFont(Type.hud(17, true, 0.04f));
            wrap(g, "Match history needs the database library (lib/sqlite-jdbc). Start the game with play.ps1 or run_client.bat.", hx + 26, hy + 80, hw - 52, 24, MUTED);
        } else if (stats.history.isEmpty()) {
            g.setFont(Type.hud(18, true, 0.04f));
            Art.text(g, "No matches yet. Play one and it shows up here.", hx + 26, hy + 80, MUTED);
        }
        double ry = hy + 56;
        for (int i = 0; i < Math.min(8, stats.history.size()); i++) {
            Object[] m = stats.history.get(i);   // {won, score, mode, length, C, T, D, mvp}
            boolean won = (Boolean) m[0];
            Rectangle2D.Double chip = new Rectangle2D.Double(hx + 26, ry + 8, 62, 26);
            g.setColor(won ? new Color(110, 230, 130, 30) : new Color(235, 70, 70, 30));
            g.fill(chip);
            g.setColor(won ? new Color(110, 230, 130, 160) : new Color(255, 155, 140, 150));
            g.draw(chip);
            g.setFont(Type.hud(14, true, 0.14f));
            Art.textCenter(g, won ? "WIN" : "LOSS", chip.x, chip.width, chip.y + 18, won ? new Color(110, 230, 130) : new Color(255, 155, 140));
            g.setFont(Type.hud(18, true, 0.04f));
            String mode = m[2] + "v" + m[2];
            Art.text(g, (String) m[1], hx + 104, ry + 28, Color.WHITE);
            double sw = g.getFontMetrics().stringWidth((String) m[1]);
            Art.text(g, mode, hx + 116 + sw, ry + 28, SOFT);
            if ((Boolean) m[7]) {
                g.setFont(Type.hud(13, true, 0.16f));
                Art.text(g, "MVP", hx + 156 + sw, ry + 28, GOLD);
            }
            int len = (Integer) m[3];
            g.setFont(Type.hud(17, true, 0.02f));
            Art.text(g, len > 0 ? len / 60 + ":" + String.format("%02d", len % 60) : "-", hx + 330, ry + 28, SOFT);
            g.setFont(Type.hud(15, true, 0.08f));
            String ctd = "C" + m[4] + " T" + m[5] + " D" + m[6];
            Art.text(g, ctd, hx + hw - 26 - g.getFontMetrics().stringWidth(ctd), ry + 28, MUTED);
            g.setColor(new Color(110, 95, 160, 70));
            g.fill(new Rectangle2D.Double(hx + 26, ry + 42, hw - 52, 1));
            ry += 42;
        }

        // achievements
        double ax = 1082, ay = 426, aw = 418, ah = 410;
        panel(g, ax, ay, aw, ah, 22, line, null);
        g.setFont(Type.hud(15, true, 0.3f));
        Art.text(g, "ACHIEVEMENTS", ax + 26, ay + 36, VIOLET);
        g.setFont(Type.hud(15, true, 0.2f));
        Art.text(g, stats.unlocked.size() + " / " + Profile.ACHIEVEMENTS.length, ax + 200, ay + 36, GOLD);
        double cw = (aw - 52 - 2 * 10) / 3, chh = 92;
        for (int i = 0; i < Profile.ACHIEVEMENTS.length; i++) {
            String[] a = Profile.ACHIEVEMENTS[i];
            boolean got = stats.unlocked.contains(a[0]);
            double cx = ax + 26 + (i % 3) * (cw + 10), cy = ay + 54 + (i / 3) * (chh + 10);
            Composite keep = g.getComposite();
            if (!got) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.38f));
            g.setColor(got ? new Color(255, 205, 90, 18) : new Color(0, 0, 0, 0));
            g.fill(new Rectangle2D.Double(cx, cy, cw, chh));
            g.setColor(got ? new Color(255, 205, 90, 90) : new Color(170, 150, 235, 70));
            g.draw(new Rectangle2D.Double(cx, cy, cw, chh));
            achievementIcon(g, i, cx + cw / 2, cy + 28, got);
            g.setFont(Type.hud(13, true, 0.08f));
            Art.textCenter(g, a[1], cx, cw, cy + 64, Color.WHITE);
            g.setFont(Type.hud(12, false, 0.02f));
            Art.textCenter(g, a[2], cx, cw, cy + 82, got ? GOLD_HI : MUTED);
            g.setComposite(keep);
        }
        boolean armed = time - resetArmedAt < 3;
        g.setFont(Type.hud(15, true, 0.2f));
        String rs = armed ? "CLICK AGAIN TO DELETE ALL STATS" : "RESET STATS ›";
        double rsw = g.getFontMetrics().stringWidth(rs);
        Art.text(g, rs, ax + aw - 26 - rsw, ay + ah - 18, armed ? new Color(255, 110, 90) : new Color(255, 155, 140));
        hits.add(new Object[]{new Rectangle2D.Double(ax + aw - 36 - rsw, ay + ah - 40, rsw + 20, 30), (Runnable) () -> {
            if (time - resetArmedAt < 3) {
                profile.reset();
                title = profile.title();
                hat = profile.hat();
                stats = profile.stats();
                resetArmedAt = -10;
            } else resetArmedAt = time;
        }});
        drawFooter(g, false);
    }

    private void achievementIcon(Graphics2D g, int i, double x, double y, boolean got) {
        switch (i) {
            case 0 -> crown(g, x, y + 10, 0.5);
            case 1 -> Art.flagIcon(g, x, y, 30, new Color(235, 70, 70), time);
            case 2 -> {
                g.setColor(BLUE_FX);
                g.setStroke(new BasicStroke(2.5f));
                g.draw(new Ellipse2D.Double(x - 13, y - 13, 26, 26));
                g.setStroke(new BasicStroke(1));
                g.setColor(RED_FX);
                g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
            }
            default -> {
                Color[] c = {null, null, null, GOLD, new Color(110, 230, 130), RED_FX, VIOLET, new Color(70, 150, 255), GOLD};
                g.setColor(c[i]);
                Shape s = switch (i) {
                    case 3 -> poly(x, y - 15, x + 4.5, y - 4.5, x + 15, y - 3.5, x + 7, y + 4, x + 9.5, y + 15, x, y + 9, x - 9.5, y + 15, x - 7, y + 4, x - 15, y - 3.5, x - 4.5, y - 4.5);
                    case 4 -> poly(x - 15, y + 12, x - 6, y - 8, x, y + 3, x + 6, y - 13, x + 15, y + 12);
                    case 5 -> new Ellipse2D.Double(x - 10, y - 14, 20, 28);
                    case 6 -> new Ellipse2D.Double(x - 13, y - 13, 26, 26);
                    case 7 -> poly(x, y - 15, x + 15, y, x, y + 15, x - 15, y);
                    default -> poly(x, y - 15, x + 14, y + 13, x - 14, y + 13);
                };
                g.fill(s);
            }
        }
    }

    private void fieldLabel(Graphics2D g, String s, double x, double y) {
        g.setFont(Type.hud(13, true, 0.25f));
        Art.text(g, s, x, y, MUTED);
    }

    // ------------------------------------------------------------------ CREDITS (drawn live, in the menu's own style)

    private double creditsStart;   // menu time when the page was opened: the sections fade in one after another from here
    private static final java.awt.font.FontRenderContext FRC = new java.awt.font.FontRenderContext(null, true, true);
    private static final Color[] GOLD_RAMP = {GOLD_HI, GOLD, GOLD_LO}, SILVER_RAMP = {Color.WHITE, new Color(208, 212, 232), new Color(120, 126, 160)};
    private static final Color CREAM = new Color(255, 236, 200);

    /** Twinkling stars round the title and the names, on the 1600x900 canvas. */
    private static final double[][] SPARKS = {{610, 140}, {990, 150}, {800, 58}, {520, 300}, {1090, 300}, {470, 345}, {1135, 350},
        {533, 436}, {1067, 436}, {800, 470}, {470, 575}, {1130, 575}, {600, 700}, {1000, 700}};

    private static double ease(double t) { t = Math.max(0, Math.min(1, t)); return 1 - Math.pow(1 - t, 3); }

    /** 0..1 position of a band of light that passes every `period` seconds and takes `dur` seconds; more than 1 while idle. */
    private double sweep(double period, double dur, double offset) { return ((time + offset) % period) / dur; }

    /** Runs body fading in and rising 24 units; e = 0..1 progress of this section's entrance. */
    private void reveal(Graphics2D g, double e, Runnable body) {
        if (e <= 0) return;
        Composite keepC = g.getComposite();
        AffineTransform keep = g.getTransform();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) e));
        g.translate(0, (1 - e) * 24);
        body.run();
        g.setTransform(keep);
        g.setComposite(keepC);
    }

    /** Centred text in a vertical colour ramp with a soft shadow and, while 0 <= sweep <= 1, a band of light running over the letters. */
    private static void shineText(Graphics2D g, String text, Font font, double cx, double base, Color[] ramp, Color shadow, double sweep) {
        java.awt.font.TextLayout tl = new java.awt.font.TextLayout(text, font, FRC);
        Shape t = tl.getOutline(AffineTransform.getTranslateInstance(cx - tl.getAdvance() / 2, base));
        Rectangle2D b = t.getBounds2D();
        g.setColor(shadow);
        g.fill(AffineTransform.getTranslateInstance(0, 3).createTransformedShape(t));
        g.setPaint(new LinearGradientPaint(0, (float) b.getMinY(), 0, (float) b.getMaxY(), new float[]{0f, .5f, 1f}, ramp));
        g.fill(t);
        if (sweep >= 0 && sweep <= 1) {   // a second fill with a see-through band: the glyph edges stay smooth
            double w = Math.max(60, b.getHeight() * 1.6), x0 = b.getMinX() - w + sweep * (b.getWidth() + 2 * w);
            g.setPaint(new LinearGradientPaint((float) x0, (float) b.getMinY(), (float) (x0 + w), (float) (b.getMinY() + w * 0.5), new float[]{0f, .5f, 1f},
                new Color[]{new Color(255, 255, 255, 0), new Color(255, 252, 235, 215), new Color(255, 255, 255, 0)}));
            g.fill(t);
        }
    }

    private static void gilded(Graphics2D g, Shape s, double y0, double y1) {
        g.setPaint(new LinearGradientPaint(0, (float) y0, 0, (float) y1, new float[]{0f, .5f, 1f}, GOLD_RAMP));
        g.fill(s);
        g.setStroke(new BasicStroke(1.3f)); g.setColor(GOLD_DK); g.draw(s);
    }

    private static void person(Graphics2D g, double cx, double cy, double k) {   // head and shoulders, k = size
        gilded(g, new Ellipse2D.Double(cx - 0.30 * k, cy - 0.55 * k, 0.60 * k, 0.60 * k), cy - 0.55 * k, cy + 0.05 * k);
        gilded(g, new Arc2D.Double(cx - 0.55 * k, cy + 0.12 * k, 1.10 * k, 1.0 * k, 0, 180, Arc2D.CHORD), cy + 0.12 * k, cy + 0.62 * k);
    }

    private static void university(Graphics2D g, double cx, double cy, double k) {
        gilded(g, poly(cx - 1.0 * k, cy - 0.2 * k, cx, cy - 0.85 * k, cx + 1.0 * k, cy - 0.2 * k), cy - 0.85 * k, cy);
        gilded(g, new Rectangle2D.Double(cx - 1.0 * k, cy - 0.2 * k, 2.0 * k, 0.16 * k), cy - 0.2 * k, cy - 0.04 * k);
        for (int i = 0; i < 4; i++) gilded(g, new Rectangle2D.Double(cx - 0.8 * k + i * 0.52 * k, cy - 0.02 * k, 0.2 * k, 0.62 * k), cy, cy + 0.6 * k);
        gilded(g, new Rectangle2D.Double(cx - 1.06 * k, cy + 0.62 * k, 2.12 * k, 0.16 * k), cy + 0.62 * k, cy + 0.78 * k);
    }

    private static void crowd(Graphics2D g, double cx, double cy, double k) {
        person(g, cx - 0.85 * k, cy + 0.12 * k, 0.72 * k);
        person(g, cx + 0.85 * k, cy + 0.12 * k, 0.72 * k);
        person(g, cx, cy, k);
    }

    /** The raven mark: a swept wing of three feathers, drawn in a 100 x 58 box with its top-left at (x, y). */
    private static void raven(Graphics2D g, double x, double y) {
        AffineTransform keep = g.getTransform();
        g.translate(x, y);
        Path2D wing = path("M 0 34 C 18 4 58 -6 100 4 C 72 8 54 16 46 28 C 62 22 80 22 98 28 C 72 34 54 42 42 58 C 38 44 22 38 0 34 Z");
        g.setPaint(new LinearGradientPaint(0, 0, 0, 58, new float[]{0f, .5f, 1f}, SILVER_RAMP));
        g.fill(wing);
        g.setStroke(new BasicStroke(1.2f)); g.setColor(new Color(60, 66, 96)); g.draw(wing);
        g.setTransform(keep);
    }

    private static void flourish(Graphics2D g, double cx, double y, double half) {   // a line fading out at both ends, with a diamond in the middle
        g.setPaint(new LinearGradientPaint((float) (cx - half), 0, (float) (cx + half), 0, new float[]{0f, .5f, 1f},
            new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 200), new Color(255, 205, 90, 0)}));
        g.fill(new Rectangle2D.Double(cx - half, y, half * 2, 1.6));
        gilded(g, poly(cx - 9, y + 0.8, cx, y - 7, cx + 9, y + 0.8, cx, y + 8.6), y - 7, y + 9);
    }

    private static void tab(Graphics2D g, double cx, double y, double w, String text, Color c1, Color c2) {   // the small ribbon on top of a panel
        panel(g, cx - w / 2, y, w, 34, 10, new Color(255, 205, 90, 220), new GradientPaint(0, (float) y, c1, 0, (float) (y + 34), c2));
        g.setFont(Type.royal(17, true, 0.22f));
        Art.textCenter(g, text, cx - w / 2, w, y + 24, CREAM);
    }

    private void section(Graphics2D g, double x, double y, double w, double h) {   // a framed panel whose edge glows softly
        panel(g, x, y, w, h, 22, new Color(255, 205, 90, (int) (130 + 50 * Math.sin(time * 1.8 + y * 0.01))), null);
        g.setStroke(new BasicStroke(1f)); g.setColor(new Color(255, 205, 90, 55));
        g.draw(poly(x + 7, y + 7, x + w - 22, y + 7, x + w - 7, y + 22, x + w - 7, y + h - 7, x + 22, y + h - 7, x + 7, y + h - 22));
    }

    private static BufferedImage lionPic;
    private static boolean lionTried;

    /** The gold lion lifted from the tabard of the knight picture (assets/lion.png); null if missing. */
    private static synchronized BufferedImage lion() {
        if (!lionTried) {
            lionTried = true;
            try { lionPic = javax.imageio.ImageIO.read(new java.io.File("assets/lion.png")); } catch (Exception e) { lionPic = null; }
        }
        return lionPic;
    }

    private static void lion(Graphics2D g, double x, double y, double k) {
        BufferedImage l = lion();
        if (l == null) return;
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(l, (int) Math.round(x), (int) Math.round(y), (int) Math.round(l.getWidth() * k), (int) Math.round(l.getHeight() * k), null);
        if (hint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);   // a fresh image graphics has none set
    }

    private static final Color INK = new Color(77, 50, 6);

    /** A hanging banner at x (its left edge) that sways a little. */
    private void banner(Graphics2D g, double x, boolean blue, double phase) {
        AffineTransform keep = g.getTransform();
        g.translate(x + 70, 12);
        g.shear(0.025 * Math.sin(time * 1.1 + phase), 0);
        g.translate(-70, -12);
        g.setColor(new Color(202, 160, 68));
        g.fill(new RoundRectangle2D.Double(-4, 0, 148, 12, 8, 8));
        g.setStroke(new BasicStroke(2)); g.setColor(INK); g.draw(new RoundRectangle2D.Double(-4, 0, 148, 12, 8, 8));
        Path2D body = poly(0, 12, 140, 12, 140, 226, 70, 262, 0, 226);
        g.setPaint(new GradientPaint(0, 12, blue ? new Color(42, 84, 196) : new Color(179, 32, 44), 0, 262, blue ? new Color(20, 40, 110) : new Color(95, 15, 28)));
        g.fill(body);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(INK); g.draw(body);
        g.setStroke(new BasicStroke(5)); g.setColor(new Color(242, 181, 53));
        g.draw(poly(10, 22, 130, 22, 130, 218, 70, 250, 10, 218));
        lion(g, 34, 66, 0.7);
        g.setTransform(keep);
    }

    /** A wall torch whose flame flickers; (x, y) is the top of its post. */
    private void torch(Graphics2D g, double x, double y, double phase) {
        Path2D post = poly(x - 6, y, x + 6, y, x + 10, y + 90, x - 10, y + 90);
        g.setColor(new Color(60, 47, 44)); g.fill(post);
        g.setStroke(new BasicStroke(3)); g.setColor(new Color(18, 12, 16)); g.draw(post);
        gilded(g, poly(x - 26, y - 4, x + 26, y - 4, x + 18, y + 30, x - 18, y + 30), y - 4, y + 30);
        gilded(g, new RoundRectangle2D.Double(x - 30, y - 10, 60, 9, 8, 8), y - 10, y - 1);
        double flick = 1 + 0.06 * Math.sin(time * 13 + phase) + 0.04 * Math.sin(time * 29 + phase * 2);
        if (fx) Art.glow(g, new Color(255, 150, 40), x, y - 40, 110, (float) (0.26 + 0.10 * Math.sin(time * 11 + phase) + 0.05 * Math.sin(time * 23 + phase)));
        AffineTransform keep = g.getTransform();
        g.translate(x, y - 8);
        g.scale(1.25 / flick, 1.25 * flick);   // taller and a touch thinner when it leaps
        g.setColor(new Color(255, 106, 20)); g.fill(path("M 0 8 C -24 -4 -26 -34 -8 -58 C -6 -42 4 -40 6 -62 C 26 -42 30 -10 0 8 Z"));
        g.setColor(new Color(255, 176, 46)); g.fill(path("M 0 6 C -15 -4 -16 -26 -4 -42 C -2 -30 4 -30 6 -46 C 18 -30 18 -8 0 6 Z"));
        g.setColor(new Color(255, 242, 168)); g.fill(path("M 0 4 C -8 -3 -8 -16 -1 -26 C 2 -16 7 -14 6 -4 C 5 1 2 3 0 4 Z"));
        g.setTransform(keep);
    }

    /** A flag on a pole at px; dir = 1 waves to the right, -1 to the left. */
    private void flag(Graphics2D g, double px, int dir, boolean blue, double phase) {
        g.setColor(new Color(202, 160, 68)); g.fill(new Rectangle2D.Double(px - 4, 400, 8, 420));
        g.setStroke(new BasicStroke(2)); g.setColor(INK); g.draw(new Rectangle2D.Double(px - 4, 400, 8, 420));
        gilded(g, new Ellipse2D.Double(px - 9, 385, 18, 18), 385, 403);
        AffineTransform keep = g.getTransform();
        g.translate(px, 410);
        g.scale(dir, 1);
        double a = 10 * Math.sin(time * 2.3 + phase);
        Path2D cloth = new Path2D.Double();
        cloth.moveTo(4, 0); cloth.curveTo(60, -14 + a, 100, 14 - a, 156, 0); cloth.lineTo(140, 52); cloth.lineTo(156, 104);
        cloth.curveTo(100, 118 - a, 60, 90 + a, 4, 104); cloth.closePath();
        g.setPaint(new GradientPaint(0, 0, blue ? new Color(42, 84, 196) : new Color(179, 32, 44), 0, 104, blue ? new Color(20, 40, 110) : new Color(95, 15, 28)));
        g.fill(cloth);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(INK); g.draw(cloth);
        Path2D trim = new Path2D.Double();
        trim.moveTo(14, 10); trim.curveTo(62, a * 0.8, 98, 22 - a * 0.8, 142, 10);
        g.setStroke(new BasicStroke(4)); g.setColor(new Color(242, 181, 53)); g.draw(trim);
        g.translate(46, 20 + a * 0.3);
        lion(g, 0, 0, 0.5);
        g.setTransform(keep);
    }

    private static void pedestal(Graphics2D g, double x, double w) {
        Path2D base = poly(x, 818, x + w, 818, x + w - 26, 884, x + 26, 884);
        g.setColor(new Color(42, 34, 52)); g.fill(base);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(new Color(14, 9, 18)); g.draw(base);
        Path2D slab = poly(x - 10, 800, x + w + 10, 800, x + w, 826, x, 826);
        g.setColor(new Color(74, 66, 88)); g.fill(slab);
        g.setColor(new Color(14, 9, 18)); g.draw(slab);
        g.setColor(new Color(21, 14, 27));
        g.draw(new Line2D.Double(x + 30, 826, x + 70, 884)); g.draw(new Line2D.Double(x + 150, 826, x + 130, 884)); g.draw(new Line2D.Double(x + 240, 826, x + 262, 884));
    }

    /** Your knight (red or blue picture) holding its gun, with the picture's top-left at (x, y); mirror turns it to face left. */
    private void creditsKnight(Graphics2D g, double x, double y, boolean blue, boolean mirror) {
        double h = 398, bob = Math.sin(time * 1.8 + (blue ? 1 : 0)) * 2.5;
        BufferedImage a = knightArt((int) Math.round(h * g.getTransform().getScaleY()), blue);
        if (a == null) return;
        double w = h * a.getWidth() / a.getHeight();
        AffineTransform keep = g.getTransform();
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.translate(x, y + bob);
        if (mirror) { g.translate(w, 0); g.scale(-1, 1); }
        g.scale(w / a.getWidth(), h / a.getHeight());
        g.drawImage(a, 0, 0, null);
        g.setTransform(keep);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        // the gun is held across the chest and tilted a little up toward the middle of the page
        double k = 199.0 / 32, th = Math.toRadians(mirror ? 7 : -7), dx = (mirror ? -4 : 4) * k, dy = -5.1 * k;
        double px = mirror ? x + w - 98 : x + 98, py = y + bob + 218;   // where the gun picture's top-left corner lands
        g.translate(px - (dx * Math.cos(th) - dy * Math.sin(th)), py - (dx * Math.sin(th) + dy * Math.cos(th)));
        g.rotate(th);
        g.scale(mirror ? -k : k, k);
        Art.gunArt(g, blue ? 1 : 0, 0, false);   // no gun picture: the knight simply stands unarmed
        g.setTransform(keep);
        if (fx) Art.glow(g, new Color(255, 210, 60), mirror ? x + w - 192 : x + 192, y + bob + 141, 42, (float) (0.14 + 0.08 * Math.sin(time * 2.6 + (blue ? 1 : 0))));   // the eyes breathe
    }

    private void creditsScenery(Graphics2D g, double ct) {
        reveal(g, ease(ct / 0.8), () -> {
            if (fx) {
                Art.glow(g, new Color(255, 138, 60), 220, 560, 260, 0.16f);
                Art.glow(g, new Color(79, 168, 255), 1380, 560, 260, 0.14f);
            }
            banner(g, 190, false, 0);
            banner(g, 1270, true, 1.7);
            torch(g, 112, 250, 0);
            torch(g, 1488, 250, 2.1);
            flag(g, 66, 1, false, 0.6);
            flag(g, 1534, -1, true, 2.4);
            pedestal(g, 60, 330);
            pedestal(g, 1210, 330);
            creditsKnight(g, 70, 408, false, false);
            creditsKnight(g, 1221, 408, true, true);
        });
    }

    private void drawCredits(Graphics2D g) {
        double ct = time - creditsStart, cx = VW / 2.0;
        creditsScenery(g, ct);

        reveal(g, ease(ct / 0.7), () -> {
            if (fx) Art.glow(g, GOLD, cx, 150, 230, (float) (0.10 + 0.05 * Math.sin(time * 1.7)));
            crown(g, cx, 94 + Math.sin(time * 1.6) * 2, 0.8);
            shineText(g, "CREDITS", Type.logo(66), cx, 188, GOLD_RAMP, GOLD_DK, sweep(9.0, 2.8, 1.0));   // the one slow shine on the page
            flourish(g, cx, 212, 400);
        });

        reveal(g, ease((ct - 0.3) / 0.7), () -> {
            g.setPaint(new LinearGradientPaint((float) (cx - 260), 0, (float) (cx - 80), 0, new float[]{0f, 1f}, new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 160)}));
            g.fill(new Rectangle2D.Double(cx - 260, 238, 180, 1.4));
            g.setPaint(new LinearGradientPaint((float) (cx + 80), 0, (float) (cx + 260), 0, new float[]{0f, 1f}, new Color[]{new Color(255, 205, 90, 160), new Color(255, 205, 90, 0)}));
            g.fill(new Rectangle2D.Double(cx + 80, 238, 180, 1.4));
            g.setFont(Type.hud(15, true, 0.5f));
            Art.textCenter(g, "A GAME BY", 0, VW, 244, new Color(255, 205, 90, 210));
        });

        reveal(g, ease((ct - 0.45) / 0.7), () -> {
            Font f = Type.royal(56, true, 0.12f);
            g.setFont(f);
            double tw = g.getFontMetrics().stringWidth("RAVEN SL"), x0 = cx - (100 + 22 + tw) / 2;
            raven(g, x0, 260);
            shineText(g, "RAVEN SL", f, x0 + 122 + tw / 2, 306, SILVER_RAMP, new Color(0, 0, 0, 170), -1);
        });

        reveal(g, ease((ct - 0.7) / 0.7), () -> {   // the three developers
            section(g, 400, 352, 800, 156);
            tab(g, cx, 335, 330, "DEVELOPMENT TEAM", new Color(150, 26, 40), new Color(88, 12, 26));
            String[] names = {"BENUKA", "DILMI", "SENUK"};
            for (int i = 0; i < 3; i++) {
                double x = 533 + i * 267;
                shineText(g, names[i], Type.royal(34, true, 0.16f), x, 444, GOLD_RAMP, GOLD_DK, -1);   // just the names, centred in the panel
            }
            g.setPaint(new LinearGradientPaint(0, 395, 0, 495, new float[]{0f, .5f, 1f}, new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 130), new Color(255, 205, 90, 0)}));
            g.fill(new Rectangle2D.Double(666, 395, 1.4, 100));
            g.fill(new Rectangle2D.Double(933, 395, 1.4, 100));
        });

        reveal(g, ease((ct - 0.95) / 0.7), () -> {   // the lecturer
            section(g, 440, 548, 720, 70);
            tab(g, cx, 531, 200, "LECTURER", new Color(24, 50, 120), new Color(10, 22, 70));
            shineText(g, "DR. NADEESHA SANDAMALI", Type.royal(28, true, 0.14f), cx, 594, GOLD_RAMP, GOLD_DK, -1);
        });

        reveal(g, ease((ct - 1.2) / 0.7), () -> {   // special thanks
            section(g, 400, 662, 800, 96);
            tab(g, cx, 645, 240, "SPECIAL THANKS", new Color(24, 50, 120), new Color(10, 22, 70));
            university(g, 466, 708, 24);
            g.setFont(Type.royal(20, true, 0.06f));
            Art.text(g, "UNIVERSITY OF RUHUNA", 505, 706, new Color(255, 215, 120));
            g.setFont(Type.hud(14, true, 0.3f));
            Art.text(g, "FACULTY OF ENGINEERING", 505, 730, SOFT);
            g.setPaint(new LinearGradientPaint(0, 676, 0, 748, new float[]{0f, .5f, 1f}, new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 130), new Color(255, 205, 90, 0)}));
            g.fill(new Rectangle2D.Double(858, 676, 1.4, 72));
            crowd(g, 912, 708, 22);
            g.setFont(Type.hud(15, true, 0.25f));
            Art.text(g, "EVERYONE WHO", 968, 695, new Color(255, 215, 120));
            Art.text(g, "SUPPORTED US", 968, 713, new Color(255, 215, 120));
            g.setFont(Type.hud(12, true, 0.15f));
            Art.text(g, "DURING THE DEVELOPMENT", 968, 732, SOFT);
        });

        reveal(g, ease((ct - 1.45) / 0.7), () -> button(g, cx - 150, 782, 300, "BACK TO MENU", false, this::back));

        for (int i = 0; i < SPARKS.length; i++) {   // twinkling stars
            double a = Math.pow(Math.max(0, Math.sin(time * 2.1 + i * 1.7)), 4), r = 3 + 8 * a;
            if (a < 0.05 || ct < 1.0) continue;
            double x = SPARKS[i][0], y = SPARKS[i][1];
            Path2D.Double star = new Path2D.Double();
            star.moveTo(x, y - r); star.quadTo(x, y, x + r, y); star.quadTo(x, y, x, y + r); star.quadTo(x, y, x - r, y); star.quadTo(x, y, x, y - r);
            g.setColor(new Color(255, 248, 215, (int) (230 * a)));
            g.fill(star);
            if (fx) Art.glow(g, new Color(255, 220, 120), x, y, 14, (float) (0.5 * a));
        }
        drawFooter(g, false);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * This computer's address on the local network, to show on the Host tile.
     * shortcut: takes the first private IPv4 of an active, non-virtual adapter; a PC on several networks may show the wrong one.
     */
    static String findLanAddress() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || n.contains("virtual") || n.contains("vmware") || n.contains("hyper-v")
                        || n.contains("vethernet") || n.contains("bluetooth")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {
            // no network information: fall through
        }
        return "localhost";
    }
}

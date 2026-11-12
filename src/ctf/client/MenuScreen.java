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
    private static final String[] HINTS = {"Host a game or join a friend", "Your level, stats and history", "Rules, controls and tips",
        "Display, sound and network test", "The people behind the throne", "Leave the throne room"};
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

    private BufferedImage back, backPage, fogImg, logoImg;   // cached still pictures (see ensureCache)
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
            default -> actions.quit();
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
        drawScene(g, vx, vy, vw, vh, page == Page.MAIN);
        switch (page) {
            case MAIN -> drawMain(g);
            case PLAY -> drawPlay(g);
            case PROFILE -> drawProfilePage(g);
            case HOWTO -> drawHowTo(g);
            case SETTINGS -> drawSettings(g);
            case CREDITS -> drawCredits(g);
        }
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
        Font lf = Type.logo(84);
        logoShape = new TextLayout("THRONEFALL", lf, new java.awt.font.FontRenderContext(null, true, true))
            .getOutline(AffineTransform.getTranslateInstance(100, 242));
        back = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D b = layer(back, sc, vx, vy);
        paintRoom(b, vx, vy, vw, vh, true);
        b.dispose();
        backPage = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        b = layer(backPage, sc, vx, vy);
        paintRoom(b, vx, vy, vw, vh, false);
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

    /** The still room: wall, window, and on the title screen the moonlight, pillars, steps, throne and logo glow; then the vignette. */
    private void paintRoom(Graphics2D b, double vx, double vy, double vw, double vh, boolean throneRoom) {
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
        if (throneRoom) {
            b.setPaint(new GradientPaint(0, 60, new Color(217, 200, 255, 95), 0, 540, new Color(156, 132, 255, 0)));   // moonlight
            b.fill(poly(312, 60, 378, 60, 520, 540, 170, 540));
            b.fill(poly(330, 60, 350, 60, 380, 540, 280, 540));
            paintThrone(b);
        }
        b.setTransform(base);
        b.setPaint(new RadialGradientPaint(new Point2D.Double(960, 405), 1050, new float[]{0f, .4f, 1f},   // vignette
            new Color[]{new Color(2, 1, 6, 0), new Color(2, 1, 6, 0), new Color(2, 1, 6, 220)}));
        b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        if (throneRoom) {
            b.setPaint(new LinearGradientPaint((float) vx, 0, 830, 0, new float[]{0f, .6f, 1f},
                new Color[]{new Color(3, 2, 8, 235), new Color(3, 2, 8, 140), new Color(3, 2, 8, 0)}));
            b.fill(new Rectangle2D.Double(vx, vy, 830 - vx, vh));
            Rectangle2D lb = logoShape.getBounds2D();
            Art.glow(b, new Color(255, 190, 80), lb.getCenterX(), lb.getCenterY(), 300, 0.16f);
        } else {   // sub-pages: a calmer, darker room so the panels read clearly
            b.setColor(new Color(4, 3, 10, 120));
            b.fill(new Rectangle2D.Double(vx, vy, vw, vh));
        }
    }

    private static void paintThrone(Graphics2D m) {   // art space
        for (int x : new int[]{120, 512}) {   // pillars
            m.setPaint(new GradientPaint(0, 0, new Color(45, 35, 71), 0, 540, new Color(17, 12, 32)));
            m.fill(new Rectangle2D.Double(x, -40, 58, 580));
            m.setColor(new Color(58, 45, 92)); m.fill(new Rectangle2D.Double(x - 6, -40, 70, 62));
        }
        double[][] steps = {{150, 470, 390, 90, 0x0d0918, 0x3a2d5c}, {190, 430, 310, 44, 0x140e24, 0x4a3a74}, {226, 396, 238, 38, 0x1a1230, 0x5a4890}};
        for (double[] st : steps) {
            m.setColor(new Color((int) st[4])); m.fill(new Rectangle2D.Double(st[0], st[1], st[2], st[3]));
            m.setColor(new Color((int) st[5])); m.fill(new Rectangle2D.Double(st[0], st[1], st[2], 4));
        }
        Path2D throne = new Path2D.Double();
        throne.moveTo(270, 400); throne.lineTo(270, 250); throne.quadTo(270, 196, 300, 176); throne.lineTo(345, 150);
        throne.lineTo(390, 176); throne.quadTo(420, 196, 420, 250); throne.lineTo(420, 400); throne.closePath();
        m.setPaint(new GradientPaint(0, 150, new Color(29, 21, 49), 0, 400, new Color(7, 5, 13))); m.fill(throne);
        m.setStroke(new BasicStroke(3)); m.setColor(new Color(78, 61, 128)); m.draw(throne);
        Path2D back2 = new Path2D.Double();
        back2.moveTo(296, 250); back2.quadTo(296, 214, 345, 196); back2.quadTo(394, 214, 394, 250); back2.lineTo(394, 340); back2.lineTo(296, 340); back2.closePath();
        m.setColor(new Color(58, 15, 34, 230)); m.fill(back2);
        for (int x : new int[]{250, 406}) {
            m.setColor(new Color(24, 16, 41)); m.fill(new Rectangle2D.Double(x, 320, 34, 80));
            m.setColor(new Color(78, 61, 128)); m.draw(new Rectangle2D.Double(x, 320, 34, 80));
        }
        m.setColor(new Color(36, 24, 56)); m.fill(new Rectangle2D.Double(282, 338, 126, 24));
        m.setStroke(new BasicStroke(2)); m.setColor(new Color(106, 85, 168)); m.draw(new Rectangle2D.Double(282, 338, 126, 24));
        m.setColor(GOLD); m.fill(new Ellipse2D.Double(338, 158, 14, 14));
    }

    private static Graphics2D layer(BufferedImage im, double sc, double vx, double vy) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(sc, sc);
        g.translate(-vx, -vy);
        return g;
    }

    private void drawScene(Graphics2D g, double vx, double vy, double vw, double vh, boolean withThrone) {
        Scenery.blit1to1(g, withThrone ? back : backPage, vx, vy);
        if (withThrone) {
            AffineTransform keep = g.getTransform();
            g.translate(AX, AY);
            g.scale(AS, AS);
            banner(g, 196, new Color(200, 48, 58), new Color(110, 18, 28), 0);
            banner(g, 430, new Color(47, 111, 214), new Color(15, 43, 102), 1.6);
            double flick = 0.8 + 0.2 * Math.sin(time * 9) * Math.sin(time * 5.3);
            if (fx) Art.glow(g, new Color(255, 200, 110), 345, 118, 95, (float) (0.55 * flick));
            AffineTransform c = g.getTransform();
            g.translate(0, Math.sin(time * 1.4) * 6);
            crown(g, 345, 132, 1);
            g.setTransform(c);
            for (int x : new int[]{149, 541}) {   // torches on the pillars
                if (fx) Art.glow(g, new Color(255, 140, 50), x, 240, 70, (float) (0.6 * flick));
                g.setColor(new Color(58, 45, 92)); g.fill(new Rectangle2D.Double(x - 8, 252, 16, 10));
                Art.torchFlame(g, x, 254, time + x, 1.0, fx);
            }
            g.setTransform(keep);
        }
        Scenery.blit1to1(g, fogImg, -(time * 16 % VW), 640);   // fog drifts slowly to the left
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

    /** A hanging team banner that sways a little; x is its left edge in art space. */
    private void banner(Graphics2D g, double x, Color top, Color bottom, double phase) {
        AffineTransform keep = g.getTransform();
        g.translate(x + 33, 40);
        g.shear(Math.sin(time * 1.9 + phase) * 0.04, 0);
        g.setPaint(new GradientPaint(0, 0, top, 0, 190, bottom));
        g.fill(poly(-33, 0, 33, 0, 33, 190, 0, 165, -33, 190));
        g.setColor(GOLD);
        g.fill(new Rectangle2D.Double(-33, 0, 66, 12));
        g.setColor(new Color(255, 205, 90, 215));
        g.fill(poly(0, 60, 14, 82, -14, 82));
        g.fill(poly(-12, 100, 12, 100, 0, 110));
        g.setTransform(keep);
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
        eyebrow(g, "THE", 100, 158);
        drawLogo(g);
        g.setFont(Type.royal(17, false, 0.48f));
        Art.text(g, "CAPTURE  ·  HOLD  ·  CONQUER", 102, 290, VIOLET);

        double y = 410;   // six items
        for (int i = 0; i < ITEMS.length; i++) {
            boolean on = i == sel;
            double h = on ? 84 : 62, x = 100 + (on ? 16 : 0);
            Rectangle2D box = new Rectangle2D.Double(90, y - 6, 560, h);
            final int item = i;
            hits.add(new Object[]{box, (Runnable) () -> open(item), i});
            if (on) {
                g.setPaint(new LinearGradientPaint((float) x, 0, (float) (x + 520), 0, new float[]{0f, .55f, 1f},
                    new Color[]{new Color(255, 205, 90, 50), new Color(255, 205, 90, 14), new Color(255, 205, 90, 0)}));
                g.fill(poly(x - 10, y - 4, x + 520, y - 4, x + 480, y + h - 10, x - 10, y + h - 10));
                if (fx) Art.glow(g, GOLD, x - 8, y + 24, 26, 0.9f);
                g.setPaint(new GradientPaint(0, (float) y, GOLD_HI, 0, (float) (y + h - 14), GOLD_LO));
                g.fill(new Rectangle2D.Double(x - 10, y + 6, 5, h - 30));
            }
            boolean quit = i == ITEMS.length - 1;
            g.setFont(Type.royal(17, false, 0.1f));
            Art.text(g, NUMERALS[i], x + 4, y + 36, on ? GOLD : STONE);
            g.setFont(Type.royal(33, true, 0.14f));
            Color lc = quit ? (on ? new Color(255, 180, 168) : new Color(225, 140, 140, 190)) : (on ? Color.WHITE : new Color(200, 196, 225, 165));
            Art.text(g, ITEMS[i], x + 52, y + 40, lc);
            if (on) {
                g.setFont(Type.hud(18, false, 0.06f));
                Art.text(g, HINTS[i], x + 54, y + 66, MUTED);
            }
            y += h;
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
        double x = 1170, y = 38, w = 382, h = 74;
        Path2D card = poly(x + 20, y, x + w, y, x + w, y + h, x, y + h, x, y + 20);
        g.setPaint(new GradientPaint((float) x, 0, new Color(10, 7, 22, 60), (float) (x + w), 0, new Color(10, 7, 22, 220)));
        g.fill(card);
        g.setColor(new Color(255, 205, 90, 80));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(card);
        g.setColor(new Color(11, 7, 24));
        g.fill(new Rectangle2D.Double(x + 14, y + 10, 54, 54));
        g.setColor(GOLD);
        g.draw(new Rectangle2D.Double(x + 14, y + 10, 54, 54));
        knight(g, x + 41, y + 13, 2.0, 0);
        g.setFont(Type.royal(22, true, 0.1f));
        Art.text(g, playerName().toUpperCase(), x + 84, y + 34, Color.WHITE);
        g.setFont(Type.hud(16, false, 0.08f));
        Art.text(g, LobbyRoom.HAT_NAMES[hat] + "  ·  last server: " + address, x + 84, y + 58, MUTED);
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

    static void knightHat(Graphics2D g, int hat, Color c) {
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
                Ellipse2D.Double ring = new Ellipse2D.Double(347, 82, 300, 68);
                for (int i = 0; i < 4; i++) line(g, ring, new Color(c.getRed(), c.getGreen(), c.getBlue(), 28), 56 - i * 8);   // soft glow
                line(g, ring, OUTLINE, 34);
                line(g, ring, c, 18);
                line(g, path("M 366 104 Q 420 78 497 78"), new Color(255, 255, 255, 180), 6);
            }
            case 5 -> mirrored(g, () -> {   // wings
                solid(g, path("M 330 318 C 240 330 140 292 80 186 C 136 206 170 196 200 158 C 212 204 262 230 350 244 Z"), c);
                line(g, path("M 326 298 C 252 306 180 280 136 226"), new Color(13, 9, 18, 140), 6);
                line(g, path("M 318 270 C 258 274 208 250 182 212"), new Color(13, 9, 18, 140), 6);
                line(g, path("M 98 192 C 148 216 188 208 208 176"), new Color(255, 255, 255, 125), 8);
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
        double x = 100, y = 862;
        g.setFont(Type.hud(17, true, 0.08f));
        if (main) {
            x = keycap(g, "↑", x, y); x = keycap(g, "↓", x - 6, y); x = label(g, "CHOOSE", x, y);
            x = keycap(g, "ENTER", x + 18, y); x = label(g, "SELECT", x, y);
            x = keycap(g, "F11", x + 18, y); label(g, "FULLSCREEN", x, y);
        } else {
            g.setFont(Type.ui(17, true));   // the menu fonts have no arrow symbols
            Art.text(g, "←", x, y - 1, SOFT);
            g.setFont(Type.hud(17, true, 0.08f));
            Art.text(g, "BACK", x + 24, y, SOFT);
            Rectangle2D r = new Rectangle2D.Double(x - 10, y - 26, 200, 38);
            hits.add(new Object[]{r, (Runnable) this::back});
            keycap(g, "ESC", x + 92, y);
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
        Path2D p = poly(x + 12, y, x + w, y, x + w - 12, y + 46, x, y + 46);
        g.setPaint(blue ? new GradientPaint(0, (float) y, new Color(201, 240, 255), 0, (float) (y + 46), new Color(31, 111, 176))
            : new LinearGradientPaint(0, (float) y, 0, (float) (y + 46), new float[]{0f, .5f, 1f}, new Color[]{GOLD_HI, GOLD, GOLD_LO}));
        g.fill(p);
        g.setFont(Type.royal(19, true, 0.25f));
        Art.textCenter(g, text, x, w, y + 31, blue ? new Color(3, 18, 38) : new Color(26, 15, 2));
        hits.add(new Object[]{p, action});
    }

    // ------------------------------------------------------------------ PLAY

    private void drawPlay(Graphics2D g) {
        pageTitle(g, "PLAY", "Choose your path");
        double ty = 196, th = 572, tw = 448;
        playTile(g, 0, 100, ty, tw, th);
        playTile(g, 1, 580, ty, tw, th);

        double cx = 1120, cw = 400, cy = 150, ch = 618;   // your knight
        panel(g, cx, cy, cw, ch, 30, new Color(255, 205, 90, 80), null);
        g.setFont(Type.hud(16, true, 0.3f));
        Art.textCenter(g, "YOUR KNIGHT", cx, cw, cy + 44, VIOLET);
        if (fx) Art.glow(g, GOLD, cx + cw / 2, cy + 390, 120, 0.35f);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx + cw / 2, cy + 404), 110, new float[]{0f, 1f},
            new Color[]{new Color(255, 205, 90, 100), new Color(255, 205, 90, 0)}));
        g.fill(new Ellipse2D.Double(cx + cw / 2 - 110, cy + 392, 220, 26));
        knight(g, cx + cw / 2, cy + 120, 11, 1);
        picker(g, cx + 24, cy + 450, cw - 48, LobbyRoom.HAT_NAMES[hat].toUpperCase(), () -> stepHat(-1),
            () -> stepHat(1));
        picker(g, cx + 24, cy + 506, cw - 48, COLOR_NAMES[hatColor], () -> hatColor = (hatColor + COLOR_NAMES.length - 1) % COLOR_NAMES.length,
            () -> hatColor = (hatColor + 1) % COLOR_NAMES.length);
        double ny = cy + 560;
        g.setColor(new Color(255, 205, 90, 70));
        g.fill(new Rectangle2D.Double(cx + 24, ny, cw - 48, 1));
        field(g, cx + 40, ny + 12, cw - 80, name.toString(), editName, true,
            () -> editName = true);

        if (!status.isEmpty()) {
            g.setFont(Type.hud(19, true, 0.04f));
            Art.text(g, status, 100, 806, statusError ? new Color(255, 130, 110) : GOLD_HI);
        }
        drawFooter(g, false);
    }

    private void playTile(Graphics2D g, int which, double x, double y, double w, double h) {
        boolean on = tile == which, host = which == 0;
        double lift = on ? -8 : 0;
        y += lift;
        Paint fill = new GradientPaint(0, (float) y, host ? new Color(42, 11, 22) : new Color(8, 24, 58), 0, (float) (y + h * 0.75),
            host ? new Color(13, 6, 16) : new Color(7, 8, 20));
        Path2D p = panel(g, x, y, w, h, 30, on ? GOLD : new Color(255, 255, 255, 30), fill);
        hits.add(new Object[]{p, (Runnable) () -> tile = which, which});
        Shape oldClip = g.getClip();
        g.clip(p);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x + w / 2, y + 90), 300, new float[]{0f, 1f},
            new Color[]{host ? new Color(255, 90, 50, 80) : new Color(60, 170, 255, 80), new Color(0, 0, 0, 0)}));
        g.fill(new Rectangle2D.Double(x, y, w, h));
        AffineTransform keep = g.getTransform();
        g.translate(x, y);
        g.scale(w / 300, 232 / 200.0);
        if (host) beaconArt(g); else gateArt(g);
        g.setTransform(keep);
        g.setClip(oldClip);

        double tx = x + 32, cy = y + 290;
        g.setFont(Type.royal(36, true, 0.12f));
        Art.text(g, host ? "HOST A GAME" : "JOIN A GAME", tx, cy, host ? new Color(255, 184, 168) : new Color(168, 214, 255));
        g.setFont(Type.hud(19, false, 0.03f));
        wrap(g, host ? "Light the beacon. Run the game on this computer and pick 1v1 to 4v4 in the lobby."
            : "March to a friend's castle. Type the address shown on their Play screen.", tx, cy + 34, w - 64, 25, SOFT);
        if (host) {
            g.setFont(Type.hud(20, true, 0.08f));
            String a = "YOUR ADDRESS   " + lanAddress;
            double aw = g.getFontMetrics().stringWidth(a) + 28;
            g.setColor(new Color(0, 0, 0, 115)); g.fill(new Rectangle2D.Double(tx, cy + 112, aw, 38));
            g.setColor(new Color(255, 205, 90, 120)); g.draw(new Rectangle2D.Double(tx, cy + 112, aw, 38));
            Art.text(g, a, tx + 14, cy + 138, GOLD_HI);
            button(g, tx, y + h - 80, 170, busy && tile == 0 ? "..." : "HOST", false, () -> go(0));
        } else {
            field(g, tx, cy + 96, w - 64, address.toString(), !editName, false, () -> editName = false);
            g.setFont(Type.hud(16, false, 0.04f));
            double rx = tx;
            String last = prefs.get("lastJoined", "");
            for (String r : last.isEmpty() || last.equals("localhost") ? new String[]{"localhost"} : new String[]{"localhost", last}) {
                double rw = g.getFontMetrics().stringWidth(r) + 20;
                Rectangle2D rr = new Rectangle2D.Double(rx, cy + 150, rw, 28);
                g.setColor(new Color(170, 150, 235, 90)); g.draw(rr);
                Art.text(g, r, rx + 10, cy + 170, SOFT);
                final String pick = r;
                hits.add(new Object[]{rr, (Runnable) () -> { address.setLength(0); address.append(pick); editName = false; }});
                rx += rw + 8;
            }
            button(g, tx, y + h - 80, 170, busy && tile == 1 ? "..." : "JOIN", true, () -> go(1));
            if (onlineServer != null)   // the cloud server from online-server.txt: play with anyone, anywhere
                button(g, tx + 186, y + h - 80, 170, "ONLINE", false, () -> {
                    address.setLength(0);
                    address.append(onlineServer);
                    editName = false;
                    go(1);
                });
        }
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


    private void beaconArt(Graphics2D g) {   // 300x200 art space
        g.setColor(new Color(20, 6, 12)); g.fill(poly(0, 200, 0, 150, 80, 128, 150, 135, 230, 150, 300, 125, 300, 200));
        g.setColor(new Color(28, 13, 26)); g.fill(new Rectangle2D.Double(120, 70, 60, 90));
        g.setColor(new Color(74, 31, 42)); g.setStroke(new BasicStroke(3)); g.draw(new Rectangle2D.Double(120, 70, 60, 90));
        g.setColor(new Color(37, 16, 31));
        for (int i = 0; i < 4; i++) g.fill(new Rectangle2D.Double(114 + i * 20, 58, 12, 14));
        g.setColor(new Color(10, 4, 8)); g.fill(new Rectangle2D.Double(140, 120, 20, 40));
        if (fx) Art.glow(g, new Color(255, 120, 50), 150, 42, 46, (float) (0.6 + 0.15 * Math.sin(time * 7)));
        g.setColor(new Color(74, 31, 42)); g.fill(new Rectangle2D.Double(140, 56, 20, 6));
        Art.torchFlame(g, 150, 58, time, 1.1, fx);
    }

    private void gateArt(Graphics2D g) {
        g.setColor(new Color(6, 10, 24)); g.fill(poly(0, 200, 0, 140, 90, 150, 150, 130, 220, 110, 300, 140, 300, 200));
        g.setColor(new Color(60, 200, 255, 170));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, (float) (12 - time * 20 % 12)));
        g.draw(new QuadCurve2D.Double(150, 200, 140, 160, 150, 130));
        Path2D arch = new Path2D.Double();
        arch.moveTo(105, 130); arch.lineTo(105, 80); arch.quadTo(105, 50, 150, 50); arch.quadTo(195, 50, 195, 80); arch.lineTo(195, 130); arch.closePath();
        g.setColor(new Color(12, 20, 48)); g.fill(arch);
        g.setStroke(new BasicStroke(3)); g.setColor(new Color(31, 58, 122)); g.draw(arch);
        Path2D door = new Path2D.Double();
        door.moveTo(120, 130); door.lineTo(120, 88); door.quadTo(120, 66, 150, 66); door.quadTo(180, 66, 180, 88); door.lineTo(180, 130); door.closePath();
        g.setColor(new Color(60, 200, 255, (int) (60 + 25 * Math.sin(time * 2.5)))); g.fill(door);
        g.setStroke(new BasicStroke(2)); g.setColor(BLUE_FX); g.draw(door);
        g.setStroke(new BasicStroke(3)); g.setColor(new Color(31, 58, 122));
        g.draw(new Line2D.Double(150, 66, 150, 130)); g.draw(new Line2D.Double(120, 100, 180, 100));
    }

    // ------------------------------------------------------------------ HOW TO PLAY

    private void drawHowTo(Graphics2D g) {
        pageTitle(g, "HOW TO PLAY", "How to play");
        String[] titles = {"STEAL", "RETURN", "DEFEND"};
        String[] text = {"Run into the enemy base and touch their flag to pick it up.",
            "Carry it to your own base while your flag is safe at home. First to 3 captures wins.",
            "Shoot the carrier to drop the flag. Touch your own dropped flag to send it home."};
        Color[] col = {new Color(255, 184, 168), GOLD, new Color(168, 214, 255)};
        Color[] edge = {new Color(235, 70, 70), new Color(170, 150, 235, 150), new Color(70, 150, 255)};
        for (int i = 0; i < 3; i++) {
            double x = 100 + i * 476, y = 200, w = 448, h = 400;
            panel(g, x, y, w, h, 30, edge[i], null);
            AffineTransform keep = g.getTransform();
            g.translate(x + w / 2, y + 120);
            howIcon(g, i);
            g.setTransform(keep);
            g.setFont(Type.royal(34, true, 0.14f));
            Art.text(g, titles[i], x + 36, y + 262, col[i]);
            g.setFont(Type.hud(21, false, 0.03f));
            wrap(g, text[i], x + 36, y + 300, w - 72, 28, SOFT);
        }
        panel(g, 100, 636, 1400, 70, 18, new Color(170, 150, 235, 120), null);
        double x = 130;
        String[][] keys = {{"WASD", "move"}, {"MOUSE", "aim and fire"}, {"TAB", "scores"}, {"ENTER", "chat"}, {"R", "ready (lobby)"},
            {"E / C", "wardrobe"}, {"F11", "fullscreen"}};
        for (String[] k : keys) {
            x = keycap(g, k[0], x, 680);
            g.setFont(Type.hud(19, true, 0.04f));
            Art.text(g, k[1], x, 678, SOFT);
            x += g.getFontMetrics().stringWidth(k[1]) + 24;
        }
        g.setFont(Type.hud(19, true, 0.04f));
        Art.text(g, "The purple tent gives a 5 second shield.", 100, 750, new Color(201, 165, 255));
        drawFooter(g, false);
    }

    private void howIcon(Graphics2D g, int i) {
        g.setStroke(new BasicStroke(3));
        if (i == 0) {   // enemy flag on its base
            g.setColor(BLUE_FX);
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{9, 6}, (float) (time * 15 % 15)));
            g.draw(new Ellipse2D.Double(-70, 26, 140, 34));
            g.setStroke(new BasicStroke(1));
            g.scale(2, 2);
            Art.flag(g, 0, -6, 1, time);
        } else if (i == 1) {   // carry it home
            g.setColor(new Color(235, 70, 70, 70)); g.fill(new Ellipse2D.Double(-80, 24, 160, 40));
            g.setColor(RED_FX); g.draw(new Ellipse2D.Double(-80, 24, 160, 40));
            g.setColor(GOLD);
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{12, 9}, (float) (21 - time * 30 % 21)));
            g.draw(new Line2D.Double(-90, -10, 40, -10));
            g.fill(poly(40, -28, 70, -10, 40, 8));
            crown(g, -20, -52, 0.8);
        } else {   // shoot the carrier
            g.setColor(BLUE_FX);
            g.draw(new Ellipse2D.Double(-56, -56, 112, 112));
            g.draw(new Ellipse2D.Double(-22, -22, 44, 44));
            g.setStroke(new BasicStroke(4));
            g.draw(new Line2D.Double(0, -78, 0, -36)); g.draw(new Line2D.Double(0, 36, 0, 78));
            g.draw(new Line2D.Double(-78, 0, -36, 0)); g.draw(new Line2D.Double(36, 0, 78, 0));
            g.setColor(RED_FX); g.fill(new Ellipse2D.Double(-6, -6, 12, 12));
        }
    }

    // ------------------------------------------------------------------ SETTINGS

    private static final double SLIDER_W = 260;
    private double sliderX;

    private void drawSettings(Graphics2D g) {
        pageTitle(g, "SETTINGS", "Settings");
        panel(g, 100, 200, 680, 470, 30, new Color(170, 150, 235, 150), null);
        g.setFont(Type.royal(30, true, 0.14f));
        Art.text(g, "DISPLAY", 140, 262, GOLD);
        toggle(g, 140, 300, "Fullscreen", actions.fullscreen(), actions::toggleFullscreen);
        toggle(g, 140, 370, "Visual effects", fx, () -> fx = !fx);
        toggle(g, 140, 440, "Screen shake", shake, () -> shake = !shake);
        g.setFont(Type.hud(18, false, 0.03f));
        wrap(g, "Effects are the glows, sparks and fireworks. The game turns them off by itself if your computer is too slow.",
            140, 560, 600, 26, MUTED);

        panel(g, 820, 200, 680, 470, 30, new Color(170, 150, 235, 150), null);
        g.setFont(Type.royal(30, true, 0.14f));
        Art.text(g, "SOUND AND NETWORK", 860, 262, GOLD);
        sliderX = 1200;
        slider(g, 0, 860, 300, "Sound volume", volume, Math.round(volume * 100) + "%");
        slider(g, 1, 860, 370, "Test delay", lagDelay / 300.0, lagDelay + " ms");
        slider(g, 2, 860, 440, "Test packet loss", lagLoss / 30.0, lagLoss + "%");
        g.setFont(Type.hud(18, false, 0.03f));
        wrap(g, "The two test sliders fake a bad network for demos: extra delay each way and lost packets. Both apply to the next game you join.",
            860, 560, 600, 26, MUTED);
        drawFooter(g, false);
    }

    private void toggle(Graphics2D g, double x, double y, String label, boolean on, Runnable flip) {
        g.setFont(Type.hud(23, true, 0.04f));
        Art.text(g, label, x, y + 30, SOFT);
        RoundRectangle2D t = new RoundRectangle2D.Double(x + 520, y + 8, 64, 30, 30, 30);
        g.setColor(on ? new Color(110, 230, 130, 70) : new Color(42, 35, 64)); g.fill(t);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(on ? new Color(110, 230, 130) : STONE); g.draw(t);
        g.setColor(on ? new Color(110, 230, 130) : MUTED);
        g.fill(new Ellipse2D.Double(on ? x + 558 : x + 524, y + 12, 22, 22));
        g.setColor(new Color(110, 95, 160, 90));
        g.fill(new Rectangle2D.Double(x, y + 50, 600, 1));
        hits.add(new Object[]{new Rectangle2D.Double(x, y, 600, 50), (Runnable) () -> { flip.run(); save(); }});
    }

    private void slider(Graphics2D g, int id, double x, double y, String label, double k, String value) {
        g.setFont(Type.hud(23, true, 0.04f));
        Art.text(g, label, x, y + 30, SOFT);
        g.setFont(Type.hud(19, true, 0.04f));
        Art.text(g, value, sliderX - 90, y + 30, GOLD_HI);
        g.setColor(new Color(42, 35, 64));
        g.fill(new RoundRectangle2D.Double(sliderX, y + 20, SLIDER_W, 6, 6, 6));
        g.setColor(GOLD);
        g.fill(new RoundRectangle2D.Double(sliderX, y + 20, SLIDER_W * k, 6, 6, 6));
        g.setColor(GOLD_HI);
        g.fill(new Ellipse2D.Double(sliderX + SLIDER_W * k - 10, y + 13, 20, 20));
        g.setColor(new Color(110, 95, 160, 90));
        g.fill(new Rectangle2D.Double(x, y + 50, 600, 1));
        hits.add(new Object[]{new Rectangle2D.Double(sliderX - 14, y, SLIDER_W + 28, 50), (Runnable) () -> {
            dragSlider = id;
            setSlider(id, Math.max(0, Math.min(1, (mouse.getX() - sliderX) / SLIDER_W)));
        }});
    }

    private void setSlider(int id, double k) {
        switch (id) {
            case 0 -> { volume = (float) (Math.round(k * 20) / 20.0); Sfx.volume = volume; }
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

    private static void medallion(Graphics2D g, double cx, double cy, double r) {   // a person inside a laurel wreath
        g.setPaint(new Color(10, 6, 16, 150));
        g.fill(new Ellipse2D.Double(cx - r * 0.9, cy - r * 0.9, r * 1.8, r * 1.8));
        g.setStroke(new BasicStroke(1.4f)); g.setColor(new Color(255, 205, 90, 120));
        g.draw(new Ellipse2D.Double(cx - r * 0.9, cy - r * 0.9, r * 1.8, r * 1.8));
        AffineTransform keep = g.getTransform();
        for (int side = -1; side <= 1; side += 2) {
            g.setTransform(keep);
            g.translate(cx, cy);
            if (side == 1) g.scale(-1, 1);
            for (int i = 0; i < 7; i++) {
                double th = Math.toRadians(112 + i * 21);
                AffineTransform at = AffineTransform.getTranslateInstance(r * 1.08 * Math.cos(th), -r * 1.08 * Math.sin(th));
                at.rotate(-th + Math.PI / 2);
                gilded(g, at.createTransformedShape(new Ellipse2D.Double(-r * 0.10, -r * 0.22, r * 0.20, r * 0.44)), -r, r);
            }
        }
        g.setTransform(keep);
        person(g, cx, cy + r * 0.05, r * 1.0);
    }

    private static void book(Graphics2D g, double cx, double cy, double k) {
        for (int s = -1; s <= 1; s += 2)
            gilded(g, poly(cx + s * 0.04 * k, cy - 0.34 * k, cx + s * 1.0 * k, cy - 0.5 * k, cx + s * 1.0 * k, cy + 0.36 * k, cx + s * 0.04 * k, cy + 0.5 * k), cy - 0.5 * k, cy + 0.5 * k);
        g.setStroke(new BasicStroke(1.2f)); g.setColor(GOLD_DK);
        for (int i = 0; i < 3; i++) for (int s = -1; s <= 1; s += 2)
            g.draw(new Line2D.Double(cx + s * 0.18 * k, cy - 0.16 * k + i * 0.2 * k, cx + s * 0.82 * k, cy - 0.28 * k + i * 0.2 * k));
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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
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
                medallion(g, x, 416, 30);
                shineText(g, names[i], Type.royal(27, true, 0.16f), x, 484, GOLD_RAMP, GOLD_DK, -1);
            }
            g.setPaint(new LinearGradientPaint(0, 395, 0, 495, new float[]{0f, .5f, 1f}, new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 130), new Color(255, 205, 90, 0)}));
            g.fill(new Rectangle2D.Double(666, 395, 1.4, 100));
            g.fill(new Rectangle2D.Double(933, 395, 1.4, 100));
        });

        reveal(g, ease((ct - 0.95) / 0.7), () -> {   // the lecturer
            section(g, 440, 548, 720, 70);
            tab(g, cx, 531, 200, "LECTURER", new Color(24, 50, 120), new Color(10, 22, 70));
            book(g, 498, 583, 28);
            shineText(g, "DR. NADEESHA SANDAMALI", Type.royal(28, true, 0.14f), cx + 38, 594, GOLD_RAMP, GOLD_DK, -1);
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

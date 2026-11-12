package ctf.client;

import ctf.common.LobbyMap;
import ctf.common.MapData;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Part B: render loop, input, drawing, prediction, interpolation and effects.
 * Networking (Part C) calls onSnapshot(); this panel calls NetLink.sendInput().
 * Everything decorative (animation frames, particles, light) is derived locally from the snapshots.
 */
public class GamePanel extends JPanel {
    static final String[] TEAM_NAME = {"RED", "BLUE"};
    static final Color[] TEAM = {new Color(235, 70, 70), new Color(70, 150, 255)};
    static final long INTERP_DELAY_NS = 100_000_000L; // render 100ms in the past (~3 snapshots)
    static final double SHOOT_COOLDOWN = 0.25;
    static final int MAX_PARTICLES = 1000;

    private static final class Particle {
        double x, y, vx, vy, life, max; Color c; int size = 4; boolean soft;
    }

    private final NetLink link;
    private final int myId;

    private final List<Snapshot> buf = new ArrayList<>();
    private Snapshot last;
    private Map<Integer, double[]> renderPos = new HashMap<>();
    private final Map<Integer, double[]> anim = new HashMap<>(); // id -> {phase, moving-hold, prevX, prevY}
    private final List<Particle> parts = new ArrayList<>();
    private final List<double[]> beams = new ArrayList<>();      // {x, y, team, life}
    private final List<Object[]> feed = new ArrayList<>();       // {text, expiryNs}
    private final double[][] embers = new double[72][4];         // x, y, speed, phase
    // match ending (drawEnding): final moment 0-2 s, VICTORY / DEFEAT 2-7 s, then results until everyone is back in the lobby
    static final double END_FINAL = 2, END_RESULTS = 7, END_RETURN = 20;   // END_RETURN mirrors the server's AGAIN_TIMEOUT_SEC
    private final double[][] endFx = new double[90][4];          // sparks or ash: x, y (0..1 of the screen), speed, phase
    private String finalBanner = "", finalSub = "";              // "FINAL CAPTURE" / "TIME!" and who did it
    private int finalScorer = -1;                                // gets the gold ring in the final moment
    private int roundStartLeft = -1;                             // clock seconds when the match began, for its length
    private final Rectangle2D.Double backBtn = new Rectangle2D.Double();
    private String lastMatch;                                    // "RED WON 3 - 1 · MVP RAVEN" on the lobby board
    private int mvpId = -1;                                      // stands in a gold glow in the lobby until the next match
    private boolean recorded;                                    // this match is saved to the profile
    private List<String> news = List.of();                       // "+140 XP", "LEVEL UP!", achievements: shown on the results card

    // predicted own bullets: {ox, oy, vx, vy, team, age, matchedBefore, x, y}; see reconcileGhosts()
    private final List<double[]> ghosts = new ArrayList<>();
    private final List<double[]> recentImpacts = new ArrayList<>(); // {x, y, age}: ghost impacts, so the server's copy is not shown twice
    private final Map<Integer, Double> fireT = new HashMap<>();     // player id -> seconds of muzzle flash left
    private final Set<double[]> hidden = Collections.newSetFromMap(new IdentityHashMap<>()); // server bullets drawn as ghosts instead
    private Snapshot prev;
    private boolean shootLatch, firedSinceSend, shakeOn = true;

    private final Set<Integer> keys = new HashSet<>();
    private int mouseX, mouseY;
    private boolean mouseDown, showBoard, fx = true;

    private final double[] pred = new double[2];
    private boolean predReady, wasAlive = true;

    private double shake, flash, shootCd, sendAcc, time;
    private long lastTickNs = System.nanoTime();
    private final Random rnd = new Random();
    private final Scenery scenery = new Scenery();
    // The part of the world on screen, in world units. GameView sets it so the picture fills the whole window;
    // until then (and when a test paints the panel directly) it is simply the panel's own size.
    private double viewX, viewY, viewW, viewH;
    private boolean viewSet;
    private final List<double[]> rings = new ArrayList<>();      // {x, y, team (-1 white), age, life, radius}
    private final List<Object[]> notices = new ArrayList<>();    // {text, color, age}: big centre-screen announcements
    private final double[] scorePop = new double[2];             // 1 -> 0 after a team scores, drives the score bounce
    private final Map<Integer, Double> spawnT = new HashMap<>(); // player id -> seconds of respawn materialise left
    private double smokeT;
    private double endT;                                          // seconds since the match ended (see tick)

    // lobby: the waiting room, in-game chat and the ready / start buttons
    private final LobbyRoom room = new LobbyRoom();
    private boolean typing;                                       // ENTER opened the chat line; keys go to the text
    private final StringBuilder chatText = new StringBuilder();
    private final Map<Integer, Object[]> bubbles = new HashMap<>(); // player id -> {text, expiryNs}: chat above heads
    private final Rectangle2D.Double readyBtn = new Rectangle2D.Double(), startBtn = new Rectangle2D.Double(); // world units
    private boolean hoverReady, hoverStart;
    private final Rectangle2D.Double leaveBtn = new Rectangle2D.Double();

    // in-game menu (Esc): resume, settings, leave, quit. It is an online game, so nothing pauses behind it.
    /** What the in-game menu asks the game window to do. The Demo and tests leave it unset: Esc then does nothing. */
    interface Exits {
        void leave();
        void quit();
        void toggleFullscreen();
        boolean fullscreen();
        boolean hosting();
        /** Save the finished match to the player's profile; returns what to celebrate ("+140 XP", "LEVEL UP!  LEVEL 4"). */
        List<String> matchFinished(Snapshot s, int myId, int mvpId, int lengthSec);
        boolean hatUnlocked(int hat);
    }

    private enum Esc { CLOSED, MAIN, SETTINGS, LEAVE, QUIT }
    private Exits exits;
    private Esc esc = Esc.CLOSED;
    private int escSel;
    private final List<Object[]> escHits = new ArrayList<>();   // {Rectangle2D, Runnable action, Integer row}, rebuilt every frame
    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(GamePanel.class);   // shared with MenuScreen
    private final Rectangle2D.Double[] modeBtns = {new Rectangle2D.Double(), new Rectangle2D.Double(), new Rectangle2D.Double(), new Rectangle2D.Double()};

    public GamePanel(NetLink link, int myId) {
        this.link = link;
        this.myId = myId;
        setPreferredSize(new Dimension(MapData.W, MapData.H));
        setBackground(new Color(10, 8, 20));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false); // let TAB reach the key listener
        for (double[] e : embers) respawnEmber(e, true);
        Sfx.play(""); // loads the sound class now, not on the first shot

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                if (typing) { typeKey(e); return; }   // Esc while typing cancels the chat line first
                if (esc != Esc.CLOSED) { escKey(k); return; }
                if (k == KeyEvent.VK_ESCAPE) { openEsc(); return; }
                if (k == KeyEvent.VK_ENTER && (last == null || last.winner < 0)) { // open the chat line
                    typing = true;
                    keys.clear();
                    mouseDown = false;
                    return;
                }
                if (inLobby()) {
                    if (k == KeyEvent.VK_R) link.sendReady(!amReady());
                    if ((k == KeyEvent.VK_E || k == KeyEvent.VK_C) && nearLaptop()) {
                        Protocol.LobbyEntry me = entry(myId);
                        int hat = me == null ? 0 : me.hat(), col = me == null ? 0 : me.hatColor();
                        if (k == KeyEvent.VK_E) {   // next hat this player has unlocked (the crown is always free)
                            do hat = (hat + 1) % LobbyMap.HATS; while (exits != null && !exits.hatUnlocked(hat));
                        } else col++;
                        link.sendLook(hat, col);   // the server wraps the values and checks we are at the laptop
                    }
                }
                keys.add(k);
                if (k == KeyEvent.VK_TAB && (last == null || last.winner < 0)) showBoard = true;
                if (k == KeyEvent.VK_L) link.toggleLagSim();
                if (k == KeyEvent.VK_V) shakeOn = !shakeOn;
                if (k == KeyEvent.VK_M) Music.toggle();
                if (k == KeyEvent.VK_F) fx = !fx; // optional effects off if they cost frames
                if ((k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) && last != null && last.winner >= 0) {
                    setCursor(Cursor.getDefaultCursor());
                    link.requestPlayAgain();
                }
            }
            @Override public void keyReleased(KeyEvent e) {
                keys.remove(e.getKeyCode());
                if (e.getKeyCode() == KeyEvent.VK_TAB && (last == null || last.winner < 0)) showBoard = false;
            }
        });
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                mouseX = e.getX(); mouseY = e.getY();
                if (esc != Esc.CLOSED) {
                    boolean over = false;
                    for (Object[] h : escHits) if (((Rectangle2D) h[0]).contains(mouseX, mouseY)) { escSel = (Integer) h[2]; over = true; }
                    setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    return;
                }
                if (inLobby()) {
                    hoverReady = readyBtn.contains(mouseX, mouseY);
                    hoverStart = startBtn.contains(mouseX, mouseY);
                    boolean overMode = false;
                    if (amHost()) for (Rectangle2D.Double r : modeBtns) overMode |= r.contains(mouseX, mouseY);
                    overMode |= leaveBtn.contains(mouseX, mouseY);
                    setCursor(Cursor.getPredefinedCursor(hoverReady || hoverStart || overMode ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                } else if (last != null && last.winner >= 0) {
                    setCursor(Cursor.getPredefinedCursor(backBtn.contains(mouseX, mouseY) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            }
            @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
            @Override public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                if (esc != Esc.CLOSED) { // the in-game menu takes every click while it is open
                    for (int i = escHits.size() - 1; i >= 0; i--)
                        if (((Rectangle2D) escHits.get(i)[0]).contains(e.getX(), e.getY())) { ((Runnable) escHits.get(i)[1]).run(); break; }
                    return;
                }
                if (inLobby()) { // buttons take the click; it does not also fire a shot
                    if (exits != null && leaveBtn.contains(e.getX(), e.getY())) { exits.leave(); return; }
                    if (readyBtn.contains(e.getX(), e.getY())) { link.sendReady(!amReady()); return; }
                    if (startBtn.contains(e.getX(), e.getY())) { if (lobbyCanStart()) link.sendBegin(); return; }
                    for (int i = 0; i < 4; i++)
                        if (modeBtns[i].contains(e.getX(), e.getY())) { if (amHost()) link.sendMode(i + 1); return; }
                }
                if (last != null && last.winner >= 0) {   // the match is over: no shooting, only the BACK TO LOBBY button
                    if (backBtn.contains(e.getX(), e.getY())) link.requestPlayAgain();
                    return;
                }
                mouseMoved(e); // aim at the click point, not at the last move event
                mouseDown = shootLatch = true; // latch: a click shorter than one send interval still fires
                requestFocusInWindow();
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1) mouseDown = false; }
        };
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { keys.clear(); mouseDown = false; } // no stuck keys or auto-fire
        });
        addMouseListener(m);
        addMouseMotionListener(m);

        new javax.swing.Timer(16, e -> { if (!externalClock) tick(); }).start();
    }

    // ---------- network entry point (any thread) ----------

    public void onSnapshot(Snapshot s) {
        s.arrivedNs = System.nanoTime();
        SwingUtilities.invokeLater(() -> accept(s));
    }

    /** A chat line "name: text" from player fromId: shown in the feed and as a bubble over their head. */
    public void onChat(int fromId, String line) {
        SwingUtilities.invokeLater(() -> {
            say(line, new Color(235, 230, 255));
            int colon = line.indexOf(": ");
            String text = colon >= 0 ? line.substring(colon + 2) : line;
            if (text.length() > 42) text = text.substring(0, 40) + "...";
            bubbles.put(fromId, new Object[]{text, System.nanoTime() + 4_500_000_000L});
        });
    }

    // ---------- lobby helpers ----------

    private boolean inLobby() { return last != null && last.phase == Snapshot.LOBBY; }

    /** The walls of the room we are in: prediction and bullet effects must use the same ones as the server. */
    private double[][] walls() { return inLobby() ? LobbyMap.WALLS : MapData.WALLS; }

    private Protocol.LobbyEntry entry(int id) {
        Protocol.Lobby l = link.lobby();
        if (l != null) for (Protocol.LobbyEntry e : l.entries()) if (e.id() == id) return e;
        return null;
    }

    private boolean amReady() { Protocol.LobbyEntry e = entry(myId); return e != null && e.ready(); }

    private boolean amHost() { Protocol.Lobby l = link.lobby(); return l != null && l.hostId() == myId; }

    private boolean nearLaptop() { return predReady && LobbyMap.nearLaptop(pred[0], pred[1]); }

    /** Mirrors the server's rule, only to grey out the button; the server decides. */
    private boolean lobbyCanStart() {
        Protocol.Lobby l = link.lobby();
        if (l == null || l.entries().size() < l.minPlayers()) return false;
        int[] team = new int[2];
        for (Protocol.LobbyEntry e : l.entries()) {
            if (!e.ready()) return false;
            team[Math.max(0, Math.min(1, e.team()))]++;
        }
        return l.minPlayers() <= 1 || (team[0] == l.mode() && team[1] == l.mode());   // both teams full, like the server
    }

    /** Host only: the 1v1 .. 4v4 buttons above START. Others see the chosen size, greyed. */
    private void drawModePicker(Graphics2D g, double x, double y, double w) {
        Protocol.Lobby l = link.lobby();
        int mode = l == null ? 1 : l.mode();
        boolean host = amHost();
        g.setFont(Type.ui(11.5f, true));
        Art.text(g, host ? "MATCH SIZE" : "MATCH SIZE (host picks)", x, y - 6, new Color(170, 150, 235));
        double bw = (w - 3 * 8) / 4;
        for (int i = 0; i < 4; i++) {
            Rectangle2D.Double r = modeBtns[i];
            r.setRect(x + i * (bw + 8), y, bw, 34);
            boolean on = mode == i + 1, hover = host && r.contains(mouseX, mouseY);
            Color c = on ? GOLD : hover ? new Color(200, 190, 240) : new Color(110, 95, 160);
            Art.plate(g, r.x, r.y, r.width, r.height, 7, c, time, fx && on);
            g.setFont(Type.numbers(16));
            Art.textCenter(g, (i + 1) + "v" + (i + 1), r.x, r.width, r.y + 23, on ? GOLD : host ? Color.WHITE : new Color(150, 145, 175));
        }
    }

    // ---------- in-game menu (Esc) ----------

    void setExits(Exits x) { exits = x; }

    private void openEsc() {
        if (exits == null) return;
        esc = Esc.MAIN;
        escSel = 0;
        keys.clear();   // let go of everything: no running or firing while the menu is open
        mouseDown = shootLatch = false;
        showBoard = false;
    }

    /** The window's close button: quit at once, or ask first when that would hurt the others. */
    void askQuit() {
        if (quitNeedsConfirm()) { openEsc(); esc = Esc.QUIT; escSel = 1; }
        else if (exits != null) exits.quit();
    }

    private boolean inMatch() { return last != null && last.phase == Snapshot.PLAYING && last.winner < 0; }

    private int others() { Protocol.Lobby l = link.lobby(); return l == null ? 0 : Math.max(0, l.entries().size() - 1); }

    private boolean quitNeedsConfirm() { return inMatch() || (exits != null && exits.hosting() && others() > 0); }

    private void escKey(int k) {
        int rows = 0;
        for (Object[] h : escHits) rows = Math.max(rows, (Integer) h[2] + 1);
        if (k == KeyEvent.VK_ESCAPE) esc = esc == Esc.MAIN ? Esc.CLOSED : Esc.MAIN;
        else if ((k == KeyEvent.VK_UP || k == KeyEvent.VK_W) && rows > 0) escSel = (escSel + rows - 1) % rows;
        else if ((k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S) && rows > 0) escSel = (escSel + 1) % rows;
        else if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE)
            for (Object[] h : escHits) if ((Integer) h[2] == escSel) { ((Runnable) h[1]).run(); break; }
    }

    private void saveSetting(String key, Object value) {
        try {
            if (value instanceof Boolean b) prefs.putBoolean(key, b); else prefs.putFloat(key, (Float) value);
        } catch (Exception ignored) { /* remembering a setting is a convenience; never fail over it */ }
    }

    private void drawEscMenu(Graphics2D g) {
        escHits.clear();
        if (esc == Esc.CLOSED) return;
        final double x0 = vX0(), y0 = vY0(), w = vW(), h = vH();
        g.setColor(new Color(4, 3, 10, 175));
        g.fill(new Rectangle2D.Double(x0, y0, w, h));
        String title, note = null;
        List<Object[]> rows = new ArrayList<>();   // {label, action, colour}
        Color gold = new Color(255, 205, 90), red = new Color(255, 150, 135), soft = new Color(200, 196, 225);
        switch (esc) {
            case MAIN -> {
                title = "MENU";
                note = "The game keeps running while this menu is open.";
                rows.add(new Object[]{"RESUME", (Runnable) () -> esc = Esc.CLOSED, Color.WHITE});
                rows.add(new Object[]{"SETTINGS", (Runnable) () -> { esc = Esc.SETTINGS; escSel = 0; }, Color.WHITE});
                rows.add(new Object[]{inLobby() ? "LEAVE LOBBY" : "LEAVE MATCH", (Runnable) () -> {
                    if (inMatch()) { esc = Esc.LEAVE; escSel = 1; } else exits.leave();
                }, Color.WHITE});
                rows.add(new Object[]{"QUIT GAME", (Runnable) () -> {
                    if (quitNeedsConfirm()) { esc = Esc.QUIT; escSel = 1; } else exits.quit();
                }, red});
            }
            case SETTINGS -> {
                title = "SETTINGS";
                rows.add(new Object[]{"FULLSCREEN   " + (exits.fullscreen() ? "ON" : "OFF"), (Runnable) exits::toggleFullscreen, Color.WHITE});
                rows.add(new Object[]{"VISUAL EFFECTS   " + (fx ? "ON" : "OFF"), (Runnable) () -> { fx = !fx; saveSetting("fx", fx); }, Color.WHITE});
                rows.add(new Object[]{"SCREEN SHAKE   " + (shakeOn ? "ON" : "OFF"), (Runnable) () -> { shakeOn = !shakeOn; saveSetting("shake", shakeOn); }, Color.WHITE});
                rows.add(new Object[]{"SOUND   " + Math.round(Sfx.volume * 100) + "%", (Runnable) () -> {
                    Sfx.volume = Sfx.volume >= 0.99f ? 0f : Math.min(1f, Math.round(Sfx.volume * 10 + 2) / 10f);   // 0, 20 .. 100%, then back to 0
                    saveSetting("volume", Sfx.volume);
                    Sfx.play("pickup");
                }, Color.WHITE});
                rows.add(new Object[]{"BACK", (Runnable) () -> { esc = Esc.MAIN; escSel = 1; }, soft});
            }
            case LEAVE -> {
                title = "LEAVE THE MATCH?";
                note = exits.hosting() ? "You are hosting: the game keeps running for the others." : "Your team will be one player short.";
                rows.add(new Object[]{"LEAVE", (Runnable) exits::leave, red});
                rows.add(new Object[]{"STAY", (Runnable) () -> esc = Esc.CLOSED, Color.WHITE});
            }
            default -> {
                title = "QUIT THE GAME?";
                int n = others();
                note = exits.hosting() && n > 0 ? "You are hosting. Quitting ends the game for " + n + (n == 1 ? " other player." : " other players.")
                    : "Your team will be one player short.";
                rows.add(new Object[]{"QUIT", (Runnable) exits::quit, red});
                rows.add(new Object[]{"STAY", (Runnable) () -> esc = Esc.CLOSED, Color.WHITE});
            }
        }
        escSel = Math.min(escSel, rows.size() - 1);
        double pw = 460, rowH = 52, ph = 118 + rows.size() * (rowH + 8) + (note != null ? 40 : 0);
        double px = x0 + (w - pw) / 2, py = y0 + (h - ph) / 2;
        Art.plate(g, px, py, pw, ph, 16, gold, time, fx);
        g.setFont(Type.logo(18));
        Art.textCenter(g, "THE THRONEFALL", px, pw, py + 38, new Color(170, 150, 235));
        g.setFont(Type.royal(30, true, 0.14f));
        Art.textCenter(g, title, px, pw, py + 80, gold);
        double ry = py + 104;
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            boolean on = i == escSel;
            Rectangle2D.Double box = new Rectangle2D.Double(px + 36, ry, pw - 72, rowH);
            escHits.add(new Object[]{box, r[1], i});
            if (on) {
                g.setPaint(new LinearGradientPaint((float) box.x, 0, (float) (box.x + box.width), 0, new float[]{0f, .5f, 1f},
                    new Color[]{new Color(255, 205, 90, 0), new Color(255, 205, 90, 60), new Color(255, 205, 90, 0)}));
                g.fill(box);
                g.setColor(gold);
                g.fill(new Rectangle2D.Double(box.x, box.y + box.height - 2, box.width, 2));
            }
            g.setFont(Type.royal(22, true, 0.16f));
            Color c = (Color) r[2];
            Art.textCenter(g, (String) r[0], box.x, box.width, box.y + 34, on ? (c == Color.WHITE ? Color.WHITE : c.brighter()) : new Color(c.getRed(), c.getGreen(), c.getBlue(), 170));
            ry += rowH + 8;
        }
        if (note != null) {
            g.setFont(Type.hud(16, false, 0.04f));
            Art.textCenter(g, note, px, pw, ry + 22, new Color(170, 165, 200));
        }
    }

    private void typeKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_ENTER) {
            String t = chatText.toString().trim();
            if (!t.isEmpty()) link.sendChat(t);
            chatText.setLength(0);
            typing = false;
        } else if (k == KeyEvent.VK_ESCAPE) {
            chatText.setLength(0);
            typing = false;
        } else if (k == KeyEvent.VK_BACK_SPACE) {
            if (chatText.length() > 0) chatText.setLength(chatText.length() - 1);
        } else {
            char c = e.getKeyChar();
            if (c != KeyEvent.CHAR_UNDEFINED && c >= ' ' && c != 127 && chatText.length() < 120) chatText.append(c);
        }
    }

    private void accept(Snapshot s) {
        if (last != null) detectEvents(last, s);
        if (s.winner >= 0 && Math.abs(endT - s.matchEndElapsedSec) > 0.5) endT = s.matchEndElapsedSec;   // joined late, or drifted
        prev = last;
        last = s;
        buf.add(s);
        Snapshot.P me = find(s, myId);
        if (me != null) {
            if (!predReady || (me.alive && !wasAlive)) { pred[0] = me.x; pred[1] = me.y; predReady = true; }
            else if (me.alive) { // quietly pull prediction toward the authoritative position
                double ex = me.x - pred[0], ey = me.y - pred[1];
                if (Math.hypot(ex, ey) > 60) { pred[0] = me.x; pred[1] = me.y; }
                else { pred[0] += ex * 0.15; pred[1] += ey * 0.15; }
            }
            wasAlive = me.alive;
        }
    }

    // ---------- per-frame update ----------

    boolean effectsOn() { return fx; }

    void setEffects(boolean on) { fx = on; }

    void setShake(boolean on) { shakeOn = on; }

    /** Shows a short message in the event feed. */
    void tell(String message) { say(message); }

    // On its own, the panel moves things on a 16 ms timer, while whoever paints it uses another timer. The two drift
    // apart, so some painted frames show a stale position and the next one jumps: visible stutter even at 60 fps.
    // GameView takes over the clock instead: it calls frameTick() once per painted frame, right before drawing.
    private boolean externalClock;

    void useExternalClock() { externalClock = true; }

    /** Advances the game by the time since the last call. Call once per painted frame, on the Swing thread. */
    void frameTick() { tick(); }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastTickNs) / 1e9);
        lastTickNs = now;
        time += dt;

        int dx = (down(KeyEvent.VK_D) || down(KeyEvent.VK_RIGHT) ? 1 : 0) - (down(KeyEvent.VK_A) || down(KeyEvent.VK_LEFT) ? 1 : 0);
        int dy = (down(KeyEvent.VK_S) || down(KeyEvent.VK_DOWN) ? 1 : 0) - (down(KeyEvent.VK_W) || down(KeyEvent.VK_UP) ? 1 : 0);

        // client-side prediction: move now, server corrects later
        Snapshot.P me = last == null ? null : find(last, myId);
        if (predReady && me != null && me.alive && (dx != 0 || dy != 0)) {
            double len = Math.hypot(dx, dy);
            pred[0] += dx / len * MapData.SPEED * dt;
            pred[1] += dy / len * MapData.SPEED * dt;
            MapData.resolve(pred, walls());
        }
        double aim = predReady ? Math.atan2(mouseY - pred[1], mouseX - pred[0]) : 0;

        shootCd -= dt;
        if ((mouseDown || shootLatch) && shootCd <= 0 && me != null && me.alive) {
            shootCd = MapData.FIRE_INTERVAL; // mirrors the server's limit; no extra delay
            shootLatch = false;
            firedSinceSend = true;
            sendAcc = 1; // send this very tick instead of waiting for the next 30Hz slot
            ghosts.add(new double[]{pred[0], pred[1], Math.cos(aim) * MapData.BULLET_SPEED, Math.sin(aim) * MapData.BULLET_SPEED,
                me.team, 0, 0, pred[0], pred[1]});
            fireT.put(myId, 0.08);
            Sfx.play("shoot");
        } else if (me == null || !me.alive) shootLatch = false;

        sendAcc += dt;
        if (sendAcc >= 1.0 / 30) { // match the server tick rate
            sendAcc = 0;
            link.sendInput(dx, dy, aim, mouseDown || firedSinceSend);
            firedSinceSend = false;
        }

        // where everyone is drawn this frame, and whether they are walking
        renderPos = interpolate();
        if (me != null && me.alive && predReady) renderPos.put(myId, pred.clone());
        for (Map.Entry<Integer, double[]> e : renderPos.entrySet()) {
            double[] a = anim.computeIfAbsent(e.getKey(), k -> new double[]{0, 0, e.getValue()[0], e.getValue()[1]});
            double[] xy = e.getValue();
            a[1] = Math.hypot(xy[0] - a[2], xy[1] - a[3]) > 0.25 ? 0.15 : Math.max(0, a[1] - dt);
            if (a[1] > 0) a[0] += dt * 10;
            a[2] = xy[0]; a[3] = xy[1];
        }

        reconcileGhosts(dt);
        for (Iterator<Map.Entry<Integer, Double>> it = fireT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Double> e = it.next();
            double left = e.getValue() - dt;
            if (left <= 0) it.remove(); else e.setValue(left);
        }
        if (last != null) // a new server bullet right at someone's gun means they just fired
            for (double[] b : last.bullets) if (!hidden.contains(b))
                for (Snapshot.P p : last.players) {
                    double[] xy = renderPos.get(p.id);
                    if (xy == null || !p.alive || p.team != teamOf(b)) continue;
                    if (Math.hypot(b[0] - (xy[0] + Math.cos(p.aim) * 14), b[1] - (xy[1] + Math.sin(p.aim) * 14)) < 22) fireT.put(p.id, 0.08);
                }
        recentImpacts.removeIf(r -> (r[2] += dt) > 0.6);
        for (Iterator<Particle> it = parts.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt; p.x += p.vx * dt; p.y += p.vy * dt;
            if (p.life <= 0) it.remove();
        }
        for (Iterator<double[]> it = beams.iterator(); it.hasNext(); ) if ((it.next()[3] -= dt) <= 0) it.remove();
        for (double[] e : embers) {
            e[1] -= e[2] * dt;
            e[0] += Math.sin(time * 1.5 + e[3]) * 12 * dt;
            if (e[1] < vY0() - 5) respawnEmber(e, false);
        }
        if (last != null && last.winner >= 0) {
            endT += dt;   // smooth local clock for the ending; accept() re-syncs it to the server's
            if (!recorded && endT >= 4 && exits != null) {   // by now the final STATS line has arrived (it is sent with GAMEOVER)
                recorded = true;
                Snapshot.P mvp = best(last, RANK);
                news = exits.matchFinished(last, myId, mvp == null ? -1 : mvp.id, roundStartLeft > 0 ? roundStartLeft - last.timeLeftSec : -1);
            }
            boolean won = won();
            for (double[] e : endFx) {   // sparks rise for the winners, ash drifts down for the losers
                e[1] += (won ? -1 : 1) * e[2] * dt;
                e[3] += dt * 2;
                if (e[1] < -0.05 || e[1] > 1.05) spawnEndFx(e, false, won);
            }
        }
        for (Iterator<double[]> it = rings.iterator(); it.hasNext(); ) { double[] r = it.next(); if ((r[3] += dt) >= r[4]) it.remove(); }
        for (Iterator<Object[]> it = notices.iterator(); it.hasNext(); ) { Object[] n = it.next(); n[2] = (Double) n[2] + dt; if ((Double) n[2] > 2.6) it.remove(); }
        for (int t = 0; t < 2; t++) scorePop[t] = Math.max(0, scorePop[t] - 1.4 * dt);
        for (Iterator<Map.Entry<Integer, Double>> it = spawnT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Double> e = it.next();
            double left = e.getValue() - dt;
            if (left <= 0) it.remove(); else e.setValue(left);
        }
        room.step(dt);
        bubbles.values().removeIf(b -> (long) b[1] < now);
        smokeT -= dt;
        if (fx && smokeT <= 0 && !inLobby()) { // smoke and sparks rising from the braziers
            smokeT = 0.14;
            for (double[] w : MapData.WALLS) {
                double tx = w[0] + w[2] / 2, ty = w[1] - 16;
                addSoft(tx + (rnd.nextDouble() - 0.5) * 6, ty, (rnd.nextDouble() - 0.5) * 10, -22 - rnd.nextDouble() * 12, 1.1, new Color(120, 112, 150), 9);
                if (rnd.nextInt(3) == 0) addP(tx, ty + 4, (rnd.nextDouble() - 0.5) * 30, -50 - rnd.nextDouble() * 40, 0.6, new Color(255, 170, 70), 2);
            }
        }
        shake = Math.max(0, shake - 40 * dt);
        flash = Math.max(0, flash - 2.5 * dt);
        feed.removeIf(f -> (long) f[1] < now);
        repaint();
    }

    /**
     * Own shots are drawn from a local prediction so they appear on the click, while the server stays the
     * only judge of hits. A server bullet lying on a ghost's ray is that ghost's twin: it is hidden, not drawn twice.
     * A ghost the server never answers within about one round trip was rejected (cooldown etc.) and is dropped.
     * A ghost whose twin vanished (hit or wall on the server) is dropped too.
     */
    private void reconcileGhosts(double dt) {
        hidden.clear();
        boolean[] matchedNow = new boolean[ghosts.size()];
        if (last != null) {
            for (double[] b : last.bullets)
                for (int i = 0; i < ghosts.size(); i++) {
                    double[] g = ghosts.get(i);
                    double along = ((b[0] - g[0]) * g[2] + (b[1] - g[1]) * g[3]) / MapData.BULLET_SPEED;
                    double side = Math.abs((b[0] - g[0]) * g[3] - (b[1] - g[1]) * g[2]) / MapData.BULLET_SPEED;
                    if ((int) g[4] == teamOf(b) && side < 8 && along > -8 && along < MapData.BULLET_SPEED * g[5] + 14) {
                        matchedNow[i] = true;
                        hidden.add(b);
                        break;
                    }
                }
        }
        double grace = link.pingMs() / 1000.0 + 0.2;
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            double[] g = ghosts.get(i);
            g[5] += dt;
            g[7] = g[0] + g[2] * g[5];
            g[8] = g[1] + g[3] * g[5];
            if (matchedNow[i]) g[6] = 1;
            int target = inLobby() ? LobbyMap.targetHit(g[7] - g[2] * dt, g[8] - g[3] * dt, g[7], g[8]) : -1;
            if (target >= 0) room.hitTarget(target);
            if (target >= 0 || MapData.nearSolid(g[7], g[8], 0, walls())) { // wall or target impact, shown at once on our own screen
                impact(g[7], g[8], (int) g[4]);
                Sfx.play("wall");
                recentImpacts.add(new double[]{g[7], g[8], 0});
                ghosts.remove(i);
            } else if ((g[6] == 1 && !matchedNow[i]) || (g[6] == 0 && g[5] > grace)) ghosts.remove(i);
        }
    }

    private void impact(double x, double y, int team) {
        burst(x, y, Art.FX[team], 8, 110);
        burst(x, y, new Color(150, 140, 170), 4, 50);
        addP(x, y, 0, 0, 0.1, Color.WHITE, 9);
    }

    private void respawnEmber(double[] e, boolean anywhere) {
        e[0] = vX0() + rnd.nextDouble() * vW();
        e[1] = anywhere ? vY0() + rnd.nextDouble() * vH() : vY0() + vH() + 5;
        e[2] = 15 + rnd.nextDouble() * 30;
        e[3] = rnd.nextDouble() * 6.28;
    }

    private boolean down(int k) { return keys.contains(k); }

    private static int teamOf(double[] bullet) { return Math.max(0, Math.min(1, (int) bullet[2])); }

    // ---------- events inferred from snapshot differences ----------

    private void detectEvents(Snapshot a, Snapshot b) {
        if (a.phase != b.phase) {
            ghosts.clear();
            setCursor(Cursor.getDefaultCursor());
            if (b.phase == Snapshot.PLAYING) {
                announce("FIGHT!", GOLD);
                Sfx.play("score");
            } else if (b.phase == Snapshot.LOBBY) {
                say("Back in the lobby", GOLD);
                if (a.winner >= 0) rememberMatch(a);
            }
            if (b.phase == Snapshot.PLAYING) { roundStartLeft = b.timeLeftSec; mvpId = -1; }
        }
        if (a.phase == Snapshot.LOBBY && b.phase == Snapshot.LOBBY) {
            if (a.countdown > 0 && b.countdown <= 0) say("Countdown cancelled", new Color(255, 130, 100));
            if (b.countdown > 0 && Math.ceil(a.countdown) != Math.ceil(b.countdown)) Sfx.play("pickup"); // one beep per second
        }
        double[][] oldWalls = a.phase == Snapshot.LOBBY ? LobbyMap.WALLS : MapData.WALLS;
        for (Snapshot.P p : b.players) {
            Snapshot.P o = find(a, p.id);
            if (o == null) { say(p.name + " joined"); continue; }
            if (o.alive && !p.alive) {
                burst(o.x, o.y, Art.FX[o.team], 28, 230);
                addP(o.x, o.y, 0, 0, 0.12, Color.WHITE, 14);
                ring(o.x, o.y, o.team, 0.55, 58);
                Sfx.play("hit");
                say(p.name + " was eliminated", TEAM[o.team]);
                if (p.id == myId) { flash = 1; shake = 12; }
            } else if (!o.alive && p.alive) {
                burst(p.x, p.y, Art.FX[p.team], 18, 120);
                beams.add(new double[]{p.x, p.y + 12, p.team, 0.6});
                ring(p.x, p.y, p.team, 0.7, 50);
                spawnT.put(p.id, 0.7);
            }
            // Shield state transitions
            if (o.shieldSec <= 0 && p.shieldSec > 0) {
                burst(p.x, p.y, new Color(170, 70, 255), 24, 160);
                burst(p.x, p.y, new Color(80, 230, 255), 16, 120);
                Sfx.play("shield");
                say(p.name + " activated MAGIC SHIELD", new Color(170, 90, 255));
                if (p.id == myId) announce("MAGIC SHIELD", new Color(170, 90, 255));
            } else if (o.shieldSec > 0 && p.shieldSec <= 0) {
                burst(p.x, p.y, new Color(170, 60, 255), 20, 140);
                Sfx.play("expire");
                if (p.id == myId) say("MAGIC SHIELD EXPIRED");
            }
        }
        for (Snapshot.P o : a.players) if (find(b, o.id) == null) say(o.name + " left");

        for (double[] o : a.bullets) { // a bullet that vanished next to a wall hit it (hits on players show as eliminations)
            boolean still = false;
            for (double[] n : b.bullets) if (Math.hypot(n[0] - o[0], n[1] - o[1]) < 30) { still = true; break; }
            boolean ours = false;
            for (double[] r : recentImpacts) if (Math.hypot(r[0] - o[0], r[1] - o[1]) < 40) ours = true;
            if (!still && a.phase == Snapshot.LOBBY && b.phase == Snapshot.LOBBY) { // only targets and walls in the lobby
                boolean popped = false;
                for (int i = 0; i < LobbyMap.TARGETS.length; i++) {
                    double[] t = LobbyMap.TARGETS[i];
                    if (Math.hypot(t[0] - o[0], t[1] - o[1]) < t[2] + 26) {
                        room.hitTarget(i);
                        if (!ours) { burst(o[0], o[1], new Color(255, 220, 160), 10, 120); Sfx.play("wall"); }
                        popped = true;
                        break;
                    }
                }
                if (!popped && !ours && MapData.nearSolid(o[0], o[1], 24, oldWalls)) impact(o[0], o[1], teamOf(o));
            } else if (!still && a.phase != Snapshot.LOBBY) {
                boolean deflected = false;
                for (Snapshot.P p : b.players) {
                    if (p.shieldSec > 0 && Math.hypot(p.x - o[0], p.y - o[1]) < MapData.R + 12) {
                        burst(p.x, p.y, new Color(90, 230, 255), 14, 160);
                        burst(p.x, p.y, new Color(200, 100, 255), 8, 120);
                        Sfx.play("deflect");
                        deflected = true;
                        break;
                    }
                }
                if (!deflected && !ours && MapData.nearSolid(o[0], o[1], 24, oldWalls)) impact(o[0], o[1], teamOf(o));
            }
        }

        for (int t = 0; t < 2; t++) {
            Snapshot.F o = a.flags[t], n = b.flags[t];
            if (n.state == Snapshot.CARRIED && o.state != Snapshot.CARRIED) {
                say(name(b, n.carrier) + " took the " + TEAM_NAME[t] + " flag", GOLD);
                announce(n.carrier == myId ? "YOU TOOK THE " + TEAM_NAME[t] + " FLAG" : TEAM_NAME[t] + " FLAG TAKEN", n.carrier == myId ? GOLD : TEAM[t]);
                Sfx.play("pickup");
                double[] at = flagSpot(b, n);
                burst(at[0], at[1], Art.FX[t], 22, 170);
                ring(at[0], at[1], t, 0.7, 54);
            } else if (n.state == Snapshot.DROPPED && o.state == Snapshot.CARRIED) {
                say(TEAM_NAME[t] + " flag dropped", new Color(255, 120, 90));
                announce(TEAM_NAME[t] + " FLAG DROPPED", new Color(255, 130, 100));
                Sfx.play("drop");
                ring(n.x, n.y, t, 0.6, 40);
            } else if (n.state == Snapshot.BASE && o.state == Snapshot.DROPPED) {
                say(TEAM_NAME[t] + " flag returned", Art.FX[t]);
                announce(TEAM_NAME[t] + " FLAG RETURNED", TEAM[t]);
                burst(MapData.BASES[t][0], MapData.BASES[t][1], Art.FX[t], 20, 150);
                beams.add(new double[]{MapData.BASES[t][0], MapData.BASES[t][1] + 12, t, 0.8});
                ring(MapData.BASES[t][0], MapData.BASES[t][1], t, 0.9, 70);
            }
            int other = 1 - t; // the team that scores by carrying flag t home
            if (b.score[other] > a.score[other]) {
                say(TEAM_NAME[other] + " captured the flag!", GOLD);
                announce(TEAM_NAME[other] + " CAPTURED THE FLAG!", TEAM[other]);
                scorePop[other] = 1;
                ring(MapData.BASES[other][0], MapData.BASES[other][1], other, 1.1, 120);
                Sfx.play("score");
                burst(MapData.BASES[other][0], MapData.BASES[other][1], Art.FX[other], 60, 280);
                beams.add(new double[]{MapData.BASES[other][0], MapData.BASES[other][1] + 12, other, 1.4});
                shake = Math.max(shake, 7);
            }
        }
        if (b.winner >= 0 && a.winner < 0) matchEnded(a, b);
    }

    // ---------- match ending ----------

    private boolean won() {
        Snapshot.P me = last == null ? null : find(last, myId);
        return me != null && last.winner == me.team;
    }

    /** Who ended it: the capture that reached 3, or the clock. Sets the banner for the final moment. */
    private void matchEnded(Snapshot a, Snapshot b) {
        int w = b.winner, taken = 1 - w;   // the winners scored by bringing home the other team's flag
        endT = b.matchEndElapsedSec;
        finalScorer = -1;
        recorded = false;
        news = List.of();
        if (b.score[w] > a.score[w]) {
            finalBanner = "FINAL CAPTURE";
            if (a.flags[taken].state == Snapshot.CARRIED) finalScorer = a.flags[taken].carrier;   // unknown if that snapshot was lost
            finalSub = (finalScorer >= 0 ? name(b, finalScorer).toUpperCase() : TEAM_NAME[w]) + " BRINGS THE " + TEAM_NAME[taken] + " FLAG HOME";
        } else {
            finalBanner = "TIME!";
            finalSub = TEAM_NAME[w] + (b.score[0] == b.score[1] ? " WINS THE TIE-BREAK" : " WINS ON POINTS");
        }
        boolean won = won();
        for (double[] e : endFx) spawnEndFx(e, true, won);
        Sfx.play(won ? "win" : "expire");
        shake = Math.max(shake, 6);
    }

    private void spawnEndFx(double[] e, boolean anywhere, boolean won) {
        e[0] = rnd.nextDouble();
        e[1] = anywhere ? rnd.nextDouble() : (won ? 1.04 : -0.04);
        e[2] = (won ? 0.08 : 0.04) + rnd.nextDouble() * 0.06;
        e[3] = rnd.nextDouble() * 6.28;
    }

    /** Best first: points, then captures, then tags, then fewer deaths. */
    private static final Comparator<Snapshot.P> RANK = (p, q) ->
        p.score != q.score ? Integer.compare(q.score, p.score) : p.captures != q.captures ? Integer.compare(q.captures, p.captures)
            : p.tags != q.tags ? Integer.compare(q.tags, p.tags) : Integer.compare(p.deaths, q.deaths);

    private static Snapshot.P best(Snapshot s, Comparator<Snapshot.P> by) {
        Snapshot.P b = null;
        for (Snapshot.P p : s.players) if (b == null || by.compare(p, b) < 0) b = p;
        return b;
    }

    /** The lobby board and the MVP glow remember the match just played. */
    private void rememberMatch(Snapshot s) {
        int w = s.winner;
        Snapshot.P mvp = best(s, RANK);
        mvpId = mvp == null ? -1 : mvp.id;
        lastMatch = "LAST MATCH: " + TEAM_NAME[w] + " WON " + s.score[w] + " - " + s.score[1 - w] + (mvp != null ? "  ·  MVP " + mvp.name.toUpperCase() : "");
    }

    /** Where the flag was just picked up: the carrier if known, else its base. */
    private static double[] flagSpot(Snapshot s, Snapshot.F f) {
        Snapshot.P c = find(s, f.carrier);
        return c != null ? new double[]{c.x, c.y} : new double[]{f.x, f.y};
    }

    private void say(String s) { say(s, new Color(150, 140, 190)); }

    private void say(String s, Color c) {
        feed.add(new Object[]{s, System.nanoTime() + 4_000_000_000L, c});
        if (feed.size() > 5) feed.remove(0);
    }

    /** Large centre-screen message for the moments that matter. */
    private void announce(String text, Color c) {
        notices.add(new Object[]{text, c, 0.0});
        if (notices.size() > 2) notices.remove(0);
    }

    private void ring(double x, double y, int team, double life, double radius) {
        if (rings.size() < 24) rings.add(new double[]{x, y, team, 0, life, radius});
    }

    private void addP(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (parts.size() >= MAX_PARTICLES) return;
        Particle p = new Particle();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.max = p.life = life; p.c = c; p.size = size;
        parts.add(p);
    }

    /** Large, faint, growing puff (smoke). */
    private void addSoft(double x, double y, double vx, double vy, double life, Color c, int size) {
        if (parts.size() >= MAX_PARTICLES) return;
        Particle p = new Particle();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.max = p.life = life; p.c = c; p.size = size; p.soft = true;
        parts.add(p);
    }

    private void burst(double x, double y, Color c, int n, double speed) {
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, sp = speed * (0.3 + rnd.nextDouble() * 0.7);
            addP(x, y, Math.cos(ang) * sp, Math.sin(ang) * sp, 0.3 + rnd.nextDouble() * 0.4, c, 5);
        }
    }

    // ---------- interpolation ----------

    /** Positions of every player at (now - delay), lerped between the two snapshots around that time. */
    private Map<Integer, double[]> interpolate() {
        Map<Integer, double[]> out = new HashMap<>();
        if (buf.isEmpty()) return out;
        long rt = System.nanoTime() - INTERP_DELAY_NS;
        while (buf.size() > 2 && buf.get(1).arrivedNs <= rt) buf.remove(0);
        Snapshot a = buf.get(0), b = buf.size() > 1 ? buf.get(1) : a;
        double t = 0;
        if (a != b && b.arrivedNs > a.arrivedNs) t = Math.max(0, Math.min(1, (double) (rt - a.arrivedNs) / (b.arrivedNs - a.arrivedNs)));
        for (Snapshot.P pb : b.players) {
            Snapshot.P pa = find(a, pb.id);
            if (pa == null) pa = pb;
            out.put(pb.id, new double[]{pa.x + (pb.x - pa.x) * t, pa.y + (pb.y - pa.y) * t});
        }
        return out;
    }

    // ---------- drawing ----------

    /** Tells the panel which part of the world is on screen (world units), so the picture can fill the whole window. */
    void setView(double x0, double y0, double w, double h) {
        viewX = x0; viewY = y0; viewW = w; viewH = h;
        viewSet = true;
    }

    private int ox() { return (int) Math.round(vX0()); }
    private int oy() { return (int) Math.round(vY0()); }
    private double vX0() { return viewSet ? viewX : 0; }
    private double vY0() { return viewSet ? viewY : 0; }
    private double vW() { return viewSet ? viewW : (getWidth() > 0 ? getWidth() : MapData.W); }
    private double vH() { return viewSet ? viewH : (getHeight() > 0 ? getHeight() : MapData.H); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        paintWorld(g);
        paintOverlay(g);
        g.dispose();
    }

    private double shakeX, shakeY;

    /**
     * The battlefield: scenery, fighters, bullets, particles. GameView may draw this at a lower resolution than
     * the screen when the computer is slow; nothing in here is text, so that costs no sharpness.
     */
    void paintWorld(Graphics2D g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // where the local fighter stands, -0.5..0.5 across the arena: drives the parallax layers
        double camX = predReady ? (pred[0] - MapData.W / 2.0) / MapData.W : 0, camY = predReady ? (pred[1] - MapData.H / 2.0) / MapData.H : 0;
        if (last == null) {
            scenery.drawBack(g, vX0(), vY0(), vW(), vH(), 0, 0, time, false);
            g.dispose();
            return;
        }
        Snapshot s = last;
        AffineTransform base = g.getTransform();
        double sh = shakeOn ? shake : 0;
        shakeX = (rnd.nextDouble() - 0.5) * sh; shakeY = (rnd.nextDouble() - 0.5) * sh;
        g.translate(shakeX, shakeY);

        boolean lobby = s.phase == Snapshot.LOBBY;
        if (lobby) {
            room.drawWorld(g, vX0(), vY0(), vW(), vH(), time, fx);
            double[] mvpAt = mvpId >= 0 ? renderPos.get(mvpId) : null;   // last match's MVP stands in a gold glow
            if (mvpAt != null) Art.glow(g, GOLD, mvpAt[0], mvpAt[1] + 6, 80, (float) (0.55 + 0.2 * Math.sin(time * 2.6)));
            drawPlayers(g, s);
        } else {
            scenery.drawBack(g, vX0(), vY0(), vW(), vH(), camX, camY, time, fx);
            drawBases(g);   // the walls are part of the cached backdrop (Scenery.buildBack), not redrawn every frame
            drawTorches(g);
            Art.tent(g, MapData.TENT_X, MapData.TENT_Y, s.tentCooldown <= 0 ? 1.0 : 1.0 - s.tentCooldown / MapData.TENT_RECHARGE, time, fx);
            drawEntities(g, s);
        }
        for (double[] b : s.bullets) if (!hidden.contains(b)) drawBullet(g, b[0], b[1], bulletAngle(b), teamOf(b));
        for (double[] gh : ghosts) drawBullet(g, gh[7], gh[8], Math.atan2(gh[3], gh[2]), (int) gh[4]);
        for (Particle p : parts) {
            Composite old = g.getComposite();
            float a = (float) Math.max(0, Math.min(1, p.life / p.max));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, p.soft ? a * 0.35f : a));
            g.setColor(p.c);
            if (p.soft) {
                double r = p.size * (1.6 - a * 0.8);
                g.fill(new Ellipse2D.Double(p.x - r / 2, p.y - r / 2, r, r));
            } else g.fill(new Ellipse2D.Double(p.x - p.size / 2.0, p.y - p.size / 2.0, p.size, p.size));
            g.setComposite(old);
        }
        for (double[] r : rings) { // expanding ground ripples
            double k = r[3] / r[4], rad = r[5] * (1 - (1 - k) * (1 - k));
            Color c = r[2] < 0 ? Color.WHITE : Art.FX[(int) r[2]];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (200 * (1 - k))));
            g.setStroke(new BasicStroke((float) (3.5 * (1 - k) + 0.8)));
            g.draw(new Ellipse2D.Double(r[0] - rad, r[1] + 10 - rad * 0.5, rad * 2, rad));
        }
        g.setStroke(new BasicStroke(1));
        for (double[] b : beams) Art.beam(g, b[0], b[1], Art.FX[(int) b[2]], (float) Math.min(1, b[3]));
        if (fx) drawEmbers(g);

        if (!lobby) scenery.drawFront(g, camX, camY, fx);
        g.setTransform(base);

        // the edge shading (vignette) is baked into the cached backdrop: Scenery.buildBack
        int fx0 = (int) Math.floor(vX0()), fy0 = (int) Math.floor(vY0()), fw = (int) Math.ceil(vW()) + 1, fh = (int) Math.ceil(vH()) + 1;
        if (flash > 0) { g.setColor(new Color(255, 0, 0, (int) (110 * flash))); g.fillRect(fx0, fy0, fw, fh); }
        Snapshot.P me = find(s, myId);
        if (s.winner >= 0 && me != null && me.team != s.winner) { // the losing side sees the match in cold, dim light
            g.setColor(new Color(10, 12, 40, 80));
            g.fillRect(fx0, fy0, fw, fh);
        }
        g.dispose();
    }

    /**
     * Everything that is text or UI: names, HUD, scoreboard, victory and leaderboard. It is always drawn at the
     * screen's real pixel size (GameView hands in the final graphics), so glyph outlines are rasterised once, at
     * native resolution, with fractional metrics and kerning, and never resampled.
     */
    void paintOverlay(Graphics2D g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (last == null) {
            g.setFont(Type.display(24));
            Art.textCenter(g, "Waiting for server...", vX0(), vW(), vY0() + vH() / 2, Color.WHITE);
            g.dispose();
            return;
        }
        Snapshot s = last;
        AffineTransform hudBase = g.getTransform();
        g.translate(shakeX, shakeY); // names ride with the shaking battlefield, the HUD does not
        drawNames(g, s);
        drawBubbles(g);
        g.setTransform(hudBase);
        if (s.phase == Snapshot.LOBBY) {
            drawLobbyHud(g, s);
            drawChatBox(g);
            drawEscMenu(g);
            g.dispose();
            return;
        }
        if (s.winner >= 0) drawEnding(g, s);
        else {
            drawHud(g, s);
            if (showBoard) {
                g.translate(vX0() + (vW() - MapData.W) / 2, vY0() + (vH() - MapData.H) / 2);
                drawBoard(g, s);
                g.setTransform(hudBase);
            }
            drawChatBox(g);
        }
        drawEscMenu(g);
        g.dispose();
    }

    private void drawNames(Graphics2D g, Snapshot s) {
        g.setFont(Type.ui(12.5f, true));
        boolean lobby = s.phase == Snapshot.LOBBY;
        Protocol.Lobby l = link.lobby();
        for (Snapshot.P p : s.players) {
            double[] xy = renderPos.get(p.id);
            if (xy == null || !p.alive) continue;
            Color c = p.team == 0 ? new Color(255, 175, 165) : new Color(185, 230, 255);
            String label = p.name, title = null;
            if (lobby) { // in the lobby the name also says level, title, who is ready and who is the host
                Protocol.LobbyEntry e = entry(p.id);
                if (e != null) {
                    label = "Lv " + e.level() + "  " + label;
                    if (e.title() > 0 && e.title() < Profile.TITLES.length) title = Profile.TITLES[e.title()];
                }
                if (l != null && l.hostId() == p.id) label = "HOST  " + label;
                if (e != null && e.ready()) { label += "  READY"; c = new Color(130, 245, 150); }
                if (p.id == mvpId) { label += "  MVP"; if (e == null || !e.ready()) c = new Color(255, 225, 140); }
            }
            Art.namePlate(g, label, xy[0], xy[1] + 50, c);
            if (title != null) {
                Font keep = g.getFont();
                g.setFont(Type.ui(10f, true));
                Art.textCenter(g, title, xy[0] - 100, 200, xy[1] + 64, new Color(190, 170, 250));
                g.setFont(keep);
            }
        }
    }

    /** Recent chat shown in a speech bubble above the speaker. */
    private void drawBubbles(Graphics2D g) {
        g.setFont(Type.ui(12f, true));
        FontMetrics fm = g.getFontMetrics();
        for (Map.Entry<Integer, Object[]> b : bubbles.entrySet()) {
            double[] xy = renderPos.get(b.getKey());
            if (xy == null) continue;
            String text = (String) b.getValue()[0];
            double w = fm.stringWidth(text) + 18, h = 24, x = xy[0] - w / 2, y = xy[1] - 96;
            long left = (long) b.getValue()[1] - System.nanoTime();
            float a = (float) Math.max(0, Math.min(1, left / 400_000_000.0)); // fades out in its last 0.4 s
            Composite keep = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            Path2D.Double tail = new Path2D.Double();
            tail.moveTo(xy[0] - 6, y + h - 1); tail.lineTo(xy[0] + 6, y + h - 1); tail.lineTo(xy[0], y + h + 8); tail.closePath();
            g.setColor(new Color(245, 242, 255, 235));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
            g.fill(tail);
            g.setColor(new Color(60, 50, 100));
            g.draw(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
            Art.text(g, text, x + 9, y + 16.5, new Color(30, 24, 50));
            g.setComposite(keep);
        }
    }

    /** The chat line being typed (ENTER opens it, ENTER sends, ESC cancels). */
    private void drawChatBox(Graphics2D g) {
        if (!typing) return;
        double x = vX0() + 14, y = vY0() + vH() - 34, w = Math.min(520, vW() - 28);
        Art.plate(g, x, y, w, 26, 6, new Color(170, 150, 235), time, fx);
        g.setFont(Type.ui(12.5f, true));
        String caret = ((int) (time * 2.5) & 1) == 0 ? "_" : " ";
        Art.text(g, "SAY: " + chatText + caret, x + 10, y + 17.5, Color.WHITE);
    }

    /** Lobby screen furniture: room labels, who you are, ready and start buttons, countdown and controls. */
    private void drawLobbyHud(Graphics2D g, Snapshot s) {
        Protocol.Lobby l = link.lobby();
        room.drawLabels(g, l, nearLaptop(), s.countdown, lastMatch, time);
        final double x0 = vX0(), y0 = vY0(), w = vW(), h = vH(), cx = x0 + w / 2;
        Snapshot.P me = find(s, myId);
        int mt = me == null ? 0 : me.team;

        Art.plate(g, x0 + 14, y0 + 14, 210, 46, 9, TEAM[mt], time, fx);
        g.setFont(Art.title(15));
        Art.text(g, "LOBBY  -  YOU ARE " + TEAM_NAME[mt], x0 + 28, y0 + 33, TEAM[mt].brighter());
        g.setFont(Type.ui(11.5f, true));
        int ping = link.pingMs();
        Art.text(g, "PING " + ping + "ms" + (link.lagSimOn() ? " LAG" : "") + (amHost() ? "   YOU ARE THE HOST" : ""),
            x0 + 28, y0 + 50, ping > 150 ? Color.ORANGE : new Color(170, 175, 195));
        if (exits != null) {   // a visible way out for players who do not know about Esc
            leaveBtn.setRect(x0 + 14, y0 + 68, 130, 30);
            boolean hov = leaveBtn.contains(mouseX, mouseY) && esc == Esc.CLOSED;
            Art.plate(g, leaveBtn.x, leaveBtn.y, leaveBtn.width, leaveBtn.height, 7, hov ? new Color(255, 150, 135) : new Color(150, 95, 110), time, false);
            g.setFont(Type.ui(12.5f, true));
            Art.textCenter(g, "←  LEAVE", leaveBtn.x, leaveBtn.width, leaveBtn.y + 20, hov ? Color.WHITE : new Color(230, 200, 205));
        }

        double bw = 200, bh = 48, bx = x0 + w - bw - 18, by = y0 + h - bh - 18;
        boolean ready = amReady();
        readyBtn.setRect(bx, by, bw, bh);
        Color rc = ready ? new Color(110, 230, 130) : GOLD;
        lobbyButton(g, readyBtn, rc, hoverReady, ready ? "READY!" : "I'M READY", ready ? "click or R to cancel" : "click or press R");
        if (amHost()) {
            startBtn.setRect(bx - bw - 12, by, bw, bh);
            boolean ok = lobbyCanStart() && s.countdown <= 0;
            lobbyButton(g, startBtn, ok ? new Color(255, 120, 70) : new Color(110, 105, 135), hoverStart && ok,
                s.countdown > 0 ? "STARTING..." : "START MATCH", ok ? "everyone is ready" : s.countdown > 0 ? "get ready" : "full teams, all ready");
        } else startBtn.setRect(0, 0, 0, 0);
        drawModePicker(g, bx - bw - 12, by - 50, bw * 2 + 12);

        if (s.countdown > 0) { // big centre countdown, each number popping in
            int n = (int) Math.ceil(s.countdown);
            double frac = s.countdown - Math.floor(s.countdown), k = 1 + 0.35 * frac;
            g.setFont(Art.title(22));
            Art.textCenter(g, "MATCH STARTS IN", x0, w, y0 + h * 0.30, Color.WHITE);
            AffineTransform keep = g.getTransform();
            g.translate(cx, y0 + h * 0.30 + 125);
            g.scale(k, k);
            g.setFont(Type.numbers(96));
            if (fx) Art.glow(g, GOLD, 0, -34, 90, (float) (0.6 * frac));
            Art.textCenter(g, String.valueOf(n), -100, 200, 0, GOLD);
            g.setTransform(keep);
        }

        drawFeed(g, x0, y0, h);
        if (!typing) {
            g.setFont(Type.ui(11.5f, false));
            Art.textCenter(g, "WASD move   MOUSE shoot the targets   ENTER chat   R ready   E / C wardrobe   ESC menu",
                x0, w - bw * 2 - 30, y0 + h - 10, new Color(235, 232, 255, 200));
        }
    }

    private void lobbyButton(Graphics2D g, Rectangle2D.Double r, Color c, boolean hover, String label, String hint) {
        Art.plate(g, r.x, r.y, r.width, r.height, 10, hover ? c.brighter() : c, time, fx);
        if (hover) {
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
            g.fill(Art.chamfer(r.x, r.y, r.width, r.height, 10));
        }
        g.setFont(Art.title(17));
        Art.textCenter(g, label, r.x, r.width, r.y + 23, Color.WHITE);
        g.setFont(Type.ui(10.5f, true));
        Art.textCenter(g, hint, r.x, r.width, r.y + 39, new Color(200, 196, 225));
    }

    /** Team light pools, rune rings and crystals at each home base. */
    private void drawBases(Graphics2D g) {
        for (int t = 0; t < 2; t++) {
            double[] b = MapData.BASES[t];   // the pooled light and the disc are baked into the backdrop; the ring and crystals animate
            Stroke old = g.getStroke();
            g.setColor(Art.FX[t]);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 6}, (float) (t == 0 ? time * 12 % 14 : 14 - time * 12 % 14)));
            g.draw(new Ellipse2D.Double(b[0] - b[2], b[1] - b[2], b[2] * 2, b[2] * 2));
            g.setStroke(old);
            for (int k = -1; k <= 1; k += 2) drawCrystal(g, b[0] + (t == 0 ? 34 : -34), b[1] + k * 62, t);
        }
    }

    private void drawCrystal(Graphics2D g, double x, double y, int t) {
        double pulse = 0.7 + 0.3 * Math.sin(time * 2.5 + x + y);
        if (fx) Art.glow(g, Art.FX[t], x, y, 38, (float) (0.5 * pulse));
        Path2D.Double d = new Path2D.Double();
        d.moveTo(x, y - 14); d.lineTo(x + 8, y - 1); d.lineTo(x, y + 12); d.lineTo(x - 8, y - 1); d.closePath();
        g.setColor(new Color(20, 14, 40));
        g.fill(d);
        g.setColor(new Color(Art.FX[t].getRed(), Art.FX[t].getGreen(), Art.FX[t].getBlue(), (int) (255 * pulse)));
        g.fill(new Rectangle2D.Double(x - 3, y - 7, 6, 12));
        g.setColor(Color.WHITE);
        g.fillRect((int) x - 1, (int) y - 8, 2, 3);
        g.setColor(new Color(255, 255, 255, 90));
        g.draw(d);
    }

    /** A brazier on top of every wall block. */
    private void drawTorches(Graphics2D g) {
        for (double[] w : MapData.WALLS) Art.torchFlame(g, w[0] + w[2] / 2, w[1] + 3, time, 1.0, fx);
    }

    private void drawEmbers(Graphics2D g) {
        for (double[] e : embers) {
            g.setColor(e[0] < MapData.W / 2.0 ? new Color(255, 150, 60, 170) : new Color(110, 220, 255, 150));
            g.fill(new Ellipse2D.Double(e[0], e[1], 2.4, 2.4));
        }
    }

    private void drawEntities(Graphics2D g, Snapshot s) {
        for (int t = 0; t < 2; t++) Art.pedestal(g, MapData.BASES[t][0], MapData.BASES[t][1], t);

        for (int t = 0; t < 2; t++) { // flags in the world, from the authoritative flag state
            Snapshot.F f = s.flags[t];
            if (f.state == Snapshot.BASE) Art.flag(g, MapData.BASES[t][0], MapData.BASES[t][1], t, time);
            else if (f.state == Snapshot.DROPPED) {
                double pulse = 0.5 + 0.5 * Math.sin(time * 5);
                Art.beam(g, f.x, f.y + 12, Art.FX[t], (float) (0.25 + 0.2 * pulse));
                g.setColor(Art.FX[t]);
                g.draw(new Ellipse2D.Double(f.x - 12 - pulse * 4, f.y + 6 - pulse * 2, 24 + pulse * 8, 12 + pulse * 4));
                Art.flag(g, f.x, f.y, t, time);
            }
        }
        drawPlayers(g, s);
        for (int t = 0; t < 2; t++) { // carried flags ride above the carrier's head
            Snapshot.F f = s.flags[t];
            double[] xy = f.state == Snapshot.CARRIED ? renderPos.get(f.carrier) : null;
            if (xy == null) continue;
            if (fx) Art.glow(g, Art.FX[t], xy[0] + 8, xy[1] - 58, 42, 0.5f);
            Art.flag(g, xy[0] + 5, xy[1] - 58, t, time);
        }
    }

    private void drawPlayers(Graphics2D g, Snapshot s) {
        List<Snapshot.P> order = new ArrayList<>(Arrays.asList(s.players)); // lower on screen draws on top
        order.removeIf(p -> renderPos.get(p.id) == null);
        order.sort(Comparator.comparingDouble(p -> renderPos.get(p.id)[1]));
        for (Snapshot.P p : order) drawPlayer(g, p, renderPos.get(p.id), p.id == myId);
    }

    /**
     * Fighters are 36x48 world pixels (the old 24x32 at 1.5x). The server's hit circle (radius 14) is centred
     * on (px, py), which is the fighter's chest: the head rises above the circle and the boots sit just below it.
     */
    private void drawPlayer(Graphics2D g, Snapshot.P p, double[] xy, boolean mine) {
        double[] a = anim.get(p.id);
        boolean moving = a != null && a[1] > 0;
        double aim = mine ? Math.atan2(mouseY - xy[1], mouseX - xy[0]) : p.aim;
        boolean over = last != null && last.winner >= 0;
        if (over && p.team == last.winner && p.alive) {
            aim = -Math.PI / 2 + Math.sin(time * 5 + p.id) * 0.18; // victorious weapon raise
            if (fx && rnd.nextInt(3) == 0) addP(xy[0] + (rnd.nextDouble() - 0.5) * 14, xy[1] + 10, (rnd.nextDouble() - 0.5) * 8, -25 - rnd.nextDouble() * 20, 0.4, new Color(180, 80, 255), 3);
        }
        double px = xy[0], py = xy[1];
        final double SH = Art.SPR_H * PX;

        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new Ellipse2D.Double(px - 22, py + 19, 44, 14)); // shadow under the boots
        if (!p.alive) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            if (!drawKnight(g, p.team, 3, px, py + 18, false, 0, 0)) pixelArt(g, Art.spriteCrisp(p.team, 3, spriteK(g)), px, py + 18, false);
            g.setComposite(old);
            return;
        }
        double st = spawnT.getOrDefault(p.id, 0.0);
        float alpha = st > 0 ? (float) (0.25 + 0.75 * (1 - st / 0.7)) * (((int) (time * 30) & 1) == 0 ? 1f : 0.7f) : (over && p.team != last.winner ? 0.7f : 1f);
        Composite keep = g.getComposite();

        g.setColor(new Color(Art.FX[p.team].getRed(), Art.FX[p.team].getGreen(), Art.FX[p.team].getBlue(), 36)); // team ring under the feet
        g.fill(new Ellipse2D.Double(px - 25, py + 17, 50, 17));
        g.setColor(mine ? Color.WHITE : Art.FX[p.team]);
        g.setStroke(new BasicStroke(mine ? 2 : 1.5f));
        g.draw(new Ellipse2D.Double(px - 25, py + 17, 50, 17));
        g.setStroke(new BasicStroke(1));

        int phase = a == null ? 0 : (int) a[0];
        int frame = moving ? 1 + (phase & 1) : 0;
        double bob = moving ? ((phase & 1) == 0 ? 0 : -3.5) : Math.sin(time * 3 + p.id) * 1.8;
        boolean left = Math.cos(aim) < 0;
        double cy0 = py - 5 + bob, y0 = cy0 - SH / 2;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        Protocol.LobbyEntry look = entry(p.id);   // the hat chosen at the lobby laptop, worn in the match too
        if (!drawKnight(g, p.team, frame, px, cy0, left, look == null ? 0 : look.hat(), look == null ? 0 : look.hatColor())) {
            pixelArt(g, Art.spriteCrisp(p.team, frame, spriteK(g)), px, cy0, left);   // assets/knight*.png missing: the old sprite and hats
            if (look != null) LobbyRoom.hat(g, look.hat(), look.hatColor(), px, y0 + 6);
        }
        g.setComposite(keep);

        // Body energy cycle / magical invulnerability ring
        if (p.shieldSec > 0) {
            Art.shieldRing(g, px, py, p.shieldSec, MapData.SHIELD_DURATION, time);
        }

        double ft = fireT.getOrDefault(p.id, 0.0);
        double kick = -4 * Math.min(1, ft / 0.08); // recoil: the barrel jumps back
        AffineTransform old = g.getTransform();
        g.translate(px, py - 4 + bob);
        g.rotate(aim);
        g.scale(GUN_SCALE, GUN_SCALE);
        if (left) g.scale(1, -1); // keeps the grip underneath when aiming leftwards
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        if (!Art.gunArt(g, p.team, kick, ft > 0)) Art.gun(g, p.team, kick, ft > 0);
        g.setComposite(keep);
        g.setTransform(old);
        if (ft > 0 && fx) Art.glow(g, Art.FX[p.team], px + Math.cos(aim) * 44, py - 4 + bob + Math.sin(aim) * 44, 38, 0.9f);

        if (mine && !isCarrier(p.id)) { // bobbing chevron marks the local fighter
            double cy = y0 - 12 + Math.sin(time * 4) * 2;
            Path2D.Double ch = new Path2D.Double();
            ch.moveTo(px - 8, cy - 8); ch.lineTo(px + 8, cy - 8); ch.lineTo(px, cy + 3); ch.closePath();
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new AffineTransform(1, 0, 0, 1, 1, 1).createTransformedShape(ch));
            g.setColor(Color.WHITE);
            g.fill(ch);
        }
    }

    /**
     * Draws the knight picture (red or blue by team) for a fighter whose sprite centre is (cx, cy), with the chosen hat.
     * frame: 0 idle, 1 and 2 running (a small lean), 3 knocked out (tipped onto its side). False if the picture is missing.
     */
    private boolean drawKnight(Graphics2D g, int team, int frame, double cx, double cy, boolean left, int hat, int hatColor) {
        double h = Art.SPR_H * PX;
        BufferedImage a = MenuScreen.knightArt((int) Math.round(h * g.getTransform().getScaleY()), team == 1);
        if (a == null) return false;
        double w = h * a.getWidth() / a.getHeight();
        AffineTransform keep = g.getTransform();
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        if (frame == 3) {
            g.translate(cx, cy);
            g.rotate(Math.PI / 2);
            g.translate(-w / 2, -h / 2 + 4);
        } else {
            g.translate(cx, cy + h / 2);   // stand on the feet
            if (frame > 0) g.rotate((frame == 1 ? -1 : 1) * 0.07);
            if (left) g.scale(-1, 1);
            g.translate(-w / 2, -h);
        }
        AffineTransform at = g.getTransform();
        g.scale(w / a.getWidth(), h / a.getHeight());
        g.drawImage(a, 0, 0, null);
        if (frame != 3 && hat > 0) {   // hats are drawn in the picture's own pixels
            g.setTransform(at);
            g.scale(w / MenuScreen.KNIGHT_W, h / MenuScreen.KNIGHT_H);
            MenuScreen.knightHat(g, hat, LobbyRoom.HAT_COLORS[Math.floorMod(hatColor, LobbyRoom.HAT_COLORS.length)]);
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        g.setTransform(keep);
        return true;
    }

    /** World units per sprite pixel: fighters are 43x58 world units (the original 24x32 at 1.8x). */
    static final double PX = 2.4, GUN_SCALE = 1.2;

    /** Whole screen pixels per sprite pixel, so each one stays a crisp square. */
    private static int spriteK(Graphics2D g) { return Math.max(1, (int) Math.round(PX * g.getTransform().getScaleX())); }

    /** Copies a pre-scaled pixel-art image to the screen 1:1, centred on world point (cx, cy), optionally mirrored. */
    private static void pixelArt(Graphics2D g, BufferedImage im, double cx, double cy, boolean mirror) {
        AffineTransform at = g.getTransform();
        int w = im.getWidth(), h = im.getHeight();
        int dx = (int) Math.round(at.getTranslateX() + cx * at.getScaleX() - w / 2.0), dy = (int) Math.round(at.getTranslateY() + cy * at.getScaleY() - h / 2.0);
        g.setTransform(new AffineTransform());
        if (mirror) g.drawImage(im, dx + w, dy, -w, h, null); else g.drawImage(im, dx, dy, null);
        g.setTransform(at);
    }

    private boolean isCarrier(int id) {
        if (last == null) return false;
        for (Snapshot.F f : last.flags) if (f.state == Snapshot.CARRIED && f.carrier == id) return true;
        return false;
    }

    /** Travel direction of a server bullet, from where it was one snapshot ago; 0 if it just spawned. */
    private double bulletAngle(double[] b) {
        if (prev != null)
            for (double[] o : prev.bullets)
                if (teamOf(o) == teamOf(b) && Math.hypot(b[0] - o[0], b[1] - o[1]) < 30 && (b[0] != o[0] || b[1] != o[1]))
                    return Math.atan2(b[1] - o[1], b[0] - o[0]);
        return 0;
    }

    /** Glowing energy bolt with a fading tail; its bright core is exactly at (x, y), the collision position. */
    private void drawBullet(Graphics2D g, double x, double y, double ang, int t) {
        if (fx) Art.glow(g, Art.FX[t], x, y, 30, 0.8f);
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.rotate(ang);
        Color c = Art.FX[t];
        if (fx) {
            g.setPaint(new GradientPaint(-52, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0), 0, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 190)));
            g.fill(new Rectangle2D.Double(-52, -4, 52, 8));
        }
        double len = 21 + Math.sin(time * 40);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
        g.fill(new RoundRectangle2D.Double(-len / 2 - 5, -7, len + 6, 14, 14, 14)); // soft halo
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(-len / 2 - 3, -4.5, len, 9, 9, 9));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(-3, -2.2, 12, 4.4, 4.4, 4.4));
        g.setTransform(old);
    }

    // ---------- HUD ----------

    private static final Color GOLD = new Color(255, 205, 90);

    private void drawHud(Graphics2D g, Snapshot s) {
        Snapshot.P me = find(s, myId);
        int mt = me == null ? 0 : me.team;
        final double x0 = vX0(), y0 = vY0(), w = vW(), h = vH(), cx = x0 + w / 2;
        final double PW = 192, PH = 46, TW = 100, GAP = 8, top = y0 + 22;

        // top centre: red score | timer | blue score
        boolean low = s.timeLeftSec <= 30 && s.winner < 0;
        Color timerC = low ? new Color(255, 80, 80) : new Color(170, 150, 235);
        Art.plate(g, cx - TW / 2, top - 4, TW, PH + 8, 12, timerC, time, fx);
        g.setFont(Type.numbers(31));
        Color tc = low && ((int) (time * 3) & 1) == 0 ? new Color(255, 120, 110) : Color.WHITE;
        Art.textCenter(g, String.format("%d:%02d", s.timeLeftSec / 60, s.timeLeftSec % 60), (int) (cx - TW / 2), (int) TW, (int) (top + PH / 2 + 10), tc);

        for (int t = 0; t < 2; t++) {
            double px = t == 0 ? cx - TW / 2 - GAP - PW : cx + TW / 2 + GAP;
            Art.plate(g, px, top, PW, PH, 9, TEAM[t], time, fx);
            Snapshot.F f = s.flags[t]; // this team's own flag
            boolean home = f.state == Snapshot.BASE, taken = f.state == Snapshot.CARRIED;
            Color ic = home ? TEAM[t] : taken ? GOLD : (((int) (time * 4) & 1) == 0 ? new Color(255, 110, 80) : new Color(255, 180, 150));
            double iconX = t == 0 ? px + 24 : px + PW - 24;
            if (!home && fx) Art.glow(g, ic, iconX, top + PH / 2, 22, 0.5f);
            Art.flagIcon(g, iconX, top + PH / 2, 26, ic, time);
            String name = TEAM_NAME[t], st = home ? "FLAG SAFE" : taken ? "FLAG TAKEN" : "FLAG DROPPED";
            Color stc = home ? new Color(170, 175, 195) : taken ? GOLD : new Color(255, 130, 100);
            g.setFont(Art.title(15));
            FontMetrics fm = g.getFontMetrics();
            double nx = t == 0 ? px + 46 : px + PW - 46 - fm.getStringBounds(name, g).getWidth();
            Art.text(g, name, nx, top + 19, TEAM[t]);
            g.setFont(Type.ui(10.5f, true));
            double stw = g.getFontMetrics().getStringBounds(st, g).getWidth();
            Art.text(g, st, t == 0 ? px + 46 : px + PW - 46 - stw, top + 35, stc);

            AffineTransform keep = g.getTransform(); // score, bouncing when the team captures
            g.setFont(Type.numbers(35));
            String sc = String.valueOf(s.score[t]);
            fm = g.getFontMetrics();
            double sx = t == 0 ? px + PW - 18 - fm.getStringBounds(sc, g).getWidth() / 2.0 : px + 18 + fm.getStringBounds(sc, g).getWidth() / 2.0, sy = top + 35;
            g.translate(sx, sy - 11);
            double k = 1 + 0.5 * scorePop[t];
            g.scale(k, k);
            if (scorePop[t] > 0 && fx) Art.glow(g, TEAM[t], 0, 0, 30, (float) (0.8 * scorePop[t]));
            Art.text(g, sc, -fm.getStringBounds(sc, g).getWidth() / 2, 12, scorePop[t] > 0 ? Color.WHITE : TEAM[t].brighter());
            g.setTransform(keep);
        }

        // top left: who you are
        double redX = cx - TW / 2 - GAP - PW, iy = x0 + 14 + 196 < redX - 6 ? top : top + PH + 8; // narrow windows: tuck it under the red score
        Art.plate(g, x0 + 14, iy, 196, PH, 9, TEAM[mt], time, fx);
        g.setFont(Art.title(15));
        Art.text(g, "YOU - " + TEAM_NAME[mt] + " TEAM", (int) x0 + 28, (int) iy + 19, TEAM[mt].brighter());
        g.setFont(Type.ui(11.5f, true));
        int ping = link.pingMs();
        String stats = me == null ? "" : "  K " + me.tags + "  C " + me.captures;
        Art.text(g, "PING " + ping + "ms" + (link.lagSimOn() ? " LAG" : "") + stats, (int) x0 + 28, (int) iy + 36, ping > 150 ? Color.ORANGE : new Color(170, 175, 195));

        // bottom left: compact controls guide
        g.setFont(Type.ui(11.5f, false));
        if (!typing) Art.textCenter(g, "WASD move   MOUSE aim/fire   TAB scores   ENTER chat   ESC menu   F11 fullscreen   M music " + (Music.isOn() ? "on" : "off") + "   F fx " + (fx ? "on" : "off") + "   V shake " + (shakeOn ? "on" : "off") + "   L lag",
            x0, w, y0 + h - 10, new Color(235, 232, 255, 200));

        drawFeed(g, x0, y0, h);
        drawNotices(g, cx, top, PH);
        drawStatusBanner(g, s, me, mt, cx, y0, h);
    }

    /** Event feed, bottom left: joins, eliminations, flags and chat. */
    private void drawFeed(Graphics2D g, double x0, double y0, double h) {
        g.setFont(Type.ui(12.5f, true));
        int n = feed.size();
        for (int i = 0; i < n; i++) {
            Object[] f = feed.get(i);
            String t = (String) f[0];
            Color fc = (Color) f[2];
            double fw = g.getFontMetrics().stringWidth(t) + 26, fy = y0 + h - 52 - (n - 1 - i) * 26;
            Path2D.Double pl = Art.chamfer(x0 + 14, fy, fw, 21, 5);
            g.setColor(new Color(6, 6, 18, 190));
            g.fill(pl);
            g.setColor(fc);
            g.fillRect((int) x0 + 14, (int) fy + 3, 3, 15);
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), 120));
            g.draw(pl);
            Art.text(g, t, (int) x0 + 24, (int) fy + 15, Color.WHITE);
        }
    }

    /** Big announcements: slide in under the timer, fade out. */
    private void drawNotices(Graphics2D g, double cx, double top, double PH) {
        for (int i = 0; i < notices.size(); i++) {
            Object[] nt = notices.get(i);
            double age = (Double) nt[2];
            float a = (float) Math.min(1, Math.min(age / 0.15, (2.6 - age) / 0.6));
            if (a <= 0) continue;
            Color c = (Color) nt[1];
            double ny = top + PH + 36 + (notices.size() - 1 - i) * 40 + (1 - Math.min(1, age / 0.2)) * -10;
            g.setFont(Art.title(26));
            FontMetrics fm = g.getFontMetrics();
            double bw = fm.stringWidth((String) nt[0]) + 120;
            Composite keep = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.setPaint(new LinearGradientPaint((float) (cx - bw / 2), 0, (float) (cx + bw / 2), 0, new float[]{0f, 0.2f, 0.8f, 1f},
                new Color[]{new Color(4, 4, 16, 0), new Color(4, 4, 16, 190), new Color(4, 4, 16, 190), new Color(4, 4, 16, 0)}));
            g.fill(new Rectangle2D.Double(cx - bw / 2, ny - 27, bw, 38));
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
            g.fill(new Rectangle2D.Double(cx - bw * 0.4, ny - 28, bw * 0.8, 1.5));
            g.fill(new Rectangle2D.Double(cx - bw * 0.4, ny + 10, bw * 0.8, 1.5));
            Art.textCenter(g, (String) nt[0], (int) (cx - bw / 2), (int) bw, (int) ny, c.brighter());
            g.setComposite(keep);
        }
    }

    /** Bottom centre: personal status (carrying the flag, eliminated). */
    private void drawStatusBanner(Graphics2D g, Snapshot s, Snapshot.P me, int mt, double cx, double y0, double h) {
        String banner = null;
        for (Snapshot.F f : s.flags) if (f.state == Snapshot.CARRIED && f.carrier == myId) banner = "YOU HAVE THE FLAG - RUN HOME";
        if (me != null && !me.alive) banner = "ELIMINATED - RESPAWNING";
        if (banner != null && s.winner < 0) {
            g.setFont(Art.title(18));
            int bw = g.getFontMetrics().stringWidth(banner) + 44;
            boolean good = banner.startsWith("YOU");
            Color bc = good ? GOLD : TEAM[mt];
            if (good) bc = ((int) (time * 3) & 1) == 0 ? GOLD : new Color(255, 235, 170);
            Art.plate(g, cx - bw / 2.0, y0 + h - 78, bw, 34, 9, bc, time, fx);
            Art.textCenter(g, banner, (int) (cx - bw / 2.0), bw, (int) (y0 + h - 55), Color.WHITE);
        }
    }

    private void drawBoard(Graphics2D g, Snapshot s) {
        Art.panel(g, 150, 100, 500, 400, Art.BORDER);
        for (int t = 0; t < 2; t++) {
            int x = 170 + t * 250, y = 135;
            g.setFont(Art.font(16));
            Art.text(g, TEAM_NAME[t] + "  " + s.score[t], x, y, TEAM[t]);
            g.setFont(Art.font(12));
            for (Snapshot.P p : s.players) if (p.team == t) {
                y += 24;
                Art.text(g, p.name, x, y, Color.WHITE);
                Art.text(g, p.captures + "C " + p.tags + "T", x + 100, y, new Color(200, 205, 220));
                Art.text(g, p.pingMs + "ms", x + 170, y, new Color(200, 205, 220));
            }
        }
    }

    // ---------- match ending: drawing (docs/ending-design.html) ----------

    private final Map<String, Shape> wordShapes = new HashMap<>();   // VICTORY / DEFEAT outlines, built once

    private Shape word(String text, float size) {
        return wordShapes.computeIfAbsent(text + size, k -> new java.awt.font.TextLayout(text, Type.logo(size),
            new java.awt.font.FontRenderContext(null, true, true)).getOutline(null));
    }

    private static double ease(double t) { t = Math.max(0, Math.min(1, t)); return 1 - Math.pow(1 - t, 3); }

    private static Composite alpha(double a) { return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, a))); }

    private void drawEnding(Graphics2D g, Snapshot s) {
        final double x0 = vX0(), y0 = vY0(), w = vW(), h = vH();
        backBtn.setRect(0, 0, 0, 0);
        Composite keep = g.getComposite();
        if (endT < END_FINAL) drawFinalMoment(g, endT, x0, y0, w, h);
        else if (endT < END_RESULTS) drawVerdict(g, s, endT - END_FINAL, x0, y0, w, h);
        else drawResults(g, s, endT - END_RESULTS, x0, y0, w, h);
        g.setComposite(keep);
    }

    /** 0-2 s: colour drains, film bars slide in, the scorer gets a gold ring, a banner names them; a white flash first. */
    private void drawFinalMoment(Graphics2D g, double t, double x0, double y0, double w, double h) {
        g.setColor(new Color(16, 12, 30, (int) (95 * ease(t / 0.3))));
        g.fill(new Rectangle2D.Double(x0, y0, w, h));
        double[] at = finalScorer >= 0 ? renderPos.get(finalScorer) : null;
        if (at != null) {
            double k = (t * 1.4) % 1, rx = 30 + 22 * k;
            if (fx) Art.glow(g, GOLD, at[0], at[1], 70, 0.6f);
            g.setStroke(new BasicStroke(3.5f));
            g.setColor(new Color(255, 205, 90, (int) (255 * (1 - k))));
            g.draw(new Ellipse2D.Double(at[0] - rx, at[1] + 22 - rx * 0.33, rx * 2, rx * 0.66));
            g.setStroke(new BasicStroke(1));
        }
        double bar = h * 0.09 * ease(t / 0.25);
        g.setColor(Color.BLACK);
        g.fill(new Rectangle2D.Double(x0, y0, w, bar));
        g.fill(new Rectangle2D.Double(x0, y0 + h - bar, w, bar));

        double a = ease((t - 0.12) / 0.3), by = y0 + h * 0.36, bw = w * (0.55 + 0.25 * a);
        g.setComposite(alpha(a));
        double bx = x0 + (w - bw) / 2;
        g.setPaint(new LinearGradientPaint((float) bx, 0, (float) (bx + bw), 0, new float[]{0f, .2f, .8f, 1f},
            new Color[]{new Color(4, 4, 16, 0), new Color(4, 4, 16, 215), new Color(4, 4, 16, 215), new Color(4, 4, 16, 0)}));
        g.fill(new Rectangle2D.Double(bx, by, bw, 104));
        g.setPaint(new LinearGradientPaint((float) bx, 0, (float) (bx + bw), 0, new float[]{0f, .5f, 1f},
            new Color[]{new Color(255, 205, 90, 0), GOLD, new Color(255, 205, 90, 0)}));
        g.fill(new Rectangle2D.Double(bx + bw * 0.15, by, bw * 0.7, 2));
        g.fill(new Rectangle2D.Double(bx + bw * 0.15, by + 102, bw * 0.7, 2));
        g.setFont(Type.royal(46, true, 0.28f));
        if (fx) Art.glow(g, GOLD, x0 + w / 2, by + 44, 160, 0.25f);
        Art.textCenter(g, finalBanner, x0, w, by + 58, GOLD);
        g.setFont(Type.hud(21, true, 0.14f));
        Art.textCenter(g, finalSub, x0, w, by + 88, new Color(220, 214, 240));
        g.setComposite(alpha(0.85 * (1 - t / 0.35)));   // the flash at the very first moment
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(x0, y0, w, h));
    }

    /** 2-7 s: VICTORY (gold rays, banners unroll, the crown drops) or DEFEAT (cold room, torn banners fall, ash). */
    private void drawVerdict(Graphics2D g, Snapshot s, double t, double x0, double y0, double w, double h) {
        boolean won = won();
        int winner = s.winner, side = won ? winner : 1 - winner;
        double cx = x0 + w / 2, a0 = ease(t / 0.35);
        g.setComposite(alpha(a0));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, y0 + h * 0.45), (float) (Math.max(w, h) * 0.75), new float[]{0f, 1f},
            won ? new Color[]{new Color(74, 34, 16), new Color(8, 4, 10)} : new Color[]{new Color(26, 34, 54), new Color(4, 5, 10)}));
        g.fill(new Rectangle2D.Double(x0, y0, w, h));
        if (won) {   // slowly turning rays of light
            AffineTransform k = g.getTransform();
            g.translate(cx, y0 + h * 0.42);
            g.rotate(time * 0.12);
            g.setColor(new Color(255, 205, 90, 26));
            double r = Math.max(w, h);
            for (int i = 0; i < 20; i++) {
                double a1 = i * Math.PI * 2 / 20, a2 = a1 + 0.12;
                Path2D.Double wedge = new Path2D.Double();
                wedge.moveTo(0, 0); wedge.lineTo(Math.cos(a1) * r, Math.sin(a1) * r); wedge.lineTo(Math.cos(a2) * r, Math.sin(a2) * r); wedge.closePath();
                g.fill(wedge);
            }
            g.setTransform(k);
        }
        for (int dir = -1; dir <= 1; dir += 2) {   // a team banner on each side
            AffineTransform k = g.getTransform();
            double bx = cx + dir * w * 0.33, bwid = 96, len = h * 0.52;
            g.translate(bx, y0);
            if (won) len *= ease((t - 0.1) / 0.8);
            else {
                double fall = Math.max(0, t - 0.5);
                g.rotate(-dir * Math.min(0.45, fall * 0.4));
                g.translate(0, fall * fall * 120);
                g.setComposite(alpha(a0 * Math.max(0, 1 - fall / 3)));
            }
            Color c = TEAM[side];
            if (!won) c = new Color(c.getRed() / 2 + 20, c.getGreen() / 2 + 25, c.getBlue() / 2 + 40);
            g.setPaint(new GradientPaint(0, 0, c, 0, (float) Math.max(1, len), c.darker().darker()));
            Path2D.Double b = new Path2D.Double();
            b.moveTo(-bwid / 2, 0); b.lineTo(bwid / 2, 0);
            if (won) { b.lineTo(bwid / 2, len); b.lineTo(0, len - 30); b.lineTo(-bwid / 2, len); }
            else {   // torn edge
                b.lineTo(bwid / 2, len * 0.7); b.lineTo(bwid * 0.3, len * 0.78); b.lineTo(bwid * 0.38, len * 0.9); b.lineTo(bwid * 0.05, len);
                b.lineTo(-bwid * 0.12, len * 0.82); b.lineTo(-bwid * 0.3, len * 0.95); b.lineTo(-bwid / 2, len * 0.74);
            }
            b.closePath();
            g.fill(b);
            g.setColor(won ? GOLD : new Color(130, 120, 90));
            g.fill(new Rectangle2D.Double(-bwid / 2, 0, bwid, 10));
            g.setTransform(k);
            g.setComposite(alpha(a0));
        }
        drawEndFx(g, x0, y0, w, h, won);
        if (won) {   // the crown drops in with a bounce
            double k = (t - 0.2) / 0.55, drop = k >= 1 ? 0 : (1 - ease(k)) * -420 + Math.sin(Math.min(1, Math.max(0, k)) * Math.PI) * 18;
            MenuScreen.crown(g, cx, y0 + h * 0.24 + drop, 1.5);
        }
        Shape word = word(won ? "VICTORY" : "DEFEAT", 118);
        Rectangle2D wb = word.getBounds2D();
        double wa = ease((t - 0.3) / 0.45), sc = won ? 1 + 0.45 * (1 - wa) : 1;
        double wy = y0 + h * 0.47 + (won ? 0 : -40 * (1 - wa)) + (!won && t > 0.75 && t < 1.0 ? Math.sin(t * 90) * 4 : 0);
        AffineTransform k = g.getTransform();
        g.translate(cx, wy);
        g.scale(sc, sc);
        g.translate(-wb.getCenterX(), -wb.getCenterY());
        g.setComposite(alpha(wa));
        if (won && fx) Art.glow(g, new Color(255, 190, 80), wb.getCenterX(), wb.getCenterY(), 260, 0.35f);
        AffineTransform k2 = g.getTransform();
        g.translate(0, 6);
        g.setColor(won ? new Color(94, 61, 12) : new Color(20, 27, 42));
        g.fill(word);
        g.setTransform(k2);
        g.setPaint(new LinearGradientPaint(0, (float) wb.getMinY(), 0, (float) wb.getMaxY(), new float[]{0f, .45f, 1f},
            won ? new Color[]{new Color(255, 248, 225), GOLD, new Color(185, 130, 42)} : new Color[]{new Color(230, 238, 252), new Color(159, 179, 214), new Color(77, 95, 128)}));
        g.fill(word);
        g.setTransform(k);

        double sa = ease((t - 0.9) / 0.4), sy = y0 + h * 0.70;
        g.setComposite(alpha(sa));
        Color red = won || winner == 0 ? new Color(255, 155, 140) : new Color(185, 160, 160), blue = new Color(156, 202, 255);
        g.setFont(Type.hud(44, true, 0.02f));
        Art.textCenter(g, s.score[0] + "", cx - 120, 80, sy, red);
        Art.textCenter(g, s.score[1] + "", cx + 40, 80, sy, blue);
        Art.textCenter(g, "—", cx - 40, 80, sy - 4, new Color(143, 138, 168));
        g.setFont(Type.royal(18, true, 0.3f));
        Art.textCenter(g, "RED", cx - 260, 140, sy - 10, red);
        Art.textCenter(g, "BLUE", cx + 120, 140, sy - 10, blue);
        g.setFont(Type.royal(17, true, 0.3f));
        Art.textCenter(g, won ? "THE THRONE IS YOURS" : "THE THRONE HAS FALLEN", x0, w, y0 + h * 0.80, won ? new Color(255, 241, 196) : new Color(143, 166, 200));
    }

    private void drawEndFx(Graphics2D g, double x0, double y0, double w, double h, boolean won) {
        for (double[] e : endFx) {
            double a = 0.5 + 0.5 * Math.sin(e[3] * 2);
            g.setColor(won ? new Color(255, 190 + (int) (40 * a), 90, (int) (220 * a)) : new Color(170, 180, 200, (int) (140 * a)));
            double x = x0 + (e[0] + Math.sin(e[3]) * (won ? 0.006 : 0.02)) * w, y = y0 + e[1] * h, r = won ? 2.4 : 3;
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
    }

    /** 7 s on: one card for everyone. Awards, both teams' stats, and the vote to go back to the lobby. */
    private void drawResults(Graphics2D g, Snapshot s, double t, double x0, double y0, double w, double h) {
        g.setColor(new Color(6, 4, 14, (int) (235 * ease(t / 0.3))));
        g.fill(new Rectangle2D.Double(x0, y0, w, h));
        g.setComposite(alpha(ease(t / 0.35)));
        int[] n = new int[2];
        for (Snapshot.P p : s.players) n[Math.max(0, Math.min(1, p.team))]++;
        int perTeam = Math.max(1, Math.max(n[0], n[1]));
        double cw = Math.min(w - 60, 1000), ch = Math.min(h - 40, 370 + perTeam * 36), px = x0 + (w - cw) / 2, py = y0 + (h - ch) / 2, pad = 34;
        Art.plate(g, px, py, cw, ch, 22, new Color(255, 205, 90, 170), time, fx);
        g.setFont(Type.logo(36));
        Art.text(g, "Match results", px + pad, py + 58, GOLD);
        int len = roundStartLeft > 0 ? Math.max(0, roundStartLeft - s.timeLeftSec) : -1;
        String meta = perTeam + "v" + perTeam + (len >= 0 ? "   ·   " + len / 60 + ":" + String.format("%02d", len % 60) : "")
            + "   ·   " + TEAM_NAME[s.winner] + " WINS " + s.score[s.winner] + " - " + s.score[1 - s.winner];
        g.setFont(Type.hud(17, true, 0.1f));
        Art.text(g, meta, px + cw - pad - g.getFontMetrics().stringWidth(meta) - 8, py + 52, new Color(143, 138, 168));

        Snapshot.P mvp = best(s, RANK), runner = best(s, Comparator.comparingInt(p -> -p.captures)), shot = best(s, Comparator.comparingInt(p -> -p.tags));
        String[][] award = {
            {"MVP", mvp == null ? "—" : mvp.name, mvp == null ? "" : mvp.score + " points"},
            {"FLAG RUNNER", runner == null || runner.captures == 0 ? "—" : runner.name, runner == null || runner.captures == 0 ? "no captures" : runner.captures + (runner.captures == 1 ? " capture" : " captures")},
            {"SHARPSHOOTER", shot == null || shot.tags == 0 ? "—" : shot.name, shot == null || shot.tags == 0 ? "no tags" : shot.tags + (shot.tags == 1 ? " tag" : " tags")}};
        double aw = (cw - 2 * pad - 2 * 18) / 3, ay = py + 84;
        for (int i = 0; i < 3; i++) {
            double ax = px + pad + i * (aw + 18), pop = ease((t - 0.2 - i * 0.15) / 0.3);
            Composite k = g.getComposite();
            g.setComposite(alpha(pop * ease(t / 0.35)));
            g.setColor(new Color(255, 205, 90, 16));
            g.fill(new Rectangle2D.Double(ax, ay, aw, 74));
            g.setColor(new Color(255, 205, 90, 70));
            g.draw(new Rectangle2D.Double(ax, ay, aw, 74));
            double ix = ax + 36, iy = ay + 38;
            if (i == 0) MenuScreen.crown(g, ix, iy + 10, 0.5);
            else if (i == 1) Art.flagIcon(g, ix, iy, 34, TEAM[0], time);
            else {
                g.setColor(new Color(60, 200, 255));
                g.setStroke(new BasicStroke(2.5f));
                g.draw(new Ellipse2D.Double(ix - 15, iy - 15, 30, 30));
                g.draw(new Ellipse2D.Double(ix - 5, iy - 5, 10, 10));
                g.draw(new Line2D.Double(ix, iy - 21, ix, iy - 10)); g.draw(new Line2D.Double(ix, iy + 10, ix, iy + 21));
                g.draw(new Line2D.Double(ix - 21, iy, ix - 10, iy)); g.draw(new Line2D.Double(ix + 10, iy, ix + 21, iy));
                g.setStroke(new BasicStroke(1));
            }
            g.setFont(Type.hud(13, true, 0.2f));
            Art.text(g, award[i][0], ax + 72, ay + 22, new Color(170, 150, 235));
            g.setFont(Type.royal(21, true, 0.08f));
            Art.text(g, award[i][1].toUpperCase(), ax + 72, ay + 46, Color.WHITE);
            g.setFont(Type.hud(14, false, 0.04f));
            Art.text(g, award[i][2], ax + 72, ay + 64, new Color(143, 138, 168));
            g.setComposite(k);
        }

        double colW = (cw - 2 * pad - 30) / 2, ty = ay + 112;
        for (int team = 0; team < 2; team++) {
            double tx = px + pad + team * (colW + 30);
            Color tc = team == 0 ? new Color(255, 155, 140) : new Color(156, 202, 255);
            g.setFont(Type.royal(22, true, 0.16f));
            Art.text(g, TEAM_NAME[team], tx, ty, tc);
            if (team == s.winner) {
                g.setFont(Type.hud(12, true, 0.2f));
                double wx = tx + g.getFontMetrics(Type.royal(22, true, 0.16f)).stringWidth(TEAM_NAME[team]) + 16;
                g.setColor(new Color(255, 205, 90, 140));
                g.draw(new Rectangle2D.Double(wx, ty - 15, 64, 19));
                Art.text(g, "WINNER", wx + 7, ty, GOLD);
            }
            g.setFont(Type.hud(32, true, 0f));
            Art.text(g, s.score[team] + "", tx + colW - 22, ty + 4, tc);
            double ry = ty + 30;
            g.setFont(Type.hud(12, true, 0.16f));
            Color hc = new Color(143, 138, 168);
            Art.text(g, "PLAYER", tx + 40, ry, hc);
            String[] cols = {"C", "T", "D", "PTS"};
            double[] cx = {colW - 190, colW - 140, colW - 90, colW - 30};
            for (int c = 0; c < 4; c++) Art.textCenter(g, cols[c], tx + cx[c] - 20, 40, ry, hc);
            g.setColor(new Color(110, 95, 160, 120));
            g.fill(new Rectangle2D.Double(tx, ry + 8, colW, 1));
            List<Snapshot.P> rows = new ArrayList<>();
            for (Snapshot.P p : s.players) if (p.team == team) rows.add(p);
            rows.sort(RANK);
            for (Snapshot.P p : rows) {
                ry += 36;
                if (mvp != null && p.id == mvp.id) {
                    g.setPaint(new GradientPaint((float) tx, 0, new Color(255, 205, 90, 45), (float) (tx + colW), 0, new Color(255, 205, 90, 0)));
                    g.fill(new Rectangle2D.Double(tx, ry - 24, colW, 34));
                    MenuScreen.crown(g, tx + 16, ry - 4, 0.3);
                } else {
                    g.setColor(TEAM[team]);
                    g.fill(new Ellipse2D.Double(tx + 9, ry - 15, 14, 14));
                }
                g.setFont(Type.royal(17, true, 0.06f));
                Art.text(g, p.name.toUpperCase(), tx + 40, ry, Color.WHITE);
                double nw = g.getFontMetrics().stringWidth(p.name.toUpperCase());
                Protocol.LobbyEntry le = entry(p.id);
                g.setFont(Type.hud(12, true, 0.2f));
                String extra = (le != null ? "LV " + le.level() + "   " : "") + (p.id == myId ? "YOU" : "");
                Art.text(g, extra, tx + 50 + nw, ry, new Color(170, 150, 235));
                g.setFont(Type.hud(18, true, 0f));
                int[] v = {p.captures, p.tags, p.deaths, p.score};
                for (int c = 0; c < 4; c++) Art.textCenter(g, v[c] + "", tx + cx[c] - 20, 40, ry, c == 3 ? new Color(255, 241, 196) : new Color(200, 196, 225));
                g.setColor(new Color(110, 95, 160, 70));
                g.fill(new Rectangle2D.Double(tx, ry + 10, colW, 1));
            }
        }

        // what this match did for your profile: XP, level up, achievements
        double fy = py + ch - 40;
        if (!news.isEmpty()) {
            g.setFont(Type.hud(17, true, 0.12f));
            Art.textCenter(g, String.join("     ·     ", news), px, cw, fy - 50, GOLD);
        }

        // the vote to go back: a tick per player who pressed BACK TO LOBBY, and the seconds left before it happens anyway
        Protocol.Lobby l = link.lobby();
        int votes = 0, total = 0;
        boolean mine = false;
        if (l != null) for (Protocol.LobbyEntry e : l.entries()) { total++; if (e.ready()) { votes++; if (e.id() == myId) mine = true; } }
        g.setFont(Type.hud(16, true, 0.1f));
        Art.text(g, "BACK TO LOBBY", px + pad, fy + 6, new Color(200, 196, 225));
        double tkx = px + pad + 150;
        for (int i = 0; i < total; i++, tkx += 30) {
            boolean on = i < votes;
            Rectangle2D.Double box = new Rectangle2D.Double(tkx, fy - 12, 22, 22);
            g.setColor(on ? new Color(110, 230, 130, 50) : new Color(0, 0, 0, 0));
            g.fill(box);
            g.setColor(on ? new Color(110, 230, 130) : new Color(170, 150, 235, 110));
            g.draw(box);
            if (on) {
                g.setStroke(new BasicStroke(2.4f));
                g.draw(new Line2D.Double(tkx + 5, fy, tkx + 9, fy + 5));
                g.draw(new Line2D.Double(tkx + 9, fy + 5, tkx + 17, fy - 6));
                g.setStroke(new BasicStroke(1));
            }
        }
        Art.text(g, votes + "/" + total + " READY", tkx + 8, fy + 6, new Color(200, 196, 225));

        double left = Math.max(0, END_RETURN - endT), bw = 230, bx = px + cw - pad - bw, rcx = bx - 34;
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(255, 205, 90, 45));
        g.draw(new Ellipse2D.Double(rcx - 20, fy - 21, 40, 40));
        g.setColor(GOLD);
        g.draw(new Arc2D.Double(rcx - 20, fy - 21, 40, 40, 90, 360 * left / (END_RETURN - END_RESULTS), Arc2D.OPEN));
        g.setStroke(new BasicStroke(1));
        g.setFont(Type.hud(18, true, 0f));
        Art.textCenter(g, (int) Math.ceil(left) + "", rcx - 20, 40, fy + 6, new Color(255, 241, 196));
        backBtn.setRect(bx, fy - 23, bw, 46);
        boolean hover = backBtn.contains(mouseX, mouseY) && !mine;
        Path2D.Double btn = new Path2D.Double();
        btn.moveTo(bx + 12, fy - 23); btn.lineTo(bx + bw, fy - 23); btn.lineTo(bx + bw - 12, fy + 23); btn.lineTo(bx, fy + 23); btn.closePath();
        g.setPaint(mine ? new GradientPaint(0, (float) (fy - 23), new Color(80, 75, 100), 0, (float) (fy + 23), new Color(45, 40, 60))
            : new LinearGradientPaint(0, (float) (fy - 23), 0, (float) (fy + 23), new float[]{0f, .5f, 1f},
                hover ? new Color[]{Color.WHITE, new Color(255, 225, 140), GOLD} : new Color[]{new Color(255, 241, 196), GOLD, new Color(185, 130, 42)}));
        g.fill(btn);
        g.setFont(Type.royal(18, true, 0.22f));
        Art.textCenter(g, mine ? "WAITING..." : "BACK TO LOBBY", bx, bw, fy + 7, mine ? new Color(200, 196, 225) : new Color(26, 15, 2));
    }

    private static Snapshot.P find(Snapshot s, int id) {
        for (Snapshot.P p : s.players) if (p.id == id) return p;
        return null;
    }

    private static String name(Snapshot s, int id) {
        Snapshot.P p = find(s, id);
        return p == null ? "?" : p.name;
    }
}

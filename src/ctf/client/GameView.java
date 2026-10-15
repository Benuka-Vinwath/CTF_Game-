package ctf.client;

import ctf.common.MapData;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;

/**
 * Shows Dilmi's GamePanel at any window size. The arena is always the same 800x600 world (the server's
 * collision code depends on it), so the window is zoomed until the whole arena plus a small margin fits,
 * and whatever the window shows beyond the arena is filled with real scenery (GamePanel/Scenery), not black
 * bars. This component tells GamePanel which part of the world is visible, and converts mouse positions from
 * screen pixels back into world pixels before handing the events to GamePanel.
 * F11 toggles full screen and Esc leaves it.
 */
public class GameView extends JComponent {
    private final GamePanel game;
    private static final double MAX_RENDER_SCALE = 2.0;   // draw at most 2 pixels per world pixel, then enlarge (keeps 4K fast)
    private static final double MARGIN = 16;               // world units of scenery kept visible around the arena
    private BufferedImage frame;                           // the game drawn at the window's real pixel size
    private double renderCap = MAX_RENDER_SCALE;           // lowered automatically on slow computers
    private double windowMs;                               // total drawing time of the frames in the current 2-second window
    private int windowFrames, slowWindows;
    private long firstFrameNs, windowStartNs;
    private boolean effectsSwitchedOff;
    private final Runnable toggleFullscreen;
    private final Runnable leaveFullscreen;

    public GameView(GamePanel game, Runnable toggleFullscreen, Runnable leaveFullscreen) {
        this.game = game;
        this.toggleFullscreen = toggleFullscreen;
        this.leaveFullscreen = leaveFullscreen;
        game.setDoubleBuffered(false);
        game.useExternalClock();   // one clock for movement and painting: see GamePanel.frameTick
        game.setSize(MapData.W, MapData.H);   // GamePanel is never shown itself: we paint it
        setPreferredSize(new Dimension(MapData.W, MapData.H));
        setMinimumSize(new Dimension(MapData.W / 2, MapData.H / 2));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);   // let TAB reach the key listener

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F11) { toggleFullscreen.run(); return; }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { leaveFullscreen.run(); return; }
                for (KeyListener l : game.getKeyListeners()) l.keyPressed(forKey(e));
            }
            @Override public void keyReleased(KeyEvent e) {
                for (KeyListener l : game.getKeyListeners()) l.keyReleased(forKey(e));
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                for (MouseListener l : game.getMouseListeners()) l.mousePressed(forMouse(e));
            }
            @Override public void mouseReleased(MouseEvent e) {
                for (MouseListener l : game.getMouseListeners()) l.mouseReleased(forMouse(e));
            }
            @Override public void mouseMoved(MouseEvent e) {
                for (MouseMotionListener l : game.getMouseMotionListeners()) l.mouseMoved(forMouse(e));
            }
            @Override public void mouseDragged(MouseEvent e) {
                for (MouseMotionListener l : game.getMouseMotionListeners()) l.mouseDragged(forMouse(e));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {   // lets GamePanel release held keys and the fire button
                for (FocusListener l : game.getFocusListeners()) l.focusLost(new FocusEvent(game, FocusEvent.FOCUS_LOST));
            }
        });

        // On Windows a sleeping thread is woken only every ~15 ms unless some thread is asking for a finer clock. A
        // thread that sleeps forever makes Java request the 1 ms clock for the whole program (a well-known trick).
        Thread fineClock = new Thread(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) { }
        }, "fine-clock");
        fineClock.setDaemon(true);
        fineClock.start();

        Thread clock = new Thread(this::runClock, "frame-clock");   // GamePanel is not on screen, so we drive its frames
        clock.setDaemon(true);
        clock.setPriority(Thread.MAX_PRIORITY);   // a little less likely to be kept waiting by other programs
        clock.start();
        javax.swing.SwingUtilities.invokeLater(() -> Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 2));   // the drawing thread
    }

    private final java.util.concurrent.atomic.AtomicInteger framesInFlight = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Starts a frame 60 times a second at exact moments, however long the previous frame took. (A repeating Swing
     * timer waits a fixed delay after the last tick instead, so every slow frame pushes all later ones back.)
     * Sleeps for most of the wait and spins for the last stretch, because sleeping alone is not precise enough.
     */
    private void runClock() {
        final long period = 1_000_000_000L / 60;
        long next = System.nanoTime() + period;
        while (true) {
            long wait = next - System.nanoTime() - 1_000_000L;
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
            while (System.nanoTime() < next) Thread.onSpinWait();
            // One frame may be running and one waiting. A frame that overruns its slot by a little then just starts
            // a little late; only when frames really pile up (the computer cannot keep 60) is a beat skipped.
            if (framesInFlight.incrementAndGet() <= 2) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try { frame(); } finally { framesInFlight.decrementAndGet(); }
                });
            } else {
                framesInFlight.decrementAndGet();
            }
            next += period;
            if (next < System.nanoTime()) next = System.nanoTime() + period;   // fell behind: restart the rhythm, no burst
        }
    }

    /** One frame: paint right now if the window is on screen, otherwise just keep the game moving. */
    private void frame() {
        setCursor(game.getCursor());
        if (isShowing()) paintImmediately(0, 0, getWidth(), getHeight());
        else game.frameTick();
    }

    /** Pixels per world pixel: the whole arena plus MARGIN on every side must fit. */
    double scale() {
        return Math.max(0.01, Math.min(getWidth() / (MapData.W + 2 * MARGIN), getHeight() / (MapData.H + 2 * MARGIN)));
    }

    /** World coordinate at the left / top edge of the window. */
    double worldX0() { return MapData.W / 2.0 - getWidth() / scale() / 2; }

    double worldY0() { return MapData.H / 2.0 - getHeight() / scale() / 2; }

    private KeyEvent forKey(KeyEvent e) {
        return new KeyEvent(game, e.getID(), e.getWhen(), e.getModifiersEx(), e.getKeyCode(), e.getKeyChar());
    }

    /** The same mouse event, with the position converted from screen pixels to world pixels. */
    private MouseEvent forMouse(MouseEvent e) {
        int wx = (int) Math.round(e.getX() / scale() + worldX0());
        int wy = (int) Math.round(e.getY() / scale() + worldY0());
        return new MouseEvent(game, e.getID(), e.getWhen(), e.getModifiersEx(), wx, wy,
                e.getClickCount(), e.isPopupTrigger(), e.getButton());
    }

    private boolean errorShown;

    /** Time spent in the last frame, in nanoseconds: game update, world drawing, copy to the window, text and UI. A diagnostic aid. */
    static final long[] PHASE_NS = new long[4];

    @Override
    protected void paintComponent(Graphics g0) {
        try {
            paintGame(g0);
        } catch (Throwable t) {   // never leave a silent blank window: say what went wrong, on screen and in a log file
            if (!errorShown) {
                errorShown = true;
                Main.logError("Drawing the game failed", t);
            }
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(java.awt.Color.WHITE);
            g.drawString("Drawing error: " + t, 20, 30);
            g.drawString("Details are in ctf-client-error.log (please send it)", 20, 52);
            g.dispose();
        }
    }

    private void paintGame(Graphics g0) {
        // Two passes, both at the screen's REAL pixel size (a 150% scaled screen has more pixels than getWidth()):
        //  1. the battlefield is drawn into an image with one pixel per screen pixel and copied 1:1;
        //  2. all text and UI (GamePanel.paintOverlay) is then drawn straight onto the window at native resolution,
        //     so glyphs are rasterised once and never resampled, even when pass 1 had to drop to a lower resolution.
        long started = System.nanoTime();
        game.frameTick();   // update positions for exactly the moment this frame will be shown
        long tTick = System.nanoTime();
        java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
        double dpi = gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
        double s = scale() * dpi;                       // real screen pixels per world pixel
        double rs = Math.min(s, renderCap);             // only when the computer is slow: draw the world smaller, then enlarge
        double vw = getWidth() / scale(), vh = getHeight() / scale(), vx0 = worldX0(), vy0 = worldY0();
        int pw = (int) Math.round(getWidth() * dpi), ph = (int) Math.round(getHeight() * dpi);
        int rw = Math.max(1, (int) Math.round(vw * rs));
        int rh = Math.max(1, (int) Math.round(vh * rs));
        if (frame == null || frame.getWidth() != rw || frame.getHeight() != rh) {
            frame = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D fg = frame.createGraphics();
        fg.scale(rw / vw, rh / vh);
        fg.translate(-vx0, -vy0);
        fg.setClip(new java.awt.geom.Rectangle2D.Double(vx0, vy0, vw, vh));
        game.setView(vx0, vy0, vw, vh);
        game.paintWorld(fg);
        fg.dispose();
        long tWorld = System.nanoTime();

        Graphics2D g = (Graphics2D) g0.create();
        java.awt.geom.AffineTransform origin = g.getTransform();   // where this component starts, in device pixels
        g.setTransform(new java.awt.geom.AffineTransform());
        g.translate(origin.getTranslateX(), origin.getTranslateY());
        if (rw == pw && rh == ph) g.drawImage(frame, 0, 0, null);
        else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(frame, 0, 0, pw, ph, null);                // fills the whole window: no bars
        }
        long tCopy = System.nanoTime();
        g.scale(pw / vw, ph / vh);
        g.translate(-vx0, -vy0);
        g.clip(new java.awt.geom.Rectangle2D.Double(vx0, vy0, vw, vh));
        game.paintOverlay(g);
        g.dispose();
        long tEnd = System.nanoTime();
        PHASE_NS[0] = tTick - started; PHASE_NS[1] = tWorld - tTick; PHASE_NS[2] = tCopy - tWorld; PHASE_NS[3] = tEnd - tCopy;

        // Adapt to slow computers: if drawing takes too long, draw at a slightly smaller size from now on.
        // It only ever steps down, so the picture can never flip back and forth. The first frames are
        // ignored: they include one-off work such as preparing the stretched background.
        long now = System.nanoTime();
        if (firstFrameNs == 0) firstFrameNs = started;
        if (now - firstFrameNs > 5_000_000_000L) {   // ignore the first five seconds: Java is still warming up and every frame is slow
            if (windowStartNs == 0) windowStartNs = now;
            windowMs += Math.min(80, (now - started) / 1e6);
            windowFrames++;
            if (now - windowStartNs >= 2_000_000_000L && windowFrames >= 10) {   // every two seconds, judge the average frame cost
                double avg = windowMs / windowFrames;
                windowMs = 0;
                windowFrames = 0;
                windowStartNs = now;
                // 60 frames a second leaves 16.7 ms per frame. Above about 14 ms on average, the slowest frames no
                // longer fit and movement starts to judder. First give up the optional effects (they cost about half
                // of the world drawing); only if that is still not enough, draw at a slightly smaller size.
                if (avg > 14 && game.effectsOn() && !effectsSwitchedOff) {
                    if (++slowWindows >= 2) {   // slow twice in a row, not just one busy moment
                        effectsSwitchedOff = true;   // once only: if the player turns them back on with F, respect that
                        game.setEffects(false);
                        game.tell("Effects switched off to keep the game smooth. Press F to turn them back on.");
                        slowWindows = 0;
                    }
                } else if (avg > 20) {
                    if (++slowWindows >= 2 && renderCap > 1.0) {   // slow twice in a row, not just one busy moment
                        renderCap = Math.max(1.0, Math.min(renderCap, s) - 0.15);
                        slowWindows = 0;
                    }
                } else {
                    slowWindows = 0;
                }
            }
        }
    }
}
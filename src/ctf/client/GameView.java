package ctf.client;

import ctf.common.MapData;

import javax.swing.JComponent;
import java.awt.Color;
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
 * Shows Dilmi's GamePanel at any window size. GamePanel always draws a fixed 800x600 world, so this
 * component draws it scaled to fit (with black bars to keep the shape) and converts mouse positions
 * from screen pixels back into world pixels before handing the events to GamePanel.
 * F11 toggles full screen and Esc leaves it.
 */
public class GameView extends JComponent {
    private final GamePanel game;
    private static final double MAX_RENDER_SCALE = 2.0;   // never draw the world bigger than 1600x1200 pixels
    private BufferedImage frame;                           // the game drawn at the window's real pixel size
    private double renderCap = MAX_RENDER_SCALE;           // lowered automatically on slow computers
    private double avgMs = 16;                             // recent time to draw one frame
    private int frames;
    private final Runnable toggleFullscreen;
    private final Runnable leaveFullscreen;

    public GameView(GamePanel game, Runnable toggleFullscreen, Runnable leaveFullscreen) {
        this.game = game;
        this.toggleFullscreen = toggleFullscreen;
        this.leaveFullscreen = leaveFullscreen;
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

        new javax.swing.Timer(16, e -> {   // GamePanel is not on screen, so we do its repainting and cursor
            setCursor(game.getCursor());
            repaint();
        }).start();
    }

    /** Pixels per world pixel: the whole 800x600 world must fit. */
    double scale() {
        return Math.min(getWidth() / (double) MapData.W, getHeight() / (double) MapData.H);
    }

    double offsetX() { return (getWidth() - MapData.W * scale()) / 2; }

    double offsetY() { return (getHeight() - MapData.H * scale()) / 2; }

    private KeyEvent forKey(KeyEvent e) {
        return new KeyEvent(game, e.getID(), e.getWhen(), e.getModifiersEx(), e.getKeyCode(), e.getKeyChar());
    }

    /** The same mouse event, with the position converted from screen pixels to world pixels. */
    private MouseEvent forMouse(MouseEvent e) {
        int wx = (int) Math.round((e.getX() - offsetX()) / scale());
        int wy = (int) Math.round((e.getY() - offsetY()) / scale());
        return new MouseEvent(game, e.getID(), e.getWhen(), e.getModifiersEx(), wx, wy,
                e.getClickCount(), e.isPopupTrigger(), e.getButton());
    }

    private boolean errorShown;

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
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.WHITE);
            g.drawString("Drawing error: " + t, 20, 30);
            g.drawString("Details are in ctf-client-error.log (please send it)", 20, 52);
            g.dispose();
        }
    }

    private void paintGame(Graphics g0) {
        // The game is drawn into a plain image whose size is the window's REAL pixel size (a 150% scaled
        // screen has more pixels than getWidth() says), with the world scaled up while drawing, so lines,
        // text and effects are sharp instead of an enlarged small picture. Then the image is copied 1:1.
        // (Painting GamePanel straight onto the window's graphics broke on scaled screens: stretched stripes.)
        java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
        double dpi = gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
        long started = System.nanoTime();
        double s = scale() * dpi;                       // real screen pixels per world pixel
        double rs = Math.min(s, renderCap);             // big screens: draw smaller, then enlarge (keeps it fast)
        int rw = Math.max(1, (int) Math.round(MapData.W * rs));
        int rh = Math.max(1, (int) Math.round(MapData.H * rs));
        if (frame == null || frame.getWidth() != rw || frame.getHeight() != rh) {
            frame = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D fg = frame.createGraphics();
        fg.scale(rw / (double) MapData.W, rh / (double) MapData.H);
        fg.setClip(0, 0, MapData.W, MapData.H);
        game.paint(fg);
        fg.dispose();

        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());       // the bars beside the game
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(frame, (int) Math.round(offsetX()), (int) Math.round(offsetY()),
                (int) Math.round(MapData.W * scale()), (int) Math.round(MapData.H * scale()), null);
        g.dispose();

        // Adapt to slow computers: if drawing takes too long, draw at a slightly smaller size from now on.
        // It only ever steps down, so the picture can never flip back and forth. The first frames are
        // ignored: they include one-off work such as preparing the stretched background.
        frames++;
        if (frames > 40) {
            avgMs = avgMs * 0.9 + Math.min(80, (System.nanoTime() - started) / 1e6) * 0.1;
            if (avgMs > 26 && renderCap > 1.0) {
                renderCap = Math.max(1.0, Math.min(renderCap, s) - 0.15);
                avgMs = 18;
            }
        }
    }
}
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
    private double avgMs = 16;                             // recent time to draw one frame
    private int frames;
    private final Runnable toggleFullscreen;
    private final Runnable leaveFullscreen;

    public GameView(GamePanel game, Runnable toggleFullscreen, Runnable leaveFullscreen) {
        this.game = game;
        this.toggleFullscreen = toggleFullscreen;
        this.leaveFullscreen = leaveFullscreen;
        game.setDoubleBuffered(false);
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

    @Override
    protected void paintComponent(Graphics g0) {
        // The game is drawn into a plain image whose size is the window's REAL pixel size (a 150% scaled
        // screen has more pixels than getWidth() says), with the world scaled up while drawing, so lines,
        // text and effects are sharp instead of an enlarged small picture. Then the image is copied 1:1.
        // (Painting GamePanel straight onto the window's graphics broke on scaled screens: stretched stripes.)
        java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
        double dpi = gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
        long started = System.nanoTime();
        double s = scale() * dpi;                       // real screen pixels per world pixel
        double rs = Math.min(s, renderCap);             // big screens: draw smaller, then enlarge (keeps it fast)
        double vw = getWidth() / scale(), vh = getHeight() / scale(), vx0 = worldX0(), vy0 = worldY0();
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
        game.paint(fg);
        fg.dispose();

        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (Math.abs(rw - getWidth() * dpi) < 1.5 && Math.abs(rh - getHeight() * dpi) < 1.5 && dpi == 1.0) g.drawImage(frame, 0, 0, null); // 1:1 copy is much cheaper
        else g.drawImage(frame, 0, 0, getWidth(), getHeight(), null);   // fills the whole window: no bars
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
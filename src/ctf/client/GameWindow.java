package ctf.client;

import ctf.client.net.NetClient;
import ctf.client.net.NetEvents;
import ctf.common.Snapshot;
import ctf.server.GameServer;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * The one game window. It opens on the main menu (MenuScreen); HOST starts a server inside this program and joins
 * it, JOIN connects to a friend. As soon as the server says WELCOME the window switches to the game (lobby room,
 * then the match). If the connection drops, it goes back to the menu and says why.
 * All NetEvents methods run on network threads, so anything that touches Swing is wrapped in invokeLater.
 */
public class GameWindow extends JFrame implements NetEvents, MenuScreen.Actions {
    static final int PORT = 5000;

    private final MenuScreen menu = new MenuScreen(this);
    private volatile NetClient client;
    private volatile GamePanel panel;   // set while a game is open
    private GameView gameView;
    private boolean fullscreen, hosting;

    /** address: prefilled on the Play screen when given on the command line (play.ps1), else the last one used. */
    public GameWindow(String address) {
        super("The ThroneFall");
        if (address != null) menu.setAddress(address);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        setMinimumSize(new Dimension(640, 360));
        show(menu);
        pack();
        setLocationRelativeTo(null);
        menu.start();
    }

    private void show(JComponent c) {
        getContentPane().removeAll();
        getContentPane().add(c);
        revalidate();
        repaint();
        c.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ menu actions

    @Override
    public void host() {
        if (!hosting) {
            try {
                GameServer.start(PORT, 2);   // lives as long as this window; the lobby needs two full teams to start
                hosting = true;
            } catch (IOException e) {
                menu.setStatus("Could not host: port " + PORT + " is busy. Is a game already hosting on this computer?", true, false);
                return;
            }
        }
        connect("localhost", PORT);
    }

    @Override
    public void join(String address) {
        String host = address;
        int port = PORT;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {   // "host:port", but leave IPv6 addresses alone
            try {
                port = Integer.parseInt(address.substring(colon + 1).trim());
                host = address.substring(0, colon).trim();
            } catch (NumberFormatException e) {
                menu.setStatus("The port after ':' must be a number.", true, false);
                return;
            }
        }
        connect(host, port);
    }

    private void connect(String host, int port) {
        if (client != null) return;
        menu.setStatus("Connecting to " + host + "...", false, true);
        String name = menu.playerName();
        int hat = menu.hat(), color = menu.hatColor();
        new Thread(() -> {
            NetClient c = new NetClient(this);
            client = c;   // set first: events can arrive as soon as connect() returns
            try {
                c.connect(host, port, name, -1, hat, color);
            } catch (IOException ex) {
                client = null;
                SwingUtilities.invokeLater(() -> menu.setStatus("Could not reach " + host + ":" + port + ". Check the address and that the host is in the game.", true, false));
            }
        }, "connect").start();
    }

    @Override
    public void quit() {
        NetClient c = client;
        if (c != null) c.disconnect();
        System.exit(0);
    }

    private void openGame(int myId) {
        NetClient c = client;
        if (panel != null || c == null) return;
        c.setLagSimulation(menu.lagDelay(), menu.lagLoss());
        GamePanel gp = new GamePanel(c, myId);
        gp.setEffects(menu.effects());
        gp.setShake(menu.shake());
        gameView = new GameView(gp, this::toggleFullscreen, this::leaveFullscreen);
        panel = gp;
        menu.stop();
        menu.setStatus("", false, false);
        show(gameView);
    }

    /** Back to the menu after the connection ended; the reason shows under the Play tiles. */
    private void closeGame(String reason) {
        client = null;
        if (gameView != null) gameView.stop();
        gameView = null;
        panel = null;
        menu.setStatus(reason, true, false);
        show(menu);
        menu.start();
    }

    // ------------------------------------------------------------------ fullscreen (F11 in the menu and in the game)

    @Override
    public boolean fullscreen() { return fullscreen; }

    @Override
    public void toggleFullscreen() {
        if (fullscreen) leaveFullscreen();
        else enterFullscreen();
    }

    private void enterFullscreen() {
        if (fullscreen) return;
        java.awt.GraphicsDevice device = getGraphicsConfiguration().getDevice();
        dispose();                 // a window can only lose its title bar while it is not displayed
        setUndecorated(true);
        setResizable(false);
        setVisible(true);
        device.setFullScreenWindow(this);
        fullscreen = true;
        getContentPane().getComponent(0).requestFocusInWindow();
    }

    private void leaveFullscreen() {
        if (!fullscreen) return;
        java.awt.GraphicsDevice device = getGraphicsConfiguration().getDevice();
        device.setFullScreenWindow(null);
        dispose();
        setUndecorated(false);
        setResizable(true);        // in a normal window you can drag it bigger, and the game scales
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        fullscreen = false;
        getContentPane().getComponent(0).requestFocusInWindow();
    }

    // ------------------------------------------------------------------ NetEvents (network threads)

    @Override
    public void onWelcome(int playerId, int team) {
        SwingUtilities.invokeLater(() -> openGame(playerId));
    }

    @Override
    public void onChat(int fromId, String line) {
        GamePanel p = panel;
        if (p != null) p.onChat(fromId, line);
    }

    @Override
    public void onSnapshot(Snapshot snapshot) {
        GamePanel p = panel;
        if (p != null) p.onSnapshot(snapshot);   // GamePanel hands it to the Swing thread itself
    }

    @Override
    public void onServerError(String message) {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.tell("! " + message);
            else menu.setStatus("The server said: " + message, true, false);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> closeGame("Disconnected: " + reason));
    }
}

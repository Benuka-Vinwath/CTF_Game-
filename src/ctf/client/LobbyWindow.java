package ctf.client;

import ctf.client.net.NetClient;
import ctf.client.net.NetEvents;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * Connect screen: server, port and name. As soon as the server says WELCOME the game window opens and the
 * player stands in the lobby room, where teams, ready, chat and the host's start all happen (GamePanel).
 * All NetEvents methods run on network threads, so anything that touches Swing is wrapped in invokeLater.
 */
public class LobbyWindow extends JFrame implements NetEvents {
    private final JTextField hostField;
    private final JTextField portField = new JTextField("5000", 5);
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 10);
    private final JButton connectBtn = new JButton("Connect");
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JSlider delaySlider = new JSlider(0, 300, 0);
    private final JSlider lossSlider = new JSlider(0, 30, 0);

    private volatile NetClient client;
    private volatile GamePanel panel;   // set once the game screen is open
    private JFrame gameFrame;           // the game window (Swing thread only)
    private GameView gameView;
    private boolean fullscreen;

    public LobbyWindow(String defaultHost) {
        super("Capture the Flag - Connect");
        hostField = new JTextField(defaultHost, 12);
        buildUi();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        pack();
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Server:"));
        top.add(hostField);
        top.add(new JLabel("Port:"));
        top.add(portField);
        top.add(new JLabel("Name:"));
        top.add(nameField);
        top.add(connectBtn);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT));
        status.add(statusLabel);

        JPanel debug = new JPanel(new GridLayout(2, 2, 4, 2));
        debug.setBorder(BorderFactory.createTitledBorder("Network test (simulated, UDP only; L in game toggles it)"));
        debug.add(new JLabel("Added delay each way (ms):"));
        debug.add(delaySlider);
        debug.add(new JLabel("Packet loss (%):"));
        debug.add(lossSlider);

        add(top, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);
        add(debug, BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> connect());
        nameField.addActionListener(e -> connect());
        delaySlider.addChangeListener(e -> applyLag());
        lossSlider.addChangeListener(e -> applyLag());
    }

    // ------------------------------------------------------------------ actions

    private void connect() {
        if (client != null) return;
        final String host = hostField.getText().trim();
        final String name = nameField.getText();
        final int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Port must be a number");
            return;
        }
        connectBtn.setEnabled(false);
        statusLabel.setText("Connecting...");
        new Thread(() -> {
            NetClient c = new NetClient(this);
            client = c;   // set first: events can arrive as soon as connect() returns
            try {
                c.connect(host, port, name, -1);
            } catch (IOException ex) {
                client = null;
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Could not connect");
                    connectBtn.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Could not connect to " + host + ":" + port + "\n" + ex.getMessage());
                });
            }
        }, "connect").start();
    }

    private void applyLag() {
        NetClient c = client;
        if (c != null) c.setLagSimulation(delaySlider.getValue(), lossSlider.getValue());
    }

    private void quit() {
        NetClient c = client;
        if (c != null) c.disconnect();
        System.exit(0);
    }

    private void openGame(int myId) {
        if (panel != null || client == null) return;
        NetClient c = client;
        GamePanel gp = new GamePanel(c, myId);
        gameView = new GameView(gp, this::toggleFullscreen, this::leaveFullscreen);
        gameFrame = new JFrame("Capture the Flag - " + Protocol.cleanName(nameField.getText()));
        gameFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        gameFrame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        gameFrame.add(gameView);
        gameFrame.pack();
        gameFrame.setLocationRelativeTo(null);
        gameFrame.setVisible(true);
        gameView.requestFocusInWindow();
        panel = gp;
        setVisible(false);
    }

    private void toggleFullscreen() {
        if (fullscreen) leaveFullscreen();
        else enterFullscreen();
    }

    private void enterFullscreen() {
        if (fullscreen || gameFrame == null) return;
        java.awt.GraphicsDevice device = gameFrame.getGraphicsConfiguration().getDevice();
        gameFrame.dispose();                 // a window can only lose its title bar while it is not displayed
        gameFrame.setUndecorated(true);
        gameFrame.setResizable(false);
        gameFrame.setVisible(true);
        device.setFullScreenWindow(gameFrame);
        fullscreen = true;
        gameView.requestFocusInWindow();
    }

    private void leaveFullscreen() {
        if (!fullscreen || gameFrame == null) return;
        java.awt.GraphicsDevice device = gameFrame.getGraphicsConfiguration().getDevice();
        device.setFullScreenWindow(null);
        gameFrame.dispose();
        gameFrame.setUndecorated(false);
        gameFrame.setResizable(true);        // in a normal window you can drag it bigger, and the game scales
        gameFrame.pack();
        gameFrame.setLocationRelativeTo(null);
        gameFrame.setVisible(true);
        fullscreen = false;
        gameView.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ NetEvents (network threads)

    @Override
    public void onWelcome(int playerId, int team) {
        SwingUtilities.invokeLater(() -> {
            applyLag();
            openGame(playerId);
        });
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
            else statusLabel.setText("Server: " + message);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) {
                JOptionPane.showMessageDialog(null, "Disconnected: " + reason);
                System.exit(0);
            }
            client = null;
            connectBtn.setEnabled(true);
            statusLabel.setText(reason);
        });
    }
}

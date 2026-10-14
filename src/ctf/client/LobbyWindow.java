package ctf.client;

import ctf.client.net.NetClient;
import ctf.client.net.NetEvents;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * Lobby screen: connect, pick a team, ready up and chat. When the server sends START it opens
 * Dilmi's GamePanel and routes the snapshots into it. All NetEvents methods run on network
 * threads, so anything that touches Swing is wrapped in SwingUtilities.invokeLater.
 */
public class LobbyWindow extends JFrame implements NetEvents {
    private final JTextField hostField;
    private final JTextField portField = new JTextField("5000", 5);
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 10);
    private final JButton connectBtn = new JButton("Connect");

    private final DefaultListModel<String> playerModel = new DefaultListModel<>();
    private final JTextArea chatArea = new JTextArea(8, 30);
    private final JTextField chatInput = new JTextField();
    private final JButton redBtn = new JButton("Join RED");
    private final JButton blueBtn = new JButton("Join BLUE");
    private final JToggleButton readyBtn = new JToggleButton("Ready");
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JSlider delaySlider = new JSlider(0, 300, 0);
    private final JSlider lossSlider = new JSlider(0, 30, 0);

    private volatile NetClient client;
    private volatile GamePanel panel;   // set once the game screen is open
    private volatile int myId = -1;
    private JFrame gameFrame;           // the game window (Swing thread only)
    private GameView gameView;
    private boolean fullscreen;

    public LobbyWindow(String defaultHost) {
        super("Capture the Flag - Lobby");
        hostField = new JTextField(defaultHost, 12);
        buildUi();
        setLobbyEnabled(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        pack();
        setLocationRelativeTo(null);
        new javax.swing.Timer(1000, e -> {   // keep the ping display fresh
            NetClient c = client;
            if (c != null && c.isConnected() && panel == null) statusLabel.setText("Ping: " + c.pingMs() + " ms");
        }).start();
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

        JScrollPane players = new JScrollPane(new JList<>(playerModel));
        players.setBorder(BorderFactory.createTitledBorder("Players"));
        players.setPreferredSize(new Dimension(230, 220));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JPanel chat = new JPanel(new BorderLayout(4, 4));
        chat.setBorder(BorderFactory.createTitledBorder("Chat"));
        chat.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        chat.add(chatInput, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        center.add(players, BorderLayout.WEST);
        center.add(chat, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(redBtn);
        actions.add(blueBtn);
        actions.add(readyBtn);
        actions.add(statusLabel);

        JPanel debug = new JPanel(new GridLayout(2, 2, 4, 2));
        debug.setBorder(BorderFactory.createTitledBorder("Network test (simulated, UDP only)"));
        debug.add(new JLabel("Added delay each way (ms):"));
        debug.add(delaySlider);
        debug.add(new JLabel("Packet loss (%):"));
        debug.add(lossSlider);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(debug, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> connect());
        redBtn.addActionListener(e -> { if (client != null) client.sendTeam(0); });
        blueBtn.addActionListener(e -> { if (client != null) client.sendTeam(1); });
        readyBtn.addActionListener(e -> { if (client != null) client.sendReady(readyBtn.isSelected()); });
        chatInput.addActionListener(e -> {
            if (client != null) client.sendChat(chatInput.getText());
            chatInput.setText("");
        });
        delaySlider.addChangeListener(e -> applyLag());
        lossSlider.addChangeListener(e -> applyLag());
    }

    private void setLobbyEnabled(boolean on) {
        redBtn.setEnabled(on);
        blueBtn.setEnabled(on);
        readyBtn.setEnabled(on);
        chatInput.setEnabled(on);
        connectBtn.setEnabled(!on);
        hostField.setEnabled(!on);
        portField.setEnabled(!on);
        nameField.setEnabled(!on);
    }

    private void log(String line) {
        chatArea.append(line + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // ------------------------------------------------------------------ actions

    private void connect() {
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

    private void openGame() {
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
            myId = playerId;
            setLobbyEnabled(true);
            applyLag();
            log("* Connected. You are on the " + (team == 0 ? "RED" : "BLUE") + " team.");
        });
    }

    @Override
    public void onLobby(Protocol.Lobby lobby) {
        SwingUtilities.invokeLater(() -> {
            playerModel.clear();
            for (Protocol.LobbyEntry e : lobby.entries()) {
                playerModel.addElement(e.name() + "   [" + (e.team() == 0 ? "RED" : "BLUE") + "]"
                        + (e.ready() ? "   READY" : ""));
                if (e.id() == myId) readyBtn.setSelected(e.ready());
            }
        });
    }

    @Override
    public void onChat(String line) {
        SwingUtilities.invokeLater(() -> log(line));
    }

    @Override
    public void onStart() {
        SwingUtilities.invokeLater(this::openGame);
    }

    @Override
    public void onSnapshot(Snapshot snapshot) {
        GamePanel p = panel;
        if (p != null) p.onSnapshot(snapshot);   // GamePanel hands it to the Swing thread itself
    }

    @Override
    public void onServerError(String message) {
        SwingUtilities.invokeLater(() -> log("! " + message));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) {
                JOptionPane.showMessageDialog(null, "Disconnected: " + reason);
                System.exit(0);
            }
            client = null;
            playerModel.clear();
            readyBtn.setSelected(false);
            setLobbyEnabled(false);
            statusLabel.setText("Disconnected");
            log("* " + reason);
        });
    }
}
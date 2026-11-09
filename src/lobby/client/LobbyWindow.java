package lobby.client;

import lobby.common.LobbyMap;
import lobby.common.LobbyProtocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual Lobby Window
 * ───────────────────
 * Layout:
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  [Server] [Port] [Name] [Connect]              TOP      │
 *   ├─────────────────────────────────────────────────────────┤
 *   │                                                         │
 *   │             LobbyPanel (1120 × 630)            CENTER   │
 *   │   WASD to walk • R to ready up • No shooting            │
 *   │                                                         │
 *   ├─────────────────────────────────────────────────────────┤
 *   │  [chat input ..................] [Send] [Ready]  SOUTH   │
 *   └─────────────────────────────────────────────────────────┘
 *
 * Flow:
 *  1. Player connects to LobbyServer (port 6000)
 *  2. Walks around, chats, presses Ready
 *  3. When all players ready → countdown → server sends LAUNCH host:port
 *  4. This window closes; ctf.client.LobbyWindow opens connecting to the CTF game server
 */
public class LobbyWindow extends JFrame implements LobbyEvents {

    // ── top bar ──────────────────────────────────────────────────────────────
    private final JTextField hostField  = new JTextField("localhost", 12);
    private final JTextField portField  = new JTextField("6000", 5);
    private final JTextField nameField  = new JTextField(System.getProperty("user.name","Player"), 10);
    private final JButton    connectBtn = new JButton("Connect");
    private final JLabel     statusLabel= new JLabel("Not connected");

    // ── game arena ───────────────────────────────────────────────────────────
    private LobbyPanel panel;
    private final JPanel placeholder;

    // ── bottom bar ───────────────────────────────────────────────────────────
    private final JTextField chatInput = new JTextField();
    private final JButton    sendBtn   = new JButton("Send");
    private final JToggleButton readyBtn = new JToggleButton("☐ Ready");

    // ── network ──────────────────────────────────────────────────────────────
    private volatile LobbyNet net;
    private volatile int myId      = -1;
    private volatile int myTeam    = 0;
    private volatile int minPlayers = 2;
    private String myName;

    // Per-player cache (team, name, ready) so MOVE doesn't lose team data
    private final ConcurrentHashMap<Integer, int[]>    playerTeams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String>   playerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean>  playerReady = new ConcurrentHashMap<>();

    // ── constructor ──────────────────────────────────────────────────────────

    public LobbyWindow(String defaultHost) {
        super("CTF – Visual Lobby");
        hostField.setText(defaultHost);

        placeholder = new JPanel(new BorderLayout());
        placeholder.setBackground(new Color(10, 8, 20));
        placeholder.setPreferredSize(new Dimension(LobbyMap.W, LobbyMap.H));
        JLabel hint = new JLabel(
                "<html><center>Enter a server address and click <b>Connect</b><br>"
                + "Walk: <b>WASD / Arrows</b> &nbsp;&nbsp; Ready up: <b>R</b> or the Ready button</center></html>",
                SwingConstants.CENTER);
        hint.setForeground(new Color(140, 130, 190));
        hint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        placeholder.add(hint, BorderLayout.CENTER);

        buildUi();
        setControlsEnabled(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        pack();
        setLocationRelativeTo(null);
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private void buildUi() {
        // TOP
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.setBorder(BorderFactory.createMatteBorder(0,0,1,0, new Color(50,44,70)));
        top.setBackground(new Color(16,13,28));
        top.add(lbl("Server:")); top.add(hostField);
        top.add(lbl("Port:"));   top.add(portField);
        top.add(lbl("Name:"));   top.add(nameField);
        top.add(connectBtn);     top.add(statusLabel);
        statusLabel.setForeground(new Color(140,130,190));

        // CENTER placeholder
        JPanel center = new JPanel(new CardLayout());
        center.add(placeholder, "placeholder");
        getContentPane().add(top,    BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);

        // SOUTH
        JPanel south = new JPanel(new BorderLayout(4,0));
        south.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,0,0,0, new Color(50,44,70)),
                BorderFactory.createEmptyBorder(4,6,4,6)));
        south.setBackground(new Color(16,13,28));
        style(chatInput);
        readyBtn.setBackground(new Color(45,38,65));
        readyBtn.setForeground(new Color(220,215,255));
        readyBtn.setFocusPainted(false);

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        rightBtns.setBackground(new Color(16,13,28));
        rightBtns.add(sendBtn);
        rightBtns.add(readyBtn);
        south.add(chatInput, BorderLayout.CENTER);
        south.add(rightBtns, BorderLayout.EAST);
        getContentPane().add(south, BorderLayout.SOUTH);

        // actions
        connectBtn.addActionListener(e -> connect());
        sendBtn.addActionListener(e -> sendChat());
        chatInput.addActionListener(e -> sendChat());
        readyBtn.addActionListener(e -> toggleReady());
    }

    private static JLabel lbl(String t) {
        JLabel l = new JLabel(t); l.setForeground(new Color(170,162,210)); return l;
    }

    private static void style(JTextField f) {
        f.setBackground(new Color(24,20,36)); f.setForeground(new Color(220,215,255));
        f.setCaretColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60,52,90)),
                BorderFactory.createEmptyBorder(2,6,2,6)));
    }

    private void setControlsEnabled(boolean on) {
        chatInput.setEnabled(on); sendBtn.setEnabled(on); readyBtn.setEnabled(on);
        connectBtn.setEnabled(!on); hostField.setEnabled(!on);
        portField.setEnabled(!on);  nameField.setEnabled(!on);
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private void connect() {
        final String host = hostField.getText().trim();
        final int    port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this,"Port must be a number"); return; }

        myName = LobbyProtocol.sanitize(nameField.getText().trim());
        connectBtn.setEnabled(false);
        statusLabel.setText("Connecting…");

        new Thread(() -> {
            LobbyNet n = new LobbyNet(this);
            net = n;
            try { n.connect(host, port, myName); }
            catch (IOException ex) {
                net = null;
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Could not connect");
                    connectBtn.setEnabled(true);
                    JOptionPane.showMessageDialog(this,
                            "Could not connect to " + host + ":" + port + "\n" + ex.getMessage());
                });
            }
        }, "lobby-connect").start();
    }

    private void sendChat() {
        String text = chatInput.getText().trim(); chatInput.setText("");
        LobbyNet n = net; if (n != null && !text.isBlank()) n.sendChat(text);
    }

    private void toggleReady() {
        boolean r = readyBtn.isSelected();
        readyBtn.setText(r ? "✔ Ready" : "☐ Ready");
        LobbyNet n = net; if (n != null) n.sendReady(r);
        if (panel != null) panel.setMyReady(r);
    }

    private void quit() {
        LobbyNet n = net; if (n != null) n.disconnect();
        System.exit(0);
    }

    // ── open visual lobby panel ───────────────────────────────────────────────

    private void openLobbyPanel(double sx, double sy) {
        panel = new LobbyPanel(net, myId, myTeam, myName, sx, sy);

        // Keyboard shortcut: R toggles ready inside the arena
        panel.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) {
                    readyBtn.doClick();   // triggers toggleReady()
                }
            }
        });

        JPanel center = (JPanel) ((BorderLayout) getContentPane().getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
        center.removeAll();
        center.add(panel);
        center.revalidate(); center.repaint();
        pack(); setLocationRelativeTo(null);
        panel.requestFocusInWindow();
    }

    // ── LobbyEvents callbacks ─────────────────────────────────────────────────

    @Override
    public void onWelcome(int id, int min) {
        SwingUtilities.invokeLater(() -> {
            myId = id; minPlayers = min;
            statusLabel.setText("Connected – waiting for " + min + "+ players");
            setControlsEnabled(true);
        });
    }

    @Override
    public void onState(List<LobbyProtocol.PlayerState> players) {
        SwingUtilities.invokeLater(() -> {
            double sx = LobbyMap.SPAWN_X, sy = LobbyMap.SPAWN_Y;
            for (LobbyProtocol.PlayerState ps : players) {
                if (ps.id() == myId) { sx=ps.x(); sy=ps.y(); myTeam=ps.team(); break; }
            }
            if (panel == null) openLobbyPanel(sx, sy);

            for (LobbyProtocol.PlayerState ps : players) {
                if (ps.id() == myId) continue;
                playerTeams.put(ps.id(), new int[]{ps.team()});
                playerNames.put(ps.id(), ps.name());
                playerReady.put(ps.id(), ps.ready());
                panel.addOrUpdateRemote(ps.id(), ps.team(), ps.x(), ps.y(), ps.ready(), ps.name());
            }
        });
    }

    @Override
    public void onJoin(int id, int team, String name) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null || id == myId) return;
            playerTeams.put(id, new int[]{team});
            playerNames.put(id, name);
            playerReady.put(id, false);
            panel.addOrUpdateRemote(id, team, LobbyMap.SPAWN_X, LobbyMap.SPAWN_Y, false, name);
            panel.pushChat("★ " + name + " joined the lobby");
        });
    }

    @Override
    public void onLeave(int id) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            String name = playerNames.getOrDefault(id, "Player " + id);
            playerTeams.remove(id); playerNames.remove(id); playerReady.remove(id);
            panel.removeRemote(id);
            panel.pushChat("★ " + name + " left");
        });
    }

    @Override
    public void onMove(int id, double x, double y) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null || id == myId) return;
            int     team  = playerTeams.containsKey(id) ? playerTeams.get(id)[0] : 0;
            String  name  = playerNames.get(id);
            boolean ready = playerReady.getOrDefault(id, false);
            panel.addOrUpdateRemote(id, team, x, y, ready, name);
        });
    }

    @Override
    public void onReadyUpdate(int id, boolean ready) {
        SwingUtilities.invokeLater(() -> {
            playerReady.put(id, ready);
            if (panel != null) panel.setRemoteReady(id, ready);
            String name = playerNames.getOrDefault(id, "Player " + id);
            if (panel != null) panel.pushChat("● " + name + (ready ? " is READY" : " is NOT ready"));
        });
    }

    @Override
    public void onReadyStatus(int readyCount, int total) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Ready: " + readyCount + " / " + total
                    + "  (need " + minPlayers + "+)");
            if (panel != null) panel.setReadyStatus(readyCount, total);
        });
    }

    @Override
    public void onCountdown(int seconds) {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.setCountdown(seconds);
            if (seconds > 0)
                statusLabel.setText("⚡ Game starting in " + seconds + "...");
            else
                statusLabel.setText("⚡ Launching game...");
        });
    }

    @Override
    public void onLaunch(String gameHost, int gamePort) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Joining game at " + gameHost + ":" + gamePort + "...");
            // Disconnect from lobby cleanly
            LobbyNet n = net; net = null;
            if (n != null) n.disconnect();

            // Brief pause so the user sees the transition message
            new javax.swing.Timer(800, e -> {
                ((javax.swing.Timer)e.getSource()).stop();
                launchCTFGame(gameHost, gamePort);
            }).start();
        });
    }

    @Override
    public void onChat(String line) {
        SwingUtilities.invokeLater(() -> { if (panel != null) panel.pushChat(line); });
    }

    @Override
    public void onError(String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, "Server error: " + message));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            // If we triggered disconnect ourselves (before launch), ignore quietly.
            if (net == null) return;
            net = null;
            statusLabel.setText("Disconnected");
            setControlsEnabled(false);
            if (panel != null) panel.pushChat("★ Disconnected: " + reason);
        });
    }

    // ── transition to CTF game ────────────────────────────────────────────────

    /**
     * Opens the original CTF lobby window pointing at the game server.
     * The visual lobby window stays hidden (or is disposed) while the game runs.
     */
    private void launchCTFGame(String host, int port) {
        setVisible(false);
        // Open the original CTF lobby which will connect & start the game.
        ctf.client.LobbyWindow ctfLobby = new ctf.client.LobbyWindow(host);
        // Pre-fill port in case it's not the default 5000
        // (ctf.client.LobbyWindow exposes portField via its constructor arg for host only,
        //  so we just open it and the player connects manually with the correct port shown).
        ctfLobby.setVisible(true);

        // Show a quick guide in a dialog so the player knows what to do
        JOptionPane.showMessageDialog(ctfLobby,
                "Visual lobby complete!\n\n"
                + "The CTF game server is running on port " + port + ".\n"
                + "• The server field is pre-filled with:  " + host + "\n"
                + "• Change the Port to: " + port + " (if needed)\n"
                + "• Enter your name and click Connect\n"
                + "• Choose a team and click Ready to start the match!",
                "Joining CTF Game", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> new LobbyWindow(host).setVisible(true));
    }
}

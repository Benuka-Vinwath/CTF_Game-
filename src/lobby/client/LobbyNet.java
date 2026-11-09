package lobby.client;

import lobby.common.LobbyMap;
import lobby.common.LobbyProtocol;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TCP client for the visual lobby server.
 * Runs a background reader thread; all events are delivered to {@link LobbyEvents}.
 */
public class LobbyNet {

    private final LobbyEvents events;
    private Socket     socket;
    private PrintWriter out;
    private volatile boolean connected;

    public LobbyNet(LobbyEvents events) { this.events = events; }

    // ── connection ───────────────────────────────────────────────────────────

    public void connect(String host, int port, String name) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        out = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);
        connected = true;

        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        Thread reader = new Thread(() -> readLoop(in), "lobby-reader");
        reader.setDaemon(true);
        reader.start();

        sendTcp("HELLO " + LobbyProtocol.sanitize(name));
    }

    public void disconnect() {
        sendTcp("BYE");
        close("You left the lobby");
    }

    private void close(String reason) {
        if (!connected) return;
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        events.onDisconnected(reason);
    }

    // ── outgoing ─────────────────────────────────────────────────────────────

    private synchronized void sendTcp(String line) {
        if (connected && out != null) out.println(line);
    }

    public void sendMove(double x, double y) {
        sendTcp(String.format(java.util.Locale.ROOT, "MOVE %.1f %.1f", x, y));
    }

    public void sendChat(String text) {
        if (text != null && !text.isBlank())
            sendTcp("CHAT " + LobbyProtocol.sanitizeChatText(text));
    }

    public void sendReady(boolean ready) {
        sendTcp(ready ? "READY" : "UNREADY");
    }

    public boolean isConnected() { return connected; }

    // ── incoming ─────────────────────────────────────────────────────────────

    private void readLoop(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) handle(line.trim());
            close("Server closed the connection");
        } catch (IOException e) {
            close(connected ? "Connection lost: " + e.getMessage() : "Disconnected");
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) return;
        int    sp  = line.indexOf(' ');
        String cmd = sp < 0 ? line : line.substring(0, sp);

        switch (cmd) {
            case "WELCOME" -> {
                int[] w = LobbyProtocol.decodeWelcome(line);
                if (w != null) events.onWelcome(w[0], w[1]);
            }
            case "STATE" -> events.onState(LobbyProtocol.decodeState(line));
            case "JOIN"  -> {
                int[]  jd = LobbyProtocol.decodeJoin(line);
                String jn = LobbyProtocol.decodeJoinName(line);
                if (jd != null) events.onJoin(jd[0], jd[1], jn != null ? jn : "Player");
            }
            case "LEAVE" -> {
                try { events.onLeave(Integer.parseInt(line.split(" ")[1])); }
                catch (Exception ignored) {}
            }
            case "MOVE" -> {
                double[] mv = LobbyProtocol.decodeMoveAll(line);
                if (mv != null) events.onMove((int) mv[0], mv[1], mv[2]);
            }
            case "READY_UPDATE" -> {
                int[] ru = LobbyProtocol.decodeReadyUpdate(line);
                if (ru != null) events.onReadyUpdate(ru[0], ru[1] == 1);
            }
            case "READY_STATUS" -> {
                int[] rs = LobbyProtocol.decodeReadyStatus(line);
                if (rs != null) events.onReadyStatus(rs[0], rs[1]);
            }
            case "COUNTDOWN" -> {
                int s = LobbyProtocol.decodeCountdown(line);
                if (s >= 0) events.onCountdown(s);
            }
            case "LAUNCH" -> {
                String[] la = LobbyProtocol.decodeLaunch(line);
                if (la != null) {
                    try { events.onLaunch(la[0], Integer.parseInt(la[1])); }
                    catch (NumberFormatException ignored) {}
                }
            }
            case "CHAT"  -> {
                int sp2 = line.indexOf(' ');
                events.onChat(sp2 >= 0 ? line.substring(sp2 + 1) : line);
            }
            case "ERROR" -> {
                int sp2 = line.indexOf(' ');
                events.onError(sp2 >= 0 ? line.substring(sp2 + 1) : line);
            }
        }
    }
}

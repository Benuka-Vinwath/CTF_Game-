import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One per connected client, running on its own thread (thread-per-client).
 *
 * Reader thread (this Runnable): reads TCP lines from the client and calls GameState.
 * Writer thread: takes lines from the outbox queue and writes them to the socket.
 * The queue means game logic never waits on a slow client's network.
 */
public class ClientHandler implements Runnable {
    private static final String CLOSE = "\u0000CLOSE";
    private static final int MAX_QUEUE = 1000;

    private final Socket socket;
    private final GameState game;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private volatile boolean open = true;
    private Player player;   // null until HELLO

    public ClientHandler(Socket socket, GameState game) {
        this.socket = socket;
        this.game = game;
    }

    /** Thread-safe and non-blocking: callable from any thread, including inside the game lock. */
    public void send(String line) {
        if (!open) return;
        if (outbox.size() > MAX_QUEUE) { close(); return; }   // client stopped reading
        outbox.offer(line);
    }

    @Override
    public void run() {
        Thread writer = new Thread(this::writeLoop, Thread.currentThread().getName() + "-writer");
        writer.setDaemon(true);
        writer.start();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!handle(line.trim())) break;
            }
        } catch (IOException e) {
            // connection reset: treat as a disconnect
        } finally {
            close();
        }
    }

    /** Returns false to close the connection. */
    private boolean handle(String line) {
        if (line.isEmpty()) return true;
        String[] parts = line.split(" ", 2);
        String cmd = parts[0].toUpperCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        if (player == null) {
            if (!cmd.equals("HELLO")) { send("ERROR send HELLO <team> <name> first"); return true; }
            String[] a = arg.split(" ", 2);
            int team = parseInt(a[0], -1);
            String name = a.length > 1 ? cleanName(a[1]) : "Player";
            player = game.addPlayer(name, team, this);
            if (player == null) { send("ERROR server full"); return false; }
            System.out.println("[tcp] " + name + " connected from " + socket.getRemoteSocketAddress());
            return true;
        }

        switch (cmd) {
            case "READY"   -> game.setReady(player.id, true);
            case "UNREADY" -> game.setReady(player.id, false);
            case "TEAM"    -> game.changeTeam(player.id, parseInt(arg, -1));
            case "PING"    -> send("PONG " + arg);   // client measures round-trip time
            case "CHAT"    -> game.chat(player.id, arg);
            case "BYE"     -> { return false; }
            default        -> send("ERROR unknown command " + cmd);
        }
        return true;
    }

    private void writeLoop() {
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line = outbox.take();
                if (line == CLOSE) break;
                out.write(line);
                out.write('\n');
                if (outbox.isEmpty()) out.flush();   // batch several lines into one flush
            }
        } catch (IOException | InterruptedException e) {
            // socket closed
        } finally {
            close();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private synchronized void close() {
        if (!open) return;
        open = false;
        outbox.offer(CLOSE);                          // writer flushes, then closes the socket
        if (player != null) {
            System.out.println("[tcp] " + player.name + " disconnected");
            game.removePlayer(player.id);
        }
    }

    private static String cleanName(String s) {
        String n = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (n.isEmpty()) n = "Player";
        return n.length() > 12 ? n.substring(0, 12) : n;
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}

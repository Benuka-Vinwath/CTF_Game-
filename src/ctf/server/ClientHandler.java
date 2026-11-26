package ctf.server;

import ctf.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One per connected client, on its own thread (thread-per-client; see Threads for why that stays cheap).
 *
 * The reader thread (this Runnable) reads TCP lines and calls the player's room (a GameState). A second, writer thread
 * takes lines from an outbox queue and writes them to the socket, so game logic never waits on a
 * slow client's network. A client that stops reading entirely (queue over MAX_QUEUE) is dropped.
 *
 * Commands (client to server): ROOMS (allowed before HELLO: the public room list),
 * HELLO team name hat colour level title version [QUICK | CREATE:mode:public|private | JOIN:code] [player key],
 * MODE n (host: n players per team), READY, UNREADY, TEAM n, PING x, RTT ms, CHAT text,
 * BEGIN (host starts the countdown), LOOK hat colour, AGAIN (back to the lobby after a match), BYE
 */
public class ClientHandler implements Runnable, Conn {
    private static final String CLOSE = "\u0000CLOSE";
    private static final int MAX_QUEUE = 1000;

    private final Socket socket;
    private final RoomManager rooms;
    private volatile GameState game;   // the player's room; null until HELLO
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private volatile boolean open = true;
    private boolean closed;   // guarded by close()'s lock
    private volatile Player player;   // null until HELLO; read by close() on the writer thread too

    public ClientHandler(Socket socket, RoomManager rooms) {
        this.socket = socket;
        this.rooms = rooms;
    }

    /** Thread-safe and non-blocking: callable from any thread, including inside a room's lock. */
    @Override
    public void send(String line) {
        if (!open) return;
        if (outbox.size() > MAX_QUEUE) { drop(); return; }
        outbox.offer(line);
    }

    /**
     * Too far behind: stop queueing and cut the socket. The caller may hold a room's lock (a broadcast), so the player
     * is not removed here; the reader thread sees the socket close and leaves the room from its own thread.
     */
    private void drop() {
        open = false;
        outbox.clear();
        outbox.offer(CLOSE);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        Threads.start(Thread.currentThread().getName() + "-writer", this::writeLoop);
        // Not try-with-resources: closing the reader would close the socket before the writer has sent the last line
        // (e.g. "ERROR ... get the latest version"). The writer closes the socket once its queue is flushed.
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
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

        if (cmd.equals("ROOMS")) { send(Protocol.encodeRooms(rooms.listPublic())); return true; }

        if (player == null) {
            if (!cmd.equals("HELLO")) { send("ERROR send HELLO <team> <name> first"); return true; }
            String[] a = arg.split(" ");   // HELLO team name hat colour level title version; cleanName keeps the name free of spaces
            String version = a.length > 6 ? a[6] : "older";
            if (!version.equals(Protocol.VERSION)) {   // the wire format changes between versions: refuse clearly instead of failing strangely
                send("ERROR Your game is version " + version + " but this server runs " + Protocol.VERSION + ". Get the latest version to play.");
                return false;
            }
            int team = parseInt(a[0], -1);
            String name = Protocol.cleanName(a.length > 1 ? a[1] : "");
            String key = Db.ON && a.length > 8 && Db.validKey(a[8]) ? Db.hash(a[8]) : null;   // 9th field: the player's secret key
            int level = key != null ? Db.join(key, name)   // online: the level comes from the database, not from the client
                    : a.length > 4 ? parseInt(a[4], 1) : 1;
            RoomManager.Joined j = rooms.join(a.length > 7 ? a[7] : Protocol.QUICK, name, team,
                    a.length > 2 ? parseInt(a[2], 0) : 0, a.length > 3 ? parseInt(a[3], 0) : 0,
                    level, a.length > 5 ? parseInt(a[5], 0) : 0, this);
            if (j.error() != null) { send("ERROR " + j.error()); return false; }
            game = j.room().game;
            player = j.player();
            player.key = key;
            System.out.println("[tcp] " + name + " connected from " + socket.getRemoteSocketAddress() + " to room " + j.room().code);
            return true;
        }

        switch (cmd) {
            case "READY"   -> game.setReady(player.id, true);
            case "UNREADY" -> game.setReady(player.id, false);
            case "TEAM"    -> game.changeTeam(player.id, parseInt(arg, -1));
            case "PING"    -> send("PONG " + arg);                       // client measures the round trip
            case "RTT"     -> game.setPing(player.id, parseInt(arg, 0)); // client reports it for the scoreboard
            case "CHAT"    -> game.chat(player.id, arg);
            case "BEGIN"   -> game.begin(player.id);                     // host only; the server checks
            case "MODE"    -> game.setMode(player.id, parseInt(arg, 0)); // host only: 1v1 .. 4v4
            case "LOOK"    -> {
                String[] a = arg.split(" ");
                game.setLook(player.id, parseInt(a[0], 0), a.length > 1 ? parseInt(a[1], 0) : 0);
            }
            case "AGAIN"   -> game.wantAgain(player.id);
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
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Only ever called on this client's own reader or writer thread, never inside a room's lock. */
    private synchronized void close() {
        if (closed) return;
        closed = true;
        open = false;
        outbox.offer(CLOSE);                          // writer flushes, then closes the socket
        if (player != null) {
            System.out.println("[tcp] " + player.name + " disconnected");
            rooms.leave(player.id);
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}

package lobby.server;

import lobby.common.LobbyMap;
import lobby.common.LobbyProtocol;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lobby Server — coordinates the pre-game visual lobby then launches the CTF game.
 *
 * Flow:
 *  1. Players connect, walk around, chat.
 *  2. Each player clicks Ready → server tracks ready count.
 *  3. When readyCount >= minPlayers and ALL connected players are ready,
 *     a countdown starts (3 s).
 *  4. The CTF GameServer is started in a child JVM process on gamePort.
 *  5. LAUNCH <host> <gamePort> is broadcast → every client opens the CTF game.
 *
 * Usage:
 *   java -cp out lobby.server.LobbyServer [lobbyPort] [minPlayers] [gamePort]
 *   Defaults: 6000  2  5000
 */
public class LobbyServer {

    private static final int DEFAULT_LOBBY_PORT = 6000;
    private static final int DEFAULT_GAME_PORT  = 5000;
    private static final int DEFAULT_MIN        = 2;
    private static final int MAX_PLAYERS        = 32;
    private static final int COUNTDOWN_SECS     = 3;

    private static int minPlayers;
    private static int gamePort;
    private static String gameHost = "localhost";

    // ── player registry ──────────────────────────────────────────────────────
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final Map<Integer, PlayerConn> players = new LinkedHashMap<>();

    // ── countdown state ──────────────────────────────────────────────────────
    private static volatile boolean countdownRunning = false;
    private static volatile boolean gameLaunched     = false;

    // ── entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        int lobbyPort = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_LOBBY_PORT;
        minPlayers    = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_MIN;
        gamePort      = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_GAME_PORT;

        // Try to detect the machine's LAN IP so remote players can connect.
        try {
            gameHost = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ignored) {
            gameHost = "localhost";
        }

        System.out.printf("[LobbyServer] port=%d  minPlayers=%d  game will start on %s:%d%n",
                lobbyPort, minPlayers, gameHost, gamePort);

        try (ServerSocket ss = new ServerSocket(lobbyPort)) {
            //noinspection InfiniteLoopStatement
            while (true) {
                Socket sock = ss.accept();
                sock.setTcpNoDelay(true);
                int id = nextId.getAndIncrement();
                Thread t = new Thread(new PlayerConn(sock, id), "player-" + id);
                t.setDaemon(true);
                t.start();
            }
        }
    }

    // ── broadcast helpers ────────────────────────────────────────────────────

    static synchronized void broadcast(String line, int excludeId) {
        for (PlayerConn c : players.values())
            if (c.id != excludeId) c.send(line);
    }

    static synchronized void broadcastAll(String line) {
        broadcast(line, -1);
    }

    // ── ready check: called whenever a ready status changes ──────────────────

    static synchronized void checkReady() {
        int total = players.size();
        int ready = 0;
        for (PlayerConn c : players.values()) if (c.ready) ready++;

        broadcastAll(LobbyProtocol.encodeReadyStatus(ready, total));

        boolean allReady = total >= minPlayers && ready == total;

        if (allReady && !countdownRunning && !gameLaunched) {
            countdownRunning = true;
            Thread t = new Thread(LobbyServer::runCountdown, "countdown");
            t.setDaemon(true);
            t.start();
        } else if (!allReady && countdownRunning) {
            // Someone un-readied — cancel by flag (countdown thread checks it).
            countdownRunning = false;
            broadcastAll(LobbyProtocol.encodeChat(0, "Server", "Countdown cancelled – not all players ready"));
        }
    }

    private static void runCountdown() {
        for (int s = COUNTDOWN_SECS; s >= 0; s--) {
            if (!countdownRunning) return;   // cancelled
            broadcastAll(LobbyProtocol.encodeCountdown(s));
            if (s > 0) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            }
        }
        if (!countdownRunning) return;
        launchGame();
    }

    // ── launch the CTF game server ───────────────────────────────────────────

    private static synchronized void launchGame() {
        if (gameLaunched) return;
        gameLaunched = true;
        countdownRunning = false;

        int playerCount;
        synchronized (LobbyServer.class) { playerCount = players.size(); }

        System.out.printf("[LobbyServer] Launching CTF GameServer on port %d with %d players...%n",
                gamePort, playerCount);

        try {
            // Build the command to start ctf.server.GameServer in a new JVM process.
            String javaExe = ProcessHandle.current().info().command().orElse("java");
            // Classpath: same 'out' directory the lobby was started from.
            String cp = System.getProperty("java.class.path");
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-cp", cp,
                    "ctf.server.GameServer",
                    String.valueOf(gamePort),
                    String.valueOf(minPlayers));
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.start();
            System.out.println("[LobbyServer] CTF GameServer process started.");
        } catch (IOException e) {
            System.err.println("[LobbyServer] WARNING: could not start CTF GameServer automatically: " + e.getMessage());
            System.err.println("[LobbyServer] Start it manually: java -cp out ctf.server.GameServer " + gamePort);
        }

        // Give the game server 1 second to open its TCP port before clients try to connect.
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}

        broadcastAll(LobbyProtocol.encodeLaunch(gameHost, gamePort));
        System.out.printf("[LobbyServer] LAUNCH sent → %s:%d%n", gameHost, gamePort);
    }

    // ── PlayerConn ───────────────────────────────────────────────────────────

    static final class PlayerConn implements Runnable {

        final int    id;
        private final Socket   sock;
        private PrintWriter    out;

        volatile String  name  = "Player";
        volatile double  x     = LobbyMap.SPAWN_X;
        volatile double  y     = LobbyMap.SPAWN_Y;
        volatile int     team  = 0;
        volatile boolean ready = false;

        PlayerConn(Socket sock, int id) { this.sock = sock; this.id = id; }

        void send(String line) { if (out != null) out.println(line); }

        @Override
        public void run() {
            try {
                out = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)), true);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));

                // Send id + minPlayers
                send(LobbyProtocol.encodeWelcome(id, minPlayers));

                String line;
                while ((line = in.readLine()) != null) handleLine(line.trim());
            } catch (IOException ignored) {
            } finally {
                remove();
            }
        }

        private void handleLine(String line) {
            if (line.isEmpty()) return;
            int sp = line.indexOf(' ');
            String cmd = sp < 0 ? line : line.substring(0, sp);
            String arg = sp < 0 ? "" : line.substring(sp + 1);

            switch (cmd) {
                case "HELLO"   -> doHello(arg);
                case "MOVE"    -> doMove(arg);
                case "CHAT"    -> doChat(arg);
                case "READY"   -> doReady(true);
                case "UNREADY" -> doReady(false);
                case "BYE"     -> { try { sock.close(); } catch (IOException ignored) {} }
                // SHOOT is intentionally absent
                default        -> send(LobbyProtocol.encodeError("Unknown: " + cmd));
            }
        }

        private void doHello(String arg) {
            synchronized (LobbyServer.class) {
                if (players.size() >= MAX_PLAYERS) {
                    send(LobbyProtocol.encodeError("Lobby full"));
                    try { sock.close(); } catch (IOException ignored) {}
                    return;
                }

                name = LobbyProtocol.sanitize(arg);
                team = players.size() % 2;

                // Spread spawn positions
                x = LobbyMap.SPAWN_X + (players.size() % 8 - 3.5) * 48.0;
                y = LobbyMap.SPAWN_Y + (players.size() / 8 - 1.5) * 48.0;
                LobbyMap.resolve(new double[]{x, y});

                players.put(id, this);

                // If game already launched, tell newcomer to go straight to game
                if (gameLaunched) {
                    send(LobbyProtocol.encodeLaunch(gameHost, gamePort));
                    return;
                }

                // Full state snapshot for the newcomer
                List<LobbyProtocol.PlayerState> snapshot = new ArrayList<>();
                for (PlayerConn c : players.values())
                    snapshot.add(new LobbyProtocol.PlayerState(c.id, c.team, c.x, c.y, c.ready, c.name));
                send(LobbyProtocol.encodeState(snapshot));

                // Tell everyone else
                broadcast(LobbyProtocol.encodeJoin(id, team, name), id);
            }

            // Broadcast updated ready status to everyone
            checkReady();
            System.out.printf("[Lobby] %s (#%d) joined. Total: %d%n", name, id, players.size());
        }

        private void doMove(String arg) {
            try {
                String[] t = arg.split(" ");
                double[] pos = { Double.parseDouble(t[0]), Double.parseDouble(t[1]) };
                LobbyMap.resolve(pos);
                x = pos[0]; y = pos[1];
            } catch (Exception e) { return; }
            broadcast(LobbyProtocol.encodeMove(id, x, y), id);
        }

        private void doChat(String text) {
            String safe = LobbyProtocol.sanitizeChatText(text);
            if (safe.isEmpty()) return;
            broadcastAll(LobbyProtocol.encodeChat(id, name, safe));
        }

        private void doReady(boolean r) {
            ready = r;
            broadcastAll(LobbyProtocol.encodeReadyUpdate(id, r));
            System.out.printf("[Lobby] %s is %s%n", name, r ? "READY" : "UNREADY");
            checkReady();
        }

        private void remove() {
            synchronized (LobbyServer.class) { players.remove(id); }
            broadcastAll(LobbyProtocol.encodeLeave(id));
            try { sock.close(); } catch (IOException ignored) {}
            System.out.printf("[Lobby] %s (#%d) left. Total: %d%n", name, id, players.size());
            if (!gameLaunched) checkReady();
        }
    }
}

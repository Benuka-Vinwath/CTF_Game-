package ctf.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point. Starts three kinds of threads:
 *   1. accept thread - TCP accept loop, one new ClientHandler thread per client
 *   2. udp-receiver  - receives input packets
 *   3. tick-loop     - runs the game 30 times per second and sends snapshots
 *
 * Usage: java ctf.server.GameServer [port] [minPlayers]   (defaults 5000 and 2)
 * TCP and UDP both use the same port number. The game's HOST button calls start() inside the client instead.
 */
public class GameServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int minPlayers = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        start(port, minPlayers).join();   // run until the process is stopped
    }

    /**
     * Opens the TCP and UDP sockets now (so a busy port fails here, with an IOException the caller can show),
     * then serves on background threads. Returns the accept thread.
     */
    public static Thread start(int port, int minPlayers) throws IOException {
        GameState game = new GameState(minPlayers, port);
        ServerSocket server = new ServerSocket(port);
        UdpServer udp;
        try {
            udp = new UdpServer(port, game);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        Thread udpThread = new Thread(udp, "udp-receiver");
        udpThread.setDaemon(true);
        udpThread.start();

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tick-loop");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> {
            try {
                game.tick();
                String snapshot = game.snapshotLine();
                for (InetSocketAddress addr : game.udpTargets()) udp.send(snapshot, addr);
            } catch (Exception e) {
                e.printStackTrace();   // never let one bad tick stop the loop
            }
        }, 0, 1_000_000 / GameState.TICK_RATE, TimeUnit.MICROSECONDS);

        System.out.println("CTF server on TCP+UDP port " + port + ", " + GameState.TICK_RATE
                + " ticks/s, starts with " + minPlayers + " ready players");
        Thread accept = new Thread(() -> {
            try (server) {
                while (true) {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);   // send small messages immediately
                    new Thread(new ClientHandler(s, game), "client-" + s.getPort()).start();
                }
            } catch (IOException e) {
                System.err.println("Server stopped: " + e.getMessage());
            }
        }, "accept");
        accept.setDaemon(true);   // a server inside the game closes with the game window
        accept.start();
        return accept;
    }
}

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point. Starts three kinds of threads:
 *   1. main thread     - TCP accept loop, one new ClientHandler thread per client
 *   2. udp-receiver    - receives input packets
 *   3. tick-loop       - runs the game 30 times per second and sends snapshots
 *
 * Usage: java GameServer [port] [minPlayers]   (defaults: 5000, 2)
 * TCP and UDP both use the same port number.
 */
public class GameServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int minPlayers = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        GameState game = new GameState(minPlayers, port);

        UdpServer udp = new UdpServer(port, game);
        new Thread(udp, "udp-receiver").start();

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tick-loop");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> {
            try {
                game.tick();
                String snapshot = game.snapshot();
                for (InetSocketAddress addr : game.udpTargets()) udp.send(snapshot, addr);
            } catch (Exception e) {
                e.printStackTrace();   // never let one bad tick stop the loop
            }
        }, 0, 1_000_000 / GameState.TICK_RATE, TimeUnit.MICROSECONDS);

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("CTF server on TCP+UDP port " + port
                    + ", " + GameState.TICK_RATE + " ticks/s, starts with " + minPlayers + " ready players");
            while (true) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);   // send small messages immediately
                new Thread(new ClientHandler(s, game), "client-" + s.getPort()).start();
            }
        }
    }
}

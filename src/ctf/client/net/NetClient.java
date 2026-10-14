package ctf.client.net;

import ctf.client.NetLink;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client side of the connection, and the NetLink the game screen uses.
 *  TCP: lobby, chat, ping, start and game over (reliable, in order).
 *  UDP: our input goes up, world snapshots come down, 30 per second (fast, may be lost or reordered).
 */
public class NetClient implements NetLink {
    private final NetEvents events;
    private Socket tcp;
    private PrintWriter tcpOut;
    private volatile boolean connected;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile int myId = -1;
    private volatile int token;
    private volatile int udpPort;
    private volatile int lastRtt;
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    private volatile Map<Integer, Protocol.Stat> stats = Map.of();

    private volatile DatagramSocket udp;
    private volatile InetSocketAddress udpServer;
    private final AtomicInteger inputSeq = new AtomicInteger();
    private long lastTick = -1;   // guarded by handleSnapshot being synchronized

    // Simulated bad network, applied to UDP in both directions
    private volatile int simDelayMs;
    private volatile int simLossPercent;
    private final Random rng = new Random();

    private final AtomicLong statReceived = new AtomicLong();
    private final AtomicLong statStale = new AtomicLong();
    private final AtomicLong statBad = new AtomicLong();
    private final AtomicLong statSimLost = new AtomicLong();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "net-scheduler");
        t.setDaemon(true);
        return t;
    });

    public NetClient(NetEvents events) {
        this.events = events;
    }

    // ------------------------------------------------------------------ TCP

    /** Blocks briefly. Call from a background thread. team: 0 red, 1 blue, -1 automatic. */
    public void connect(String host, int port, String name, int team) throws IOException {
        tcp = new Socket();
        tcp.connect(new InetSocketAddress(host, port), 3000);
        tcp.setTcpNoDelay(true);
        tcpOut = new PrintWriter(tcp.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(tcp.getInputStream(), StandardCharsets.UTF_8));
        connected = true;

        Thread reader = new Thread(() -> tcpLoop(in), "tcp-reader");
        reader.setDaemon(true);
        reader.start();

        sendTcp("HELLO " + team + " " + Protocol.cleanName(name));
        scheduler.scheduleAtFixedRate(() -> sendTcp("PING " + System.nanoTime()),
                2, 2, TimeUnit.SECONDS);
    }

    private void tcpLoop(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) handleTcp(line.trim());
            close("Server closed the connection");
        } catch (IOException e) {
            close(closed.get() ? "Disconnected" : "Connection lost: " + e.getMessage());
        }
    }

    private void handleTcp(String line) {
        if (line.isEmpty()) return;
        String[] parts = line.split(" ", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        try {
            switch (parts[0]) {
                case "WELCOME" -> {
                    String[] t = arg.split(" ");
                    myId = Integer.parseInt(t[0]);
                    token = Integer.parseInt(t[1]);
                    udpPort = Integer.parseInt(t[2]);
                    openUdp();
                    events.onWelcome(myId, Integer.parseInt(t[3]));
                }
                case "LOBBY" -> {
                    Protocol.Lobby lobby = Protocol.decodeLobby(line);
                    if (lobby == null) return;
                    names.clear();
                    for (Protocol.LobbyEntry e : lobby.entries()) names.put(e.id(), e.name());
                    events.onLobby(lobby);
                }
                case "CHAT" -> events.onChat(arg);
                case "START" -> events.onStart();
                case "STATS" -> {
                    Map<Integer, Protocol.Stat> m = new ConcurrentHashMap<>();
                    for (Protocol.Stat s : Protocol.decodeStats(line)) m.put(s.id(), s);
                    stats = m;
                }
                case "PONG" -> {
                    lastRtt = (int) ((System.nanoTime() - Long.parseLong(arg.trim())) / 1_000_000);
                    sendTcp("RTT " + lastRtt);   // tell the server, so the scoreboard can show it
                }
                case "GAMEOVER" -> {
                    String[] t = arg.split(" ");
                    events.onGameOver(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]));
                }
                case "ERROR" -> events.onServerError(arg);
                default -> { }   // EVENT and unknown lines are ignored
            }
        } catch (RuntimeException e) {
            // a malformed line from the network must never kill the reader thread
        }
    }

    private synchronized void sendTcp(String line) {
        if (connected && tcpOut != null) tcpOut.println(line);
    }

    public void sendReady(boolean ready) { sendTcp(ready ? "READY" : "UNREADY"); }

    public void sendTeam(int team) { sendTcp("TEAM " + team); }

    public void sendChat(String text) {
        if (text != null && !text.isBlank()) sendTcp("CHAT " + text.replace('\n', ' ').replace('\r', ' '));
    }

    public void disconnect() {
        sendTcp("BYE");
        close("You left the game");
    }

    private void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        scheduler.shutdownNow();
        try { if (tcp != null) tcp.close(); } catch (IOException ignored) { }
        DatagramSocket u = udp;
        if (u != null) u.close();
        events.onDisconnected(reason);
    }

    // ------------------------------------------------------------------ UDP

    private void openUdp() {
        try {
            udp = new DatagramSocket();
            udpServer = new InetSocketAddress(tcp.getInetAddress(), udpPort);
        } catch (IOException e) {
            close("Could not open UDP socket: " + e.getMessage());
            return;
        }
        Thread t = new Thread(this::udpLoop, "udp-reader");
        t.setDaemon(true);
        t.start();
    }

    private void udpLoop() {
        byte[] buf = new byte[2048];
        while (connected) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (rng.nextInt(100) < simLossPercent) {   // simulated packet loss
                    statSimLost.incrementAndGet();
                    continue;
                }
                runLater(() -> handleSnapshot(line), simDelayMs);
            } catch (IOException e) {
                if (connected) close("UDP error: " + e.getMessage());
                return;
            }
        }
    }

    /** Runs now, or after delayMs (simulated lag). */
    private void runLater(Runnable r, int delayMs) {
        if (delayMs <= 0) { r.run(); return; }
        try {
            scheduler.schedule(r, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private synchronized void handleSnapshot(String line) {
        Snapshot s = Protocol.decodeSnapshot(line);
        if (s == null) { statBad.incrementAndGet(); return; }
        statReceived.incrementAndGet();
        if (s.tick <= lastTick) {          // old or duplicate: UDP can reorder
            statStale.incrementAndGet();
            return;
        }
        lastTick = s.tick;
        Map<Integer, Protocol.Stat> st = stats;
        for (Snapshot.P p : s.players) {   // names and statistics travel on TCP, merge them in
            p.name = names.getOrDefault(p.id, "P" + p.id);
            Protocol.Stat x = st.get(p.id);
            if (x != null) {
                p.tags = x.tags();
                p.captures = x.captures();
                p.deaths = x.deaths();
                p.score = x.score();
                p.pingMs = x.ping();
            }
        }
        events.onSnapshot(s);
    }

    // ------------------------------------------------------------------ NetLink (called by the game screen)

    /** Called about 30 times a second. A lost packet is harmless: the next one replaces it. */
    @Override
    public void sendInput(int dx, int dy, double aim, boolean shoot) {
        DatagramSocket u = udp;
        InetSocketAddress to = udpServer;
        if (!connected || u == null || to == null || myId < 0) return;
        String line = Protocol.encodeInput(new Protocol.Input(myId, token,
                inputSeq.incrementAndGet(), dx, dy, aim, shoot));
        if (rng.nextInt(100) < simLossPercent) {
            statSimLost.incrementAndGet();
            return;
        }
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        runLater(() -> {
            try {
                u.send(new DatagramPacket(data, data.length, to));
            } catch (IOException ignored) {
                // UDP send failures are not fatal
            }
        }, simDelayMs);
    }

    @Override
    public int pingMs() { return lastRtt + 2 * simDelayMs; }

    @Override
    public void requestPlayAgain() { sendTcp("AGAIN"); }

    @Override
    public void toggleLagSim() {
        if (lagSimOn()) setLagSimulation(0, 0);
        else setLagSimulation(100, 5);
    }

    @Override
    public boolean lagSimOn() { return simDelayMs > 0 || simLossPercent > 0; }

    /** Extra one-way delay and random packet loss on UDP, to test how the game copes. */
    public void setLagSimulation(int delayMs, int lossPercent) {
        simDelayMs = Math.max(0, delayMs);
        simLossPercent = Math.max(0, Math.min(100, lossPercent));
    }

    // ------------------------------------------------------------------ information

    public int getMyId() { return myId; }

    public int getRtt() { return lastRtt; }

    public boolean isConnected() { return connected; }

    public long getPacketsReceived() { return statReceived.get(); }

    public long getPacketsStale() { return statStale.get(); }

    public long getPacketsBad() { return statBad.get(); }

    public long getPacketsSimulatedLost() { return statSimLost.get(); }
}
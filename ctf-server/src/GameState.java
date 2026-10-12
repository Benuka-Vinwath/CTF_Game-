import java.net.InetSocketAddress;
import java.util.*;

/**
 * The single source of truth for the game (authoritative server).
 *
 * Concurrency: many threads touch this object (one TCP handler thread per client,
 * the UDP receive thread, and the tick thread). Every public method is
 * synchronized, so only one thread changes the game at a time. Sending to clients
 * from inside a lock is safe because ClientHandler.send() only puts the line on a
 * queue; it never blocks on the network.
 */
public class GameState {
    public enum Phase { LOBBY, PLAYING }

    public static final int TICK_RATE = 30;               // updates per second
    public static final double SPEED = 200;               // pixels per second
    public static final double CARRIER_SPEED_FACTOR = 0.85;
    public static final int CAPTURES_TO_WIN = 3;
    public static final long ROUND_MS = 5 * 60_000;
    public static final long RESPAWN_MS = 3_000;
    public static final long FLAG_RETURN_MS = 10_000;
    public static final int MAX_PLAYERS = 8;
    public static final String[] TEAM_NAMES = { "Red", "Blue" };

    private final int minPlayers;
    private final int udpPort;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final Flag[] flags = {
        new Flag(0, GameMap.BASES[0][0], GameMap.BASES[0][1]),
        new Flag(1, GameMap.BASES[1][0], GameMap.BASES[1][1]),
    };
    private final int[] score = new int[2];
    private final Random rng = new Random();

    private Phase phase = Phase.LOBBY;
    private long roundEndAt;
    private long tick;
    private int nextId = 1;

    public GameState(int minPlayers, int udpPort) {
        this.minPlayers = minPlayers;
        this.udpPort = udpPort;
    }

    // ------------------------------------------------------------------
    // Lobby and room state (called from ClientHandler threads over TCP)
    // ------------------------------------------------------------------

    public synchronized Player addPlayer(String name, int team, ClientHandler conn) {
        if (players.size() >= MAX_PLAYERS) return null;
        if (team != 0 && team != 1) team = teamSize(0) <= teamSize(1) ? 0 : 1;   // auto-balance

        Player p = new Player(nextId++, name, team, rng.nextInt(Integer.MAX_VALUE), conn);
        players.put(p.id, p);
        conn.send("WELCOME " + p.id + " " + p.token + " " + udpPort + " " + p.team);

        if (phase == Phase.PLAYING) {        // joining mid-game: drop straight in
            p.ready = true;
            spawn(p);
            conn.send("START");
        }
        event(name + " joined " + TEAM_NAMES[team]);
        broadcastLobby();
        return p;
    }

    public synchronized void removePlayer(int id) {
        Player p = players.remove(id);
        if (p == null) return;
        dropFlagCarriedBy(p, System.currentTimeMillis());   // carrier left: their flag drops
        event(p.name + " left");
        if (players.isEmpty() && phase == Phase.PLAYING) resetToLobby();
        broadcastLobby();
        tryStart();   // the leaver may have been the only one not ready
    }

    public synchronized void setReady(int id, boolean ready) {
        Player p = players.get(id);
        if (p == null || phase != Phase.LOBBY) return;
        p.ready = ready;
        broadcastLobby();
        tryStart();
    }

    public synchronized void changeTeam(int id, int team) {
        Player p = players.get(id);
        if (p == null || phase != Phase.LOBBY || (team != 0 && team != 1)) return;
        p.team = team;
        broadcastLobby();
    }

    public synchronized void chat(int id, String text) {
        Player p = players.get(id);
        if (p == null || text.isBlank()) return;
        String msg = "CHAT " + p.name + ": " + text.substring(0, Math.min(text.length(), 200));
        for (Player q : players.values()) q.conn.send(msg);
    }

    private void tryStart() {
        if (phase != Phase.LOBBY || players.size() < minPlayers) return;
        for (Player p : players.values()) if (!p.ready) return;
        startRound();
    }

    private void startRound() {
        score[0] = score[1] = 0;
        for (Flag f : flags) f.returnToBase();
        for (Player p : players.values()) spawn(p);
        phase = Phase.PLAYING;
        roundEndAt = System.currentTimeMillis() + ROUND_MS;
        for (Player p : players.values()) p.conn.send("START");
        event("Round started! First to " + CAPTURES_TO_WIN + " captures wins");
    }

    // ------------------------------------------------------------------
    // Input (called from the UDP receive thread)
    // ------------------------------------------------------------------

    /** keys bitmask: 1 = up, 2 = down, 4 = left, 8 = right */
    public synchronized void handleInput(int id, int token, int seq, int keys, InetSocketAddress from) {
        Player p = players.get(id);
        if (p == null || p.token != token) return;   // reject spoofed packets
        p.udpAddr = from;                             // remember where to send snapshots
        if (seq <= p.lastSeq) return;                 // UDP can reorder: ignore stale input
        p.lastSeq = seq;
        p.up = (keys & 1) != 0;
        p.down = (keys & 2) != 0;
        p.left = (keys & 4) != 0;
        p.right = (keys & 8) != 0;
    }

    // ------------------------------------------------------------------
    // The tick loop (called 30 times per second by the tick thread)
    // ------------------------------------------------------------------

    public synchronized void tick() {
        tick++;
        if (phase != Phase.PLAYING) return;
        long now = System.currentTimeMillis();
        double dt = 1.0 / TICK_RATE;

        // 1. Respawn
        for (Player p : players.values())
            if (!p.alive && now >= p.respawnAt) spawn(p);

        // 2. Movement. The server moves players; clients cannot set positions,
        //    so speed hacks and teleporting are impossible.
        for (Player p : players.values()) {
            if (!p.alive) continue;
            int dx = (p.right ? 1 : 0) - (p.left ? 1 : 0);
            int dy = (p.down ? 1 : 0) - (p.up ? 1 : 0);
            if (dx == 0 && dy == 0) continue;
            double step = SPEED * dt * (isCarrier(p) ? CARRIER_SPEED_FACTOR : 1);
            if (dx != 0 && dy != 0) step /= Math.sqrt(2);          // no faster diagonals
            double nx = p.x + dx * step, ny = p.y + dy * step;
            if (!GameMap.blocked(nx, p.y, GameMap.PLAYER_R)) p.x = nx;   // x and y separately
            if (!GameMap.blocked(p.x, ny, GameMap.PLAYER_R)) p.y = ny;   // so players slide on walls
        }

        // 3. Tagging: touching an enemy who is in YOUR half tags them
        List<Player> list = new ArrayList<>(players.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Player a = list.get(i), b = list.get(j);
                if (a.team == b.team || !a.alive || !b.alive) continue;
                if (dist(a.x, a.y, b.x, b.y) >= 2 * GameMap.PLAYER_R) continue;
                boolean aIntruder = GameMap.onEnemySide(a), bIntruder = GameMap.onEnemySide(b);
                if (aIntruder && !bIntruder) tag(a, b, now);
                else if (bIntruder && !aIntruder) tag(b, a, now);
            }
        }

        // 4. Flags (the state machine)
        for (Flag f : flags) {
            updateFlag(f, now);
            if (phase != Phase.PLAYING) return;   // a capture ended the round
        }

        // 5. Round timer
        if (now >= roundEndAt) {
            int winner = score[0] > score[1] ? 0 : score[1] > score[0] ? 1 : -1;
            endRound(winner);
        }
    }

    private void updateFlag(Flag f, long now) {
        switch (f.state) {
            case CARRIED -> {
                Player c = players.get(f.carrierId);
                if (c == null || !c.alive) { f.drop(f.x, f.y, now); return; }
                f.x = c.x;
                f.y = c.y;
                // Capture: carrier is inside their own base AND their own flag is home
                double[] home = GameMap.BASES[c.team];
                if (dist(c.x, c.y, home[0], home[1]) < GameMap.BASE_R
                        && flags[c.team].state == Flag.State.AT_BASE) {
                    score[c.team]++;
                    f.returnToBase();
                    event(c.name + " captured the " + TEAM_NAMES[f.team] + " flag! "
                            + "Red " + score[0] + " - " + score[1] + " Blue");
                    if (score[c.team] >= CAPTURES_TO_WIN) endRound(c.team);
                }
            }
            case DROPPED -> {
                if (now - f.droppedAt >= FLAG_RETURN_MS) {
                    f.returnToBase();
                    event("The " + TEAM_NAMES[f.team] + " flag returned to base");
                    return;
                }
                checkTouch(f);
            }
            case AT_BASE -> checkTouch(f);
        }
    }

    /** Someone touches a flag that is at base or on the ground. */
    private void checkTouch(Flag f) {
        for (Player p : players.values()) {
            if (!p.alive || dist(p.x, p.y, f.x, f.y) >= GameMap.PLAYER_R + GameMap.FLAG_R) continue;
            if (p.team != f.team && !isCarrier(p)) {               // enemy grabs it
                f.pickUp(p);
                event(p.name + " took the " + TEAM_NAMES[f.team] + " flag!");
                return;
            }
            if (p.team == f.team && f.state == Flag.State.DROPPED) { // teammate returns it
                f.returnToBase();
                event(p.name + " returned the " + TEAM_NAMES[f.team] + " flag");
                return;
            }
        }
    }

    private void tag(Player victim, Player by, long now) {
        victim.alive = false;
        victim.respawnAt = now + RESPAWN_MS;
        dropFlagCarriedBy(victim, now);
        event(by.name + " tagged " + victim.name);
    }

    private void dropFlagCarriedBy(Player p, long now) {
        for (Flag f : flags) {
            if (f.state == Flag.State.CARRIED && f.carrierId == p.id) {
                f.drop(p.x, p.y, now);
                event(p.name + " dropped the " + TEAM_NAMES[f.team] + " flag");
            }
        }
    }

    private void endRound(int winner) {
        String msg = "GAMEOVER " + winner + " " + score[0] + " " + score[1];
        for (Player p : players.values()) p.conn.send(msg);
        event(winner < 0 ? "Round over: draw" : TEAM_NAMES[winner] + " wins the round!");
        resetToLobby();
        broadcastLobby();
    }

    private void resetToLobby() {
        phase = Phase.LOBBY;
        for (Flag f : flags) f.returnToBase();
        for (Player p : players.values()) {
            p.ready = false;   // everyone presses READY again to play again
            p.alive = true;
            p.up = p.down = p.left = p.right = false;
        }
    }

    private void spawn(Player p) {
        double[] base = GameMap.BASES[p.team];
        for (int tries = 0; tries < 30; tries++) {
            double ang = rng.nextDouble() * 2 * Math.PI;
            double r = rng.nextDouble() * (GameMap.BASE_R - GameMap.PLAYER_R);
            double x = base[0] + Math.cos(ang) * r, y = base[1] + Math.sin(ang) * r;
            if (!GameMap.blocked(x, y, GameMap.PLAYER_R)) { p.x = x; p.y = y; break; }
            p.x = base[0];
            p.y = base[1];
        }
        p.alive = true;
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    /**
     * One UDP snapshot of the whole world:
     * S tick phase msLeft scoreRed scoreBlue
     *   [flag0: state x y carrierId] [flag1: state x y carrierId]
     *   playerCount [per player: id team x y alive lastSeq]
     */
    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder(256);
        long msLeft = phase == Phase.PLAYING ? Math.max(0, roundEndAt - System.currentTimeMillis()) : 0;
        sb.append("S ").append(tick).append(' ').append(phase).append(' ').append(msLeft)
          .append(' ').append(score[0]).append(' ').append(score[1]);
        for (Flag f : flags)
            sb.append(' ').append(f.state).append(' ').append(Math.round(f.x))
              .append(' ').append(Math.round(f.y)).append(' ').append(f.carrierId);
        sb.append(' ').append(players.size());
        for (Player p : players.values())
            sb.append(' ').append(p.id).append(' ').append(p.team)
              .append(' ').append(Math.round(p.x)).append(' ').append(Math.round(p.y))
              .append(' ').append(p.alive ? 1 : 0).append(' ').append(p.lastSeq);
        return sb.toString();
    }

    public synchronized List<InetSocketAddress> udpTargets() {
        List<InetSocketAddress> out = new ArrayList<>();
        for (Player p : players.values()) if (p.udpAddr != null) out.add(p.udpAddr);
        return out;
    }

    /** LOBBY phase minPlayers id:team:ready:name,id:team:ready:name,... */
    private void broadcastLobby() {
        StringJoiner list = new StringJoiner(",");
        for (Player p : players.values())
            list.add(p.id + ":" + p.team + ":" + (p.ready ? 1 : 0) + ":" + p.name);
        String msg = "LOBBY " + phase + " " + minPlayers + " " + list;
        for (Player p : players.values()) p.conn.send(msg);
    }

    private void event(String text) {
        System.out.println("[event] " + text);
        for (Player p : players.values()) p.conn.send("EVENT " + text);
    }

    private boolean isCarrier(Player p) {
        for (Flag f : flags) if (f.state == Flag.State.CARRIED && f.carrierId == p.id) return true;
        return false;
    }

    private int teamSize(int team) {
        int n = 0;
        for (Player p : players.values()) if (p.team == team) n++;
        return n;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }
}

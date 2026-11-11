package ctf.server;

import ctf.common.LobbyMap;
import ctf.common.MapData;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The single source of truth for the game (authoritative server). The rules mirror the client's
 * Demo.java and use the shared MapData, so the client's movement prediction matches the server.
 *
 * Phases: LOBBY (walk around the waiting room, pick a team pad, ready up; the host starts a 5 s countdown)
 *   -> PLAYING -> ENDED (victory screen) -> back to LOBBY. Nobody can be hurt in the lobby.
 *
 * Concurrency: TCP handler threads, the UDP receive thread and the tick thread all call in here.
 * Every public method is synchronized, so only one thread changes the game at a time. Sending to
 * clients from inside the lock is safe because Conn.send() only queues the line.
 *
 * All timers count in ticks (fixed DT per tick), never wall-clock time, so tests are deterministic.
 */
public class GameState {
    public enum Phase { LOBBY, PLAYING, ENDED }

    public static final int TICK_RATE = 30;
    public static final double DT = 1.0 / TICK_RATE;
    public static final int CAPTURES_TO_WIN = 3;
    public static final double ROUND_SEC = 300;
    public static final double RESPAWN_SEC = 3;
    public static final double FLAG_RETURN_SEC = 10;
    public static final double FLAG_TOUCH_R = 25;
    public static final double INPUT_TIMEOUT_SEC = 0.5;   // no input for this long: the player stops
    public static final double AGAIN_TIMEOUT_SEC = 20;    // after a match everyone returns to the lobby by itself after this long
    public static final int MAX_PLAYERS = LobbyMap.MAX_PLAYERS;
    public static final int MAX_TEAM = LobbyMap.MAX_TEAM;
    public static final double COUNTDOWN_SEC = 5;
    private static final String[] TEAM_NAMES = {"Red", "Blue"};

    private final int minPlayers;
    private final int udpPort;
    private final Random rng;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final Flag[] flags = {new Flag(0), new Flag(1)};
    private final List<double[]> bullets = new ArrayList<>();   // x, y, vx, vy, team, shooterId
    private final int[] score = new int[2];

    private Phase phase = Phase.LOBBY;
    private double timeLeft = ROUND_SEC;
    private double tentCooldown;
    private double endElapsed;
    private double countdown;     // > 0 while the host's start is counting down in the lobby
    private int mode = 1;         // players per team: 1 = 1v1 ... 4 = 4v4. The host picks it; it grows when a team overflows
    private int winner = -1;
    private long tick;
    private int nextId = 1;

    public GameState(int minPlayers, int udpPort) {
        this(minPlayers, udpPort, new Random());
    }

    public GameState(int minPlayers, int udpPort, Random rng) {
        this.minPlayers = minPlayers;
        this.udpPort = udpPort;
        this.rng = rng;
    }

    // ------------------------------------------------------------------ lobby (TCP threads)

    public synchronized Player addPlayer(String name, int team, Conn conn) { return addPlayer(name, team, 0, 0, 1, 0, conn); }

    /** hat and hatColor were chosen in the main menu; they are wrapped into range like setLook does. */
    public synchronized Player addPlayer(String name, int team, int hat, int hatColor, int level, int title, Conn conn) {
        if (players.size() >= MAX_PLAYERS) return null;
        if (team != 0 && team != 1) team = teamSize(0) <= teamSize(1) ? 0 : 1;   // auto-balance
        if (teamSize(team) >= MAX_TEAM) team = 1 - team;
        if (teamSize(team) >= mode) mode = teamSize(team) + 1;   // a full team: the match grows (1v1 -> 2v2)
        Player p = new Player(nextId++, name, team, rng.nextInt(Integer.MAX_VALUE), conn);
        p.hat = Math.floorMod(hat, LobbyMap.HATS);
        p.hatColor = Math.floorMod(hatColor, LobbyMap.HAT_COLORS);
        p.level = Math.max(1, Math.min(level, 999));   // shown to others only; each player's own computer keeps the real profile
        p.title = Math.max(0, Math.min(title, 31));
        players.put(p.id, p);
        conn.send("WELCOME " + p.id + " " + p.token + " " + udpPort + " " + p.team);
        if (phase == Phase.LOBBY) {
            placeInLobby(p);
            checkCountdown();                // a new, unready player stops a running countdown
        } else {                             // joining a running game: drop straight in
            p.ready = true;
            spawn(p);
            conn.send("START");
        }
        log(name + " joined " + TEAM_NAMES[team]);
        broadcastLobby();
        return p;
    }

    public synchronized void removePlayer(int id) {
        Player p = players.remove(id);
        if (p == null) return;
        dropFlagCarriedBy(p);
        log(p.name + " left");
        if (players.isEmpty()) resetToLobby();
        checkCountdown();
        broadcastLobby();
    }

    public synchronized void setReady(int id, boolean ready) {
        Player p = players.get(id);
        if (p == null || phase != Phase.LOBBY) return;
        p.ready = ready;
        checkCountdown();
        broadcastLobby();
    }

    public synchronized void changeTeam(int id, int team) {
        Player p = players.get(id);
        if (p == null || phase != Phase.LOBBY || (team != 0 && team != 1)) return;
        if (p.team != team && teamSize(team) >= mode) return;   // that team is full
        p.team = team;
        broadcastLobby();
    }

    /** Host only: the match size (1 = 1v1 ... 4 = 4v4). Refused if a team already has more players than that. */
    public synchronized void setMode(int id, int n) {
        if (id != hostId() || phase != Phase.LOBBY || n < 1 || n > MAX_TEAM || n < teamSize(0) || n < teamSize(1)) return;
        mode = n;
        checkCountdown();
        broadcastLobby();
    }

    /** The host pressed START: begin the countdown if everyone is ready. Anyone else is ignored. */
    public synchronized void begin(int id) {
        if (id != hostId() || !canStart() || countdown > 0) return;
        countdown = COUNTDOWN_SEC;
        log("Countdown started by " + players.get(id).name);
    }

    /** Hat and hat colour, chosen at the lobby laptop. The server checks the player really is at the laptop. */
    public synchronized void setLook(int id, int hat, int color) {
        Player p = players.get(id);
        if (p == null || phase != Phase.LOBBY || !LobbyMap.nearLaptop(p.x, p.y)) return;
        p.hat = Math.floorMod(hat, LobbyMap.HATS);
        p.hatColor = Math.floorMod(color, LobbyMap.HAT_COLORS);
        broadcastLobby();
    }

    public synchronized void chat(int id, String text) {
        Player p = players.get(id);
        if (p == null || text.isBlank()) return;
        String msg = "CHAT " + p.id + " " + p.name + ": " + text.substring(0, Math.min(text.length(), 200));
        for (Player q : players.values()) q.conn.send(msg);
    }

    /** The first player still connected is the host; when they leave, the next one takes over. */
    private int hostId() {
        return players.isEmpty() ? -1 : players.keySet().iterator().next();
    }

    private boolean canStart() {
        if (phase != Phase.LOBBY || players.size() < minPlayers) return false;
        // both teams must be full (2v2 needs 2 and 2); a server started for 1 player is the solo test server
        if (minPlayers > 1 && (teamSize(0) != mode || teamSize(1) != mode)) return false;
        for (Player p : players.values()) if (!p.ready) return false;
        return true;
    }

    /** A running countdown stops as soon as the start conditions no longer hold. */
    private void checkCountdown() {
        if (countdown > 0 && !canStart()) {
            countdown = 0;
            log("Countdown cancelled");
        }
    }

    /** The client asks to go back to the lobby on the victory screen. */
    public synchronized void wantAgain(int id) {
        Player p = players.get(id);
        if (p == null || phase != Phase.ENDED || p.wantsAgain) return;
        p.wantsAgain = true;
        p.ready = true;   // during ENDED, "ready" in the LOBBY list means "voted to go back", so everyone sees the ticks
        broadcastLobby();
    }

    public synchronized void setPing(int id, int ms) {
        Player p = players.get(id);
        if (p != null) p.pingMs = Math.max(0, Math.min(ms, 9999));
    }

    private void startRound() {
        score[0] = score[1] = 0;
        winner = -1;
        timeLeft = ROUND_SEC;
        tentCooldown = 0;
        endElapsed = 0;
        countdown = 0;
        bullets.clear();
        for (Flag f : flags) f.returnToBase();
        for (Player p : players.values()) {
            p.tags = p.captures = p.deaths = 0;
            p.shieldSec = 0;
            p.fireCd = 0;
            p.wantsAgain = false;
            p.shootLatch = false;
            spawn(p);
        }
        phase = Phase.PLAYING;
        broadcast("START");
        broadcastLobby();   // the phase changed
        log("Round started");
    }

    // ------------------------------------------------------------------ input (UDP thread)

    public synchronized void handleInput(Protocol.Input in, InetSocketAddress from) {
        Player p = players.get(in.id());
        if (p == null || p.token != in.token()) return;   // reject spoofed packets
        p.udpAddr = from;                                  // remember where to send snapshots
        if (in.seq() <= p.lastSeq) return;                 // UDP can reorder: ignore stale input
        p.lastSeq = in.seq();
        p.dx = in.dx();
        p.dy = in.dy();
        p.aim = in.aim();
        if (in.shoot()) p.shootLatch = true;
        p.inputAge = 0;
    }

    // ------------------------------------------------------------------ the tick (tick thread)

    public synchronized void tick() {
        tick++;
        switch (phase) {
            case LOBBY -> lobbyTick();
            case ENDED -> {
                endElapsed += DT;
                if (endElapsed >= AGAIN_TIMEOUT_SEC || allWantAgain()) {
                    resetToLobby();
                    broadcastLobby();
                }
            }
            case PLAYING -> {
                playTick();
                if (tick % TICK_RATE == 0) broadcastStats();
            }
        }
    }

    /** The waiting room: walking, team pads and practice shooting. Bullets pass through players here. */
    private void lobbyTick() {
        boolean changed = false;
        for (Player p : players.values()) {
            p.fireCd -= DT;
            p.inputAge += DT;
            move(p, LobbyMap.WALLS);
            int pad = LobbyMap.padAt(p.x, p.y);
            if (pad >= 0 && pad != p.team && teamSize(pad) < mode) {
                p.team = pad;
                changed = true;
            }
        }
        shoot();
        if (moveBullets()) changed = true;
        if (changed) broadcastLobby();
        if (countdown > 0) {
            countdown -= DT;
            if (countdown <= 1e-9) startRound();
        }
    }

    private void playTick() {
        timeLeft = Math.max(0, timeLeft - DT);
        tentCooldown = Math.max(0, tentCooldown - DT);

        for (Player p : players.values()) {
            p.shieldSec = Math.max(0, p.shieldSec - DT);
            p.fireCd -= DT;
            p.inputAge += DT;
            if (!p.alive) {
                p.shootLatch = false;
                p.respawnIn -= DT;
                if (p.respawnIn <= 0) spawn(p);
                continue;
            }
            move(p, MapData.WALLS);
        }
        tent();
        shoot();
        moveBullets();
        updateFlags();
        if (phase == Phase.PLAYING && timeLeft <= 0) endByTimer();
    }

    /** Same formula as the client's prediction: normalised direction, then MapData.resolve. */
    private void move(Player p, double[][] walls) {
        if (p.inputAge > INPUT_TIMEOUT_SEC) { p.dx = 0; p.dy = 0; }
        if (p.dx == 0 && p.dy == 0) return;
        double len = Math.hypot(p.dx, p.dy);
        double[] pos = {p.x + p.dx / len * MapData.SPEED * DT, p.y + p.dy / len * MapData.SPEED * DT};
        MapData.resolve(pos, walls);
        p.x = pos[0];
        p.y = pos[1];
    }

    private void tent() {
        if (tentCooldown > 0) return;
        for (Player p : players.values()) {
            if (p.alive && p.shieldSec <= 0
                    && Math.hypot(p.x - MapData.TENT_X, p.y - MapData.TENT_Y) <= MapData.TENT_R) {
                p.shieldSec = MapData.SHIELD_DURATION;
                tentCooldown = MapData.TENT_RECHARGE;
                return;
            }
        }
    }

    private void shoot() {
        for (Player p : players.values()) {
            if (!p.alive || !p.shootLatch || p.fireCd > 0) continue;
            p.shootLatch = false;
            p.fireCd = MapData.FIRE_INTERVAL;
            bullets.add(new double[]{p.x, p.y, Math.cos(p.aim) * MapData.BULLET_SPEED,
                    Math.sin(p.aim) * MapData.BULLET_SPEED, p.team, p.id});
        }
    }

    /** Returns true if a lobby practice target was hit (the hit counts changed). */
    private boolean moveBullets() {
        boolean lobby = phase == Phase.LOBBY, targetHit = false;
        double[][] walls = lobby ? LobbyMap.WALLS : MapData.WALLS;
        for (Iterator<double[]> it = bullets.iterator(); it.hasNext(); ) {
            double[] b = it.next();
            double ox = b[0], oy = b[1];
            b[0] += b[2] * DT;
            b[1] += b[3] * DT;
            boolean gone = b[0] < 0 || b[0] > MapData.W || b[1] < 0 || b[1] > MapData.H || inWall(walls, b[0], b[1]);
            if (lobby) {   // nobody can be hurt in the lobby: bullets only hit the practice targets
                if (LobbyMap.targetHit(ox, oy, b[0], b[1]) >= 0) {
                    Player shooter = players.get((int) b[5]);
                    if (shooter != null) shooter.targetHits++;
                    gone = targetHit = true;
                }
                if (gone) it.remove();
                continue;
            }
            for (Player p : players.values()) {
                if (!p.alive || p.team == (int) b[4]) continue;
                if (MapData.segHits(ox, oy, b[0], b[1], p.x, p.y, MapData.R)) {
                    if (p.shieldSec <= 0) eliminate(p, (int) b[5]);   // a shielded player absorbs it
                    gone = true;
                    break;
                }
            }
            if (gone) it.remove();
        }
        return targetHit;
    }

    private static boolean inWall(double[][] walls, double x, double y) {
        for (double[] w : walls) {
            if (x > w[0] && x < w[0] + w[2] && y > w[1] && y < w[1] + w[3]) return true;
        }
        return false;
    }

    private void eliminate(Player victim, int shooterId) {
        victim.alive = false;
        victim.respawnIn = RESPAWN_SEC;
        victim.deaths++;
        Player shooter = players.get(shooterId);
        if (shooter != null) shooter.tags++;
        dropFlagCarriedBy(victim);
    }

    private void updateFlags() {
        for (Flag f : flags) {
            switch (f.state) {
                case CARRIED -> {
                    Player c = players.get(f.carrierId);
                    if (c == null || !c.alive) { f.drop(f.x, f.y); break; }
                    f.x = c.x;
                    f.y = c.y;
                    double[] home = MapData.BASES[c.team];
                    // capture: carrier is in their own base AND their own flag is at home
                    if (Math.hypot(c.x - home[0], c.y - home[1]) < home[2]
                            && flags[c.team].state == Flag.State.AT_BASE) {
                        capture(c, f);
                    }
                }
                case DROPPED -> {
                    f.droppedFor += DT;
                    if (f.droppedFor >= FLAG_RETURN_SEC) f.returnToBase();
                    else touch(f);
                }
                case AT_BASE -> touch(f);
            }
            if (phase != Phase.PLAYING) return;   // a capture may have ended the round
        }
    }

    /** An enemy touching a flag takes it; a teammate touching a dropped flag returns it. */
    private void touch(Flag f) {
        for (Player p : players.values()) {
            if (!p.alive || Math.hypot(p.x - f.x, p.y - f.y) >= FLAG_TOUCH_R) continue;
            if (p.team != f.team && !isCarrier(p)) {
                f.pickUp(p);
                return;
            }
            if (p.team == f.team && f.state == Flag.State.DROPPED) {
                f.returnToBase();
                return;
            }
        }
    }

    private void capture(Player c, Flag taken) {
        score[c.team]++;
        c.captures++;
        taken.returnToBase();
        log(c.name + " captured the " + TEAM_NAMES[taken.team] + " flag");
        if (score[c.team] >= CAPTURES_TO_WIN) endRound(c.team);
    }

    private void endByTimer() {
        int w;
        if (score[0] != score[1]) w = score[0] > score[1] ? 0 : 1;
        else {                                  // tie: more tags wins, then a coin flip
            int[] tags = new int[2];
            for (Player p : players.values()) tags[p.team] += p.tags;
            w = tags[0] != tags[1] ? (tags[0] > tags[1] ? 0 : 1) : rng.nextInt(2);
        }
        endRound(w);
    }

    private void endRound(int winningTeam) {
        phase = Phase.ENDED;
        winner = winningTeam;
        endElapsed = 0;
        bullets.clear();
        for (Player p : players.values()) { p.dx = p.dy = 0; p.shootLatch = false; p.wantsAgain = false; p.ready = false; }
        broadcast("GAMEOVER " + winner + " " + score[0] + " " + score[1]);
        broadcastStats();
        broadcastLobby();   // everyone shows as not voted yet
        log(TEAM_NAMES[winner] + " wins " + score[0] + "-" + score[1]);
    }

    private void resetToLobby() {
        phase = Phase.LOBBY;
        winner = -1;
        countdown = 0;
        timeLeft = ROUND_SEC;
        bullets.clear();
        for (Flag f : flags) f.returnToBase();
        for (Player p : players.values()) {
            p.ready = false;
            p.alive = true;
            p.dx = p.dy = 0;
            p.shieldSec = 0;
            p.targetHits = 0;
            p.shootLatch = false;
            placeInLobby(p);
        }
        log("Back to the lobby");
    }

    private void placeInLobby(Player p) {
        double[] at = LobbyMap.spawn(p.id);
        p.x = at[0];
        p.y = at[1];
    }

    private boolean allWantAgain() {
        if (players.isEmpty()) return false;
        for (Player p : players.values()) if (!p.wantsAgain) return false;
        return true;
    }

    private void spawn(Player p) {
        p.x = MapData.BASES[p.team][0];
        p.y = MapData.BASES[p.team][1];
        p.alive = true;
        p.respawnIn = 0;
    }

    private void dropFlagCarriedBy(Player p) {
        for (Flag f : flags) {
            if (f.state == Flag.State.CARRIED && f.carrierId == p.id) f.drop(p.x, p.y);
        }
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

    // ------------------------------------------------------------------ output

    /** The world as one UDP line, built from the client's own Snapshot class. */
    public synchronized String snapshotLine() {
        Snapshot s = new Snapshot();
        s.tick = tick;
        s.timeLeftSec = (int) timeLeft;
        s.score = score.clone();
        s.winner = winner;
        s.tentCooldown = tentCooldown;
        s.matchEndElapsedSec = phase == Phase.ENDED ? endElapsed : 0;
        s.phase = phase.ordinal();
        s.countdown = countdown;
        for (int i = 0; i < 2; i++) {
            Snapshot.F f = s.flags[i];
            f.state = flags[i].state.ordinal();
            f.x = flags[i].x;
            f.y = flags[i].y;
            f.carrier = flags[i].carrierId;
        }
        s.players = new Snapshot.P[players.size()];
        int i = 0;
        for (Player p : players.values()) {
            Snapshot.P q = new Snapshot.P();
            q.id = p.id;
            q.team = p.team;
            q.x = p.x;
            q.y = p.y;
            q.aim = p.aim;
            q.alive = p.alive;
            q.shieldSec = p.shieldSec;
            s.players[i++] = q;
        }
        s.bullets = new double[bullets.size()][];
        for (int k = 0; k < s.bullets.length; k++) {
            double[] b = bullets.get(k);
            s.bullets[k] = new double[]{b[0], b[1], b[4]};
        }
        return Protocol.encodeSnapshot(s);
    }

    public synchronized List<InetSocketAddress> udpTargets() {
        List<InetSocketAddress> out = new ArrayList<>();
        for (Player p : players.values()) if (p.udpAddr != null) out.add(p.udpAddr);
        return out;
    }

    private void broadcastLobby() {
        List<Protocol.LobbyEntry> list = new ArrayList<>();
        for (Player p : players.values())
            list.add(new Protocol.LobbyEntry(p.id, p.team, p.ready, p.hat, p.hatColor, p.targetHits, p.level, p.title, p.name));
        broadcast(Protocol.encodeLobby(phase.name(), minPlayers, hostId(), mode, list));
    }

    private void broadcastStats() {
        List<Protocol.Stat> list = new ArrayList<>();
        for (Player p : players.values()) {
            list.add(new Protocol.Stat(p.id, p.tags, p.captures, p.deaths, p.score(), p.pingMs));
        }
        broadcast(Protocol.encodeStats(list));
    }

    private void broadcast(String line) {
        for (Player p : players.values()) p.conn.send(line);
    }

    private void log(String text) {
        System.out.println("[game] " + text);
    }

    // ------------------------------------------------------------------ read access for tests

    synchronized Phase phase() { return phase; }

    synchronized Player player(int id) { return players.get(id); }

    synchronized Flag flag(int team) { return flags[team]; }

    synchronized int score(int team) { return score[team]; }

    synchronized int winner() { return winner; }

    synchronized int bulletCount() { return bullets.size(); }

    synchronized double tentCooldown() { return tentCooldown; }

    synchronized double countdown() { return countdown; }

    synchronized int mode() { return mode; }

    synchronized void setTimeLeft(double seconds) { timeLeft = seconds; }
}

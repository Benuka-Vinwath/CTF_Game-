package ctf.server;

import ctf.common.Protocol;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Many matches on one server. Each room is one GameState with a short code (K7QX) that friends type to join.
 *  QUICK          any public room with a free slot (the fullest first), or a new public room
 *  CREATE:m:pub   a new room of size m (1 = 1v1 ... 4 = 4v4), public (listed) or private (code only)
 *  JOIN:code      that room, if it exists and is not full
 * A room is deleted when its last player leaves.
 *
 * Locking: join and leave hold this manager's lock and then take a room's lock, never the other way round.
 * Nothing that holds a room's lock calls back in here (ClientHandler leaves from its own threads), so no deadlock.
 * UDP input skips this lock entirely: byPlayer is a concurrent map.
 */
public class RoomManager {
    public static final int MAX_ROOMS = 100;
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no O/0 or I/1 to mix up
    static final int CODE_LEN = 4;

    public static final class Room {
        public final String code;
        public final boolean isPublic;
        public final GameState game;

        Room(String code, boolean isPublic, GameState game) {
            this.code = code;
            this.isPublic = isPublic;
            this.game = game;
        }
    }

    /** The outcome of a join: the room and the new player, or the reason it was refused. */
    public record Joined(Room room, Player player, String error) {}

    private final int minPlayers;
    private final int udpPort;
    private final Random rng;
    private final AtomicInteger ids = new AtomicInteger();   // player ids, unique across all rooms
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<Integer, Room> byPlayer = new ConcurrentHashMap<>();

    public RoomManager(int minPlayers, int udpPort) {
        this(minPlayers, udpPort, new SecureRandom());   // unguessable private codes and UDP tokens
    }

    public RoomManager(int minPlayers, int udpPort, Random rng) {
        this.minPlayers = minPlayers;
        this.udpPort = udpPort;
        this.rng = rng;
    }

    // shortcut: rooms are only limited per connection (one HELLO) and by MAX_ROOMS; add a per-IP limit if spam appears
    public synchronized Joined join(String request, String name, int team, int hat, int color, int level, int title, Conn conn) {
        String[] r = (request == null || request.isBlank() ? Protocol.QUICK : request.trim()).split(":");
        Room room;
        switch (r[0].toUpperCase()) {
            case "QUICK" -> {
                room = quickRoom();
                if (room == null) room = newRoom(defaultMode(), true);
                if (room == null) return refused("server full, try again soon");
            }
            case "CREATE" -> {
                int mode = r.length > 1 ? parseInt(r[1], defaultMode()) : defaultMode();
                room = newRoom(mode, r.length > 2 && r[2].equalsIgnoreCase("public"));
                if (room == null) return refused("server busy, too many rooms");
            }
            case "JOIN" -> {
                room = r.length > 1 ? rooms.get(r[1].trim().toUpperCase()) : null;
                if (room == null) return refused("room not found");
                if (!room.game.hasRoom()) return refused("room full");
            }
            default -> { return refused("unknown room request " + r[0]); }
        }
        conn.send("ROOM " + room.code + " " + (room.isPublic ? "public" : "private"));   // before WELCOME, so the lobby can show the code
        Player p = room.game.addPlayer(name, team, hat, color, level, title, conn);
        if (p == null) {   // not expected: every join goes through this lock, and the room had space
            dropIfEmpty(room);
            return refused("room full");
        }
        byPlayer.put(p.id, room);
        return new Joined(room, p, null);
    }

    public synchronized void leave(int playerId) {
        Room room = byPlayer.remove(playerId);
        if (room == null) return;
        room.game.removePlayer(playerId);
        dropIfEmpty(room);
    }

    /** UDP thread: hand the packet to the sender's room. The room still checks the token, so another room's token fails. */
    public void handleInput(Protocol.Input in, InetSocketAddress from) {
        Room room = byPlayer.get(in.id());
        if (room != null) room.game.handleInput(in, from);
    }

    /** Tick thread: advance every room and send each room's snapshot to that room's players only. */
    public void tickAll(BiConsumer<String, InetSocketAddress> send) {
        for (Room room : all()) {
            try {
                room.game.tick();
                String snapshot = room.game.snapshotLine();
                for (InetSocketAddress addr : room.game.udpTargets()) send.accept(snapshot, addr);
            } catch (Exception e) {
                e.printStackTrace();   // one broken room must not stop the others
            }
        }
    }

    /** The public rooms, for the ROOMS list. */
    public synchronized List<Protocol.RoomInfo> listPublic() {
        List<Protocol.RoomInfo> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            if (r.isPublic) out.add(new Protocol.RoomInfo(r.code, r.game.mode(), r.game.playerCount(), r.game.phase().name()));
        }
        return out;
    }

    public synchronized List<Room> all() { return new ArrayList<>(rooms.values()); }

    public synchronized Room find(String code) { return code == null ? null : rooms.get(code.trim().toUpperCase()); }

    // ------------------------------------------------------------------ inside the lock

    private Room quickRoom() {
        Room best = null;
        for (Room r : rooms.values()) {
            if (r.isPublic && r.game.hasRoom() && (best == null || r.game.playerCount() > best.game.playerCount())) best = r;
        }
        return best;
    }

    private Room newRoom(int mode, boolean isPublic) {
        if (rooms.size() >= MAX_ROOMS) return null;
        String code;
        do code = newCode(); while (rooms.containsKey(code));
        Room room = new Room(code, isPublic, new GameState(minPlayers, mode, udpPort, rng, ids::incrementAndGet));
        rooms.put(code, room);
        System.out.println("[rooms] " + code + " opened (" + room.game.mode() + "v" + room.game.mode() + ", "
                + (isPublic ? "public" : "private") + "), " + rooms.size() + " open");
        return room;
    }

    private void dropIfEmpty(Room room) {
        if (room.game.playerCount() == 0 && rooms.remove(room.code) != null)
            System.out.println("[rooms] " + room.code + " closed, " + rooms.size() + " open");
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private int defaultMode() { return Math.max(1, (minPlayers + 1) / 2); }

    private static Joined refused(String why) { return new Joined(null, null, why); }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}

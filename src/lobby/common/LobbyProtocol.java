package lobby.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wire format for the visual lobby (TCP, newline-terminated, UTF-8).
 *
 * Client → Server:
 *   HELLO <name>              – join request
 *   MOVE <x> <y>              – position update (~20/sec)
 *   CHAT <text>               – chat message
 *   READY                     – player is ready to start
 *   UNREADY                   – player changed mind
 *   BYE                       – graceful disconnect
 *
 * Server → Client:
 *   WELCOME <id> <minPlayers> – your id + how many needed to start
 *   STATE <n> {id team x y ready name}... – full snapshot on join
 *   JOIN <id> <team> <name>   – player joined
 *   LEAVE <id>                – player left
 *   MOVE <id> <x> <y>         – player moved
 *   READY_UPDATE <id> <0|1>   – a player's ready status changed
 *   READY_STATUS <ready> <total> – count summary after any change
 *   COUNTDOWN <seconds>       – game starting in N seconds (0 = launching now)
 *   LAUNCH <host> <gamePort>  – connect to the CTF game server NOW
 *   CHAT <id> <name>: <text>  – chat broadcast
 *   ERROR <reason>
 *
 * No SHOOT command exists – the lobby forbids combat.
 */
public final class LobbyProtocol {

    private LobbyProtocol() {}

    // ── player state record ──────────────────────────────────────────────────

    public record PlayerState(int id, int team, double x, double y, boolean ready, String name) {}

    // ── encode: server → client ──────────────────────────────────────────────

    public static String encodeWelcome(int id, int minPlayers) {
        return "WELCOME " + id + " " + minPlayers;
    }

    public static String encodeState(List<PlayerState> players) {
        StringBuilder sb = new StringBuilder("STATE ").append(players.size());
        for (PlayerState p : players) {
            sb.append(' ').append(p.id())
              .append(' ').append(p.team())
              .append(' ').append(fmt(p.x()))
              .append(' ').append(fmt(p.y()))
              .append(' ').append(p.ready() ? 1 : 0)
              .append(' ').append(sanitize(p.name()));
        }
        return sb.toString();
    }

    public static String encodeJoin(int id, int team, String name) {
        return "JOIN " + id + " " + team + " " + sanitize(name);
    }

    public static String encodeLeave(int id) {
        return "LEAVE " + id;
    }

    public static String encodeMove(int id, double x, double y) {
        return "MOVE " + id + " " + fmt(x) + " " + fmt(y);
    }

    public static String encodeReadyUpdate(int id, boolean ready) {
        return "READY_UPDATE " + id + " " + (ready ? 1 : 0);
    }

    public static String encodeReadyStatus(int readyCount, int total) {
        return "READY_STATUS " + readyCount + " " + total;
    }

    public static String encodeCountdown(int seconds) {
        return "COUNTDOWN " + seconds;
    }

    public static String encodeLaunch(String host, int gamePort) {
        return "LAUNCH " + host + " " + gamePort;
    }

    public static String encodeChat(int id, String name, String text) {
        return "CHAT " + id + " " + sanitize(name) + ": " + sanitizeChatText(text);
    }

    public static String encodeError(String reason) {
        return "ERROR " + reason;
    }

    // ── decode helpers ───────────────────────────────────────────────────────

    /** Parse a STATE line. Returns empty list on error. */
    public static List<PlayerState> decodeState(String line) {
        List<PlayerState> out = new ArrayList<>();
        try {
            String[] t = line.trim().split(" ");
            if (!t[0].equals("STATE")) return out;
            int n = Integer.parseInt(t[1]);
            int i = 2;
            for (int k = 0; k < n; k++) {
                int     id    = Integer.parseInt(t[i++]);
                int     team  = Integer.parseInt(t[i++]);
                double  x     = Double.parseDouble(t[i++]);
                double  y     = Double.parseDouble(t[i++]);
                boolean ready = t[i++].equals("1");
                String  name  = t[i++];
                out.add(new PlayerState(id, team, x, y, ready, name));
            }
        } catch (Exception e) { out.clear(); }
        return out;
    }

    /** "WELCOME id minPlayers" → {id, minPlayers} or null. */
    public static int[] decodeWelcome(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length < 3) return null;
            return new int[]{ Integer.parseInt(t[1]), Integer.parseInt(t[2]) };
        } catch (Exception e) { return null; }
    }

    /** "JOIN id team name" → {id, team} or null. */
    public static int[] decodeJoin(String line) {
        try {
            String[] t = line.trim().split(" ", 4);
            if (t.length < 3) return null;
            return new int[]{ Integer.parseInt(t[1]), Integer.parseInt(t[2]) };
        } catch (Exception e) { return null; }
    }

    public static String decodeJoinName(String line) {
        String[] t = line.trim().split(" ", 4);
        return t.length >= 4 ? t[3] : (t.length == 3 ? "Player" : null);
    }

    /** "MOVE id x y" → {id, x, y} or null. */
    public static double[] decodeMoveAll(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length < 4) return null;
            return new double[]{ Integer.parseInt(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]) };
        } catch (Exception e) { return null; }
    }

    /** "READY_UPDATE id 0|1" → {id, ready} or null. */
    public static int[] decodeReadyUpdate(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length < 3) return null;
            return new int[]{ Integer.parseInt(t[1]), Integer.parseInt(t[2]) };
        } catch (Exception e) { return null; }
    }

    /** "READY_STATUS ready total" → {ready, total} or null. */
    public static int[] decodeReadyStatus(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length < 3) return null;
            return new int[]{ Integer.parseInt(t[1]), Integer.parseInt(t[2]) };
        } catch (Exception e) { return null; }
    }

    /** "LAUNCH host port" → {"host", port-str} or null. */
    public static String[] decodeLaunch(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length < 3) return null;
            return new String[]{ t[1], t[2] };
        } catch (Exception e) { return null; }
    }

    /** "COUNTDOWN n" → n or -1. */
    public static int decodeCountdown(String line) {
        try { return Integer.parseInt(line.trim().split(" ")[1]); }
        catch (Exception e) { return -1; }
    }

    // ── sanitisation ─────────────────────────────────────────────────────────

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "Player";
        String s = raw.trim().replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "Player" : (s.length() > 12 ? s.substring(0, 12) : s);
    }

    public static String sanitizeChatText(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}

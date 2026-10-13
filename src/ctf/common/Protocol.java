package ctf.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * The wire format, in one place, used by both the server and the client so they can never disagree.
 * Everything is one line of text with fields separated by single spaces.
 *
 * UDP, client to server (30 per second):
 *   I id token seq dx dy aim shoot
 * UDP, server to client (30 per second):
 *   S tick timeLeftSec redScore blueScore winner tentCooldown matchEndSec
 *     flagState x y carrier   (red flag)   flagState x y carrier   (blue flag)
 *     nPlayers  { id team x y aim alive shieldSec }...
 *     nBullets  { x y team }...
 * TCP lines (lobby and slow data) are described in docs/PROTOCOL.md; LOBBY and STATS have codecs here.
 *
 * Every decode method returns null (or an empty list) for malformed input instead of throwing,
 * because anything arriving from the network may be garbage.
 */
public final class Protocol {
    private Protocol() {}

    public record Input(int id, int token, int seq, int dx, int dy, double aim, boolean shoot) {}

    public record Stat(int id, int tags, int captures, int deaths, int score, int ping) {}

    public record LobbyEntry(int id, int team, boolean ready, String name) {}

    public record Lobby(String phase, int minPlayers, List<LobbyEntry> entries) {}

    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static String f2(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    /** Letters, digits and underscore only, at most 12 characters; safe inside the space and colon separated lines. */
    public static String cleanName(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (n.isEmpty()) n = "Player";
        return n.length() > 12 ? n.substring(0, 12) : n;
    }

    // ------------------------------------------------------------------ UDP: input

    public static String encodeInput(Input in) {
        return "I " + in.id() + " " + in.token() + " " + in.seq() + " " + in.dx() + " " + in.dy()
                + " " + f2(in.aim()) + " " + (in.shoot() ? 1 : 0);
    }

    /** dx and dy are clamped to -1, 0, 1 so a modified client cannot move faster. */
    public static Input decodeInput(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length != 8 || !t[0].equals("I")) return null;
            double aim = Double.parseDouble(t[6]);
            if (Double.isNaN(aim) || Double.isInfinite(aim)) return null;
            return new Input(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]),
                    Integer.signum(Integer.parseInt(t[4])), Integer.signum(Integer.parseInt(t[5])),
                    aim, t[7].equals("1"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ UDP: snapshot

    public static String encodeSnapshot(Snapshot s) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("S ").append(s.tick).append(' ').append(s.timeLeftSec)
          .append(' ').append(s.score[0]).append(' ').append(s.score[1])
          .append(' ').append(s.winner).append(' ').append(f1(s.tentCooldown))
          .append(' ').append(f1(s.matchEndElapsedSec));
        for (Snapshot.F f : s.flags) {
            sb.append(' ').append(f.state).append(' ').append(f1(f.x)).append(' ').append(f1(f.y))
              .append(' ').append(f.carrier);
        }
        sb.append(' ').append(s.players.length);
        for (Snapshot.P p : s.players) {
            sb.append(' ').append(p.id).append(' ').append(p.team).append(' ').append(f1(p.x)).append(' ')
              .append(f1(p.y)).append(' ').append(f2(p.aim)).append(' ').append(p.alive ? 1 : 0)
              .append(' ').append(f2(p.shieldSec));
        }
        sb.append(' ').append(s.bullets.length);
        for (double[] b : s.bullets) {
            sb.append(' ').append(f1(b[0])).append(' ').append(f1(b[1])).append(' ').append((int) b[2]);
        }
        return sb.toString();
    }

    /** Returns null if the line is malformed or truncated. Names and statistics are filled in later from LOBBY and STATS. */
    public static Snapshot decodeSnapshot(String line) {
        try {
            String[] t = line.trim().split(" ");
            if (t.length < 18 || !t[0].equals("S")) return null;
            int i = 1;
            Snapshot s = new Snapshot();
            s.tick = Long.parseLong(t[i++]);
            s.timeLeftSec = Integer.parseInt(t[i++]);
            s.score[0] = Integer.parseInt(t[i++]);
            s.score[1] = Integer.parseInt(t[i++]);
            s.winner = Integer.parseInt(t[i++]);
            s.tentCooldown = Double.parseDouble(t[i++]);
            s.matchEndElapsedSec = Double.parseDouble(t[i++]);
            for (int k = 0; k < 2; k++) {
                Snapshot.F f = s.flags[k];
                f.state = Integer.parseInt(t[i++]);
                f.x = Double.parseDouble(t[i++]);
                f.y = Double.parseDouble(t[i++]);
                f.carrier = Integer.parseInt(t[i++]);
            }
            int n = Integer.parseInt(t[i++]);
            if (n < 0 || n > 64) return null;
            s.players = new Snapshot.P[n];
            for (int k = 0; k < n; k++) {
                Snapshot.P p = new Snapshot.P();
                p.id = Integer.parseInt(t[i++]);
                p.team = Integer.parseInt(t[i++]);
                p.x = Double.parseDouble(t[i++]);
                p.y = Double.parseDouble(t[i++]);
                p.aim = Double.parseDouble(t[i++]);
                p.alive = t[i++].equals("1");
                p.shieldSec = Double.parseDouble(t[i++]);
                s.players[k] = p;
            }
            int nb = Integer.parseInt(t[i++]);
            if (nb < 0 || nb > 512) return null;
            s.bullets = new double[nb][];
            for (int k = 0; k < nb; k++) {
                s.bullets[k] = new double[]{Double.parseDouble(t[i]), Double.parseDouble(t[i + 1]),
                        Integer.parseInt(t[i + 2])};
                i += 3;
            }
            return s;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ TCP: STATS and LOBBY

    /** STATS id:tags:captures:deaths:score:ping,id:...   Sent about once a second; too slow-changing for UDP. */
    public static String encodeStats(List<Stat> stats) {
        StringJoiner sj = new StringJoiner(",");
        for (Stat s : stats) {
            sj.add(s.id() + ":" + s.tags() + ":" + s.captures() + ":" + s.deaths() + ":" + s.score() + ":" + s.ping());
        }
        return "STATS " + sj;
    }

    public static List<Stat> decodeStats(String line) {
        List<Stat> out = new ArrayList<>();
        String rest = line.length() > 6 ? line.substring(6).trim() : "";
        if (rest.isEmpty()) return out;
        for (String e : rest.split(",")) {
            String[] p = e.split(":");
            if (p.length < 6) continue;
            try {
                out.add(new Stat(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])));
            } catch (NumberFormatException ignored) { /* skip a malformed entry */ }
        }
        return out;
    }

    /** LOBBY phase minPlayers id:team:ready:name,id:team:ready:name,... */
    public static String encodeLobby(String phase, int minPlayers, List<LobbyEntry> entries) {
        StringJoiner sj = new StringJoiner(",");
        for (LobbyEntry e : entries) {
            sj.add(e.id() + ":" + e.team() + ":" + (e.ready() ? 1 : 0) + ":" + e.name());
        }
        return "LOBBY " + phase + " " + minPlayers + " " + sj;
    }

    public static Lobby decodeLobby(String line) {
        String[] t = line.trim().split(" ", 4);
        if (t.length < 3 || !t[0].equals("LOBBY")) return null;
        List<LobbyEntry> entries = new ArrayList<>();
        try {
            int min = Integer.parseInt(t[2]);
            if (t.length == 4 && !t[3].isEmpty()) {
                for (String e : t[3].split(",")) {
                    String[] p = e.split(":", 4);
                    if (p.length < 4) continue;
                    entries.add(new LobbyEntry(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2].equals("1"), p[3]));
                }
            }
            return new Lobby(t[1], min, entries);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package ctf.server;

import ctf.common.Progress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The online profile database (PostgreSQL on Supabase). Only the server talks to it; players never see the password.
 * Turned on by environment variables DB_URL, DB_USER, DB_PASSWORD (on the cloud server: /etc/thronefall.env).
 * Without DB_URL (SOLO, LAN, tests) it is off and nothing is saved online.
 * Players are stored by a SHA-256 hash of their secret key, never the key itself. All calls share one connection;
 * if the database is down, joins carry on with level 1 and it is tried again a minute later.
 */
final class Db {
    private static final String URL = System.getenv("DB_URL");
    static final boolean ON = URL != null && !URL.isBlank();
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-save");
        t.setDaemon(true);
        return t;
    });
    private static Connection conn;
    private static long downUntil;   // after a failure, skip the database until then so joins do not wait on timeouts

    private Db() { }

    /** One player's line in a finished match. key is the hashed key (null for bots and players without one). */
    record Row(String key, String name, int team, int captures, int tags, int deaths, int score, boolean mvp, boolean won) { }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java has SHA-256
        }
    }

    /** A key from HELLO is worth saving: a UUID-like token, not junk. */
    static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9-]{20,64}"); }

    private static Connection conn() throws SQLException {
        if (System.currentTimeMillis() < downUntil) throw new SQLException("database marked down");
        try {
            if (conn != null && conn.isValid(2)) return conn;
            if (conn != null) try { conn.close(); } catch (SQLException ignored) { }
            Properties p = new Properties();
            p.setProperty("user", env("DB_USER"));
            p.setProperty("password", env("DB_PASSWORD"));
            p.setProperty("connectTimeout", "5");
            p.setProperty("socketTimeout", "10");
            conn = DriverManager.getConnection(URL, p);
            createTables(conn);
            System.out.println("[db] connected");
            return conn;
        } catch (SQLException e) {
            conn = null;
            downUntil = System.currentTimeMillis() + 60_000;
            System.err.println("[db] unavailable for a minute: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name) { String v = System.getenv(name); return v == null ? "" : v; }

    /** Safe to run on every connect. Row Level Security with no policies hides the tables from Supabase's public web API. */
    private static void createTables(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS players (key_hash TEXT PRIMARY KEY, name TEXT NOT NULL,"
                + " xp INT NOT NULL DEFAULT 0, matches INT NOT NULL DEFAULT 0, wins INT NOT NULL DEFAULT 0,"
                + " captures INT NOT NULL DEFAULT 0, tags INT NOT NULL DEFAULT 0, deaths INT NOT NULL DEFAULT 0, mvps INT NOT NULL DEFAULT 0,"
                + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_seen TIMESTAMPTZ NOT NULL DEFAULT now())");
            s.execute("CREATE TABLE IF NOT EXISTS matches (id BIGSERIAL PRIMARY KEY, played_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                + " mode INT, winner INT, red_score INT, blue_score INT, length_sec INT)");
            s.execute("CREATE TABLE IF NOT EXISTS match_players (match_id BIGINT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,"
                + " key_hash TEXT REFERENCES players(key_hash), name TEXT, team INT, captures INT, tags INT, deaths INT,"
                + " score INT, was_mvp BOOLEAN, won BOOLEAN, xp_gained INT)");
            for (String t : new String[]{"players", "matches", "match_players"})
                s.execute("ALTER TABLE " + t + " ENABLE ROW LEVEL SECURITY");
        }
    }

    /** On join: creates or renames the player and returns their level from the online XP (1 if the database is off or down). */
    static synchronized int join(String keyHash, String name) {
        if (!ON || keyHash == null) return 1;
        try (PreparedStatement p = conn().prepareStatement(
                "INSERT INTO players (key_hash, name) VALUES (?, ?) ON CONFLICT (key_hash)"
                + " DO UPDATE SET name = EXCLUDED.name, last_seen = now() RETURNING xp")) {
            p.setString(1, keyHash);
            p.setString(2, name);
            try (ResultSet r = p.executeQuery()) { return r.next() ? Progress.levelOf(r.getInt(1))[0] : 1; }
        } catch (SQLException e) {
            System.err.println("[db] join failed: " + e.getMessage());
            return 1;
        }
    }

    /** Called inside the room's lock at the end of a match: queues the save so the game never waits on the network. */
    static void saveMatch(int mode, int winner, int red, int blue, int lengthSec, List<Row> rows) {
        if (!ON || rows.stream().noneMatch(r -> r.key() != null)) return;   // nobody to save (bots only)
        SAVER.submit(() -> save(mode, winner, red, blue, lengthSec, rows));
    }

    private static synchronized void save(int mode, int winner, int red, int blue, int lengthSec, List<Row> rows) {
        Connection c = null;
        try {
            c = conn();
            c.setAutoCommit(false);
            long matchId;
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO matches (mode, winner, red_score, blue_score, length_sec) VALUES (?,?,?,?,?) RETURNING id")) {
                p.setInt(1, mode); p.setInt(2, winner); p.setInt(3, red); p.setInt(4, blue); p.setInt(5, lengthSec);
                try (ResultSet r = p.executeQuery()) { r.next(); matchId = r.getLong(1); }
            }
            try (PreparedStatement row = c.prepareStatement("INSERT INTO match_players (match_id, key_hash, name, team, captures, tags,"
                     + " deaths, score, was_mvp, won, xp_gained) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement pl = c.prepareStatement("INSERT INTO players (key_hash, name, xp, matches, wins, captures, tags, deaths, mvps)"
                     + " VALUES (?,?,?,1,?,?,?,?,?) ON CONFLICT (key_hash) DO UPDATE SET name = EXCLUDED.name, xp = players.xp + EXCLUDED.xp,"
                     + " matches = players.matches + 1, wins = players.wins + EXCLUDED.wins, captures = players.captures + EXCLUDED.captures,"
                     + " tags = players.tags + EXCLUDED.tags, deaths = players.deaths + EXCLUDED.deaths, mvps = players.mvps + EXCLUDED.mvps,"
                     + " last_seen = now()")) {   // an upsert: a player who joined while the database was down still gets a row
                for (Row r : rows) {
                    int xp = r.key() == null ? 0 : Progress.xpFor(r.won(), r.captures(), r.tags(), r.mvp());
                    row.setLong(1, matchId); row.setString(2, r.key()); row.setString(3, r.name()); row.setInt(4, r.team());
                    row.setInt(5, r.captures()); row.setInt(6, r.tags()); row.setInt(7, r.deaths()); row.setInt(8, r.score());
                    row.setBoolean(9, r.mvp()); row.setBoolean(10, r.won()); row.setInt(11, xp);
                    row.addBatch();
                    if (r.key() == null) continue;
                    pl.setString(1, r.key()); pl.setString(2, r.name()); pl.setInt(3, xp); pl.setInt(4, r.won() ? 1 : 0);
                    pl.setInt(5, r.captures()); pl.setInt(6, r.tags()); pl.setInt(7, r.deaths()); pl.setInt(8, r.mvp() ? 1 : 0);
                    pl.addBatch();
                }
                pl.executeBatch();    // players first: match_players points at them
                row.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            System.err.println("[db] saving a match failed: " + e.getMessage());
            if (c != null) try { c.rollback(); } catch (SQLException ignored) { }
        } finally {
            if (c != null) try { c.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }
}

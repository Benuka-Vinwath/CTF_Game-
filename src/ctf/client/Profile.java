package ctf.client;

import ctf.common.Snapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.prefs.Preferences;

/**
 * The player's profile, kept in a SQLite database file in the user's folder (docs/profile-design.html):
 *   players        who you are: name, hat, title, favourite team, XP
 *   matches        one row per finished match
 *   match_players  every player's stats in that match (player_id is set only for you)
 *   achievements   what you unlocked, and when
 * Lifetime numbers (wins, K/D, MVPs) are always calculated from match_players, never stored twice.
 * If the SQLite library (lib/sqlite-jdbc-*.jar) is missing, the profile still works for name and looks
 * (kept in java.util.prefs, as before) but keeps no history: ok() is false.
 */
final class Profile {
    static final String[] TITLES = {"RECRUIT", "SQUIRE", "KNIGHT", "MARKSMAN", "CHAMPION", "THRONE KEEPER", "VETERAN", "WARLORD", "LEGEND"};
    static final String[] HAT_LOCK = {"REMOVED", null, "REMOVED", null, null, null};   // index = hat; null = free for everyone; "REMOVED" is never available: the picker offers only Crown, Horns, Halo and Wings, and the slots are kept so saved hat numbers still match

    /** id, what it says, what it unlocks. */
    static final String[][] ACHIEVEMENTS = {
        {"FIRST_WIN", "FIRST WIN", "title: Squire"}, {"CAPTURES_10", "10 CAPTURES", "badge"}, {"TAGS_50", "50 TAGS", "title: Marksman"},
        {"MVP_5", "5 \u00d7 MVP", "title: Throne Keeper"}, {"SHUTOUT", "3-0 WIN", "badge"}, {"STREAK_5", "5 WIN STREAK", "badge"},
        {"MATCHES_100", "100 MATCHES", "title: Veteran"}, {"MVP_4V4", "4v4 MVP", "title: Warlord"}, {"LEVEL_10", "LEVEL 10", "title: Legend"}};
    private static final String[] TITLE_LOCK = {null, "FIRST_WIN", "LEVEL_3", "TAGS_50", "LEVEL_5", "MVP_5", "MATCHES_100", "MVP_4V4", "LEVEL_10"};

    /** Everything the Profile page shows, read in one go. */
    static final class Stats {
        int matches, wins, captures, tags, deaths, mvps, bestStreak, level = 1, xp, xpInto, xpNeed = need(1);
        final List<Object[]> history = new ArrayList<>();   // {won, "RED 3 - 1", mode, lengthSec, captures, tags, deaths, mvp}
        final Set<String> unlocked = new HashSet<>();
    }

    private final Preferences prefs;   // the old settings: name and looks are mirrored there in case the database is missing
    private Connection db;   // null when SQLite is unavailable
    private String id;
    private String name;
    private int hat, hatColor, title, team = -1, xp;

    Profile(Path file, Preferences prefs) {
        this.prefs = prefs;
        name = prefs.get("name", System.getProperty("user.name", "Knight"));
        hat = prefs.getInt("hat", 1);
        hatColor = prefs.getInt("hatColor", 0);
        try {
            Files.createDirectories(file.getParent());
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("CREATE TABLE IF NOT EXISTS players (id TEXT PRIMARY KEY, name TEXT NOT NULL, hat INTEGER, hat_color INTEGER,"
                    + " title INTEGER, favourite_team INTEGER, xp INTEGER NOT NULL DEFAULT 0, created_at TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS matches (id INTEGER PRIMARY KEY AUTOINCREMENT, played_at TEXT, mode INTEGER,"
                    + " winner INTEGER, red_score INTEGER, blue_score INTEGER, length_sec INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS match_players (match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,"
                    + " player_id TEXT REFERENCES players(id), name TEXT, team INTEGER, captures INTEGER, tags INTEGER, deaths INTEGER,"
                    + " score INTEGER, was_mvp INTEGER, won INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS achievements (player_id TEXT NOT NULL REFERENCES players(id), achievement TEXT NOT NULL,"
                    + " unlocked_at TEXT, PRIMARY KEY (player_id, achievement))");
            }
            id = firstPlayer();   // one profile per file: the first player row is you
            if (id == null || !loadPlayer()) {   // first launch: make the player from the old settings
                id = UUID.randomUUID().toString();
                try (PreparedStatement p = db.prepareStatement("INSERT INTO players (id, name, hat, hat_color, title, favourite_team, xp, created_at) VALUES (?,?,?,?,0,-1,0,?)")) {
                    p.setString(1, id); p.setString(2, name); p.setInt(3, hat); p.setInt(4, hatColor); p.setString(5, now());
                    p.executeUpdate();
                }
            }
            if (!hatUnlocked(hat)) save(name, 1, hatColor, title, team);   // a hat picked before hats had to be earned goes back to the crown
        } catch (Exception | LinkageError e) {   // no driver, locked or broken file: play on without history
            System.err.println("Profile database unavailable: " + e);
            db = null;
        }
    }

    /** The profile in the user's folder: C:\Users\<you>\ThroneFall\profile.db */
    static Profile open() {
        return new Profile(Path.of(System.getProperty("user.home"), "ThroneFall", "profile.db"), Preferences.userNodeForPackage(Profile.class));
    }

    private String firstPlayer() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM players ORDER BY created_at LIMIT 1")) {
            return r.next() ? r.getString(1) : null;
        }
    }

    boolean ok() { return db != null; }

    private boolean loadPlayer() throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT name, hat, hat_color, title, favourite_team, xp FROM players WHERE id = ?")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return false;
                name = r.getString(1); hat = r.getInt(2); hatColor = r.getInt(3); title = r.getInt(4); team = r.getInt(5); xp = r.getInt(6);
                return true;
            }
        }
    }

    String name() { return name; }
    int hat() { return hat; }
    int hatColor() { return hatColor; }
    int title() { return title; }
    int favouriteTeam() { return team; }
    int level() { return levelOf(xp)[0]; }

    /** Saves who you are. Locked hats and titles are refused (kept as they were). */
    void save(String newName, int newHat, int newColor, int newTitle, int newTeam) {
        Set<String> got = unlocked();
        name = newName;
        if (hatOpen(newHat, got)) hat = newHat;
        hatColor = newColor;
        if (titleOpen(newTitle, got)) title = newTitle;
        team = newTeam;
        prefs.put("name", name); prefs.putInt("hat", hat); prefs.putInt("hatColor", hatColor);   // also used if the database is missing
        if (db == null) return;
        try (PreparedStatement p = db.prepareStatement("UPDATE players SET name=?, hat=?, hat_color=?, title=?, favourite_team=? WHERE id=?")) {
            p.setString(1, name); p.setInt(2, hat); p.setInt(3, hatColor); p.setInt(4, title); p.setInt(5, team); p.setString(6, id);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Could not save the profile: " + e);
        }
    }

    boolean hatUnlocked(int h) { return hatOpen(h, unlocked()); }
    boolean titleUnlocked(int t) { return titleOpen(t, unlocked()); }

    private boolean hatOpen(int h, Set<String> got) { return h >= 0 && h < HAT_LOCK.length && (HAT_LOCK[h] == null || got.contains(HAT_LOCK[h])); }

    private boolean titleOpen(int t, Set<String> got) {
        if (t < 0 || t >= TITLES.length) return false;
        String need = TITLE_LOCK[t];
        if (need == null || got.contains(need)) return true;
        return need.startsWith("LEVEL_") && level() >= Integer.parseInt(need.substring(6));
    }

    private Set<String> unlocked() {
        Set<String> got = new HashSet<>();
        if (db == null) return got;
        try (PreparedStatement p = db.prepareStatement("SELECT achievement FROM achievements WHERE player_id = ?")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) { while (r.next()) got.add(r.getString(1)); }
        } catch (SQLException e) {
            System.err.println("Could not read achievements: " + e);
        }
        return got;
    }

    // ------------------------------------------------------------------ XP and levels

    /** XP needed to go from level n to n+1: 200, 250, 300 ... */
    static int need(int n) { return 150 + 50 * n; }

    /** {level, XP into that level, XP needed for the next} */
    static int[] levelOf(int xp) {
        int lv = 1;
        while (xp >= need(lv)) { xp -= need(lv); lv++; }
        return new int[]{lv, xp, need(lv)};
    }

    static int xpFor(boolean won, int captures, int tags, boolean mvp) {
        return (won ? 100 : 40) + 20 * captures + 5 * tags + (mvp ? 50 : 0);
    }

    // ------------------------------------------------------------------ recording a match

    /**
     * Saves a finished match (all players' rows, yours marked with your id), adds your XP and unlocks achievements.
     * Returns what to celebrate on the results card, e.g. "LEVEL UP!  LEVEL 8", "ACHIEVEMENT: FIRST WIN". Safe to call once per match.
     */
    List<String> recordMatch(Snapshot s, int myId, int mvpId, int lengthSec) {
        List<String> news = new ArrayList<>();
        Snapshot.P me = null;
        int[] perTeam = new int[2];
        for (Snapshot.P p : s.players) {
            if (p.id == myId) me = p;
            perTeam[Math.max(0, Math.min(1, p.team))]++;
        }
        if (db == null || me == null || s.winner < 0) return news;
        boolean won = me.team == s.winner, wasMvp = mvpId == myId;
        int mode = Math.max(perTeam[0], perTeam[1]);
        int before = level();
        try {
            db.setAutoCommit(false);
            long matchId;
            try (PreparedStatement p = db.prepareStatement("INSERT INTO matches (played_at, mode, winner, red_score, blue_score, length_sec) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1, now()); p.setInt(2, mode); p.setInt(3, s.winner); p.setInt(4, s.score[0]); p.setInt(5, s.score[1]); p.setInt(6, Math.max(0, lengthSec));
                p.executeUpdate();
                try (ResultSet k = p.getGeneratedKeys()) { k.next(); matchId = k.getLong(1); }
            }
            try (PreparedStatement p = db.prepareStatement("INSERT INTO match_players (match_id, player_id, name, team, captures, tags, deaths, score, was_mvp, won)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                for (Snapshot.P q : s.players) {
                    p.setLong(1, matchId); p.setString(2, q.id == myId ? id : null); p.setString(3, q.name); p.setInt(4, q.team);
                    p.setInt(5, q.captures); p.setInt(6, q.tags); p.setInt(7, q.deaths); p.setInt(8, q.score);
                    p.setInt(9, q.id == mvpId ? 1 : 0); p.setInt(10, q.team == s.winner ? 1 : 0);
                    p.addBatch();
                }
                p.executeBatch();
            }
            int gained = xpFor(won, me.captures, me.tags, wasMvp);
            xp += gained;
            try (PreparedStatement p = db.prepareStatement("UPDATE players SET xp = ? WHERE id = ?")) { p.setInt(1, xp); p.setString(2, id); p.executeUpdate(); }
            news.add("+" + gained + " XP");
            int after = level();
            if (after > before) news.add("LEVEL UP!  LEVEL " + after);

            Stats st = stats();
            Set<String> got = new HashSet<>(st.unlocked);
            boolean shutout = won && s.score[1 - s.winner] == 0;
            String[][] checks = {
                {"FIRST_WIN", String.valueOf(st.wins >= 1)}, {"CAPTURES_10", String.valueOf(st.captures >= 10)}, {"TAGS_50", String.valueOf(st.tags >= 50)},
                {"MVP_5", String.valueOf(st.mvps >= 5)}, {"SHUTOUT", String.valueOf(shutout)}, {"STREAK_5", String.valueOf(st.bestStreak >= 5)},
                {"MATCHES_100", String.valueOf(st.matches >= 100)}, {"MVP_4V4", String.valueOf(wasMvp && mode == 4)}, {"LEVEL_10", String.valueOf(after >= 10)}};
            try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO achievements (player_id, achievement, unlocked_at) VALUES (?,?,?)")) {
                for (String[] c : checks) {
                    if (!Boolean.parseBoolean(c[1]) || got.contains(c[0])) continue;
                    p.setString(1, id); p.setString(2, c[0]); p.setString(3, now());
                    p.executeUpdate();
                    for (String[] a : ACHIEVEMENTS) if (a[0].equals(c[0])) news.add("ACHIEVEMENT: " + a[1] + "  (" + a[2] + ")");
                }
            }
            db.commit();
        } catch (SQLException e) {
            System.err.println("Could not save the match: " + e);
            try { db.rollback(); } catch (SQLException ignored) { /* nothing more to undo */ }
            news.clear();
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) { /* the connection stays usable */ }
        }
        return news;
    }

    // ------------------------------------------------------------------ reading

    /** Lifetime numbers, recent matches and achievements, all calculated from the tables. */
    Stats stats() {
        Stats st = new Stats();
        int[] lv = levelOf(xp);
        st.level = lv[0]; st.xp = xp; st.xpInto = lv[1]; st.xpNeed = lv[2];
        if (db == null) return st;
        try {
            try (PreparedStatement p = db.prepareStatement("SELECT COUNT(*), COALESCE(SUM(won),0), COALESCE(SUM(captures),0), COALESCE(SUM(tags),0),"
                    + " COALESCE(SUM(deaths),0), COALESCE(SUM(was_mvp),0) FROM match_players WHERE player_id = ?")) {
                p.setString(1, id);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    st.matches = r.getInt(1); st.wins = r.getInt(2); st.captures = r.getInt(3); st.tags = r.getInt(4); st.deaths = r.getInt(5); st.mvps = r.getInt(6);
                }
            }
            try (PreparedStatement p = db.prepareStatement("SELECT mp.won FROM match_players mp JOIN matches m ON m.id = mp.match_id"
                    + " WHERE mp.player_id = ? ORDER BY m.id")) {
                p.setString(1, id);
                int run = 0;
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) { run = r.getInt(1) == 1 ? run + 1 : 0; st.bestStreak = Math.max(st.bestStreak, run); }
                }
            }
            try (PreparedStatement p = db.prepareStatement("SELECT mp.won, m.winner, m.red_score, m.blue_score, m.mode, m.length_sec, mp.captures, mp.tags,"
                    + " mp.deaths, mp.was_mvp FROM match_players mp JOIN matches m ON m.id = mp.match_id WHERE mp.player_id = ? ORDER BY m.id DESC LIMIT 20")) {
                p.setString(1, id);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        int w = r.getInt(2), rs = r.getInt(3), bs = r.getInt(4);
                        String score = (w == 0 ? "RED " + rs + " - " + bs : "BLUE " + bs + " - " + rs);
                        st.history.add(new Object[]{r.getInt(1) == 1, score, r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getInt(9), r.getInt(10) == 1});
                    }
                }
            }
            st.unlocked.addAll(unlocked());
        } catch (SQLException e) {
            System.err.println("Could not read the profile: " + e);
        }
        return st;
    }

    /** Deletes your match history, achievements and XP. Name and looks stay. Cannot be undone. */
    void reset() {
        if (db == null) return;
        try (Statement s = db.createStatement()) {
            db.setAutoCommit(false);
            s.execute("DELETE FROM matches");   // match_players rows go with them (ON DELETE CASCADE)
            try (PreparedStatement p = db.prepareStatement("DELETE FROM achievements WHERE player_id = ?")) { p.setString(1, id); p.executeUpdate(); }
            try (PreparedStatement p = db.prepareStatement("UPDATE players SET xp = 0, title = 0 WHERE id = ?")) { p.setString(1, id); p.executeUpdate(); }
            db.commit();
            xp = 0;
            title = 0;
            if (HAT_LOCK[hat] != null) save(name, 1, hatColor, 0, team);   // a hat you no longer own goes back to the crown
        } catch (SQLException e) {
            System.err.println("Could not reset the profile: " + e);
            try { db.rollback(); } catch (SQLException ignored) { /* nothing more to undo */ }
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) { /* the connection stays usable */ }
        }
    }

    private static String now() { return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
}

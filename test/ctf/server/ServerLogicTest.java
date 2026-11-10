package ctf.server;

import ctf.common.LobbyMap;
import ctf.common.MapData;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic tests of the server rules, with no sockets and no wall-clock time.
 * Run: java -cp out ctf.server.ServerLogicTest
 */
public class ServerLogicTest {
    private static int passed, failed;

    static class FakeConn implements Conn {
        final List<String> lines = new ArrayList<>();
        public void send(String line) { lines.add(line); }
        boolean got(String prefix) { return lines.stream().anyMatch(l -> l.startsWith(prefix)); }
    }

    private static final InetSocketAddress ADDR = new InetSocketAddress("127.0.0.1", 9999);

    private GameState g;
    private final List<Player> ps = new ArrayList<>();
    private final List<FakeConn> conns = new ArrayList<>();
    private int seq;

    /** A started game with one player per entry of teams (0 red, 1 blue). */
    private void newGame(int... teams) {
        g = new GameState(teams.length, 5000, new Random(1));
        ps.clear();
        conns.clear();
        seq = 0;
        for (int i = 0; i < teams.length; i++) {
            FakeConn c = new FakeConn();
            conns.add(c);
            ps.add(g.addPlayer("P" + i, teams[i], c));
        }
        for (Player p : ps) g.setReady(p.id, true);
        g.begin(ps.get(0).id);   // the first player is the host
        ticks(COUNTDOWN_TICKS);
    }

    private static final int COUNTDOWN_TICKS = (int) (GameState.COUNTDOWN_SEC * GameState.TICK_RATE) + 1;

    /** A lobby with one unready player per entry of teams. */
    private void newLobby(int... teams) {
        g = new GameState(teams.length, 5000, new Random(1));
        ps.clear();
        conns.clear();
        seq = 0;
        for (int i = 0; i < teams.length; i++) {
            FakeConn c = new FakeConn();
            conns.add(c);
            ps.add(g.addPlayer("P" + i, teams[i], c));
        }
    }

    private void winMatch(Player a) {
        for (int i = 0; i < 3; i++) {
            place(a, MapData.BASES[1][0], MapData.BASES[1][1]);
            g.tick();
            place(a, MapData.BASES[0][0], MapData.BASES[0][1]);
            g.tick();
        }
    }

    private Player p(int i) { return ps.get(i); }

    private void input(Player p, int dx, int dy, double aim, boolean shoot) {
        g.handleInput(new Protocol.Input(p.id, p.token, ++seq, dx, dy, aim, shoot), ADDR);
    }

    private void ticks(int n) { for (int i = 0; i < n; i++) g.tick(); }

    private void place(Player p, double x, double y) { p.x = x; p.y = y; }

    // ------------------------------------------------------------------ tests

    void hostStartsWhenAllReady() {
        g = new GameState(2, 5000, new Random(1));
        FakeConn c0 = new FakeConn(), c1 = new FakeConn();
        Player a = g.addPlayer("A", 0, c0), b = g.addPlayer("B", 1, c1);
        check(g.phase() == GameState.Phase.LOBBY, "starts in lobby");
        check(c0.got("LOBBY LOBBY 2 " + a.id + " "), "LOBBY line names the first player as host");
        g.setReady(a.id, true);
        g.begin(a.id);
        check(g.countdown() == 0, "host cannot start while someone is not ready");
        g.setReady(b.id, true);
        check(g.phase() == GameState.Phase.LOBBY, "everyone ready does not start by itself");
        g.begin(b.id);
        check(g.countdown() == 0, "a player who is not the host cannot start");
        g.begin(a.id);
        check(g.countdown() == GameState.COUNTDOWN_SEC, "host starts a 5 s countdown");
        ticks(COUNTDOWN_TICKS - 5);
        check(g.phase() == GameState.Phase.LOBBY, "still in the lobby during the countdown");
        ticks(5);
        check(g.phase() == GameState.Phase.PLAYING, "round starts when the countdown ends");
        check(c0.got("START") && c1.got("START"), "START sent to everyone");
        check(c0.got("WELCOME " + a.id + " " + a.token + " 5000 0"), "WELCOME carries id, token, port, team");
        check(a.x == MapData.BASES[0][0] && b.x == MapData.BASES[1][0], "players spawn at their bases");
    }

    void countdownCancelsWhenSomeoneUnreadies() {
        newLobby(0, 1);
        for (Player p : ps) g.setReady(p.id, true);
        g.begin(p(0).id);
        ticks(30);
        g.setReady(p(1).id, false);
        check(g.countdown() == 0, "un-readying stops the countdown");
        ticks(COUNTDOWN_TICKS);
        check(g.phase() == GameState.Phase.LOBBY, "and the match does not start");
        g.setReady(p(1).id, true);
        g.begin(p(0).id);
        g.addPlayer("Late", -1, new FakeConn());
        check(g.countdown() == 0, "a new unready player stops the countdown too");
    }

    void hostPassesOnWhenTheyLeave() {
        newLobby(0, 1, 1);
        g.removePlayer(p(0).id);
        g.begin(p(1).id);
        check(g.countdown() == 0, "nobody can start with fewer than the minimum players");
        Player late = g.addPlayer("New", 0, new FakeConn());
        for (Player p : List.of(p(1), p(2), late)) g.setReady(p.id, true);
        g.begin(late.id);
        check(g.countdown() == 0, "the newest player is not the host");
        g.begin(p(1).id);
        check(g.countdown() > 0, "the next player becomes host and can start");
    }

    void lobbyIsSafeAndHasItsOwnWalls() {
        newLobby(0, 1);
        Player a = p(0), b = p(1);
        check(Math.hypot(a.x - LobbyMap.SPAWN_X, a.y - LobbyMap.SPAWN_Y) < 100, "players appear in the lobby room");
        place(a, 300, 200);
        place(b, 400, 200);
        input(a, 0, 0, 0, true);
        ticks(25);
        check(b.alive && a.tags == 0, "bullets cannot hurt anyone in the lobby");
        double[] w = LobbyMap.WALLS[0];
        place(a, w[0] + w[2] / 2, w[1] + w[3] + 40);
        for (int i = 0; i < 30; i++) { input(a, 0, -1, 0, false); g.tick(); }
        check(a.y >= w[1] + w[3] + MapData.R - 1e-6, "lobby crates block movement (y=" + a.y + ")");
        double[] mapWall = MapData.WALLS[0];   // the arena's centre column is not in the lobby
        place(a, mapWall[0] - 40, mapWall[1] + 20);
        for (int i = 0; i < 20; i++) { input(a, 1, 0, 0, false); g.tick(); }
        check(a.x > mapWall[0], "arena walls do not exist in the lobby");
    }

    void practiceTargetsCountHits() {
        newLobby(0, 1);
        Player a = p(0);
        double[] t = LobbyMap.TARGETS[1];
        place(a, t[0], t[1] - 150);
        input(a, 0, 0, Math.PI / 2, true);   // straight down at the target
        ticks(20);
        check(a.targetHits == 1, "hitting a practice target counts (" + a.targetHits + ")");
        check(g.bulletCount() == 0, "the bullet stops at the target");
        check(conns.get(1).lines.stream().anyMatch(l -> l.startsWith("LOBBY") && l.contains(":1:P0")),
                "everyone is told the new hit count");
    }

    void teamPadsSwitchTeamUpToTheCap() {
        newLobby(0, 0, 0, 0, 1);
        Player blue = p(4), red = p(0);
        place(blue, LobbyMap.PADS[0][0], LobbyMap.PADS[0][1]);
        g.tick();
        check(blue.team == 1, "a full team pad does nothing");
        place(red, LobbyMap.PADS[1][0], LobbyMap.PADS[1][1]);
        g.tick();
        check(red.team == 1, "standing on the blue pad joins blue");
        g.tick();
        check(blue.team == 0, "now red has room again, so the blue player on the red pad joins red");
    }

    void lookNeedsTheLaptop() {
        newLobby(0, 1);
        Player a = p(0);
        place(a, 300, 300);
        g.setLook(a.id, 2, 3);
        check(a.hat == 0, "hat cannot change away from the laptop");
        place(a, LobbyMap.LAPTOP_X, LobbyMap.LAPTOP_Y + 50);
        g.setLook(a.id, 2, 3);
        check(a.hat == 2 && a.hatColor == 3, "hat changes at the laptop");
        g.setLook(a.id, -1, 99);
        check(a.hat == LobbyMap.HATS - 1 && a.hatColor == 99 % LobbyMap.HAT_COLORS, "out-of-range looks wrap around");
    }

    void chatCarriesSenderId() {
        newLobby(0, 1);
        g.chat(p(0).id, "hi there");
        check(conns.get(1).got("CHAT " + p(0).id + " P0: hi there"), "chat line carries the sender's id for the bubble");
    }

    void movementMatchesClientPrediction() {
        newGame(0, 1);
        Player a = p(0);
        double[] pred = {a.x, a.y};
        for (int i = 0; i < 40; i++) {
            input(a, 1, 1, 0, false);
            g.tick();
            double len = Math.hypot(1, 1);   // the formula used by GamePanel
            pred[0] += 1 / len * MapData.SPEED * (1.0 / 30);
            pred[1] += 1 / len * MapData.SPEED * (1.0 / 30);
            MapData.resolve(pred);
        }
        check(Math.abs(a.x - pred[0]) < 1e-9 && Math.abs(a.y - pred[1]) < 1e-9, "server movement equals client prediction");
    }

    void wallsBlockMovement() {
        newGame(0, 1);
        Player a = p(0);
        double[] w = MapData.WALLS[0];   // the centre column is ahead
        place(a, w[0] - 100, w[1] + w[3] / 2);
        for (int i = 0; i < 90; i++) { input(a, 1, 0, 0, false); g.tick(); }
        check(a.x <= w[0] - MapData.R + 1e-6, "player cannot walk into a wall (x=" + a.x + ")");
    }

    void staleInputStopsPlayer() {
        newGame(0, 1);
        Player a = p(0);
        input(a, 1, 0, 0, false);
        ticks(20);
        double x20 = a.x;
        ticks(10);
        check(a.x == x20, "player stops when input stops arriving");
        check(x20 > MapData.BASES[0][0], "player did move while input was fresh");
    }

    void spoofedAndStaleUdpIgnored() {
        newGame(0, 1);
        Player a = p(0);
        g.handleInput(new Protocol.Input(a.id, a.token + 1, 1, 1, 0, 0, false), ADDR);
        check(a.udpAddr == null && a.dx == 0, "wrong token is rejected");
        g.handleInput(new Protocol.Input(a.id, a.token, 5, 1, 0, 0, false), ADDR);
        g.handleInput(new Protocol.Input(a.id, a.token, 3, -1, 0, 0, false), ADDR);
        check(a.dx == 1, "older sequence number is ignored");
        check(a.udpAddr != null, "valid packet registers the UDP address");
    }

    void bulletTagsEnemy() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, 100, 200);
        place(b, 300, 200);
        input(a, 0, 0, 0, true);
        ticks(25);
        check(!b.alive, "enemy is eliminated by a bullet");
        check(a.tags == 1 && b.deaths == 1 && a.score() == 25, "tag, death and score are counted");
        check(g.bulletCount() == 0, "bullet is removed after the hit");
    }

    void friendlyFireAndShield() {
        newGame(0, 1, 0);
        Player a = p(0), b = p(1), c = p(2);
        place(a, 100, 200);
        place(c, 200, 200);
        place(b, 300, 200);
        b.shieldSec = 5;
        input(a, 0, 0, 0, true);
        ticks(25);
        check(c.alive, "bullets pass through teammates");
        check(b.alive && a.tags == 0, "a shielded player absorbs the bullet");
        check(g.bulletCount() == 0, "bullet is gone after being absorbed");
    }

    void fireRateIsLimited() {
        newGame(0, 1);
        Player a = p(0);
        place(a, 100, MapData.H - 20);
        for (int i = 0; i < 30; i++) {
            input(a, 0, 0, -Math.PI / 2, true);   // held fire for one second, aimed at the top
            g.tick();
        }
        check(g.bulletCount() <= 5, "at most one shot per 0.25 s while holding fire (" + g.bulletCount() + " in flight)");
        check(g.bulletCount() >= 3, "held fire does keep shooting");
    }

    void respawnAfterThreeSeconds() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, 100, 200);
        place(b, 300, 200);
        input(a, 0, 0, 0, true);
        ticks(25);
        check(!b.alive, "setup: b is dead");
        ticks(60);
        check(!b.alive, "still dead after about 2 seconds");
        ticks(40);
        check(b.alive && b.x == MapData.BASES[1][0] && b.y == MapData.BASES[1][1], "respawned at base after about 3 seconds");
    }

    void captureScoresAndReturnsFlag() {
        newGame(0, 1);
        Player a = p(0);
        place(a, MapData.BASES[1][0], MapData.BASES[1][1]);
        g.tick();
        check(g.flag(1).state == Flag.State.CARRIED && g.flag(1).carrierId == a.id, "enemy touching the flag picks it up");
        place(a, MapData.BASES[0][0], MapData.BASES[0][1]);
        g.tick();
        check(g.score(0) == 1 && a.captures == 1, "carrying it home scores a capture");
        check(g.flag(1).state == Flag.State.AT_BASE, "the captured flag returns to its base");
        check(a.score() == 100, "a capture is worth 100 points");
    }

    void cannotCaptureWhenOwnFlagIsAway() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(b, MapData.BASES[0][0], MapData.BASES[0][1]);   // blue takes the red flag
        g.tick();
        place(a, MapData.BASES[1][0], MapData.BASES[1][1]);   // red takes the blue flag
        g.tick();
        place(a, MapData.BASES[0][0], MapData.BASES[0][1]);
        g.tick();
        check(g.score(0) == 0, "no capture while our own flag is not at base");
    }

    void killedCarrierDropsFlagAndTeammateReturnsIt() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, 300, 150);
        g.flag(1).pickUp(a);
        place(b, 500, 150);
        input(b, 0, 0, Math.PI, true);
        ticks(30);
        check(!a.alive, "setup: carrier was shot");
        Flag f = g.flag(1);
        check(f.state == Flag.State.DROPPED && f.carrierId == -1, "flag is dropped when its carrier dies");
        check(Math.abs(f.x - 300) < 1 && Math.abs(f.y - 150) < 1, "flag drops where the carrier fell");
        place(b, f.x, f.y);
        g.tick();
        check(f.state == Flag.State.AT_BASE, "a teammate of the flag's team returns it by touching");
    }

    void droppedFlagReturnsAfterTenSeconds() {
        newGame(0, 1);
        Player a = p(0);
        place(a, 300, 100);
        g.flag(1).pickUp(a);
        g.removePlayer(a.id);
        check(g.flag(1).state == Flag.State.DROPPED, "flag drops when its carrier disconnects");
        ticks(290);
        check(g.flag(1).state == Flag.State.DROPPED, "still on the ground after about 9.7 s");
        ticks(15);
        check(g.flag(1).state == Flag.State.AT_BASE, "returns by itself after 10 s");
    }

    void tentGivesShieldOnceThenRecharges() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, MapData.TENT_X, MapData.TENT_Y);
        g.tick();
        check(a.shieldSec > 4.9 && a.shieldSec <= 5.0, "tent grants a 5 s shield");
        check(g.tentCooldown() > 9.9, "tent starts a 10 s recharge");
        place(b, MapData.TENT_X, MapData.TENT_Y);
        g.tick();
        check(b.shieldSec == 0, "tent gives nothing during the recharge");
        place(a, 100, 100);   // standing in a recharged tent would, correctly, grant a new shield
        place(b, 100, 500);
        ticks(310);
        check(g.tentCooldown() == 0 && a.shieldSec == 0 && b.shieldSec == 0, "shield expired and tent recharged");
        place(b, MapData.TENT_X, MapData.TENT_Y);
        g.tick();
        check(b.shieldSec > 4.9, "the recharged tent works again");
    }

    void threeCapturesEndTheMatchAndEveryoneGoesBackToTheLobby() {
        newGame(0, 1);
        Player a = p(0);
        winMatch(a);
        check(g.phase() == GameState.Phase.ENDED && g.winner() == 0, "three captures end the match for the capturing team");
        check(conns.get(1).got("GAMEOVER 0 3 0"), "GAMEOVER is sent to every client");
        ticks(30);
        Snapshot s = Protocol.decodeSnapshot(g.snapshotLine());
        check(s != null && s.winner == 0 && s.matchEndElapsedSec > 0.9, "snapshot shows the winner and the elapsed time since the end");
        g.wantAgain(a.id);
        ticks(2);
        check(g.phase() == GameState.Phase.ENDED, "going back waits for every player");
        g.wantAgain(p(1).id);
        ticks(2);
        check(g.phase() == GameState.Phase.LOBBY && g.winner() == -1 && !a.ready, "everyone is back in the lobby, not ready");
        check(Math.hypot(a.x - LobbyMap.SPAWN_X, a.y - LobbyMap.SPAWN_Y) < 100, "and standing in the lobby room");
        for (Player p : ps) g.setReady(p.id, true);
        g.begin(a.id);
        ticks(COUNTDOWN_TICKS);
        check(g.phase() == GameState.Phase.PLAYING && g.score(0) == 0 && a.captures == 0, "the next match resets score and stats");
    }

    void backToLobbyByItselfAfterTimeout() {
        newGame(0, 1);
        winMatch(p(0));
        ticks((int) (GameState.AGAIN_TIMEOUT_SEC * GameState.TICK_RATE) + 5);
        check(g.phase() == GameState.Phase.LOBBY, "everyone returns to the lobby after the 90 s victory screen");
    }

    void timerDecidesByScoreThenTags() {
        newGame(0, 1);
        Player a = p(0);
        place(a, MapData.BASES[1][0], MapData.BASES[1][1]);
        g.tick();
        place(a, MapData.BASES[0][0], MapData.BASES[0][1]);
        g.tick();
        g.setTimeLeft(0.05);
        ticks(3);
        check(g.phase() == GameState.Phase.ENDED && g.winner() == 0, "when time runs out the higher score wins");

        newGame(0, 1);
        a = p(0);
        place(a, 100, 200);
        place(p(1), 300, 200);
        input(a, 0, 0, 0, true);
        ticks(25);
        g.setTimeLeft(0.05);
        ticks(3);
        check(g.phase() == GameState.Phase.ENDED && g.winner() == 0, "a tied score is decided by tags");
    }

    void lateJoinerDropsStraightIn() {
        newGame(0, 1);
        FakeConn c = new FakeConn();
        Player late = g.addPlayer("Late", -1, c);
        check(late != null && c.got("START") && late.alive, "someone joining a running game gets START and spawns");
        check(late.team == 0 || late.team == 1, "auto team is assigned");
    }

    void serverFullIsRefused() {
        g = new GameState(2, 5000, new Random(1));
        for (int i = 0; i < GameState.MAX_PLAYERS; i++) g.addPlayer("P" + i, -1, new FakeConn());
        check(g.addPlayer("Extra", -1, new FakeConn()) == null, "the ninth player is refused");
    }

    void protocolRoundTrips() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, 123.4, 456.7);
        b.shieldSec = 2.5;
        g.flag(1).pickUp(a);
        input(a, 0, 0, 1.25, true);   // the server takes aim from the input packet
        g.tick();
        Snapshot s = Protocol.decodeSnapshot(g.snapshotLine());
        check(s != null && s.players.length == 2, "snapshot decodes with both players");
        Snapshot.P pa = s.players[0];
        check(pa.id == a.id && Math.abs(pa.x - 123.4) < 0.06 && Math.abs(pa.y - 456.7) < 0.06 && Math.abs(pa.aim - 1.25) < 0.006,
                "position and aim survive the round trip");
        check(s.flags[1].state == Snapshot.CARRIED && s.flags[1].carrier == a.id, "flag state and carrier survive");
        check(Math.abs(s.players[1].shieldSec - 2.5) < 0.1, "shield time survives");
        check(s.bullets.length == 1 && (int) s.bullets[0][2] == 0, "bullet and its team survive");

        check(Protocol.decodeSnapshot("garbage") == null, "garbage snapshot is rejected");
        String line = g.snapshotLine();
        check(Protocol.decodeSnapshot(line.substring(0, line.length() / 2)) == null, "truncated snapshot is rejected");
        check(Protocol.decodeSnapshot("S 1 300 0 0 -1 0.0 0.0 1 0.00 0 0.0 0.0 -1 0 0.0 0.0 -1 99999 0") == null,
                "an absurd player count is rejected");
        check(s.phase == Snapshot.PLAYING && s.countdown == 0, "phase survives the round trip");

        Protocol.Input in = Protocol.decodeInput("I 3 77 9 5 -9 1.5 1");
        check(in != null && in.dx() == 1 && in.dy() == -1 && in.shoot(), "input direction is clamped to -1..1");
        check(Protocol.decodeInput("I 3 77 9 1 0 NaN 1") == null, "NaN aim is rejected");
        check(Protocol.decodeInput("I 3 77 9") == null, "short input is rejected");

        Protocol.Lobby lob = Protocol.decodeLobby(Protocol.encodeLobby("LOBBY", 2, 1,
                List.of(new Protocol.LobbyEntry(1, 0, true, 3, 4, 7, "Ann"), new Protocol.LobbyEntry(2, 1, false, 0, 0, 0, "Bob"))));
        check(lob != null && lob.entries().size() == 2 && lob.entries().get(0).ready() && lob.entries().get(1).name().equals("Bob"),
                "lobby list round trip");
        check(lob.hostId() == 1 && lob.entries().get(0).hat() == 3 && lob.entries().get(0).hatColor() == 4
                && lob.entries().get(0).targetHits() == 7, "host, hat and target hits survive the round trip");

        newLobby(0);
        Snapshot ls = Protocol.decodeSnapshot(g.snapshotLine());
        check(ls != null && ls.phase == Snapshot.LOBBY, "lobby snapshot says LOBBY");
        List<Protocol.Stat> st = Protocol.decodeStats(Protocol.encodeStats(List.of(new Protocol.Stat(1, 2, 3, 4, 5, 6))));
        check(st.size() == 1 && st.get(0).score() == 5 && st.get(0).ping() == 6, "stats round trip");
        check(Protocol.cleanName("Al ice;:|!!!!!!!!!!!!!").length() == 12, "names are cleaned and limited to 12 characters");
    }

    // ------------------------------------------------------------------ runner

    private static void check(boolean ok, String what) {
        if (ok) { passed++; System.out.println("  PASS  " + what); }
        else { failed++; System.out.println("  FAIL  " + what); }
    }

    private void run(String name, Runnable r) {
        System.out.println("[" + name + "]");
        try { r.run(); }
        catch (RuntimeException e) { failed++; System.out.println("  FAIL  threw " + e); e.printStackTrace(System.out); }
    }

    public static void main(String[] args) {
        ServerLogicTest t = new ServerLogicTest();
        t.run("host start", t::hostStartsWhenAllReady);
        t.run("countdown cancel", t::countdownCancelsWhenSomeoneUnreadies);
        t.run("host leaves", t::hostPassesOnWhenTheyLeave);
        t.run("lobby safe, own walls", t::lobbyIsSafeAndHasItsOwnWalls);
        t.run("practice targets", t::practiceTargetsCountHits);
        t.run("team pads", t::teamPadsSwitchTeamUpToTheCap);
        t.run("laptop look", t::lookNeedsTheLaptop);
        t.run("chat id", t::chatCarriesSenderId);
        t.run("movement", t::movementMatchesClientPrediction);
        t.run("walls", t::wallsBlockMovement);
        t.run("stale input", t::staleInputStopsPlayer);
        t.run("udp security", t::spoofedAndStaleUdpIgnored);
        t.run("bullet tag", t::bulletTagsEnemy);
        t.run("friendly fire and shield", t::friendlyFireAndShield);
        t.run("fire rate", t::fireRateIsLimited);
        t.run("respawn", t::respawnAfterThreeSeconds);
        t.run("capture", t::captureScoresAndReturnsFlag);
        t.run("capture needs own flag home", t::cannotCaptureWhenOwnFlagIsAway);
        t.run("carrier killed", t::killedCarrierDropsFlagAndTeammateReturnsIt);
        t.run("flag timeout", t::droppedFlagReturnsAfterTenSeconds);
        t.run("tent", t::tentGivesShieldOnceThenRecharges);
        t.run("win and back to lobby", t::threeCapturesEndTheMatchAndEveryoneGoesBackToTheLobby);
        t.run("back to lobby timeout", t::backToLobbyByItselfAfterTimeout);
        t.run("timer end", t::timerDecidesByScoreThenTags);
        t.run("late join", t::lateJoinerDropsStraightIn);
        t.run("server full", t::serverFullIsRefused);
        t.run("protocol", t::protocolRoundTrips);
        System.out.println("\nPassed: " + passed + " | Failed: " + failed);
        if (failed > 0) System.exit(1);
    }
}

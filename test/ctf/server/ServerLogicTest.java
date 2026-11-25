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
        newLobby(0);   // solo test server: one ready player may start, and a 1v1 still has a free slot
        g.setReady(p(0).id, true);
        g.begin(p(0).id);
        check(g.countdown() > 0, "the solo server counts down for one ready player");
        g.addPlayer("Late", -1, new FakeConn());
        check(g.countdown() == 0, "a new unready player stops the countdown too");
    }

    void hostPassesOnWhenTheyLeave() {
        newLobby(0, 1, 0, 1);
        g.removePlayer(p(0).id);
        g.begin(p(1).id);
        check(g.countdown() == 0, "nobody can start with fewer than the minimum players");
        Player late = g.addPlayer("New", 0, new FakeConn());
        for (Player p : List.of(p(1), p(2), p(3), late)) g.setReady(p.id, true);
        g.begin(late.id);
        check(g.countdown() == 0, "the newest player is not the host");
        g.begin(p(1).id);
        check(g.countdown() > 0, "the next player becomes host and can start");
    }

    void matchSizeIsChosenByHostAndNeedsFullTeams() {
        newLobby(0, 1);
        check(g.mode() == 1, "two players make a 1v1");
        g.setMode(p(1).id, 2);
        check(g.mode() == 1, "only the host can change the match size");
        g.setMode(p(0).id, 2);
        check(g.mode() == 2, "the host picks 2v2");
        for (Player p : ps) g.setReady(p.id, true);
        g.begin(p(0).id);
        check(g.countdown() > 0 && g.bots().size() == 2, "START with one player per team in a 2v2: a bot fills each empty slot");
        Player c = g.addPlayer("C", 0, new FakeConn()), d = g.addPlayer("D", 1, new FakeConn());
        check(g.bots().isEmpty() && g.countdown() == 0, "joining humans take the bots' places (and are not ready yet)");
        g.setReady(c.id, true);
        g.setReady(d.id, true);
        g.begin(p(0).id);
        check(g.countdown() > 0, "2v2 starts once both teams have two ready players");
        g.setMode(p(0).id, 1);
        check(g.mode() == 2, "the size cannot drop below a team's current size");
        check(g.addPlayer("E", -1, new FakeConn()) == null, "a fifth player is refused by a full 2v2");
        check(g.mode() == 2 && g.countdown() > 0, "the refused player changes nothing");
        g.setMode(p(0).id, 3);
        check(g.mode() == 3 && g.countdown() == 0, "the host can grow the match to 3v3, which stops the countdown");
        place(c, LobbyMap.PADS[1][0], LobbyMap.PADS[1][1]);
        g.tick();
        check(c.team == 1, "pads allow moving while the other team has room");
        FakeConn fc = new FakeConn();
        g.addPlayer("F", 0, 4, 99, 7, 3, fc);
        check(fc.lines.stream().anyMatch(l -> l.startsWith("LOBBY") && l.contains(":4:" + (99 % LobbyMap.HAT_COLORS) + ":0:7:3:F")),
                "hat, level and title arrive with HELLO (the colour wrapped into range)");
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
        check(conns.get(1).lines.stream().anyMatch(l -> l.startsWith("LOBBY") && l.contains(":1:1:0:P0")),
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
        newGame(0, 1, 0, 1);   // 2v2: teams must be full to start; the fourth player waits at the blue base
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
        check(!a.ready, "at the end of a match nobody has voted yet");
        g.wantAgain(a.id);
        ticks(2);
        check(g.phase() == GameState.Phase.ENDED, "going back waits for every player");
        String lastLobby = null;
        for (String line : conns.get(1).lines) if (line.startsWith("LOBBY")) lastLobby = line;
        check(lastLobby != null && lastLobby.contains(a.id + ":0:1:"), "the vote is sent to everyone, so they see the tick (" + lastLobby + ")");
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
        check(g.addPlayer("Third", -1, new FakeConn()) == null, "a third player is refused by a running 1v1");
        g.removePlayer(p(1).id);
        FakeConn c = new FakeConn();
        Player late = g.addPlayer("Late", -1, c);
        check(late != null && c.got("START") && late.alive, "someone filling a free slot in a running game gets START and spawns");
        check(late.team == 1, "they take the team that has room");
    }

    void serverFullIsRefused() {
        g = new GameState(2, 5000, new Random(1));
        check(g.addPlayer("A", 0, new FakeConn()) != null && g.addPlayer("B", 0, new FakeConn()).team == 1,
                "asking for a full team puts you on the other one");
        check(g.addPlayer("C", -1, new FakeConn()) == null, "a third player is refused by a full 1v1 lobby");
        g = new GameState(GameState.MAX_PLAYERS, 5000, new Random(1));
        for (int i = 0; i < GameState.MAX_PLAYERS; i++) g.addPlayer("P" + i, -1, new FakeConn());
        check(g.addPlayer("Extra", -1, new FakeConn()) == null, "the ninth player is refused by a full 4v4");
    }

    /** Over a real socket: an old game version is refused with a clear message, the current one is welcomed. */
    void versionCheck() {
        int port = 47000 + new Random().nextInt(2000);
        try {
            GameServer.start(port, 2);
            String old = helloReply(port, "HELLO 0 Old 0 0 1 0 0.9");
            check(old != null && old.startsWith("ERROR") && old.contains("version 0.9") && old.contains(Protocol.VERSION),
                    "an old version is refused, naming both versions (" + old + ")");
            String none = helloReply(port, "HELLO 0 Older 0 0 1 0");
            check(none != null && none.startsWith("ERROR") && none.contains("older"), "a game that sends no version is refused too (" + none + ")");
            String ok = helloReply(port, "HELLO 0 Fresh 0 0 1 0 " + Protocol.VERSION);
            check(ok != null && ok.startsWith("WELCOME"), "the current version is welcomed (" + ok + ")");
        } catch (java.io.IOException e) {
            check(false, "server could not start on port " + port + ": " + e);
        }
    }

    /** The first reply that is not the ROOM line. */
    private static String helloReply(int port, String hello) throws java.io.IOException {
        try (Client c = new Client(port)) {
            c.send(hello);
            String line = c.read();
            return line != null && line.startsWith("ROOM ") ? c.read() : line;
        }
    }

    /** A raw TCP client for the socket tests. */
    private static final class Client implements AutoCloseable {
        final java.net.Socket s;
        final java.io.PrintWriter out;
        final java.io.BufferedReader in;

        Client(int port) throws java.io.IOException {
            s = new java.net.Socket("127.0.0.1", port);
            s.setSoTimeout(3000);
            out = new java.io.PrintWriter(s.getOutputStream(), true);
            in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
        }

        void send(String line) { out.println(line); }
        String read() throws java.io.IOException { return in.readLine(); }
        @Override public void close() throws java.io.IOException { s.close(); }
    }

    // ------------------------------------------------------------------ rooms

    private static RoomManager.Joined joinRoom(RoomManager rm, String request, String name, FakeConn c) {
        return rm.join(request, name, -1, 0, 0, 1, 0, c);
    }

    void roomsAreSeparateMatches() {
        RoomManager rm = new RoomManager(2, 5000, new Random(1));
        FakeConn a1 = new FakeConn(), a2 = new FakeConn(), b1 = new FakeConn(), b2 = new FakeConn();
        RoomManager.Joined ja = joinRoom(rm, Protocol.create(1, false), "Alpha", a1);
        String codeA = ja.room().code;
        check(codeA.length() == RoomManager.CODE_LEN && codeA.chars().allMatch(ch -> RoomManager.ALPHABET.indexOf(ch) >= 0),
                "a room code is 4 easy-to-read characters (" + codeA + ")");
        check(a1.lines.get(0).equals("ROOM " + codeA + " private") && a1.lines.get(1).startsWith("WELCOME"),
                "the ROOM line comes before WELCOME");
        RoomManager.Joined ja2 = joinRoom(rm, Protocol.join(codeA.toLowerCase()), "Alpha2", a2);
        check(ja2.room() == ja.room(), "a friend joins by code, in any letter case");
        RoomManager.Joined jb = joinRoom(rm, Protocol.create(1, false), "Bravo", b1);
        RoomManager.Joined jb2 = joinRoom(rm, Protocol.join(jb.room().code), "Bravo2", b2);
        check(!jb.room().code.equals(codeA) && rm.all().size() == 2, "a second host gets a second room");
        Player pa1 = ja.player(), pa2 = ja2.player(), pb1 = jb.player(), pb2 = jb2.player();
        check(new java.util.HashSet<>(List.of(pa1.id, pa2.id, pb1.id, pb2.id)).size() == 4, "player ids are unique across rooms");

        ja.room().game.chat(pa1.id, "hello");
        check(a2.got("CHAT") && !b1.got("CHAT"), "chat stays inside its room");
        check(b1.lines.stream().noneMatch(l -> l.contains("Alpha")), "a room's lobby list never shows another room's players");

        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", 10001), addrB = new InetSocketAddress("127.0.0.1", 10002);
        rm.handleInput(new Protocol.Input(pa1.id, pa1.token, 1, 1, 0, 0, false), addrA);
        rm.handleInput(new Protocol.Input(pb1.id, pb1.token, 1, 0, 1, 0, false), addrB);
        check(pa1.dx == 1 && pb1.dy == 1 && pb1.dx == 0, "UDP input reaches the sender's own room");
        rm.handleInput(new Protocol.Input(pa1.id, pb1.token, 2, -1, 0, 0, false), addrA);
        check(pa1.dx == 1, "a token from another room is rejected");

        java.util.Map<InetSocketAddress, String> sent = new java.util.HashMap<>();
        rm.tickAll((line, addr) -> sent.put(addr, line));
        Snapshot sa = Protocol.decodeSnapshot(sent.get(addrA)), sb = Protocol.decodeSnapshot(sent.get(addrB));
        check(sa != null && sb != null && sa.players.length == 2 && sb.players.length == 2
                && java.util.Arrays.stream(sa.players).allMatch(p -> p.id == pa1.id || p.id == pa2.id)
                && java.util.Arrays.stream(sb.players).allMatch(p -> p.id == pb1.id || p.id == pb2.id),
                "each room's snapshot goes only to its own players and shows only them");

        rm.leave(pa1.id);
        check(rm.find(codeA) != null && ja.room().game.playerCount() == 1, "a room stays open while someone is in it");
        rm.leave(pa2.id);
        check(rm.find(codeA) == null && rm.all().size() == 1, "the last player leaving closes the room");
        rm.handleInput(new Protocol.Input(pa1.id, pa1.token, 3, 0, 0, 0, false), addrA);   // must not throw for a gone player
    }

    void roomJoinRules() {
        RoomManager rm = new RoomManager(2, 5000, new Random(2));
        RoomManager.Joined none = joinRoom(rm, Protocol.join("ZZZZ"), "Lost", new FakeConn());
        check(none.player() == null && "room not found".equals(none.error()), "a wrong code is refused: room not found");
        RoomManager.Joined priv = joinRoom(rm, Protocol.create(1, false), "Host", new FakeConn());
        joinRoom(rm, Protocol.join(priv.room().code), "Friend", new FakeConn());
        RoomManager.Joined third = joinRoom(rm, Protocol.join(priv.room().code), "Third", new FakeConn());
        check("room full".equals(third.error()), "a full room is refused: room full");

        RoomManager.Joined q1 = joinRoom(rm, Protocol.QUICK, "Q1", new FakeConn());
        check(q1.room() != priv.room() && q1.room().isPublic, "quick play never picks a private room");
        RoomManager.Joined q2 = joinRoom(rm, null, "Q2", new FakeConn());
        check(q2.room() == q1.room(), "quick play (also a HELLO without a room) fills an open public room first");
        RoomManager.Joined q3 = joinRoom(rm, Protocol.QUICK, "Q3", new FakeConn());
        check(q3.room() != q1.room(), "when every public room is full, quick play opens a new one");
        RoomManager.Joined big = joinRoom(rm, Protocol.create(3, true), "Big", new FakeConn());
        check(big.room().game.mode() == 3 && big.room().isPublic, "a host picks the size and public when creating");

        List<Protocol.RoomInfo> list = rm.listPublic();
        check(list.size() == 3 && list.stream().noneMatch(r -> r.code().equals(priv.room().code)),
                "the ROOMS list shows public rooms only");
        check(Protocol.decodeRooms(Protocol.encodeRooms(list)).equals(list), "the ROOMS list survives encode/decode");
        check(Protocol.decodeRooms("ROOMS").isEmpty() && Protocol.decodeRooms("ROOMS bad,AB:x:1:LOBBY").isEmpty(),
                "an empty or broken ROOMS line decodes to nothing");
        check("unknown room request NOPE".equals(joinRoom(rm, "NOPE", "X", new FakeConn()).error()), "an unknown room request is refused");

        RoomManager many = new RoomManager(2, 5000, new Random(3));
        for (int i = 0; i < RoomManager.MAX_ROOMS; i++) joinRoom(many, Protocol.create(1, false), "H" + i, new FakeConn());
        check(many.all().size() == RoomManager.MAX_ROOMS
                && "server busy, too many rooms".equals(joinRoom(many, Protocol.create(1, false), "Late", new FakeConn()).error()),
                "the server caps the number of rooms (" + RoomManager.MAX_ROOMS + ")");
    }

    // ------------------------------------------------------------------ bots

    void botsFillEmptySlots() {
        GameState r = new GameState(2, 2, 5000, new Random(4), counterIds());
        FakeConn host = new FakeConn();
        Player me = r.addPlayer("Me", 0, host);
        r.begin(me.id);
        check(r.bots().isEmpty() && r.countdown() == 0, "nothing happens while a human is not ready");
        r.setReady(me.id, true);
        r.begin(me.id);
        List<Player> bots = r.bots();
        check(bots.size() == 3 && r.countdown() > 0, "START fills a 2v2 with 3 bots and counts down (" + bots.size() + " bots)");
        check(bots.stream().allMatch(b -> b.ready && b.name.startsWith("Bot_")), "bots are ready and named Bot_...");
        check(bots.stream().filter(b -> b.team == 0).allMatch(b -> b.bot.type == Bot.Type.GUARDIAN)
                && bots.stream().filter(b -> b.team == 1).map(b -> b.bot.type).toList().equals(List.of(Bot.Type.RAIDER, Bot.Type.GUARDIAN)),
                "styles follow the slot: a human's teammate guards, a bot team gets a raider and a guardian");
        check(r.playerCount() == 1 && r.hasRoom(), "bots do not count as players: the room still has room for humans");
        check(host.lines.stream().anyMatch(l -> l.startsWith("LOBBY") && l.contains("Bot_Raider")), "bots appear in the lobby list");
        r.setMode(me.id, 1);
        check(r.mode() == 1 && r.bots().size() == 1, "a smaller match size sends the extra bots away");
        r.removePlayer(me.id);
        check(r.bots().isEmpty() && r.playerCount() == 0, "bots leave with the last human");
    }

    void humansReplaceBotsMidMatch() {
        GameState r = new GameState(2, 1, 5000, new Random(5), counterIds());
        Player me = r.addPlayer("Me", 0, new FakeConn());
        r.setReady(me.id, true);
        r.begin(me.id);
        for (int i = 0; i < COUNTDOWN_TICKS; i++) r.tick();
        check(r.phase() == GameState.Phase.PLAYING && r.bots().size() == 1, "a 1v1 against a bot starts");
        FakeConn c = new FakeConn();
        Player friend = r.addPlayer("Friend", -1, c);
        check(friend != null && friend.team == 1 && r.bots().isEmpty() && c.got("START"), "a friend joining mid-match takes the bot's place");
        check(r.addPlayer("Third", -1, new FakeConn()) == null, "two humans fill a 1v1: a third is refused");
    }

    void botsPlayToWin() {
        GameState r = new GameState(2, 2, 5000, new Random(6), counterIds());
        Player me = r.addPlayer("Me", 0, new FakeConn());
        r.setReady(me.id, true);
        r.begin(me.id);
        for (int i = 0; i < COUNTDOWN_TICKS; i++) r.tick();
        int ticks = 0;
        boolean threw = false;
        while (r.phase() == GameState.Phase.PLAYING && ticks < GameState.TICK_RATE * 240) {
            me.x = 80; me.y = 40; me.alive = false; me.respawnIn = 99;   // the human stays out of it: bots against bots
            r.tick();
            threw |= r.grenadeCount() > 0;
            ticks++;
        }
        check(threw, "bots throw grenades in a match");
        check(r.score(0) + r.score(1) > 0, "bots path around the walls and capture flags (" + r.score(0) + "-" + r.score(1)
                + " after " + ticks / GameState.TICK_RATE + " s)");
        check(r.bots().stream().mapToInt(b -> b.tags).sum() > 0, "bots find and shoot each other");
    }

    void botNavigation() {
        double[] red = ctf.common.MapData.BASES[0], blue = ctf.common.MapData.BASES[1];
        List<double[]> path = Bot.Nav.path(red[0], red[1], blue[0], blue[1]);
        check(!path.isEmpty() && path.stream().allMatch(w -> !ctf.common.MapData.nearSolid(w[0], w[1], ctf.common.MapData.R)),
                "A* finds a way from base to base that never touches a wall");
        check(!Bot.Nav.clearShot(480, 315, 640, 315) && Bot.Nav.clearShot(100, 560, 400, 560),
                "line of sight: blocked by the middle wall, clear in the open");
        check(java.util.Arrays.equals(Bot.dir8(10, 0.5), new int[]{1, 0}) && java.util.Arrays.equals(Bot.dir8(-3, -3), new int[]{-1, -1}),
                "bots move in the same 8 directions as players");
        GameState r = new GameState(2, 1, 5000, new Random(7), counterIds());
        Player me = r.addPlayer("Me", 0, new FakeConn());
        r.setReady(me.id, true);
        r.begin(me.id);
        for (int i = 0; i < COUNTDOWN_TICKS; i++) r.tick();
        Player b = r.bots().get(0);
        double x0 = b.x, y0 = b.y;
        boolean legal = true;
        for (int i = 0; i < 90; i++) {
            r.tick();
            legal &= Math.abs(b.dx) <= 1 && Math.abs(b.dy) <= 1;
        }
        check(legal && Math.hypot(b.x - x0, b.y - y0) > 100, "a bot leaves its base within 3 s, using only legal moves");
    }

    // ------------------------------------------------------------------ grenades

    private void throwAt(Player p, double aim, double dist) { p.aim = aim; p.throwDist = dist; }

    private void ticksSec(double s) { ticks((int) Math.round(s * GameState.TICK_RATE)); }

    void grenadeBlast() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, 150, 560);
        place(b, 350, 560);
        throwAt(a, 0, 200);
        g.tick();
        check(g.grenadeCount() == 1 && a.grenades == MapData.GRENADES - 1, "a throw makes a grenade and uses one up");
        ticksSec(1.0);
        check(b.alive, "nothing happens before the fuse runs out");
        ticksSec(0.6);
        check(g.grenadeCount() == 0 && !b.alive && a.tags == 1, "it lands where aimed and the blast eliminates the enemy there");
    }

    void grenadeWallsTeamsShields() {
        newGame(0, 1, 0, 1);
        Player a = p(0), behindWall = p(1), mate = p(2), shielded = p(3);
        place(a, 640, 300);
        place(behindWall, 525, 300);   // 75 px from the blast, but the middle wall is in between
        place(mate, 600, 320);
        place(shielded, 620, 330);
        shielded.shieldSec = 5;
        throwAt(a, Math.PI, 40);
        ticksSec(1.7);
        check(behindWall.alive, "walls block the blast");
        check(mate.alive && a.alive, "the thrower and teammates are not hurt");
        check(shielded.alive, "the tent shield protects from the blast");
    }

    void grenadeLimits() {
        newGame(0, 1);
        Player a = p(0), b = p(1);
        place(a, 150, 560);
        place(b, 900, 120);
        throwAt(a, 0, 100);
        g.tick();
        throwAt(a, 0, 100);
        g.tick();
        check(g.grenadeCount() == 1 && a.grenades == 1, "the cooldown stops a second throw straight away");
        ticksSec(MapData.THROW_COOLDOWN);
        throwAt(a, 0, 100);
        g.tick();
        check(a.grenades == 0, "after the cooldown the second grenade goes");
        ticksSec(MapData.THROW_COOLDOWN);
        throwAt(a, 0, 100);
        g.tick();
        check(a.grenades == 0 && g.grenadeCount() == 0, "with none left, nothing is thrown");
        place(a, MapData.ARMORY_X, MapData.ARMORY_Y);
        g.tick();
        check(a.grenades == 1, "the armory crate gives one back");
        a.grenades = 0;
        g.tick();
        check(a.grenades == 0, "and then recharges before giving another");
        place(b, 350, 560);
        place(a, 150, 560);
        a.grenades = 1;
        g.flag(0).pickUp(b);
        throwAt(a, 0, 200);
        ticksSec(1.6);
        check(!b.alive && g.flag(0).state == Flag.State.DROPPED, "a flag carrier caught in the blast drops the flag");
        ticksSec(GameState.RESPAWN_SEC + 0.2);
        check(b.alive && b.grenades == MapData.GRENADES, "every new life starts with a full pair");
    }

    void grenadesInTheLobby() {
        newLobby(0, 1);
        Player a = p(0), b = p(1);
        place(b, a.x + 60, a.y);
        throwAt(a, 0, 60);
        g.tick();
        check(g.grenadeCount() == 1 && a.grenades == MapData.GRENADES, "lobby throws are practice: no grenade is used up");
        ticksSec(1.7);
        check(g.grenadeCount() == 0 && b.alive, "and nobody is hurt in the lobby");
    }

    void grenadeProtocol() {
        Protocol.Input in = Protocol.decodeInput(Protocol.encodeInput(new Protocol.Input(3, 77, 9, 1, 0, 0.5, false, 180)));
        check(in != null && in.throwDist() == 180, "a throw survives the input packet");
        check(Protocol.decodeInput("I 3 77 9 1 0 0.50 0 999").throwDist() == (int) MapData.GRENADE_RANGE, "a too-long throw is clamped to the range");
        check(Protocol.decodeInput("I 3 77 9 1 0 0.50 0").throwDist() == 0, "an input without the throw field still decodes");
        newGame(0, 1);
        throwAt(p(0), 0, 120);
        g.tick();
        Snapshot s = Protocol.decodeSnapshot(g.snapshotLine());
        check(s != null && s.grenades.length == 1 && s.grenades[0][3] == 0 && s.grenades[0][4] > 1.4
                && s.players[0].grenades == 1 && s.players[1].grenades == 2, "grenades, their fuse and everyone's ammo are in the snapshot");
    }

    private static java.util.function.IntSupplier counterIds() {
        int[] next = {1};
        return () -> next[0]++;
    }

    /** Over real sockets: create a private room, join it by code, list rooms, and a wrong code. */
    void roomsOverSockets() {
        int port = 49000 + new Random().nextInt(2000);
        String v = Protocol.VERSION;
        try {
            GameServer.start(port, 2);
            try (Client host = new Client(port); Client friend = new Client(port); Client browser = new Client(port); Client lost = new Client(port)) {
                host.send("HELLO 0 Host 0 0 1 0 " + v + " " + Protocol.create(2, false));
                String room = host.read(), welcome = host.read();
                check(room != null && room.matches("ROOM [A-Z2-9]{4} private") && welcome.startsWith("WELCOME"),
                        "CREATE gives the host a private room code (" + room + ")");
                String code = room.split(" ")[1];
                friend.send("HELLO -1 Friend 0 0 1 0 " + v + " " + Protocol.join(code));
                String fr = friend.read();
                check(("ROOM " + code + " private").equals(fr) && friend.read().startsWith("WELCOME"), "a friend joins with the code");
                browser.send("ROOMS");
                check("ROOMS".equals(browser.read()), "ROOMS works before HELLO and hides private rooms");
                lost.send("HELLO -1 Lost 0 0 1 0 " + v + " " + Protocol.join("QQQQ"));
                check("ERROR room not found".equals(lost.read()), "a wrong code gets a clear error");
            }
        } catch (java.io.IOException e) {
            check(false, "room socket test failed on port " + port + ": " + e);
        }
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

        Protocol.Lobby lob = Protocol.decodeLobby(Protocol.encodeLobby("LOBBY", 2, 1, 3,
                List.of(new Protocol.LobbyEntry(1, 0, true, 3, 4, 7, 12, 5, "Ann"), new Protocol.LobbyEntry(2, 1, false, 0, 0, 0, 1, 0, "Bob"))));
        check(lob != null && lob.entries().size() == 2 && lob.entries().get(0).ready() && lob.entries().get(1).name().equals("Bob"),
                "lobby list round trip");
        check(lob.hostId() == 1 && lob.mode() == 3 && lob.entries().get(0).hat() == 3 && lob.entries().get(0).hatColor() == 4
                && lob.entries().get(0).targetHits() == 7 && lob.entries().get(0).level() == 12 && lob.entries().get(0).title() == 5,
                "host, hat, target hits, level and title survive the round trip");

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
        t.run("match size", t::matchSizeIsChosenByHostAndNeedsFullTeams);
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
        t.run("version check", t::versionCheck);
        t.run("rooms are separate matches", t::roomsAreSeparateMatches);
        t.run("room join rules", t::roomJoinRules);
        t.run("rooms over sockets", t::roomsOverSockets);
        t.run("bots fill empty slots", t::botsFillEmptySlots);
        t.run("humans replace bots", t::humansReplaceBotsMidMatch);
        t.run("bot navigation", t::botNavigation);
        t.run("bots play to win", t::botsPlayToWin);
        t.run("grenade blast", t::grenadeBlast);
        t.run("grenade walls, teams, shields", t::grenadeWallsTeamsShields);
        t.run("grenade limits and armory", t::grenadeLimits);
        t.run("grenades in the lobby", t::grenadesInTheLobby);
        t.run("grenade protocol", t::grenadeProtocol);
        System.out.println("\nPassed: " + passed + " | Failed: " + failed);
        if (failed > 0) System.exit(1);
    }
}

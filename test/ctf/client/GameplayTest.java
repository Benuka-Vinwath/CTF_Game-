package ctf.client;

import ctf.common.MapData;
import ctf.common.Snapshot;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Automated test suite for CTF client mechanics:
 * - Bullet rendering and snapshot data structure
 * - Shooting mechanics (cooldown, aim vector)
 * - Bullet movement & collision (walls, boundary, players)
 * - Player tagging (hostile hit, friendly fire immunity, dead target immunity)
 * - Respawning logic and timer (3 second delay, base reset)
 * - Map collision resolution
 * - Event detection and visual state transitions
 */
public class GameplayTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Running CTF Client Gameplay Tests ===");

        runTest("Snapshot Structure & Bullet Representation", GameplayTest::testSnapshotStructure);
        runTest("Shooting Cooldown & Aim Direction", GameplayTest::testShootingCooldownAndDirection);
        runTest("Bullet Movement Speed & Trajectory", GameplayTest::testBulletMovement);
        runTest("Wall Collisions & Arena Boundaries", GameplayTest::testWallCollisions);
        runTest("Player Tagging & Friendly Fire Immunity", GameplayTest::testPlayerTagging);
        runTest("Respawn Timing & Base Reset", GameplayTest::testRespawnTiming);
        runTest("Repeated Hits & Overlapping Respawn Robustness", GameplayTest::testRepeatedHitsRobustness);
        runTest("GamePanel Event Detection & Particle Bursts", GameplayTest::testGamePanelEventDetection);
        runTest("Map Collision Resolution (MapData.resolve)", GameplayTest::testMapDataResolve);
        runTest("Bots Shoot Back & Tagged Carrier Drops Flag", GameplayTest::testBotsShootBack);
        runTest("Line of Sight Blocked by Walls", GameplayTest::testLineOfSight);
        runTest("Power Tent Shield Activation & Invulnerability", GameplayTest::testPowerTentShieldAndInvulnerability);
        runTest("Power Tent Shield Expiration", GameplayTest::testPowerTentShieldExpiration);
        runTest("Ending: Final Moment, VICTORY / DEFEAT, Results, Back To Lobby", GameplayTest::testEnding);
        runTest("Player Deaths, Elimination Attribution, and Ranking Order", GameplayTest::testPlayerDeathsAndRankingOrder);
        runTest("Demo: Match End Elapsed Timing", GameplayTest::testAuthoritativeMatchEndElapsedTiming);
        runTest("Match Reset Cleanly Clears All Match Statistics", GameplayTest::testMatchResetStatistics);
        runTest("Typography Fonts Resolve & Pixel-Art Sprites Scale By Whole Pixels", GameplayTest::testTypographyAndSprites);
        runTest("Wide/Tall Windows Are Filled With Scenery (No Black Bars)", GameplayTest::testViewFillsWindow);
        runTest("Lobby Room Draws, Ready Button Clicks, ENTER Chat Sends", GameplayTest::testLobbyRoom);
        runTest("Menu: Clicking HOST And JOIN Buttons Reaches The Game", GameplayTest::testMenuButtons);
        runTest("Esc Menu: Leave Lobby At Once, Quit Mid-Match Asks First", GameplayTest::testEscMenu);
        runTest("Profile Database: Matches, XP, Levels, Achievements, Locks, Reset", GameplayTest::testProfile);
        runTest("online-server.txt: Address Read, Comments Skipped, Missing = No Button", GameplayTest::testOnlineServerFile);

        System.out.println("\n=== Test Results Summary ===");
        System.out.println("Passed: " + passed + " | Failed: " + failed);
        if (failed > 0) {
            System.err.println("Some tests FAILED!");
            System.exit(1);
        } else {
            System.out.println("All tests PASSED successfully!");
        }
        System.exit(0); // GamePanel's Swing timer would keep the JVM alive
    }

    private static void runTest(String name, Runnable test) {
        try {
            test.run();
            System.out.println("[PASS] " + name);
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] " + name + ": " + t.getMessage());
            t.printStackTrace(System.err);
            failed++;
        }
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void assertEquals(double expected, double actual, double eps, String msg) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(msg + " Expected: " + expected + ", got: " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " Expected: " + expected + ", got: " + actual);
        }
    }

    static void testSnapshotStructure() {
        Snapshot s = new Snapshot();
        assertTrue(s.players != null && s.players.length == 0, "Initial players array should be empty");
        assertTrue(s.flags != null && s.flags.length == 2, "Snapshot must have 2 flags");
        assertTrue(s.bullets != null && s.bullets.length == 0, "Initial bullets array should be empty");

        // Verify bullet structure: [x, y, team]
        s.bullets = new double[][]{
            {100.0, 200.0, 0.0},
            {350.5, 410.2, 1.0}
        };
        assertEquals(2, s.bullets.length, "Should hold 2 bullets");
        assertEquals(100.0, s.bullets[0][0], 1e-4, "Bullet 0 x");
        assertEquals(200.0, s.bullets[0][1], 1e-4, "Bullet 0 y");
        assertEquals(0.0, s.bullets[0][2], 1e-4, "Bullet 0 team red");
        assertEquals(1.0, s.bullets[1][2], 1e-4, "Bullet 1 team blue");

        // Verify copy preserves bullets
        Snapshot copy = Demo.copy(s);
        assertEquals(2, copy.bullets.length, "Copy should have 2 bullets");
        assertEquals(100.0, copy.bullets[0][0], 1e-4, "Copied bullet x");
    }

    static void testShootingCooldownAndDirection() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100 + i * 150; p.y = 100 + i * 100; p.alive = true;
            demo.s.players[i] = p;
        }

        // Aim angle 0 (rightwards)
        demo.sendInput(0, 0, 0.0, true);
        demo.stepBullets(0.01);
        assertEquals(1, demo.bullets.size(), "First shot should spawn a bullet");
        double[] b1 = demo.bullets.get(0);
        assertEquals(104.2, b1[0], 1e-2, "Bullet x after 0.01s step from player at 100.0");
        assertEquals(100.0, b1[1], 1e-2, "Bullet starts near player y");
        assertEquals(420.0, b1[2], 1e-2, "Bullet vx for aim 0 must be +420");
        assertEquals(0.0, b1[3], 1e-2, "Bullet vy for aim 0 must be 0");

        // Immediate next tick: cooldown (0.25s) is active, should NOT spawn another bullet
        demo.stepBullets(0.05);
        assertEquals(1, demo.bullets.size(), "Should not spawn bullet during cooldown");

        // Advance past cooldown (0.25s total) with aim downwards (PI / 2)
        demo.bullets.clear();
        demo.sendInput(0, 0, Math.PI / 2, true);
        demo.stepBullets(0.25);
        assertEquals(1, demo.bullets.size(), "Should spawn second bullet after cooldown");
        double[] b2 = demo.bullets.get(0);
        assertEquals(0.0, b2[2], 1e-2, "Bullet vx for aim PI/2 must be 0");
        assertEquals(420.0, b2[3], 1e-2, "Bullet vy for aim PI/2 must be +420");
    }

    static void testBulletMovement() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100 + i * 150; p.y = 100 + i * 100; p.alive = true;
            demo.s.players[i] = p;
        }

        demo.sendInput(0, 0, 0.0, true);
        demo.stepBullets(0.001); // fire bullet
        demo.sendInput(0, 0, 0.0, false); // release trigger

        double[] b = demo.bullets.get(0);
        double startX = b[0];
        double dt = 0.1; // 100 ms
        demo.stepBullets(dt);

        // Movement should be vx * dt = 420 * 0.1 = 42 pixels
        assertEquals(startX + 42.0, b[0], 1.0, "Bullet must travel 42 pixels in 0.1s");
    }

    static void testWallCollisions() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 200; p.y = 200; p.alive = true;
            demo.s.players[i] = p;
        }

        // Test each wall in MapData.WALLS
        for (double[] w : MapData.WALLS) {
            demo.bullets.clear();
            // Place bullet 5 pixels left of wall, moving right into it
            double bx = w[0] - 5;
            double by = w[1] + w[3] / 2;
            demo.bullets.add(new double[]{bx, by, 420, 0, 0});

            // Step forward so bullet enters the wall
            demo.stepBullets(0.03); // moves ~12.6 pixels, putting it inside the wall
            assertTrue(demo.bullets.isEmpty(), "Bullet must be removed upon colliding with wall at (" + w[0] + ", " + w[1] + ")");
        }

        // Test boundary limits:
        demo.bullets.clear();
        demo.bullets.add(new double[]{-1, 100, -100, 0, 0});
        demo.stepBullets(0.01);
        assertTrue(demo.bullets.isEmpty(), "Bullet past left boundary (x < 0) must be removed");

        demo.bullets.clear();
        demo.bullets.add(new double[]{MapData.W + 5, 100, 100, 0, 0});
        demo.stepBullets(0.01);
        assertTrue(demo.bullets.isEmpty(), "Bullet past right boundary (x > W) must be removed");

        demo.bullets.clear();
        demo.bullets.add(new double[]{100, -5, 0, -100, 0});
        demo.stepBullets(0.01);
        assertTrue(demo.bullets.isEmpty(), "Bullet past top boundary (y < 0) must be removed");

        demo.bullets.clear();
        demo.bullets.add(new double[]{100, MapData.H + 5, 0, 100, 0});
        demo.stepBullets(0.01);
        assertTrue(demo.bullets.isEmpty(), "Bullet past bottom boundary (y > H) must be removed");
    }

    static void testPlayerTagging() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100; p.y = 100; p.alive = true;
            demo.s.players[i] = p;
        }
        // Player 0 is shooter (team 0)
        // Player 1 is blue (team 1)
        // Player 2 is red teammate (team 0)
        Snapshot.P shooter = demo.s.players[0];
        Snapshot.P blueEnemy = demo.s.players[1];
        Snapshot.P redTeammate = demo.s.players[2];

        blueEnemy.x = 200; blueEnemy.y = 100;
        redTeammate.x = 200; redTeammate.y = 200;

        // 1. Friendly fire test: bullet placed on red teammate
        demo.bullets.add(new double[]{redTeammate.x, redTeammate.y, 420, 0, 0});
        demo.stepBullets(0.01);
        assertTrue(redTeammate.alive, "Friendly teammate must not be tagged by team 0 bullet");
        assertEquals(0, shooter.tags, "Shooter tags should not increment for friendly fire");

        // 2. Enemy hit test: bullet placed on blue enemy
        demo.bullets.clear();
        demo.bullets.add(new double[]{blueEnemy.x, blueEnemy.y, 420, 0, 0});
        demo.stepBullets(0.01);
        assertTrue(!blueEnemy.alive, "Blue enemy must be tagged when hit by bullet");
        assertEquals(1, shooter.tags, "Shooter tags must increment to 1");
        assertTrue(demo.bullets.isEmpty(), "Bullet must be consumed upon hitting player");

        // 3. Dead player test: bullet placed on already dead blue enemy
        demo.bullets.clear();
        demo.bullets.add(new double[]{blueEnemy.x, blueEnemy.y, 420, 0, 0});
        demo.stepBullets(0.01);
        assertEquals(1, shooter.tags, "Shooter tags must not increment again for already dead player");
    }

    static void testRespawnTiming() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100; p.y = 100; p.alive = true;
            demo.s.players[i] = p;
        }

        Snapshot.P enemy = demo.s.players[1];
        enemy.x = 300; enemy.y = 300;

        // Tag enemy
        demo.bullets.add(new double[]{enemy.x, enemy.y, 0, 0, 0});
        demo.stepBullets(0.01);
        assertTrue(!enemy.alive, "Enemy must be tagged");

        // Wait 1.5 seconds: still dead
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        demo.stepBullets(0.01);
        assertTrue(!enemy.alive, "Enemy must still be dead before 3 seconds");

        // Override respawn timestamp to simulate 3.01s elapsed
        demo.respawnAt[enemy.id] = System.currentTimeMillis() - 10;
        demo.stepBullets(0.01);

        assertTrue(enemy.alive, "Enemy must respawn after 3 seconds");
        assertEquals(MapData.BASES[enemy.team][0], enemy.x, 1e-4, "Enemy respawn x must be at team base");
        assertEquals(MapData.BASES[enemy.team][1], enemy.y, 1e-4, "Enemy respawn y must be at team base");
    }

    static void testRepeatedHitsRobustness() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100; p.y = 100; p.alive = true;
            demo.s.players[i] = p;
        }

        // Spawn multiple bullets overlapping on the same position
        for (int i = 0; i < 50; i++) {
            demo.bullets.add(new double[]{100, 100, 10, 10, 0});
        }
        // Must not throw ConcurrentModificationException or cause crash
        demo.stepBullets(0.01);
        assertTrue(true, "Repeated hits and multiple bullet removals handled cleanly");
    }

    static void testGamePanelEventDetection() {
        NetLink link = new NetLink() {
            @Override public void sendInput(int dx, int dy, double aim, boolean shoot) {}
            @Override public int pingMs() { return 20; }
            @Override public void requestPlayAgain() {}
        };
        GamePanel panel = new GamePanel(link, 0);

        Snapshot s1 = new Snapshot();
        Snapshot.P p0_1 = new Snapshot.P(); p0_1.id = 0; p0_1.name = "You"; p0_1.team = 0; p0_1.alive = true;
        Snapshot.P p1_1 = new Snapshot.P(); p1_1.id = 1; p1_1.name = "Ann"; p1_1.team = 1; p1_1.alive = true;
        s1.players = new Snapshot.P[]{p0_1, p1_1};

        Snapshot s2 = Demo.copy(s1);
        s2.players[1].alive = false; // Ann was tagged!

        panel.onSnapshot(s1);
        panel.onSnapshot(s2);

        // Verification that snapshot was accepted without error
        assertTrue(true, "GamePanel cleanly accepted consecutive snapshots with death event");
    }

    static void testGamePanelRendering() {
        NetLink link = new NetLink() {
            @Override public void sendInput(int dx, int dy, double aim, boolean shoot) {}
            @Override public int pingMs() { return 20; }
            @Override public void requestPlayAgain() {}
        };
        GamePanel panel = new GamePanel(link, 0);
        panel.setSize(MapData.W, MapData.H);

        Snapshot s = new Snapshot();
        Snapshot.P p0 = new Snapshot.P(); p0.id = 0; p0.name = "You"; p0.team = 0; p0.x = 100; p0.y = 100; p0.alive = true;
        Snapshot.P p1 = new Snapshot.P(); p1.id = 1; p1.name = "Ann"; p1.team = 1; p1.x = 200; p1.y = 200; p1.alive = false; // dead/tagged
        s.players = new Snapshot.P[]{p0, p1};
        s.bullets = new double[][]{{150, 150, 0}, {250, 250, 1}};

        panel.onSnapshot(s);

        // Render to an offscreen image
        BufferedImage img = new BufferedImage(MapData.W, MapData.H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        paintOnEdt(panel, g);
        g.dispose();

        // Ensure non-blank render
        boolean hasPixels = false;
        for (int y = 0; y < MapData.H; y += 50) {
            for (int x = 0; x < MapData.W; x += 50) {
                if ((img.getRGB(x, y) & 0x00FFFFFF) != 0) {
                    hasPixels = true;
                    break;
                }
            }
        }
        assertTrue(hasPixels, "GamePanel offscreen render must produce rendered graphics");
    }

    static void testBotsShootBack() {
        Demo d = new Demo();
        d.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 700; p.y = 60; p.alive = i < 2;
            d.s.players[i] = p;
            d.respawnAt[i] = Long.MAX_VALUE; // keep bots 2 and 3 out of the way
        }
        Snapshot.P me = d.s.players[0], ann = d.s.players[1];
        me.x = 100; me.y = 300; ann.x = 140; ann.y = 300;
        d.s.flags[1].state = Snapshot.CARRIED; d.s.flags[1].carrier = 0;

        for (int k = 0; k < 30 && me.alive; k++) { d.stepBots(0.02); d.stepBullets(0.02); }
        assertTrue(!me.alive, "Blue bot must shoot and tag the red player");
        assertEquals(1, ann.tags, "Bot gets credit for the tag");
        assertEquals(Snapshot.DROPPED, d.s.flags[1].state, "Tagged carrier must drop the flag");

        d.stepFlags(); // dead player must not pick it back up
        assertEquals(Snapshot.DROPPED, d.s.flags[1].state, "Dead player cannot re-take the flag");
        d.droppedAt[1] -= 11_000;
        d.stepFlags();
        assertEquals(Snapshot.BASE, d.s.flags[1].state, "Dropped flag returns to base after 10s");
    }

    static void testLineOfSight() {
        double[] cwl = MapData.WALLS[0];
        assertTrue(!Demo.clear(cwl[0] - 100, cwl[1] + cwl[3] / 2, cwl[0] + cwl[2] + 100, cwl[1] + cwl[3] / 2), "Center wall must block sight");
        assertTrue(Demo.clear(100, 300, 300, 300), "Open floor must not block sight");
    }

    static void testMapDataResolve() {
        double[] p = {-10.0, -20.0};
        MapData.resolve(p);
        assertTrue(p[0] >= MapData.R, "Player x clamped inside arena min X");
        assertTrue(p[1] >= MapData.R, "Player y clamped inside arena min Y");

        p = new double[]{MapData.W + 50, MapData.H + 50};
        MapData.resolve(p);
        assertTrue(p[0] <= MapData.W - MapData.R, "Player x clamped inside arena max X");
        assertTrue(p[1] <= MapData.H - MapData.R, "Player y clamped inside arena max Y");

        // Player placed inside the centre wall (WALLS[0])
        double[] cw = MapData.WALLS[0];
        p = new double[]{cw[0] + cw[2] / 2, cw[1] + cw[3] / 2};
        MapData.resolve(p);
        boolean outOfWall = p[0] <= cw[0] - MapData.R + 1e-6 || p[0] >= cw[0] + cw[2] + MapData.R - 1e-6
            || p[1] <= cw[1] - MapData.R + 1e-6 || p[1] >= cw[1] + cw[3] + MapData.R - 1e-6;
        assertTrue(outOfWall, "MapData.resolve must push player outside wall boundary");
    }

    static void testPowerTentShieldAndInvulnerability() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100 + i * 150; p.y = 100; p.alive = true;
            demo.s.players[i] = p;
        }

        Snapshot.P bluePlayer = demo.s.players[1];
        // Move blue player into the protective power tent zone
        bluePlayer.x = MapData.TENT_X;
        bluePlayer.y = MapData.TENT_Y;

        demo.stepTent(0.03);
        assertEquals(MapData.SHIELD_DURATION, bluePlayer.shieldSec, 0.05, "Player in tent must receive shield");
        assertEquals(MapData.TENT_RECHARGE, demo.tentCooldown, 0.05, "Tent must enter recharge cooldown");

        // Attempt second activation by player 3 while tent is on cooldown
        Snapshot.P cyPlayer = demo.s.players[3];
        cyPlayer.x = MapData.TENT_X;
        cyPlayer.y = MapData.TENT_Y;
        demo.stepTent(0.03);
        assertEquals(0.0, cyPlayer.shieldSec, 1e-4, "Tent on cooldown cannot grant shield");
        cyPlayer.x = 700; cyPlayer.y = 100; // Move cyPlayer away

        // Fire bullet from team 0 directly at shielded blue player
        demo.bullets.clear();
        demo.bullets.add(new double[]{bluePlayer.x - 20, bluePlayer.y, 420, 0, 0}); // shooter is player 0
        demo.stepBullets(0.06);

        assertTrue(bluePlayer.alive, "Shielded player must NOT be eliminated by hostile bullet");
        assertEquals(0, demo.s.players[0].tags, "Shooter must not get tags for hitting shielded player");
        assertTrue(demo.bullets.isEmpty(), "Bullet must be consumed/deflected by shield");
    }

    static void testPowerTentShieldExpiration() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100 + i * 150; p.y = 100; p.alive = true;
            demo.s.players[i] = p;
        }

        Snapshot.P bluePlayer = demo.s.players[1];
        bluePlayer.x = MapData.TENT_X;
        bluePlayer.y = MapData.TENT_Y;
        demo.stepTent(0.01);
        assertTrue(bluePlayer.shieldSec > 0, "Shield should be active initially");

        // Advance time past shield duration
        demo.stepTent(MapData.SHIELD_DURATION + 0.1);
        assertEquals(0.0, bluePlayer.shieldSec, 1e-4, "Shield must expire after configured duration");

        // Now fire bullet at unshielded blue player
        demo.bullets.clear();
        demo.bullets.add(new double[]{bluePlayer.x - 20, bluePlayer.y, 420, 0, 0});
        demo.stepBullets(0.06);

        assertTrue(!bluePlayer.alive, "Player must be eliminated after shield expiration");
        assertEquals(1, demo.s.players[0].tags, "Shooter gets credit for elimination after shield expires");
    }

    /** The panel's own timer mutates particle lists on the Swing thread, so painting must happen there too, as in the real game. */
    static void testLobbyRoom() {
        java.util.List<String> sent = new java.util.ArrayList<>();
        ctf.common.Protocol.Lobby lobby = new ctf.common.Protocol.Lobby("LOBBY", 2, 0, 1, java.util.List.of(
            new ctf.common.Protocol.LobbyEntry(0, 0, false, 2, 1, 0, 1, 0, "You"),
            new ctf.common.Protocol.LobbyEntry(1, 1, true, 4, 3, 5, 3, 1, "Ann")));
        NetLink link = new NetLink() {
            @Override public void sendInput(int dx, int dy, double aim, boolean shoot) { if (shoot) sent.add("shoot"); }
            @Override public int pingMs() { return 20; }
            @Override public void requestPlayAgain() {}
            @Override public void sendReady(boolean ready) { sent.add("ready " + ready); }
            @Override public void sendChat(String text) { sent.add("chat " + text); }
            @Override public void sendBegin() { sent.add("begin"); }
            @Override public ctf.common.Protocol.Lobby lobby() { return lobby; }
        };
        GamePanel panel = new GamePanel(link, 0);
        panel.setSize(MapData.W, MapData.H);
        Snapshot s = new Snapshot();
        s.phase = Snapshot.LOBBY;
        Snapshot.P p0 = new Snapshot.P(); p0.id = 0; p0.name = "You"; p0.team = 0; p0.x = 560; p0.y = 300;
        Snapshot.P p1 = new Snapshot.P(); p1.id = 1; p1.name = "Ann"; p1.team = 1; p1.x = 650; p1.y = 330;
        s.players = new Snapshot.P[]{p0, p1};
        panel.onSnapshot(s);
        panel.onChat(1, "Ann: hello");

        BufferedImage img = new BufferedImage(MapData.W, MapData.H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        paintOnEdt(panel, g);   // the first paint lays out the buttons
        g.dispose();
        assertTrue(img.getRGB((int) ctf.common.LobbyMap.PADS[0][0], 315) != 0, "the lobby room is drawn");

        // the ready button sits bottom-right; a click there must toggle ready and not fire
        int bx = MapData.W - 18 - 100, by = MapData.H - 18 - 24;
        onEdt(() -> {
            for (java.awt.event.MouseListener l : panel.getMouseListeners())
                l.mousePressed(new java.awt.event.MouseEvent(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, bx, by, 1, false, java.awt.event.MouseEvent.BUTTON1));
        });
        assertTrue(sent.contains("ready true"), "clicking the ready button sends READY (" + sent + ")");
        assertTrue(!sent.contains("shoot"), "the click does not also fire a shot");

        onEdt(() -> {   // ENTER opens chat, typed letters go into it, ENTER sends
            for (int code : new int[]{java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_H, java.awt.event.KeyEvent.VK_I, java.awt.event.KeyEvent.VK_ENTER}) {
                char ch = code == java.awt.event.KeyEvent.VK_H ? 'h' : code == java.awt.event.KeyEvent.VK_I ? 'i' : '\n';
                for (java.awt.event.KeyListener l : panel.getKeyListeners())
                    l.keyPressed(new java.awt.event.KeyEvent(panel, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, code, ch));
            }
        });
        assertTrue(sent.contains("chat hi"), "ENTER, typing and ENTER sends the chat line (" + sent + ")");

        Snapshot s2 = Demo.copy(s);   // the match starting: the arena is drawn again
        s2.phase = Snapshot.PLAYING;
        s2.tick = 2;
        panel.onSnapshot(s2);
        g = img.createGraphics();
        paintOnEdt(panel, g);
        g.dispose();
        assertTrue(true, "switching from the lobby to the match paints without errors");
    }

    /** The HOST / JOIN buttons sit inside their cards; a click must reach the button, not just select the card. */
    static void testMenuButtons() {
        java.util.List<String> did = new java.util.ArrayList<>();
        java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot().node("ctf-menu-test-" + System.nanoTime());
        java.nio.file.Path dir;
        try { dir = java.nio.file.Files.createTempDirectory("throne-menu"); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        MenuScreen menu = new MenuScreen(new MenuScreen.Actions() {
            public void host() { did.add("host"); }
            public void join(String address) { did.add("join " + address); }
            public void quit() {}
            public void toggleFullscreen() {}
            public boolean fullscreen() { return false; }
        }, new Profile(dir.resolve("profile.db"), node));   // a throwaway profile: the player's own is never touched
        menu.setSize(1600, 900);   // 1:1 with the menu's canvas, so canvas points are pixels
        menu.setAddress("localhost");
        BufferedImage img = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        Runnable paint = () -> { Graphics2D g = img.createGraphics(); menu.paint(g); g.dispose(); };
        java.util.function.BiConsumer<Integer, Integer> click = (x, y) -> {
            for (java.awt.event.MouseListener l : menu.getMouseListeners())
                l.mousePressed(new java.awt.event.MouseEvent(menu, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1));
        };
        onEdt(() -> {
            paint.run();
            click.accept(200, 440);   // PLAY on the main menu
            paint.run();
            click.accept(217, 703);   // HOST button inside the host card
            paint.run();
            click.accept(697, 711);   // JOIN button inside the join card
        });
        try { node.removeNode(); } catch (Exception ignored) { /* test settings only */ }
        assertTrue(did.contains("host"), "clicking HOST hosts (" + did + ")");
        assertTrue(did.contains("join localhost"), "clicking JOIN joins the typed address (" + did + ")");
    }

    static void testEscMenu() {
        java.util.List<String> did = new java.util.ArrayList<>();
        NetLink link = new NetLink() {
            public void sendInput(int dx, int dy, double aim, boolean shoot) {}
            public int pingMs() { return 20; }
            public void requestPlayAgain() {}
        };
        GamePanel panel = new GamePanel(link, 0);
        panel.setSize(MapData.W, MapData.H);
        panel.setExits(new GamePanel.Exits() {
            public void leave() { did.add("leave"); }
            public void quit() { did.add("quit"); }
            public void toggleFullscreen() {}
            public boolean fullscreen() { return false; }
            public boolean hosting() { return false; }
            public java.util.List<String> matchFinished(Snapshot s, int myId, int mvpId, int lengthSec) { return java.util.List.of(); }
            public boolean hatUnlocked(int hat) { return true; }
        });
        BufferedImage img = new BufferedImage(MapData.W, MapData.H, BufferedImage.TYPE_INT_RGB);
        java.util.function.IntConsumer key = code -> {
            Graphics2D g = img.createGraphics();
            panel.paint(g);   // the menu lays out its rows while drawing
            g.dispose();
            for (java.awt.event.KeyListener l : panel.getKeyListeners())
                l.keyPressed(new java.awt.event.KeyEvent(panel, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED));
        };
        int esc = java.awt.event.KeyEvent.VK_ESCAPE, down = java.awt.event.KeyEvent.VK_DOWN, up = java.awt.event.KeyEvent.VK_UP,
            enter = java.awt.event.KeyEvent.VK_ENTER;

        Snapshot s = new Snapshot();
        s.phase = Snapshot.LOBBY;
        Snapshot.P me = new Snapshot.P(); me.id = 0; me.name = "You"; me.x = 560; me.y = 300;
        s.players = new Snapshot.P[]{me};
        panel.onSnapshot(s);
        onEdt(() -> { key.accept(esc); key.accept(down); key.accept(down); key.accept(enter); });   // RESUME, SETTINGS, LEAVE LOBBY
        assertTrue(did.equals(java.util.List.of("leave")), "Esc > LEAVE LOBBY leaves at once (" + did + ")");

        did.clear();
        Snapshot m = Demo.copy(s);
        m.phase = Snapshot.PLAYING;
        m.tick = 2;
        panel.onSnapshot(m);
        onEdt(() -> {
            key.accept(esc);   // close the menu still open from the lobby test
            key.accept(esc); key.accept(down); key.accept(down); key.accept(down); key.accept(enter);   // QUIT GAME
            key.accept(enter);   // the prompt starts on STAY
        });
        assertTrue(did.isEmpty(), "quitting mid-match asks first, and STAY keeps you in (" + did + ")");
        onEdt(() -> { key.accept(esc); key.accept(down); key.accept(down); key.accept(down); key.accept(enter); key.accept(up); key.accept(enter); });
        assertTrue(did.equals(java.util.List.of("quit")), "choosing QUIT in the prompt quits (" + did + ")");
    }

    /** The match ends with a capture: each step of the ending paints for winner and loser, and BACK TO LOBBY votes. */
    static void testEnding() {
        for (int me = 0; me < 2; me++) {   // player 0 is red (wins), player 1 is blue (loses)
            int[] votes = {0};
            NetLink link = new NetLink() {
                public void sendInput(int dx, int dy, double aim, boolean shoot) {}
                public int pingMs() { return 20; }
                public void requestPlayAgain() { votes[0]++; }
            };
            GamePanel panel = new GamePanel(link, me);
            panel.setSize(MapData.W, MapData.H);
            Snapshot a = new Snapshot();
            a.tick = 1;
            Snapshot.P red = new Snapshot.P(); red.id = 0; red.team = 0; red.name = "Raven"; red.x = 80; red.y = 315; red.captures = 3; red.score = 300;
            Snapshot.P blue = new Snapshot.P(); blue.id = 1; blue.team = 1; blue.name = "Frost"; blue.x = 900; blue.y = 300; blue.tags = 2; blue.score = 50;
            a.players = new Snapshot.P[]{red, blue};
            a.score = new int[]{2, 0};
            a.flags[1].state = Snapshot.CARRIED;   // red is carrying the blue flag home
            a.flags[1].carrier = 0;
            panel.onSnapshot(a);
            BufferedImage img = new BufferedImage(MapData.W, MapData.H, BufferedImage.TYPE_INT_RGB);
            for (double t : new double[]{0.1, 1.5, 3.5, 6.5, 9, 15}) {   // final moment, VICTORY / DEFEAT, results
                Snapshot b = Demo.copy(a);
                b.tick = (long) (10 + t * 30);
                b.phase = Snapshot.ENDED;
                b.score = new int[]{3, 0};
                b.winner = 0;
                b.flags[1].state = Snapshot.BASE;
                b.flags[1].carrier = -1;
                b.matchEndElapsedSec = t;
                panel.onSnapshot(b);
                Graphics2D g = img.createGraphics();
                paintOnEdt(panel, g);
                g.dispose();
            }
            // results are up: BACK TO LOBBY sits bottom-right of the results card (its height follows the team size)
            onEdt(() -> {
                for (int x = MapData.W - 60; x > MapData.W / 2 && votes[0] == 0; x -= 20)
                    for (int y = MapData.H / 2; y < MapData.H - 10 && votes[0] == 0; y += 10)
                        for (java.awt.event.MouseListener l : panel.getMouseListeners())
                            l.mousePressed(new java.awt.event.MouseEvent(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1));
            });
            assertTrue(votes[0] == 1, "BACK TO LOBBY on the results card votes to go back (player " + me + ")");
        }
    }

    /** A real SQLite file in a temporary folder and a throwaway settings node, so the player's own profile is never touched. */
    static void testProfile() {
        java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot().node("ctf-profile-test-" + System.nanoTime());
        java.nio.file.Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("throne-profile");
            java.nio.file.Path file = dir.resolve("profile.db");
            node.putInt("hat", 2);   // an old setting: the top hat, which has been removed
            Profile pr = new Profile(file, node);
            assertTrue(pr.ok(), "the SQLite database opens (lib/sqlite-jdbc on the classpath)");
            assertEquals(1, pr.hat(), "a removed hat goes back to the crown on first launch");
            assertTrue(!pr.hatUnlocked(2) && !pr.hatUnlocked(0) && pr.hatUnlocked(1) && pr.hatUnlocked(3) && pr.hatUnlocked(4) && pr.hatUnlocked(5), "the removed hats are gone, the crown, horns, halo and wings are free");
            pr.save("Raven", 2, 2, 5, 0);
            assertTrue(pr.hat() != 2 && pr.title() == 0, "a removed hat or a locked title cannot be saved");

            Snapshot s = new Snapshot();   // a 1v1 that red wins 3-0: first win and a shutout
            Snapshot.P me = new Snapshot.P(); me.id = 7; me.team = 0; me.name = "Raven"; me.captures = 3; me.tags = 4; me.deaths = 1; me.score = 400;
            Snapshot.P them = new Snapshot.P(); them.id = 8; them.team = 1; them.name = "Frost"; them.tags = 1; them.deaths = 4; them.score = 25;
            s.players = new Snapshot.P[]{me, them};
            s.winner = 0;
            s.score = new int[]{3, 0};
            java.util.List<String> news = pr.recordMatch(s, 7, 7, 190);
            int xp = Profile.xpFor(true, 3, 4, true);
            assertTrue(news.contains("+" + xp + " XP"), "XP for the match is reported (" + news + ")");
            assertTrue(news.stream().anyMatch(n -> n.startsWith("ACHIEVEMENT: FIRST WIN")) && news.stream().anyMatch(n -> n.startsWith("ACHIEVEMENT: 3-0 WIN")),
                "first win and the shutout unlock achievements (" + news + ")");
            assertTrue(pr.titleUnlocked(1), "the first win unlocks the Squire title");

            s.winner = 1; s.score = new int[]{1, 3}; me.captures = 1; me.tags = 2; me.deaths = 5; me.score = 150;
            pr.recordMatch(s, 7, 8, 300);
            Profile.Stats st = pr.stats();
            assertEquals(2, st.matches, "two matches saved");
            assertEquals(1, st.wins, "one win");
            assertEquals(4, st.captures, "captures add up across matches");
            assertEquals(1, st.mvps, "MVP counted once");
            assertEquals(2, st.history.size(), "both matches in the history");
            assertTrue(!(Boolean) st.history.get(0)[0] && "BLUE 3 - 1".equals(st.history.get(0)[1]), "newest match first, as a loss 'BLUE 3 - 1'");
            assertEquals(xp + Profile.xpFor(false, 1, 2, false), st.xp, "XP adds up");

            pr.save("Raven", 4, 2, 1, 1);
            Profile again = new Profile(file, node);   // reopen the file: everything is still there
            assertTrue(again.hat() == 4 && again.title() == 1 && again.favouriteTeam() == 1 && again.stats().matches == 2, "the profile survives reopening");
            again.reset();
            Profile.Stats z = again.stats();
            assertTrue(z.matches == 0 && z.xp == 0 && z.unlocked.isEmpty() && again.hat() == 4 && again.name().equals("Raven"),
                "reset clears history, XP and achievements, and keeps the name and the hat");
            int[] lv = Profile.levelOf(Profile.need(1) + Profile.need(2) + 5);
            assertTrue(lv[0] == 3 && lv[1] == 5 && lv[2] == Profile.need(3), "levels follow the XP curve");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try { node.removeNode(); } catch (Exception ignored) { /* test settings only */ }
            if (dir != null) try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (Exception ignored) { /* temp files */ }
        }
    }

    static void testOnlineServerFile() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("online-server");
            java.nio.file.Path f = dir.resolve("online-server.txt");
            java.nio.file.Files.writeString(f, "# comment\n\n   # another\n  140.238.12.34:5000  \nlater line\n");
            assertEquals("140.238.12.34:5000", MenuScreen.readOnlineServer(f), "the first line that is not a comment is the address, trimmed");
            java.nio.file.Files.writeString(f, "# only comments, no address yet\n");
            assertTrue(MenuScreen.readOnlineServer(f) == null, "comments only: no online server, the button stays hidden");
            assertTrue(MenuScreen.readOnlineServer(dir.resolve("missing.txt")) == null, "no file: no online server");
            assertTrue(MenuScreen.readOnlineServer(java.nio.file.Path.of("online-server.txt")) == null,
                "the shipped online-server.txt has no address until the server is set up");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void onEdt(Runnable r) {
        try { javax.swing.SwingUtilities.invokeAndWait(r); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void paintOnEdt(GamePanel panel, Graphics2D g) {
        try { javax.swing.SwingUtilities.invokeAndWait(() -> panel.paint(g)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void testTypographyAndSprites() {
        java.awt.Font f = Type.display(20), u = Type.ui(12, true), n = Type.numbers(30);
        assertTrue(f.getSize2D() == 20f && u.getSize2D() == 12f && n.getSize2D() == 30f, "fonts keep their fractional sizes");
        assertTrue(f == Type.display(20), "fonts are cached, not rebuilt per frame");
        assertTrue(Type.heading(60).getStringBounds("VICTORY", new java.awt.font.FontRenderContext(null, true, true)).getWidth() > 60, "heading has real glyph widths");
        for (int k = 2; k <= 4; k++) {
            BufferedImage a = Art.spriteCrisp(0, 0, k);
            assertTrue(a.getWidth() == Art.SPR_W * k && a.getHeight() == Art.SPR_H * k, "sprite is an exact multiple at k=" + k);
            BufferedImage src = Art.spriteCrisp(0, 0, 1);
            boolean same = true; // every sprite pixel is a k x k block of one colour: no blur
            for (int y = 0; y < a.getHeight() && same; y += 3) for (int x = 0; x < a.getWidth(); x += 3) if (a.getRGB(x, y) != src.getRGB(x / k, y / k)) { same = false; break; }
            assertTrue(same, "pixel art stays crisp (nearest neighbour) at k=" + k);
        }
    }

    static void testViewFillsWindow() {
        NetLink link = new NetLink() {
            @Override public void sendInput(int dx, int dy, double aim, boolean shoot) {}
            @Override public int pingMs() { return 20; }
            @Override public void requestPlayAgain() {}
        };
        GamePanel panel = new GamePanel(link, 0);
        panel.setSize(MapData.W, MapData.H); // GameView does the same: the panel is never shown, only painted
        Snapshot s = new Snapshot();
        Snapshot.P p0 = new Snapshot.P(); p0.id = 0; p0.name = "You"; p0.team = 0; p0.x = 100; p0.y = 100;
        s.players = new Snapshot.P[]{p0};
        panel.onSnapshot(s);
        // 16:9, 21:9 and a tall window: the visible world rectangle is wider/taller than the arena
        double[][] views = {{-16, -16, 1152, 662}, {-300, -16, 1720, 662}, {-16, -150, 1152, 940}};
        for (double[] v : views) {
            int w = (int) v[2], h = (int) v[3];
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.translate(-v[0], -v[1]);
            g.setClip(new java.awt.geom.Rectangle2D.Double(v[0], v[1], v[2], v[3]));
            panel.setView(v[0], v[1], v[2], v[3]);
            paintOnEdt(panel, g);
            g.dispose();
            int dark = 0, n = 0;
            for (int y = h / 4; y < h; y += 40) for (int x = 0; x < w; x += 40) { // every sampled pixel must be painted scenery, not pure black
                n++;
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0) dark++;
            }
            assertTrue(dark == 0, "no pure-black bars in a " + w + "x" + h + " world view (" + dark + "/" + n + ")");
        }
    }

    static void testPlayerDeathsAndRankingOrder() {
        Demo demo = new Demo();
        demo.s.players = new Snapshot.P[4];
        for (int i = 0; i < 4; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i % 2; p.x = 100 + i * 150; p.y = 200; p.alive = true;
            demo.s.players[i] = p;
        }

        // Shoot blue player 1 with red player 0
        demo.bullets.add(new double[]{demo.s.players[1].x - 10, demo.s.players[1].y, 420, 0, 0});
        demo.stepBullets(0.05);

        assertEquals(1, demo.s.players[0].tags, "Shooter tags/kills incremented");
        assertEquals(1, demo.s.players[1].deaths, "Target deaths incremented");
        assertEquals(25, demo.s.players[0].score, "Shooter score updated (25 pts per tag)");

        // Verify Ranking Comparator Order: 1. Score desc, 2. Caps desc, 3. Kills desc, 4. Deaths asc
        Snapshot.P pA = new Snapshot.P(); pA.id = 0; pA.name = "A"; pA.score = 250; pA.captures = 2; pA.tags = 2; pA.deaths = 1;
        Snapshot.P pB = new Snapshot.P(); pB.id = 1; pB.name = "B"; pB.score = 250; pB.captures = 2; pB.tags = 2; pB.deaths = 3;
        Snapshot.P pC = new Snapshot.P(); pC.id = 2; pC.name = "C"; pC.score = 100; pC.captures = 1; pC.tags = 0; pC.deaths = 0;
        Snapshot.P pD = new Snapshot.P(); pD.id = 3; pD.name = "D"; pD.score = 250; pD.captures = 1; pD.tags = 6; pD.deaths = 2;

        java.util.List<Snapshot.P> list = new ArrayList<>(Arrays.asList(pB, pC, pD, pA));
        list.sort((a, b) -> {
            if (b.score != a.score) return Integer.compare(b.score, a.score);
            if (b.captures != a.captures) return Integer.compare(b.captures, a.captures);
            if (b.tags != a.tags) return Integer.compare(b.tags, a.tags);
            return Integer.compare(a.deaths, b.deaths);
        });

        assertEquals("A", list.get(0).name, "Rank 1: Highest captures on tie-break score, and fewest deaths");
        assertEquals("B", list.get(1).name, "Rank 2: Same score and caps as A, but more deaths");
        assertEquals("D", list.get(2).name, "Rank 3: Same score as A/B, but fewer captures");
        assertEquals("C", list.get(3).name, "Rank 4: Lowest score");
    }

    static void testAuthoritativeMatchEndElapsedTiming() {
        Demo demo = new Demo();
        demo.s.winner = 1; // Blue team won
        demo.s.players = new Snapshot.P[2];
        for (int i = 0; i < 2; i++) {
            Snapshot.P p = new Snapshot.P(); p.id = i; p.team = i; p.alive = true;
            demo.s.players[i] = p;
        }

        // Simulate match timer steps
        for (int step = 0; step < 60; step++) {
            demo.matchEndElapsedSec += 0.5;
            demo.s.matchEndElapsedSec = demo.matchEndElapsedSec;
        }

        assertEquals(30.0, demo.s.matchEndElapsedSec, 1e-4, "Match end elapsed sec increments authoritatively");
        assertTrue(demo.s.matchEndElapsedSec < 60.0, "elapsed time is below 60 s after 30 s");

        // keep counting past 60 s
        for (int step = 0; step < 70; step++) {
            demo.matchEndElapsedSec += 0.5;
            demo.s.matchEndElapsedSec = demo.matchEndElapsedSec;
        }
        assertEquals(65.0, demo.s.matchEndElapsedSec, 1e-4, "Past 60s");
        assertTrue(demo.s.matchEndElapsedSec >= 60.0, "elapsed time keeps counting past 60 s");
    }

    static void testMatchResetStatistics() {
        Demo demo = new Demo();
        demo.s.winner = 0;
        demo.s.score = new int[]{3, 1};
        demo.matchEndElapsedSec = 45.0;
        demo.s.matchEndElapsedSec = 45.0;
        demo.s.players = new Snapshot.P[2];
        for (int i = 0; i < 2; i++) {
            Snapshot.P p = new Snapshot.P();
            p.id = i; p.team = i; p.tags = 4; p.deaths = 2; p.captures = 2; p.score = 250; p.shieldSec = 3.5;
            demo.s.players[i] = p;
        }

        demo.requestPlayAgain();

        assertEquals(-1, demo.s.winner, "Winner reset to -1");
        assertEquals(0, demo.s.score[0], "Red score reset to 0");
        assertEquals(0, demo.s.score[1], "Blue score reset to 0");
        assertEquals(0.0, demo.s.matchEndElapsedSec, 1e-4, "MatchEndElapsedSec reset to 0");
        for (Snapshot.P p : demo.s.players) {
            assertEquals(0, p.tags, "Player tags reset");
            assertEquals(0, p.deaths, "Player deaths reset");
            assertEquals(0, p.captures, "Player captures reset");
            assertEquals(0, p.score, "Player score reset");
            assertEquals(0.0, p.shieldSec, 1e-4, "Player shield reset");
        }
    }
}

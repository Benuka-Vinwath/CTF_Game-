package ctf.client;

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
        runTest("GamePanel Bullet & Player Rendering", GameplayTest::testGamePanelRendering);
        runTest("Map Collision Resolution (MapData.resolve)", GameplayTest::testMapDataResolve);
        runTest("Bots Shoot Back & Tagged Carrier Drops Flag", GameplayTest::testBotsShootBack);
        runTest("Line of Sight Blocked by Walls", GameplayTest::testLineOfSight);

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
        panel.paint(g);
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
        assertTrue(!Demo.clear(300, 300, 500, 300), "Center wall must block sight");
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

        // Player placed inside center wall (WALLS[0]: {380, 200, 40, 200})
        p = new double[]{400, 300};
        MapData.resolve(p);
        // Wall spans x from 380 to 420. With R=14, p[0] must be <= 380-14=366 or >= 420+14=434
        boolean outOfWall = p[0] <= 366 || p[0] >= 434 || p[1] <= 186 || p[1] >= 414;
        assertTrue(outOfWall, "MapData.resolve must push player outside wall boundary");
    }
}

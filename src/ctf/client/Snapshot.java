package ctf.client;

/** One server state update. Part C's Protocol.decode() should produce this; call GamePanel.onSnapshot(). */
public final class Snapshot {
    public static final int BASE = 0, CARRIED = 1, DROPPED = 2;

    public static final class P {
        public int id, team, pingMs, tags, captures;
        public int deaths;                          // times eliminated
        public int score;                           // individual match score (captures * 100 + tags * 25)
        public String name = "";
        public double x, y;
        public double aim; // radians, where the gun points
        public boolean alive = true;
        public double shieldSec; // remaining invulnerability seconds
    }

    public static final class F {
        public int state = BASE, carrier = -1; // carrier = player id when CARRIED
        public double x, y;                     // used when BASE or DROPPED
    }

    public P[] players = new P[0];
    public F[] flags = {new F(), new F()};      // index = team that owns the flag
    public double[][] bullets = new double[0][]; // x, y, team
    public int[] score = new int[2];
    public int timeLeftSec;
    public int winner = -1;                     // -1 = round running
    public double tentCooldown;                 // remaining cooldown before tent can be used again
    public double matchEndElapsedSec;          // authoritative seconds elapsed since match was won

    long arrivedNs; // set by GamePanel on receipt, used for interpolation
}

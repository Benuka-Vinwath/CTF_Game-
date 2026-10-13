package ctf.server;

import java.net.InetSocketAddress;

/** One connected player. Fields are only read or written while holding the GameState lock. */
public class Player {
    public final int id;
    public final String name;
    public final int token;            // secret sent over TCP; every UDP packet must carry it
    public final Conn conn;
    public int team;                   // 0 = red, 1 = blue

    public double x, y, aim;
    public boolean alive = true;
    public boolean ready = false;
    public boolean wantsAgain = false; // asked for a rematch after the match ended

    public double respawnIn;           // seconds until respawn while dead
    public double shieldSec;           // remaining invulnerability
    public double fireCd;              // seconds until the next shot is allowed

    // Latest input from UDP. The client sends intentions only, never positions.
    public int dx, dy;
    public boolean shootLatch;         // a shot was requested and has not been fired yet
    public double inputAge;            // seconds since the last input; stale input stops the player
    public int lastSeq = -1;
    public InetSocketAddress udpAddr;

    public int tags, captures, deaths;
    public int pingMs;                 // reported by the client over TCP

    public Player(int id, String name, int team, int token, Conn conn) {
        this.id = id;
        this.name = name;
        this.team = team;
        this.token = token;
        this.conn = conn;
    }

    public int score() { return captures * 100 + tags * 25; }
}

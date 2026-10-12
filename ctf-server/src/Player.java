import java.net.InetSocketAddress;

/** One connected player. Fields are only read/written while holding the GameState lock. */
public class Player {
    public final int id;
    public final String name;
    public final int token;             // secret sent over TCP; UDP packets must include it
    public final ClientHandler conn;    // this player's TCP connection
    public int team;                    // 0 = red, 1 = blue

    public double x, y;
    public boolean alive = true;
    public boolean ready = false;
    public long respawnAt = 0;

    // Latest input received over UDP (the client only sends key states, never positions)
    public boolean up, down, left, right;
    public int lastSeq = -1;            // ignore old / out-of-order UDP packets
    public InetSocketAddress udpAddr;   // where to send state snapshots

    public Player(int id, String name, int team, int token, ClientHandler conn) {
        this.id = id;
        this.name = name;
        this.team = team;
        this.token = token;
        this.conn = conn;
    }
}

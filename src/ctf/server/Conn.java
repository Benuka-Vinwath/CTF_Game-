package ctf.server;

/** Where the game logic sends TCP lines. ClientHandler implements it; tests use a fake that records the lines. */
public interface Conn {
    /** Must never block: it is called while the game lock is held. */
    void send(String line);
}

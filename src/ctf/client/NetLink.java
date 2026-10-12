package ctf.client;

/** What the client needs from Part C's networking code. */
public interface NetLink {
    /** dx, dy in {-1,0,1}; aim in radians; called ~30 times/sec. */
    void sendInput(int dx, int dy, double aim, boolean shoot);
    int pingMs();
    void requestPlayAgain();
    default void toggleLagSim() {}
    default boolean lagSimOn() { return false; }
}

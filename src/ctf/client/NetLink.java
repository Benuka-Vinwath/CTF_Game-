package ctf.client;

import ctf.common.Protocol;

/** What the client needs from Part C's networking code. */
public interface NetLink {
    /** dx, dy in {-1,0,1}; aim in radians; called ~30 times/sec. */
    void sendInput(int dx, int dy, double aim, boolean shoot);
    int pingMs();
    void requestPlayAgain();
    default void toggleLagSim() {}
    default boolean lagSimOn() { return false; }

    // lobby (the Demo has no lobby, so these do nothing by default)
    default void sendChat(String text) {}
    default void sendReady(boolean ready) {}
    /** Host only: start the countdown. The server ignores anyone else. */
    default void sendBegin() {}
    default void sendLook(int hat, int color) {}
    /** The latest LOBBY list from the server (names, teams, ready, host, hats), or null. */
    default Protocol.Lobby lobby() { return null; }
}

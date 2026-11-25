package ctf.client;

import ctf.common.Protocol;

/** What the client needs from Part C's networking code. */
public interface NetLink {
    /** dx, dy in {-1,0,1}; aim in radians; called ~30 times/sec. */
    void sendInput(int dx, int dy, double aim, boolean shoot);
    /** throwDist: a grenade throw this far toward the aim, in px; 0 = none. Offline links ignore it. */
    default void sendInput(int dx, int dy, double aim, boolean shoot, int throwDist) { sendInput(dx, dy, aim, shoot); }
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
    /** Host only: players per team, 1 (1v1) to 4 (4v4). */
    default void sendMode(int perTeam) {}
    /** The latest LOBBY list from the server (names, teams, ready, host, hats), or null. */
    default Protocol.Lobby lobby() { return null; }
    /** The room's code (K7QX) that friends type to join, or null (offline demo). */
    default String roomCode() { return null; }
    default boolean roomPublic() { return true; }
}

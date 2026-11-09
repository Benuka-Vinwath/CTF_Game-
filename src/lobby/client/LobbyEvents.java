package lobby.client;

import lobby.common.LobbyProtocol;
import java.util.List;

/**
 * Callbacks delivered by {@link LobbyNet} to the UI.
 * All methods have empty defaults — only override what you need.
 */
public interface LobbyEvents {
    default void onWelcome(int myId, int minPlayers) {}
    default void onState(List<LobbyProtocol.PlayerState> players) {}
    default void onJoin(int id, int team, String name) {}
    default void onLeave(int id) {}
    default void onMove(int id, double x, double y) {}
    default void onReadyUpdate(int id, boolean ready) {}
    default void onReadyStatus(int readyCount, int total) {}
    default void onCountdown(int seconds) {}
    /** Server says: open the CTF game at this address. */
    default void onLaunch(String host, int gamePort) {}
    default void onChat(String line) {}
    default void onError(String message) {}
    default void onDisconnected(String reason) {}
}

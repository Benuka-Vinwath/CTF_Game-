package ctf.client.net;

import ctf.common.Protocol;
import ctf.common.Snapshot;

public interface NetEvents {
    default void onWelcome(int playerId, int team) {}
    default void onLobby(Protocol.Lobby lobby) {}
    /** fromId: the sender's player id; line: "name: text". */
    default void onChat(int fromId, String line) {}
    default void onStart() {}
    default void onSnapshot(Snapshot snapshot) {}
    default void onGameOver(int winner, int redScore, int blueScore) {}
    default void onServerError(String message) {}
    default void onDisconnected(String reason) {}
}
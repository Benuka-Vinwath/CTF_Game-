package ctf.tools;

import ctf.client.net.NetClient;
import ctf.client.net.NetEvents;
import ctf.common.Protocol;
import ctf.common.Snapshot;

import java.io.IOException;
import java.util.Scanner;

public class ConsoleClient {
    static volatile int dx, dy;

    public static void main(String[] args) throws IOException {
        String name = args.length > 0 ? args[0] : "Alice";
        NetClient[] holder = new NetClient[1];
        long[] lastPrint = {0};
        NetClient client = new NetClient(new NetEvents() {
            @Override public void onWelcome(int id, int team) { System.out.println("Welcome: id=" + id + " team=" + team); }
            @Override public void onLobby(Protocol.Lobby l) {
                StringBuilder sb = new StringBuilder("Lobby (" + l.phase() + "): ");
                for (Protocol.LobbyEntry e : l.entries()) sb.append(e.name()).append(e.ready() ? "*" : "").append("[").append(e.team()).append("] ");
                System.out.println(sb);
            }
            @Override public void onChat(String line) { System.out.println(line); }
            @Override public void onStart() {
                System.out.println(">>> GAME STARTED  (commands now: right, left, up, down, stop, lag, stats)");
                Thread input = new Thread(() -> {   // what the game screen does: send input about 30 times a second
                    while (holder[0].isConnected()) {
                        holder[0].sendInput(dx, dy, 0, false);
                        try { Thread.sleep(33); } catch (InterruptedException e) { return; }
                    }
                }, "input-sender");
                input.setDaemon(true);
                input.start();
            }
            @Override public void onSnapshot(Snapshot s) {
                long now = System.currentTimeMillis();
                if (now - lastPrint[0] < 1000) return;
                lastPrint[0] = now;
                for (Snapshot.P p : s.players) {
                    if (p.id == holder[0].getMyId()) {
                        System.out.printf("[tick %d] %s at (%.0f, %.0f)  score %d-%d  time %ds  players %d%n",
                                s.tick, p.name, p.x, p.y, s.score[0], s.score[1], s.timeLeftSec, s.players.length);
                    }
                }
            }
            @Override public void onGameOver(int w, int r, int b) { System.out.println(">>> GAME OVER, winner " + w + ", " + r + "-" + b); }
            @Override public void onServerError(String m) { System.out.println("Server error: " + m); }
            @Override public void onDisconnected(String r) { System.out.println("Disconnected: " + r); }
        });
        holder[0] = client;
        client.connect("localhost", 5000, name, -1);

        Scanner kb = new Scanner(System.in);
        System.out.println("Lobby commands: r = ready, u = unready, 0 / 1 = team, quit, anything else is chat");
        while (kb.hasNextLine()) {
            String t = kb.nextLine();
            switch (t) {
                case "quit" -> { client.disconnect(); return; }
                case "r" -> client.sendReady(true);
                case "u" -> client.sendReady(false);
                case "0" -> client.sendTeam(0);
                case "1" -> client.sendTeam(1);
                case "right" -> { dx = 1; dy = 0; }
                case "left" -> { dx = -1; dy = 0; }
                case "up" -> { dx = 0; dy = -1; }
                case "down" -> { dx = 0; dy = 1; }
                case "stop" -> { dx = 0; dy = 0; }
                case "lag" -> { client.toggleLagSim(); System.out.println("lag simulation " + (client.lagSimOn() ? "ON (100 ms each way, 5% loss)" : "off")); }
                case "stats" -> System.out.println("snapshots received " + client.getPacketsReceived() + ", stale dropped "
                        + client.getPacketsStale() + ", lost by simulation " + client.getPacketsSimulatedLost() + ", ping " + client.pingMs() + " ms");
                default -> client.sendChat(t);
            }
        }
        client.disconnect();
    }
}
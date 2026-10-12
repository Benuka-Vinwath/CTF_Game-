import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * A fake client for testing the server without a real game window.
 * Usage: java TestBot <host> <port> <team 0|1> <mode attack|defend|idle> <seconds>
 *   attack: runs to the enemy flag, then back home to score
 *   defend: chases enemies in its own half, otherwise guards its flag
 *   idle:   walks to the top of the map and stands there
 */
public class TestBot {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int team = Integer.parseInt(args[2]);
        String mode = args[3];
        long deadline = System.currentTimeMillis() + Long.parseLong(args[4]) * 1000;
        String tag = "[" + mode + "-" + (team == 0 ? "red" : "blue") + "] ";

        Socket tcp = new Socket(host, port);
        BufferedReader in = new BufferedReader(new InputStreamReader(tcp.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(tcp.getOutputStream(), StandardCharsets.UTF_8), true);
        out.println("HELLO " + team + " " + mode + "_bot");

        String[] w = in.readLine().split(" ");   // WELCOME id token udpPort team
        int id = Integer.parseInt(w[1]), token = Integer.parseInt(w[2]), udpPort = Integer.parseInt(w[3]);
        System.out.println(tag + "joined as player " + id);
        out.println("READY");
        out.println("PING " + System.nanoTime());

        boolean[] over = { false };
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("PONG ")) {
                        long rtt = (System.nanoTime() - Long.parseLong(line.substring(5))) / 1_000_000;
                        System.out.println(tag + "ping " + rtt + " ms");
                    } else if (!line.startsWith("LOBBY")) {
                        System.out.println(tag + line);
                    }
                    if (line.startsWith("GAMEOVER")) over[0] = true;
                }
            } catch (IOException ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        DatagramSocket udp = new DatagramSocket();
        udp.setSoTimeout(30);
        InetSocketAddress server = new InetSocketAddress(host, udpPort);
        byte[] buf = new byte[1024];
        int seq = 0, keys = 0, snapshots = 0;

        while (System.currentTimeMillis() < deadline && !over[0]) {
            byte[] msg = ("I " + id + " " + token + " " + (seq++) + " " + keys).getBytes(StandardCharsets.UTF_8);
            udp.send(new DatagramPacket(msg, msg.length, server));
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                snapshots++;
                String snap = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                // "grab" mode: attack, then disconnect the moment we hold the flag
                if (mode.equals("grab") && snap.split(" ")[9 + (1 - team) * 4].equals(String.valueOf(id))) {
                    System.out.println(tag + "holding the flag, disconnecting");
                    break;
                }
                keys = decide(snap, id, team, mode.equals("grab") ? "attack" : mode);
            } catch (SocketTimeoutException ignored) {}
        }
        System.out.println(tag + "received " + snapshots + " UDP snapshots");
        out.println("BYE");
        tcp.close();
        udp.close();
    }

    /** Reads a snapshot and returns which keys to hold. */
    static int decide(String snap, int myId, int team, String mode) {
        String[] t = snap.split(" ");
        if (!t[2].equals("PLAYING")) return 0;
        // flags start at index 6, 4 fields each
        String enemyFlagState = t[6 + (1 - team) * 4];
        double efx = Double.parseDouble(t[7 + (1 - team) * 4]), efy = Double.parseDouble(t[8 + (1 - team) * 4]);
        int carrier = Integer.parseInt(t[9 + (1 - team) * 4]);
        double ofx = Double.parseDouble(t[7 + team * 4]), ofy = Double.parseDouble(t[8 + team * 4]);

        int n = Integer.parseInt(t[14]);
        double mx = 0, my = 0, tx, ty;
        double ex = -1, ey = -1;   // nearest intruder
        for (int i = 0; i < n; i++) {
            int b = 15 + i * 6;
            int pid = Integer.parseInt(t[b]), pteam = Integer.parseInt(t[b + 1]);
            double px = Double.parseDouble(t[b + 2]), py = Double.parseDouble(t[b + 3]);
            if (pid == myId) { mx = px; my = py; }
            boolean inMyHalf = team == 0 ? px < 600 : px > 600;
            if (pteam != team && inMyHalf && t[b + 4].equals("1")) { ex = px; ey = py; }
        }
        switch (mode) {
            case "attack" -> {
                if (carrier == myId) { tx = GameMap.BASES[team][0]; ty = GameMap.BASES[team][1]; }
                else { tx = efx; ty = efy; }
            }
            case "defend" -> {
                if (ex >= 0) { tx = ex; ty = ey; } else { tx = ofx; ty = ofy; }
            }
            default -> { tx = mx; ty = 20; }
        }
        int keys = 0;
        if (ty < my - 4) keys |= 1;
        if (ty > my + 4) keys |= 2;
        if (tx < mx - 4) keys |= 4;
        if (tx > mx + 4) keys |= 8;
        return keys;
    }
}

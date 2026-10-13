import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * UDP side: receives player input packets and sends world snapshots.
 * UDP is used for the fast, frequent data where a lost packet does not matter,
 * because a newer one arrives 33 ms later anyway.
 */
public class UdpServer implements Runnable {
    private final DatagramSocket socket;
    private final GameState game;

    public UdpServer(int port, GameState game) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.game = game;
    }

    /** Receive loop. Expected packet: "I <playerId> <token> <seq> <keys>" */
    @Override
    public void run() {
        byte[] buf = new byte[512];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (socket.isClosed()) break;
                continue;
            }
            String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
            String[] t = msg.split(" ");
            if (t.length != 5 || !t[0].equals("I")) continue;     // ignore garbage
            try {
                game.handleInput(Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3]), Integer.parseInt(t[4]),
                        (InetSocketAddress) packet.getSocketAddress());
            } catch (NumberFormatException ignored) {}
        }
    }

    /** Safe to call from the tick thread while the receive thread is blocked in receive(). */
    public void send(String msg, InetSocketAddress to) {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
            // UDP is best-effort; the next snapshot replaces this one
        }
    }

    public void close() {
        socket.close();
    }
}

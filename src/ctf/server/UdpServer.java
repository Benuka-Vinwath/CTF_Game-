package ctf.server;

import ctf.common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * UDP side: receives player input packets and sends world snapshots. UDP carries the fast,
 * frequent data where a lost packet does not matter, because a newer one arrives 33 ms later.
 */
public class UdpServer implements Runnable {
    private final DatagramSocket socket;
    private final GameState game;

    public UdpServer(int port, GameState game) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.game = game;
    }

    /** Receive loop. Expected packet: "I id token seq dx dy aim shoot" (see Protocol.decodeInput). */
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
            String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            Protocol.Input in = Protocol.decodeInput(msg);
            if (in != null) game.handleInput(in, (InetSocketAddress) packet.getSocketAddress());   // garbage is ignored
        }
    }

    /** Safe to call from the tick thread while the receive thread is blocked in receive(). */
    public void send(String msg, InetSocketAddress to) {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
            // UDP is best effort; the next snapshot replaces this one
        }
    }

    public void close() {
        socket.close();
    }
}

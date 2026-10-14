package ctf.client;

import javax.swing.SwingUtilities;

/** The game's entry point. Usage: java -cp out ctf.client.Main [serverAddress] */
public class Main {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> new LobbyWindow(host).setVisible(true));
    }
}
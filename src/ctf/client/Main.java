package ctf.client;

import javax.swing.SwingUtilities;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;

/** The game's entry point. Usage: java -cp out ctf.client.Main [serverAddress] */
public class Main {
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> logError("Uncaught error in thread " + t.getName(), e));

        String host = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> new LobbyWindow(host).setVisible(true));
    }

    /** Prints the error and also saves it in ctf-client-error.log, so a problem on another computer can be sent to us. */
    static void logError(String what, Throwable e) {
        e.printStackTrace();
        try (PrintWriter w = new PrintWriter(new FileWriter("ctf-client-error.log", true))) {
            w.println("=== " + LocalDateTime.now() + "  " + what);
            w.println("java " + System.getProperty("java.version") + ", " + System.getProperty("os.name"));
            e.printStackTrace(w);
        } catch (Exception ignored) {
            // the log is a convenience; never fail because of it
        }
    }
}

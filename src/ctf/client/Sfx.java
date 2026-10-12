package ctf.client;

import javax.sound.sampled.*;
import java.util.*;

/** Tiny synthesized sound effects, no asset files. Fails silently if there is no audio device. */
final class Sfx {
    private static final AudioFormat FMT = new AudioFormat(22050, 8, 1, true, false);
    private static final Map<String, byte[]> CLIPS = new HashMap<>();
    static {
        CLIPS.put("shoot", tone(900, 300, 90, true));
        CLIPS.put("hit", tone(300, 60, 220, true));
        CLIPS.put("pickup", tone(500, 900, 180, false));
        CLIPS.put("drop", tone(500, 200, 200, false));
        CLIPS.put("score", tone(600, 1200, 350, false));
        CLIPS.put("win", tone(400, 1500, 700, false));
    }

    private static byte[] tone(double f0, double f1, int ms, boolean noisy) {
        int n = 22050 * ms / 1000;
        byte[] b = new byte[n];
        Random r = new Random(1);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            ph += 2 * Math.PI * (f0 + (f1 - f0) * t) / 22050;
            double s = Math.sin(ph) + (noisy ? r.nextDouble() - 0.5 : 0);
            b[i] = (byte) (s * 50 * (1 - t));
        }
        return b;
    }

    static void play(String name) {
        byte[] d = CLIPS.get(name);
        if (d == null) return;
        try {
            Clip c = AudioSystem.getClip();
            c.open(FMT, d, 0, d.length);
            c.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) c.close(); });
            c.start();
        } catch (Exception | Error ignored) { /* no audio device */ }
    }
    private Sfx() {}
}

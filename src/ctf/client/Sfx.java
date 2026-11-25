package ctf.client;

import javax.sound.sampled.*;
import java.util.*;
import java.util.concurrent.*;

/** Tiny synthesized sound effects, no asset files. Fails silently if there is no audio device. */
final class Sfx {
    private static final AudioFormat FMT = new AudioFormat(22050, 8, 1, true, false);
    private static final Map<String, byte[]> CLIPS = new HashMap<>();
    // Opening the audio device can block for up to a second; do it off the UI thread so a shot never stalls input or rendering.
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sfx");
        t.setDaemon(true);
        return t;
    });

    static {
        AUDIO.execute(() -> { try { AudioSystem.getClip().close(); } catch (Exception | Error ignored) { } }); // warm-up
        CLIPS.put("shoot", tone(900, 300, 90, true));
        CLIPS.put("wall", tone(220, 90, 70, true));
        CLIPS.put("hit", tone(300, 60, 220, true));
        CLIPS.put("pickup", tone(500, 900, 180, false));
        CLIPS.put("drop", tone(500, 200, 200, false));
        CLIPS.put("score", tone(600, 1200, 350, false));
        CLIPS.put("win", tone(400, 1500, 700, false));
        CLIPS.put("shield", tone(350, 1100, 260, false));
        CLIPS.put("deflect", tone(1200, 450, 110, true));
        CLIPS.put("expire", tone(650, 220, 180, false));
        CLIPS.put("firework", tone(180, 70, 320, true));
        CLIPS.put("throw", tone(520, 180, 140, true));
        CLIPS.put("boom", boom());
    }

    /** A grenade blast: a low thump under rumbling, darkening noise, fading over most of a second. */
    private static byte[] boom() {
        int n = 22050 * 900 / 1000;
        byte[] b = new byte[n];
        Random r = new Random(7);
        double lp = 0, ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / 22050;
            double k = 0.35 * Math.exp(-t * 3) + 0.04;   // the noise gets duller as it fades
            lp += k * ((r.nextDouble() * 2 - 1) - lp);
            ph += 2 * Math.PI * (70 * Math.exp(-t * 4) + 30) / 22050;   // the thump drops in pitch
            double s = lp * 2.6 + Math.sin(ph) * 0.9 * Math.exp(-t * 6);
            double env = Math.min(1, t / 0.004) * Math.exp(-t * 3.2);
            b[i] = (byte) Math.max(-127, Math.min(127, s * 95 * env));
        }
        return b;
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

    /** 0 = silent, 1 = full; set from the Settings screen. */
    static volatile float volume = 1f;

    static void play(String name) {
        byte[] clip = CLIPS.get(name);
        float v = volume;
        if (clip == null || v <= 0) return;
        byte[] d = clip;
        if (v < 1) {   // the clips are tiny, so scaling a copy is cheaper than finding a mixer gain control
            d = clip.clone();
            for (int i = 0; i < d.length; i++) d[i] = (byte) (d[i] * v);
        }
        final byte[] out = d;
        AUDIO.execute(() -> {
            try {
                Clip c = AudioSystem.getClip();
                c.open(FMT, out, 0, out.length);
                c.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) c.close(); });
                c.start();
            } catch (Exception | Error ignored) { /* no audio device */ }
        });
    }
    private Sfx() {}
}

package ctf.client;

import javax.sound.sampled.*;

/**
 * "Grand empire" battle music, synthesised at start-up (no audio files): a 24-bar loop in D minor at 84 BPM, built
 * like a film-score march: timpani and snare, a marching string ostinato, a choir, and a brass theme that rises from a
 * brooding minor melody (theme A) to a soaring fanfare in harmony with crashes and a gong (theme B), then turns back
 * through the dominant into the loop (it plays in the lobby and the match). The menus have their own, calmer theme (see renderMenu).
 * Both are original pieces. M toggles the music. Fails silently with no audio device.
 */
final class Music {
    private static final int SR = 32000;
    private static final double BPM = 84, BEAT = 60.0 / BPM;
    enum Track { MENU, BATTLE }

    private static final Clip[] clips = new Clip[2];
    private static final boolean[] building = new boolean[2];
    private static Track current = Track.MENU;
    private static boolean wanted = true;
    private static float volume = 0.8f;

    /** The match and lobby theme (the original battle march); kept for the places that already call it. */
    static void start() { play(Track.BATTLE); }

    /** Switches to a track and loops it. Building one takes a few seconds, so that happens off the UI thread; only the first time. */
    static synchronized void play(Track t) {
        current = t;
        int i = t.ordinal();
        for (int j = 0; j < clips.length; j++) if (j != i && clips[j] != null) clips[j].stop();
        Clip c = clips[i];
        if (c != null) {
            if (wanted && !c.isRunning()) { c.setFramePosition(0); c.loop(Clip.LOOP_CONTINUOUSLY); }
            return;
        }
        if (building[i]) return;
        building[i] = true;
        Thread th = new Thread(() -> {
            try {
                byte[] pcm = t == Track.MENU ? renderMenu() : render();
                Clip nc = AudioSystem.getClip();
                nc.open(new AudioFormat(SR, 16, 1, true, false), pcm, 0, pcm.length);
                synchronized (Music.class) {
                    clips[i] = nc;
                    gain(nc);
                    if (wanted && current == t) nc.loop(Clip.LOOP_CONTINUOUSLY);
                }
            } catch (Exception | Error ignored) { /* no audio device */ }
            synchronized (Music.class) { building[i] = false; }
        }, "music-" + t);
        th.setDaemon(true);
        th.start();
    }

    static synchronized void toggle() {
        wanted = !wanted;
        Clip c = clips[current.ordinal()];
        if (c == null) return;
        if (wanted) c.loop(Clip.LOOP_CONTINUOUSLY); else c.stop();
    }

    static synchronized boolean isOn() { return wanted; }

    /** 0..1, the same volume setting as the sound effects (0.8 is the original loudness). */
    static synchronized void setVolume(float v) {
        volume = v;
        for (Clip c : clips) if (c != null) gain(c);
    }

    private static void gain(Clip c) {
        if (!c.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl f = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
        double db = volume <= 0.001f ? f.getMinimum() : -8 + 20 * Math.log10(volume / 0.8);
        f.setValue((float) Math.max(f.getMinimum(), Math.min(f.getMaximum(), db)));
    }

    /** Peak-normalises and converts the mix to 16-bit PCM. */
    private static byte[] toPcm(double[] mix) {
        int n = mix.length;
        double peak = 1e-9;
        for (double v : mix) peak = Math.max(peak, Math.abs(Math.tanh(v * 1.3)));
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int s = (int) (Math.tanh(mix[i] * 1.3) / peak * 0.86 * 32767);
            out[2 * i] = (byte) s;
            out[2 * i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    // ---------------------------------------------------------------- the menu theme

    /**
     * The menu theme: the same key (D minor) and the same opening motif as the battle march, but slow and warm. 16 bars at 68 BPM:
     * a choir and strings pad, a plucked harp running through each chord, a soft horn singing the theme, a distant gong and a quiet
     * timpani heartbeat. The reverb wraps round the loop, so the end flows back into the start.
     */
    private static byte[] renderMenu() {
        final double beat = 60.0 / 68, len8 = 8 * beat;
        final int n = (int) (16 * 4 * beat * SR);
        double[] mix = new double[n];
        // one chord per two bars: Dm Bb Gm A | Dm Bb C A
        int[][] chords = {{50, 53, 57}, {46, 50, 53}, {55, 58, 62}, {57, 61, 64}, {50, 53, 57}, {46, 50, 53}, {48, 52, 55}, {57, 61, 64}};
        int[] roots = {38, 34, 31, 33, 38, 34, 36, 33};
        double[][][] tune = new double[8][][];   // horn theme per chord: {midi, start beat, length in beats}
        tune[2] = new double[][]{{70, 0, 2}, {69, 2, 1}, {67, 3, 1}, {65, 4, 2}, {67, 6, 2}};
        tune[3] = new double[][]{{64, 0, 2}, {65, 2, 1}, {67, 3, 1}, {69, 4, 4}};
        tune[4] = new double[][]{{74, 0, 3}, {72, 3, 1}, {70, 4, 2}, {69, 6, 2}};
        tune[5] = new double[][]{{70, 0, 2}, {74, 2, 2}, {77, 4, 2}, {74, 6, 2}};
        tune[6] = new double[][]{{76, 0, 3}, {74, 3, 1}, {72, 4, 2}, {67, 6, 2}};
        tune[7] = new double[][]{{69, 0, 2}, {73, 2, 2}, {76, 4, 3}, {74, 7, 1}};
        int[] pat = {0, 1, 2, 1};
        for (int c = 0; c < 8; c++) {
            final double t0 = c * len8;
            final boolean second = c >= 4;
            for (int note : chords[c]) {
                final double f = hz(note);
                choir(mix, t0 - 0.4, len8 + 1.0, f * 2, second ? 0.075 : 0.06);
                if (second) choir(mix, t0 - 0.4, len8 + 1.0, f * 4, 0.035);
                for (double det : new double[]{-0.004, 0.0, 0.004})
                    sawVoice(mix, t0 - 0.3, len8 + 0.8, 1.2, 1.4, f * 2 * (1 + det), 0.02, 0.06);
            }
            sine(mix, t0, len8, hz(roots[c] - 12), 0.15);
            sawVoice(mix, t0, len8, 0.7, 1.0, hz(roots[c]), 0.10, 0.08);
            if (c >= 1) {   // the harp
                for (int e = 0; e < 16; e++)
                    pluck(mix, t0 + e * beat / 2, hz(chords[c][pat[e % 4]] + 12 + (e % 8 == 7 ? 12 : 0)), e % 4 == 0 ? 0.075 : 0.045);
            }
            if (tune[c] != null) for (double[] nt : tune[c]) {
                double st = t0 + nt[1] * beat, ln = nt[2] * beat;
                brass(mix, st, ln, hz(nt[0]), 0.20, false);
                brass(mix, st, ln, hz(nt[0] - 12), 0.14, false);
            }
            if (c == 0 || c == 4) bell(mix, t0, hz(38), 0.22);
            if (c >= 2) for (int bar = 0; bar < 2; bar++) {
                timp(mix, t0 + bar * 4 * beat, 38, 0.30);
                timp(mix, t0 + bar * 4 * beat + 2 * beat, 45, 0.16);
            }
        }
        swell(mix, (16 * 4 - 4) * beat, 4 * beat, 0.10);   // a soft rise that leads back into the start
        reverb(mix);
        return toPcm(mix);
    }

    /** A plucked, harp-like note: a bright attack and a quick decay. */
    private static void pluck(double[] mix, double start, double f, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (1.8 * SR);
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR;
            double v = Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 3.0) + 0.45 * Math.sin(2 * Math.PI * 2 * f * t) * Math.exp(-t * 5.5)
                + 0.2 * Math.sin(2 * Math.PI * 3 * f * t) * Math.exp(-t * 9);
            mix[idx(mix, s0 + i)] += v * amp * Math.min(1, t / 0.003);
        }
    }

    // ---------------------------------------------------------------- the score

    private static double hz(double midi) { return 440 * Math.pow(2, (midi - 69) / 12); }

    private static byte[] render() {
        final int bars = 24, n = (int) (bars * 4 * BEAT * SR);
        double[] mix = new double[n];

        // one chord per two bars: intro (Dm Bb) | theme A (Dm Bb Gm A) | theme B (Bb F C Dm) | turn (Gm A)
        int[][] chords = {{50, 53, 57}, {46, 50, 53}, {50, 53, 57}, {46, 50, 53}, {55, 58, 62}, {57, 61, 64},
                          {46, 50, 53}, {53, 57, 60}, {48, 52, 55}, {50, 53, 57}, {55, 58, 62}, {57, 61, 64}};
        int[] roots = {38, 34, 38, 34, 31, 33, 34, 29, 36, 38, 31, 33};
        // brass theme per chord: {midi, start beat, length in beats}; chords 0-1 are the intro (no theme)
        double[][][] theme = new double[12][][];
        theme[2] = new double[][]{{62, 0, 1.5}, {65, 1.5, .5}, {69, 2, 2}, {67, 4, 1}, {65, 5, 1}, {64, 6, 2}};
        theme[3] = new double[][]{{62, 0, 1.5}, {65, 1.5, .5}, {70, 2, 2}, {69, 4, 1}, {67, 5, 1}, {65, 6, 2}};
        theme[4] = new double[][]{{67, 0, 1.5}, {70, 1.5, .5}, {74, 2, 2}, {72, 4, 1}, {70, 5, 1}, {69, 6, 2}};
        theme[5] = new double[][]{{69, 0, 1.5}, {73, 1.5, .5}, {76, 2, 2}, {74, 4, 1}, {73, 5, 1}, {69, 6, 2}};
        theme[6] = new double[][]{{77, 0, 2}, {79, 2, 1}, {77, 3, 1}, {74, 4, 2}, {70, 6, 2}};
        theme[7] = new double[][]{{72, 0, 2}, {77, 2, 1}, {81, 3, 1}, {79, 4, 2}, {77, 6, 2}};
        theme[8] = new double[][]{{76, 0, 2}, {79, 2, 1}, {84, 3, 1}, {81, 4, 2}, {79, 6, 2}};
        theme[9] = new double[][]{{74, 0, 2}, {77, 2, 1}, {81, 3, 1}, {86, 4, 3}, {81, 7, 1}};
        theme[10] = new double[][]{{74, 0, 3}, {70, 3, 1}, {67, 4, 2}, {69, 6, 2}};
        theme[11] = new double[][]{{73, 0, 2}, {76, 2, 2}, {81, 4, 3}, {69, 7, 1}};

        for (int c = 0; c < 12; c++) {
            final double t0 = c * 8 * BEAT, len = 8 * BEAT;
            final boolean intro = c < 2, themeB = c >= 6 && c < 10, turn = c >= 10;
            double padAmp = themeB ? 1.0 : intro ? 0.75 : 0.65;
            for (int note : chords[c]) {
                final double f = hz(note);
                choir(mix, t0 - 0.3, len + 0.9, f * 2, 0.085 * padAmp);                    // choir "ah", an octave up
                if (themeB) choir(mix, t0 - 0.3, len + 0.9, f * 4, 0.05);                 // choir sopranos in the fanfare
                for (double det : new double[]{-0.004, 0.0, 0.004})                        // string section
                    sawVoice(mix, t0 - 0.2, len + 0.6, 0.9, 1.2, f * 2 * (1 + det), 0.026 * padAmp, 0.07);
            }
            // basses and cellos: marcato quarter notes in theme A and the turn, long and open in theme B
            if (themeB || intro) {
                sawVoice(mix, t0, len, 0.5, 0.8, hz(roots[c]), 0.20, 0.10);
                sawVoice(mix, t0, len, 0.5, 0.8, hz(roots[c] + 12), 0.10, 0.08);
            } else for (int b = 0; b < 8; b++) {
                sawVoice(mix, t0 + b * BEAT, BEAT * 0.9, 0.01, 0.12, hz(roots[c]), b % 2 == 0 ? 0.24 : 0.15, 0.11);
                sawVoice(mix, t0 + b * BEAT, BEAT * 0.9, 0.01, 0.12, hz(roots[c] + 12), 0.08, 0.09);
            }
            sine(mix, t0, len, hz(roots[c] - 12), 0.20);                                    // sub
            if (!intro && !themeB) { // string ostinato: driving eighth notes through the chord
                int[] pat = {0, 1, 2, 1, 0, 1, 2, 1, 0, 1, 2, 1, 0, 1, 2, 1};
                for (int e = 0; e < 16; e++) sawVoice(mix, t0 + e * BEAT / 2, BEAT * 0.42, 0.006, 0.07, hz(chords[c][pat[e]] + 12), e % 2 == 0 ? 0.075 : 0.05, 0.16);
            }
            if (themeB) { // soaring high strings
                for (int note : chords[c]) for (double det : new double[]{-0.003, 0.003})
                    sawVoice(mix, t0, len, 0.35, 0.6, hz(note + 24) * (1 + det), 0.022, 0.12);
            }
            // brass: the theme in octaves, plus fifth-below harmony and a low octave in the fanfare
            if (theme[c] != null) for (double[] nt : theme[c]) {
                double st = t0 + nt[1] * BEAT, ln = nt[2] * BEAT;
                brass(mix, st, ln, hz(nt[0]), themeB ? 0.30 : 0.26, themeB);
                brass(mix, st, ln, hz(nt[0] - 12), themeB ? 0.26 : 0.22, false);
                if (themeB) { brass(mix, st, ln, hz(nt[0] - 7), 0.18, true); brass(mix, st, ln, hz(nt[0] - 24), 0.18, false); }
            }
            if (c == 0 || c == 6) { bell(mix, t0, hz(38), 0.34); bell(mix, t0, hz(50), 0.18); }   // gong at the start and at the fanfare
            if (c == 6 || c == 8 || c == 10 || c == 0) { crash(mix, t0, 0.30); }
            if (c == 7 || c == 9 || c == 11) crash(mix, t0, 0.18);
            if (c == 1 || c == 5 || c == 11) { // rising cymbal swell into the next section
                swell(mix, t0 + 4 * BEAT, 4 * BEAT, 0.22);
            }
        }

        // percussion
        for (int bar = 0; bar < bars; bar++) {
            double b0 = bar * 4 * BEAT;
            boolean intro = bar < 4, themeB = bar >= 12 && bar < 20;
            double boost = intro ? 0.7 : themeB ? 1.15 : 1.0;
            if (intro) {
                timp(mix, b0, 38, 0.7 * boost);
                timp(mix, b0 + 2 * BEAT, 45, 0.55 * boost);
            } else {
                for (int b = 0; b < 4; b++) {
                    boolean strong = b % 2 == 0;
                    timp(mix, b0 + b * BEAT, strong ? 38 : 45, (strong ? 0.95 : themeB ? 0.6 : 0.35) * boost);
                    snare(mix, b0 + b * BEAT + BEAT * 0.5, themeB ? 0.28 : 0.18);
                    if (!strong) snare(mix, b0 + b * BEAT, themeB ? 0.36 : 0.30);
                }
            }
            if (bar % 4 == 3) { // timpani roll into the next phrase
                int hits = 14;
                for (int k = 0; k < hits; k++) timp(mix, b0 + 3 * BEAT + k * BEAT / hits, 38, (0.12 + 0.5 * k / hits) * boost);
            }
        }

        reverb(mix);
        return toPcm(mix);
    }

    // ---------------------------------------------------------------- instruments

    private static int idx(double[] mix, int i) { return Math.floorMod(i, mix.length); }

    private static double env(double t, double len, double atk, double rel) { return Math.max(0, Math.min(1, Math.min(t / atk, (len - t) / rel))); }

    private static void sine(double[] mix, double start, double len, double f, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (len * SR);
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR;
            mix[idx(mix, s0 + i)] += Math.sin(2 * Math.PI * f * t) * amp * env(t, len, 1.0, 1.0);
        }
    }

    /** Low-passed sawtooth: strings, basses. alpha is the one-pole filter coefficient (higher = brighter). */
    private static void sawVoice(double[] mix, double start, double len, double atk, double rel, double f, double amp, double alpha) {
        int s0 = (int) (start * SR), cnt = (int) (len * SR);
        double ph = (f * 7.31) % 1, y = 0;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR, e = env(t, len, Math.max(atk, 1e-3), Math.max(rel, 1e-3));
            ph += f / SR;
            ph -= Math.floor(ph);
            y += alpha * ((2 * ph - 1) - y);
            mix[idx(mix, s0 + i)] += y * amp * e;
        }
    }

    /** Choir "ah": harmonics shaped by two vowel formants, with vibrato and a slow swell. */
    private static void choir(double[] mix, double start, double len, double f, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (len * SR);
        int hMax = (int) Math.min(12, 5000 / f);
        double[] w = new double[hMax + 1];
        for (int h = 1; h <= hMax; h++) {
            double fr = h * f;
            w[h] = Math.exp(-Math.pow((fr - 800) / 320, 2)) + 0.7 * Math.exp(-Math.pow((fr - 1150) / 380, 2)) + 0.25 / h;
        }
        double ph = (f * 3.7) % 1;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR, e = env(t, len, 1.6, 1.6);
            ph += f * (1 + 0.005 * Math.sin(2 * Math.PI * 5.3 * t + f)) / SR;
            double x = 2 * Math.PI * ph, v = 0;
            for (int h = 1; h <= hMax; h++) v += w[h] * Math.sin(h * x);
            mix[idx(mix, s0 + i)] += v * amp * e * 0.55;
        }
    }

    /** Brass: saw + square, filter opening through the note (brighter in the fanfare), late vibrato. */
    private static void brass(double[] mix, double start, double len, double f, double amp, boolean bright) {
        int s0 = (int) (start * SR), cnt = (int) ((len + 0.5) * SR);
        double ph = (f * 1.13) % 1, y = 0, y2 = 0;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR, e = Math.min(1, t / 0.07) * (t < len ? 1 : Math.max(0, 1 - (t - len) / 0.45));
            double vib = 1 + 0.0055 * Math.sin(2 * Math.PI * 5.5 * t) * Math.min(1, t / 0.7);
            ph += f * vib / SR;
            ph -= Math.floor(ph);
            double raw = 0.62 * (2 * ph - 1) + 0.38 * (ph < 0.5 ? 1 : -1);
            double a = (bright ? 0.05 : 0.035) + (bright ? 0.2 : 0.13) * Math.min(1, t / 0.35);   // the "blat" opens the filter
            y += a * (raw - y);
            y2 += a * (y - y2);
            mix[idx(mix, s0 + i)] += (y + 0.5 * (y - y2)) * amp * e;
        }
    }

    /** Heavy bell / gong: inharmonic partials with long decay. */
    private static void bell(double[] mix, double start, double f, double amp) {
        double[] ratio = {0.5, 1, 1.19, 1.56, 2.0, 2.76, 4.07}, gain = {0.8, 1, 0.5, 0.45, 0.35, 0.22, 0.12}, decay = {8, 7, 5, 4.5, 3.6, 2.6, 1.8};
        int s0 = (int) (start * SR), cnt = (int) (8 * SR);
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR, v = 0;
            for (int p = 0; p < ratio.length; p++) v += gain[p] * Math.exp(-t * 6.9 / decay[p]) * Math.sin(2 * Math.PI * f * ratio[p] * t);
            mix[idx(mix, s0 + i)] += v * amp * Math.min(1, t / 0.004) * 0.5;
        }
    }

    /** Timpani: pitched, falling sine with a skin thump. */
    private static void timp(double[] mix, double start, double f, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (0.7 * SR);
        java.util.Random r = new java.util.Random(s0);
        double ph = 0;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR;
            ph += 2 * Math.PI * (f + f * 0.9 * Math.exp(-t * 18)) / SR;
            double v = Math.sin(ph) * Math.exp(-t * 5.5) + 0.4 * Math.sin(ph * 1.5) * Math.exp(-t * 9) + (r.nextDouble() - 0.5) * 0.7 * Math.exp(-t * 70);
            mix[idx(mix, s0 + i)] += v * amp * 0.6;
        }
    }

    /** Snare: noise burst through a high-pass plus a body tone. */
    private static void snare(double[] mix, double start, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (0.22 * SR);
        java.util.Random r = new java.util.Random(s0 + 7);
        double prev = 0;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR, nz = r.nextDouble() - 0.5, hp = nz - prev * 0.6;
            prev = nz;
            mix[idx(mix, s0 + i)] += (hp * Math.exp(-t * 24) + 0.35 * Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 40)) * amp;
        }
    }

    /** Crash cymbal: bright noise with a long decay. */
    private static void crash(double[] mix, double start, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (3.2 * SR);
        java.util.Random r = new java.util.Random(s0 + 3);
        double prev = 0, prev2 = 0;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / SR, nz = r.nextDouble() - 0.5, hp = nz - 1.4 * prev + 0.5 * prev2;
            prev2 = prev; prev = nz;
            mix[idx(mix, s0 + i)] += hp * amp * 0.5 * Math.exp(-t * 2.2) * Math.min(1, t / 0.003);
        }
    }

    /** Reversed-cymbal style rise into a downbeat. */
    private static void swell(double[] mix, double start, double len, double amp) {
        int s0 = (int) (start * SR), cnt = (int) (len * SR);
        java.util.Random r = new java.util.Random(s0 + 11);
        double prev = 0;
        for (int i = 0; i < cnt; i++) {
            double t = (double) i / cnt, nz = r.nextDouble() - 0.5, hp = nz - 0.9 * prev;
            prev = nz;
            mix[idx(mix, s0 + i)] += hp * amp * t * t;
        }
    }

    /** Cathedral reverb: four feedback combs run round the loop twice so the tail wraps seamlessly into the start. */
    private static void reverb(double[] mix) {
        int n = mix.length;
        int[] delay = {(int) (0.0297 * SR), (int) (0.0371 * SR), (int) (0.0411 * SR), (int) (0.0437 * SR)};
        double[] wet = new double[n];
        for (int d : delay) {
            double[] buf = new double[n];
            System.arraycopy(mix, 0, buf, 0, n);
            for (int pass = 0; pass < 2; pass++)
                for (int i = 0; i < n; i++) buf[i] += 0.80 * buf[Math.floorMod(i - d, n)];
            for (int i = 0; i < n; i++) wet[i] += buf[i] - mix[i];
        }
        for (int i = 0; i < n; i++) mix[i] += wet[i] * 0.035;
    }

    private Music() {}
}

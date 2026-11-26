package ctf.common;

/** XP and levels, shared so the client's local profile and the server's online profile count the same way. */
public final class Progress {
    private Progress() { }

    /** XP needed to go from level n to n+1: 200, 250, 300 ... */
    public static int need(int n) { return 150 + 50 * n; }

    /** {level, XP into that level, XP needed for the next} */
    public static int[] levelOf(int xp) {
        int lv = 1;
        while (xp >= need(lv)) { xp -= need(lv); lv++; }
        return new int[]{lv, xp, need(lv)};
    }

    public static int xpFor(boolean won, int captures, int tags, boolean mvp) {
        return (won ? 100 : 40) + 20 * captures + 5 * tags + (mvp ? 50 : 0);
    }
}

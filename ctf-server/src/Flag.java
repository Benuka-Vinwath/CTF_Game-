/** A team's flag. Always in exactly one of three states. */
public class Flag {
    public enum State { AT_BASE, CARRIED, DROPPED }

    public final int team;
    public final double baseX, baseY;

    public State state = State.AT_BASE;
    public double x, y;
    public int carrierId = -1;   // valid only when CARRIED
    public long droppedAt;       // valid only when DROPPED

    public Flag(int team, double baseX, double baseY) {
        this.team = team;
        this.baseX = baseX;
        this.baseY = baseY;
        returnToBase();
    }

    public void pickUp(Player p) {
        state = State.CARRIED;
        carrierId = p.id;
        x = p.x;
        y = p.y;
    }

    public void drop(double atX, double atY, long now) {
        state = State.DROPPED;
        carrierId = -1;
        x = atX;
        y = atY;
        droppedAt = now;
    }

    public void returnToBase() {
        state = State.AT_BASE;
        carrierId = -1;
        x = baseX;
        y = baseY;
    }
}

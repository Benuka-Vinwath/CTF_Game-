package ctf.server;

import ctf.common.MapData;

/** A team's flag. Always in exactly one of three states; the ordinal is the number sent to clients. */
public class Flag {
    public enum State { AT_BASE, CARRIED, DROPPED }

    public final int team;
    public State state = State.AT_BASE;
    public double x, y;
    public int carrierId = -1;   // valid only when CARRIED
    public double droppedFor;    // seconds on the ground, valid only when DROPPED

    public Flag(int team) {
        this.team = team;
        returnToBase();
    }

    public void pickUp(Player p) {
        state = State.CARRIED;
        carrierId = p.id;
        x = p.x;
        y = p.y;
    }

    public void drop(double atX, double atY) {
        state = State.DROPPED;
        carrierId = -1;
        x = atX;
        y = atY;
        droppedFor = 0;
    }

    public void returnToBase() {
        state = State.AT_BASE;
        carrierId = -1;
        x = MapData.BASES[team][0];
        y = MapData.BASES[team][1];
    }
}

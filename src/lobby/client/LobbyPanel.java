package lobby.client;

import lobby.common.LobbyMap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * The 2-D visual lobby arena:
 *  • Players walk freely (WASD / Arrows)
 *  • Animated avatars with name tags and team colours
 *  • Ready indicator (green tick / grey circle) above each player
 *  • Chat overlay bottom-left
 *  • Countdown overlay when game is about to start
 *  • NO shooting
 */
public class LobbyPanel extends JPanel {

    // ── colours ──────────────────────────────────────────────────────────────
    private static final Color BG         = new Color(10, 8, 20);
    private static final Color FLOOR      = new Color(22, 18, 38);
    private static final Color WALL_FILL  = new Color(45, 38, 65);
    private static final Color WALL_EDGE  = new Color(90, 75, 120);
    private static final Color GRID_LINE  = new Color(28, 24, 44);
    private static final Color[] TEAM_COL = { new Color(235, 70, 70), new Color(70, 150, 255) };
    private static final Color CHAT_BG    = new Color(0, 0, 0, 160);
    private static final Color CHAT_FG    = new Color(220, 215, 255);
    private static final Color READY_ON   = new Color(60, 220, 100);
    private static final Color READY_OFF  = new Color(100, 95, 130);

    // ── timing ───────────────────────────────────────────────────────────────
    private static final int   TICK_MS   = 16;
    private static final float SEND_RATE = 1f / 20;

    // ── remote players ───────────────────────────────────────────────────────

    static final class RemotePlayer {
        int id, team; double x, y, drawX, drawY, walkPhase; boolean ready; String name;
        RemotePlayer(int id, int team, double x, double y, boolean ready, String name) {
            this.id=id; this.team=team; this.x=x; this.y=y;
            this.drawX=x; this.drawY=y; this.ready=ready; this.name=name;
        }
    }

    // ── state ─────────────────────────────────────────────────────────────────
    private final LobbyNet net;
    private final int myId, myTeam;
    private final String myName;

    private double myX, myY, myDrawX, myDrawY, myWalkPhase;
    private boolean myReady;

    private final Map<Integer, RemotePlayer> remotes = new LinkedHashMap<>();

    private final List<String> chatLines = new ArrayList<>();
    private static final int MAX_CHAT = 8;

    // Ready HUD
    private int readyCount, totalCount;

    // Countdown overlay
    private int countdownSec = -1;

    // Input
    private final Set<Integer> keys = new HashSet<>();
    private long lastTickNs = System.nanoTime();
    private float sendAcc;
    private double time;

    // ── constructor ──────────────────────────────────────────────────────────

    public LobbyPanel(LobbyNet net, int myId, int myTeam, String myName,
                      double startX, double startY) {
        this.net = net; this.myId = myId; this.myTeam = myTeam; this.myName = myName;
        myX = startX; myDrawX = startX; myY = startY; myDrawY = startY;

        setPreferredSize(new Dimension(LobbyMap.W, LobbyMap.H));
        setBackground(BG);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e)  { keys.add(e.getKeyCode()); }
            @Override public void keyReleased(KeyEvent e) { keys.remove(e.getKeyCode()); }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { keys.clear(); }
        });

        new javax.swing.Timer(TICK_MS, e -> tick()).start();
    }

    // ── public API ───────────────────────────────────────────────────────────

    public void addOrUpdateRemote(int id, int team, double x, double y, boolean ready, String name) {
        RemotePlayer rp = remotes.get(id);
        if (rp == null) {
            remotes.put(id, new RemotePlayer(id, team, x, y, ready,
                    name != null && !name.isBlank() ? name : "Player" + id));
        } else {
            rp.x = x; rp.y = y;
            if (name != null) { rp.team = team; if (!name.isBlank()) rp.name = name; }
            rp.ready = ready;
        }
    }

    public void setRemoteReady(int id, boolean ready) {
        RemotePlayer rp = remotes.get(id);
        if (rp != null) rp.ready = ready;
    }

    public void removeRemote(int id)         { remotes.remove(id); }
    public void pushChat(String line)         { chatLines.add(line); if (chatLines.size() > MAX_CHAT) chatLines.remove(0); }
    public void setReadyStatus(int r, int t) { readyCount = r; totalCount = t; }
    public void setCountdown(int s)          { countdownSec = s; }
    public void setMyReady(boolean r)        { myReady = r; }

    // ── game loop ─────────────────────────────────────────────────────────────

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastTickNs) / 1e9);
        lastTickNs = now;
        time += dt;

        int dx = dir(KeyEvent.VK_D, KeyEvent.VK_RIGHT) - dir(KeyEvent.VK_A, KeyEvent.VK_LEFT);
        int dy = dir(KeyEvent.VK_S, KeyEvent.VK_DOWN)  - dir(KeyEvent.VK_W, KeyEvent.VK_UP);

        if (dx != 0 || dy != 0) {
            double len = Math.hypot(dx, dy);
            double[] pos = { myX + dx/len * LobbyMap.SPEED * dt, myY + dy/len * LobbyMap.SPEED * dt };
            LobbyMap.resolve(pos); myX = pos[0]; myY = pos[1];
            myWalkPhase += dt * 10;
        }
        myDrawX += (myX - myDrawX) * 0.5;
        myDrawY += (myY - myDrawY) * 0.5;

        sendAcc += (float) dt;
        if (sendAcc >= SEND_RATE) { sendAcc = 0; net.sendMove(myX, myY); }

        for (RemotePlayer rp : remotes.values()) {
            double ex = rp.x - rp.drawX, ey = rp.y - rp.drawY;
            if (Math.hypot(ex, ey) > 1.5) { rp.drawX += ex*0.25; rp.drawY += ey*0.25; rp.walkPhase += dt*10; }
        }
        repaint();
    }

    private int dir(int a, int b) { return (keys.contains(a)||keys.contains(b)) ? 1 : 0; }

    // ── painting ──────────────────────────────────────────────────────────────

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawFloor(g);
        drawWalls(g);
        for (RemotePlayer rp : remotes.values())
            drawAvatar(g, rp.drawX, rp.drawY, rp.team, rp.walkPhase, rp.ready, rp.name, false);
        drawAvatar(g, myDrawX, myDrawY, myTeam, myWalkPhase, myReady, myName, true);
        drawChatOverlay(g);
        drawReadyHud(g);
        drawTopBadge(g);
        if (countdownSec >= 0) drawCountdown(g);
        g.dispose();
    }

    // ── floor ────────────────────────────────────────────────────────────────

    private void drawFloor(Graphics2D g) {
        g.setColor(FLOOR);
        g.fillRect(0, 0, LobbyMap.W, LobbyMap.H);
        g.setColor(GRID_LINE); g.setStroke(new BasicStroke(0.5f));
        for (int x=0; x<=LobbyMap.W; x+=60) g.drawLine(x, 0, x, LobbyMap.H);
        for (int y=0; y<=LobbyMap.H; y+=60) g.drawLine(0, y, LobbyMap.W, y);
        RadialGradientPaint v = new RadialGradientPaint(
                new Point2D.Float(LobbyMap.W/2f, LobbyMap.H/2f),
                Math.max(LobbyMap.W, LobbyMap.H)*0.72f,
                new float[]{0f,1f},
                new Color[]{new Color(0,0,0,0), new Color(0,0,0,170)});
        g.setPaint(v); g.fillRect(0,0,LobbyMap.W,LobbyMap.H); g.setPaint(null);
    }

    // ── walls ────────────────────────────────────────────────────────────────

    private void drawWalls(Graphics2D g) {
        for (double[] w : LobbyMap.WALLS) {
            int wx=(int)w[0],wy=(int)w[1],ww=(int)w[2],wh=(int)w[3];
            g.setColor(WALL_FILL); g.fillRect(wx,wy,ww,wh);
            g.setColor(WALL_EDGE); g.setStroke(new BasicStroke(1.5f)); g.drawRect(wx,wy,ww,wh);
            drawTorch(g, wx+ww/2, wy+3);
        }
    }

    private void drawTorch(Graphics2D g, int tx, int ty) {
        double f = 0.6+0.4*Math.sin(time*8+tx);
        g.setColor(new Color((int)(255*f),(int)(140*f),40,200));
        g.fill(new Ellipse2D.Double(tx-4,ty-6,8,8));
        g.setColor(new Color(255,230,180,120)); g.fill(new Ellipse2D.Double(tx-2,ty-8,4,5));
    }

    // ── avatar ───────────────────────────────────────────────────────────────

    private void drawAvatar(Graphics2D g, double x, double y, int team,
                             double walkPhase, boolean ready, String name, boolean isSelf) {
        Color tc = TEAM_COL[team];
        int   R  = LobbyMap.R;
        double bob = Math.sin(walkPhase)*2.0;

        // Shadow
        g.setColor(new Color(0,0,0,60));
        g.fill(new Ellipse2D.Double(x-R+3, y-R/2.0+10, R*2-4, R-4));

        // Glow (self)
        if (isSelf) {
            RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Double(x,y+bob), R*2.5f, new float[]{0f,1f},
                    new Color[]{ new Color(tc.getRed(),tc.getGreen(),tc.getBlue(),60), new Color(0,0,0,0) });
            g.setPaint(glow); g.fill(new Ellipse2D.Double(x-R*2.5,y+bob-R*2.5,R*5,R*5)); g.setPaint(null);
        }

        // Ring
        double pulse = isSelf ? 0.7+0.3*Math.sin(time*3) : 0.9;
        g.setColor(new Color(tc.getRed(),tc.getGreen(),tc.getBlue(),(int)(220*pulse)));
        g.setStroke(new BasicStroke(isSelf ? 2.5f : 1.8f));
        g.draw(new Ellipse2D.Double(x-R, y-R+bob, R*2, R*2));

        // Body
        g.setColor(new Color(tc.getRed()/4, tc.getGreen()/4, tc.getBlue()/4+10));
        g.fill(new Ellipse2D.Double(x-R+1, y-R+1+bob, R*2-2, R*2-2));

        // Visor
        g.setColor(new Color(tc.getRed(),tc.getGreen(),tc.getBlue(),180));
        g.fill(new Ellipse2D.Double(x-6,y-4+bob,12,7));
        g.setColor(new Color(255,255,255,90)); g.fill(new Ellipse2D.Double(x-3,y-3+bob,4,3));

        // Legs
        double ls = Math.sin(walkPhase)*6;
        g.setColor(new Color(tc.getRed(),tc.getGreen(),tc.getBlue(),160));
        g.setStroke(new BasicStroke(2.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x-5+ls, y+R-2+bob, x-7+ls, y+R+8+bob));
        g.draw(new Line2D.Double(x+5-ls, y+R-2+bob, x+7-ls, y+R+8+bob));

        // Ready indicator dot above head
        double dotX = x + R - 4, dotY = y - R - 2 + bob;
        g.setColor(ready ? READY_ON : READY_OFF);
        g.fill(new Ellipse2D.Double(dotX-5, dotY-5, 10, 10));
        if (ready) {
            g.setColor(Color.WHITE); g.setStroke(new BasicStroke(1.5f));
            g.draw(new Line2D.Double(dotX-2.5, dotY+0.5, dotX-0.5, dotY+2.5));
            g.draw(new Line2D.Double(dotX-0.5, dotY+2.5, dotX+3,   dotY-2));
        }

        // Name tag
        drawNameTag(g, x, y-R-8+bob, name, isSelf, tc);
    }

    private void drawNameTag(Graphics2D g, double x, double y, String name, boolean isSelf, Color tc) {
        Font f = new Font(Font.SANS_SERIF, isSelf ? Font.BOLD : Font.PLAIN, isSelf ? 11 : 10);
        g.setFont(f); FontMetrics fm = g.getFontMetrics();
        int tw=fm.stringWidth(name), px=4, py=2;
        int bx=(int)x-tw/2-px, by=(int)y-fm.getAscent()-py;
        g.setColor(new Color(0,0,0,120));
        g.fill(new RoundRectangle2D.Double(bx,by,tw+px*2,fm.getAscent()+py*2,6,6));
        g.setColor(new Color(tc.getRed(),tc.getGreen(),tc.getBlue(),180)); g.setStroke(new BasicStroke(1f));
        g.draw(new Line2D.Double(bx+3,by,bx+tw+px*2-3,by));
        g.setColor(isSelf ? Color.WHITE : new Color(220,215,255));
        g.drawString(name, (int)x-tw/2, (int)y-py);
    }

    // ── chat overlay ─────────────────────────────────────────────────────────

    private void drawChatOverlay(Graphics2D g) {
        if (chatLines.isEmpty()) return;
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 12); g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int lh=fm.getHeight()+2, px=8, py=4, pw=320;
        int ph=chatLines.size()*lh+py*2;
        int panX=8, panY=LobbyMap.H-ph-8;
        g.setColor(CHAT_BG); g.fill(new RoundRectangle2D.Double(panX,panY,pw,ph,8,8));
        g.setColor(CHAT_FG);
        for (int i=0; i<chatLines.size(); i++) {
            String line=chatLines.get(i);
            while (fm.stringWidth(line)>pw-px*2 && line.length()>3) line=line.substring(0,line.length()-1);
            g.drawString(line, panX+px, panY+py+fm.getAscent()+i*lh);
        }
    }

    // ── ready HUD (top-right) ─────────────────────────────────────────────────

    private void drawReadyHud(Graphics2D g) {
        if (totalCount == 0) return;
        String txt = "Ready: " + readyCount + " / " + totalCount;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics(); int tw=fm.stringWidth(txt);
        int bx=LobbyMap.W-tw-18, by=6, bw=tw+12, bh=22;
        g.setColor(new Color(0,0,0,150)); g.fillRoundRect(bx,by,bw,bh,8,8);
        g.setColor(readyCount==totalCount && totalCount>0 ? READY_ON : new Color(190,185,230));
        g.drawString(txt, bx+6, by+15);
    }

    // ── top badge ────────────────────────────────────────────────────────────

    private void drawTopBadge(Graphics2D g) {
        String txt = "LOBBY  •  Walk & Chat  •  Press R to Ready Up  •  No Shooting";
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics(); int tw=fm.stringWidth(txt);
        int bx=LobbyMap.W/2-tw/2-8, by=6;
        g.setColor(new Color(0,0,0,160)); g.fill(new RoundRectangle2D.Double(bx,by,tw+16,22,10,10));
        g.setColor(new Color(80,220,120)); g.drawString(txt, bx+8, by+15);
    }

    // ── countdown overlay ─────────────────────────────────────────────────────

    private void drawCountdown(Graphics2D g) {
        // Dark overlay
        g.setColor(new Color(0,0,0,170)); g.fillRect(0,0,LobbyMap.W,LobbyMap.H);

        String line1 = countdownSec > 0 ? "GAME STARTING IN" : "LAUNCHING GAME...";
        String line2 = countdownSec > 0 ? String.valueOf(countdownSec) : "";

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        int cx = LobbyMap.W/2, cy = LobbyMap.H/2;
        g.setColor(Color.WHITE);
        g.drawString(line1, cx - fm.stringWidth(line1)/2, cy - 20);

        if (!line2.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 80));
            fm = g.getFontMetrics();
            // Colour: green→yellow→red
            Color numCol = countdownSec >= 3 ? READY_ON :
                           countdownSec == 2 ? new Color(240,200,60) :
                                               new Color(235,70,70);
            g.setColor(numCol);
            g.drawString(line2, cx - fm.stringWidth(line2)/2, cy + 65);
        }
    }
}

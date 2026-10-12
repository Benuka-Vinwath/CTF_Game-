package ctf.client;

/** Run with: java -ea -cp out ctf.client.SelfCheck */
public class SelfCheck {
    public static void main(String[] a) {
        // a fast bullet that jumps from one side of a player to the other must still hit (a point check would miss)
        assert MapData.segHits(100, 100, 160, 100, 130, 105, MapData.R);
        assert !MapData.segHits(100, 100, 160, 100, 130, 130, MapData.R);
        assert !MapData.segHits(100, 100, 120, 100, 150, 100, MapData.R); // target beyond the segment end
        assert MapData.nearSolid(390, 300, 0);   // inside the centre wall
        assert !MapData.nearSolid(300, 300, 0);  // open floor
        assert MapData.nearSolid(-1, 300, 0);    // outside the arena
        System.out.println("self-check ok");
    }
}

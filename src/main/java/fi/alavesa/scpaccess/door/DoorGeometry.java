package fi.alavesa.scpaccess.door;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a door stands, as pure functions (no Bukkit). A door is {@code width x height} blocks and ONE block deep.
 * Its anchor is the bottom centre: on the width axis a block centre for odd widths and a block EDGE for even
 * widths (so a 6-wide door spans exactly 6 blocks), on the depth axis a block centre.
 */
public final class DoorGeometry {

    private DoorGeometry() { }

    /** Snaps a yaw to 0, 90, 180 or -90. */
    public static float snap(double yaw) {
        double y = ((yaw % 360) + 360) % 360;
        int quarter = (int) Math.round(y / 90.0) % 4;
        return switch (quarter) { case 0 -> 0f; case 1 -> 90f; case 2 -> 180f; default -> -90f; };
    }

    /** Width runs along x when the front faces north/south (yaw 0 or 180), along z otherwise. */
    public static boolean widthAlongX(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return q == 0 || q == 2;
    }

    /**
     * The anchor for a door placed on {@code base} (the block its bottom row goes in) when the player clicked at
     * {@code clickAlong} (the click's coordinate on the width axis).
     *
     * @return {@code [x, y, z]}
     */
    public static double[] anchor(int baseX, int baseY, int baseZ, int width, float yaw, double clickX, double clickZ) {
        boolean alongX = widthAlongX(yaw);
        double along;
        if (width % 2 == 1) along = (alongX ? baseX : baseZ) + 0.5;
        else {
            double click = alongX ? clickX : clickZ;
            along = Math.round(click);
            double lo = alongX ? baseX : baseZ;
            along = Math.max(lo, Math.min(lo + 1, along)); // an edge of the clicked block
        }
        return alongX ? new double[] {along, baseY, baseZ + 0.5} : new double[] {baseX + 0.5, baseY, along};
    }

    /** Block coordinates {@code [x, y, z]} of every cell the door fills. */
    public static List<int[]> cells(int width, int height, double x, double y, double z, float yaw) {
        boolean alongX = widthAlongX(yaw);
        List<int[]> out = new ArrayList<>(width * height);
        double start = (alongX ? x : z) - width / 2.0;
        int by = (int) Math.floor(y + 1e-6);
        for (int i = 0; i < width; i++) {
            int along = (int) Math.floor(start + i + 0.5);
            for (int j = 0; j < height; j++) {
                out.add(alongX ? new int[] {along, by + j, (int) Math.floor(z)} : new int[] {(int) Math.floor(x), by + j, along});
            }
        }
        return out;
    }
}

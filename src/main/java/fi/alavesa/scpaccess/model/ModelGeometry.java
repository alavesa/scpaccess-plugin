package fi.alavesa.scpaccess.model;

import fi.alavesa.scpaccess.util.Affine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link BbModel} with its group hierarchy flattened: every cube carries its final (world) transform, and
 * the seats, hitboxes, bounds and the "fit" that squeezes the model into a Java item model are worked out.
 * Everything is still in Blockbench pixels and Blockbench axes; {@link #toChairSpace} converts to blocks.
 *
 * <h2>Flattening groups</h2>
 * A group rotates its content about its pivot: {@code G = T(origin) * R(rotation) * T(-origin)}, and groups
 * nest, so a cube's world transform is {@code M = G_root * ... * G_parent * C} with the cube's own
 * {@code C = T(origin) * R(rotation) * T(-origin)}. A Java model element can only express "an axis-aligned
 * box, rotated about one pivot". Any rigid transform M can be written that way: take the cube's own origin
 * {@code o}, let {@code p = M * o} be where it ends up, slide the unrotated box by {@code p - o} and rotate
 * it about {@code p} by the rotation part of M:
 * <pre>   R(x + (p - o) - p) + p  =  R(x - o) + p  =  M * x </pre>
 * 26.3 elements accept a free three-axis rotation, so this is exact - no 22.5 degree snapping.
 *
 * <h2>The fit</h2>
 * Element coordinates must stay within -16..32 (a 48 px cube centred on 8,8,8). The unrotated boxes are
 * recentred on {@link #fitCenter} and, if still too big, shrunk by {@link #fitScale}; the placed
 * item_display undoes both with its own (unlimited) translation and scale.
 */
public final class ModelGeometry {

    /** Half the allowed coordinate range of a model element: -16..32 is 8 +- 24. */
    public static final double ELEMENT_HALF_RANGE = 24;

    /** An axis-aligned box, {@code min}/{@code max} as {@code [x, y, z]}. */
    public record Box(double[] min, double[] max) {
        public double size(int axis) { return max[axis] - min[axis]; }
        public double center(int axis) { return (min[axis] + max[axis]) / 2; }
    }

    /**
     * One cube, flattened.
     *
     * @param from     corner of the box BEFORE rotation, already slid to its final place
     * @param to       opposite corner
     * @param pivot    the point the box is rotated about
     * @param rotation world rotation as Blockbench-order Euler angles {@code [x, y, z]}, degrees
     */
    public record Element(BbModel.Cube cube, double[] from, double[] to, double[] pivot, double[] rotation) { }

    /**
     * @param position  where the rider sits (px)
     * @param yawOffset degrees added to the chair's yaw to get the direction this seat faces
     */
    public record SeatPoint(String name, double[] position, double yawOffset) { }

    public final List<Element> elements = new ArrayList<>();
    public final List<SeatPoint> seats = new ArrayList<>();
    /** Click boxes: the cubes named / grouped "hitbox", or the model bounds when there are none. */
    public final List<Box> hitboxes = new ArrayList<>();
    /** True when {@link #hitboxes} came from "hitbox" cubes rather than the bounds. */
    public boolean hitboxFromModel;
    /** Bounds of the visible, rotated geometry. */
    public Box bounds;
    /** The chair's origin: the point of the model that is put on the clicked block. */
    public double[] origin;
    public double[] fitCenter;
    /** 1 when the model fits a Java item model as it is, below 1 when it had to be shrunk. */
    public double fitScale = 1;

    private ModelGeometry() { }

    public static ModelGeometry of(BbModel model, ModelLog log) throws ModelException {
        ModelGeometry g = new ModelGeometry();
        Map<BbModel.Group, Affine> groupTransforms = new HashMap<>();

        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] preLo = lo.clone(), preHi = hi.clone();
        for (BbModel.Cube cube : model.cubes) {
            Affine world = world(cube.group, groupTransforms).mul(Affine.rotationAbout(cube.origin, cube.rotation));
            boolean hitbox = cube.name.toLowerCase(Locale.ROOT).startsWith("hitbox") || (cube.group != null && cube.group.isOrIsUnder("hitbox"));
            if (hitbox) {
                // hitbox helpers are never drawn, hidden or not
                double[] a = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, b = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (int corner = 0; corner < 8; corner++) grow(a, b, world.apply(corner(cube.from, cube.to, cube.inflate, corner)));
                g.hitboxes.add(new Box(a, b));
                continue;
            }
            if (!cube.visible) continue;
            double[] pivot = world.apply(cube.origin);
            double[] from = new double[3], to = new double[3];
            for (int i = 0; i < 3; i++) {
                double slide = pivot[i] - cube.origin[i];
                from[i] = cube.from[i] - cube.inflate + slide;
                to[i] = cube.to[i] + cube.inflate + slide;
            }
            double[] rotation = world.isRotationIdentity(1e-9) ? new double[] {0, 0, 0} : world.eulerZYX();
            g.elements.add(new Element(cube, from, to, pivot, rotation));
            grow(preLo, preHi, from);
            grow(preLo, preHi, to);
            for (int corner = 0; corner < 8; corner++) grow(lo, hi, world.apply(corner(cube.from, cube.to, cube.inflate, corner)));
        }
        if (g.elements.isEmpty()) throw new ModelException("every visible cube is a hitbox helper - there is nothing to draw");
        g.bounds = new Box(lo, hi);
        g.hitboxFromModel = !g.hitboxes.isEmpty();
        if (!g.hitboxFromModel) g.hitboxes.add(g.bounds);

        // Java block/item projects are built inside the 0..16 block, whose floor centre is (8, 0, 8);
        // every other Blockbench format has its ground centre at the world origin.
        g.origin = model.format.toLowerCase(Locale.ROOT).startsWith("java") ? new double[] {8, 0, 8} : new double[] {0, 0, 0};

        g.fitCenter = new double[] {(preLo[0] + preHi[0]) / 2, (preLo[1] + preHi[1]) / 2, (preLo[2] + preHi[2]) / 2};
        double halfExtent = 0;
        for (int i = 0; i < 3; i++) halfExtent = Math.max(halfExtent, (preHi[i] - preLo[i]) / 2);
        g.fitScale = halfExtent > ELEMENT_HALF_RANGE ? ELEMENT_HALF_RANGE / halfExtent : 1;

        for (BbModel.Locator locator : model.locators) {
            if (!locator.name.toLowerCase(Locale.ROOT).startsWith("seat")) continue;
            Affine parent = world(locator.group, groupTransforms);
            double[] position = parent.apply(locator.position);
            // The seat looks along the locator's "north" (-Z), like the model's own front.
            double[] forward = parent.mul(Affine.rotationZYX(locator.rotation[0], locator.rotation[1], locator.rotation[2])).applyDirection(new double[] {0, 0, -1});
            g.seats.add(new SeatPoint(locator.name, position, yawOffsetOf(forward, locator.name, log)));
        }
        g.seats.sort(Comparator.comparingInt((SeatPoint s) -> trailingNumber(s.name())).thenComparing(SeatPoint::name));
        return g;
    }

    /**
     * How far a seat is turned away from the chair's front, as a Minecraft yaw offset.
     * <p>
     * Let {@code phi} be the angle that turns the model's front (0, 0, -1) into {@code forward} about +Y:
     * {@code Ry(phi) * (0,0,-1) = (-sin phi, 0, -cos phi)}, so {@code phi = atan2(-fx, -fz)}. Minecraft yaw grows
     * clockwise seen from above while {@code phi} grows counter-clockwise, hence the offset is {@code -phi}.
     */
    static double yawOffsetOf(double[] forward, String name, ModelLog log) {
        if (Math.hypot(forward[0], forward[2]) < 1e-6) {
            log.warn("locator '" + name + "'", "points straight up or down - the seat faces the chair's front instead");
            return 0;
        }
        double offset = -Math.toDegrees(Math.atan2(-forward[0], -forward[2]));
        return Math.abs(offset) < 1e-9 ? 0 : offset;
    }

    /** Model pixels -> blocks relative to the chair's origin, still in model axes. */
    public double[] toChairSpace(double[] px) {
        return new double[] {(px[0] - origin[0]) / 16, (px[1] - origin[1]) / 16, (px[2] - origin[2]) / 16};
    }

    public Box toChairSpace(Box px) { return new Box(toChairSpace(px.min()), toChairSpace(px.max())); }

    /** Model pixels -> the coordinate written into the item model JSON (recentred on 8,8,8 and shrunk to fit). */
    public double[] toElementSpace(double[] px) {
        return new double[] {8 + fitScale * (px[0] - fitCenter[0]), 8 + fitScale * (px[1] - fitCenter[1]), 8 + fitScale * (px[2] - fitCenter[2])};
    }

    private static Affine world(BbModel.Group group, Map<BbModel.Group, Affine> cache) {
        if (group == null) return Affine.IDENTITY;
        Affine cached = cache.get(group);
        if (cached != null) return cached;
        Affine world = world(group.parent, cache).mul(Affine.rotationAbout(group.origin, group.rotation));
        cache.put(group, world);
        return world;
    }

    private static double[] corner(double[] from, double[] to, double inflate, int corner) {
        return new double[] {
            (corner & 1) == 0 ? from[0] - inflate : to[0] + inflate,
            (corner & 2) == 0 ? from[1] - inflate : to[1] + inflate,
            (corner & 4) == 0 ? from[2] - inflate : to[2] + inflate};
    }

    private static void grow(double[] lo, double[] hi, double[] p) {
        for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], p[i]); hi[i] = Math.max(hi[i], p[i]); }
    }

    /** seat_2 -> 2, seat_10 -> 10, seat_main -> 0: so that seats are ordered the way they are numbered. */
    private static int trailingNumber(String name) {
        int end = name.length(), start = end;
        while (start > 0 && Character.isDigit(name.charAt(start - 1))) start--;
        if (start == end || end - start > 6) return 0;
        return Integer.parseInt(name.substring(start, end));
    }
}

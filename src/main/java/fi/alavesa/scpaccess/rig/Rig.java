package fi.alavesa.scpaccess.rig;

import fi.alavesa.scpaccess.model.BbModel;
import fi.alavesa.scpaccess.model.ModelGeometry;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A bbmodel split into animated bones, plus the maths that poses them. No Bukkit: unit-testable.
 * <p>
 * Frames: model px (Blockbench axes) -> {@code origin} is the point put on the object's anchor. A bone's item
 * model holds its cubes at their REST position in model px, so posing a bone is one matrix
 * {@code D = W_t(group) * W_rest(group)^-1} applied on top of the rest geometry, where {@code W} is the chain
 * of group transforms {@code T(origin + pos) * Rzyx(rest + rot) * S(scale) * T(-origin)} from the root down.
 * <p>
 * Keyframe signs follow Blockbench: files from 4.x store rotation x,y and position x mirrored relative to the
 * rest pose (BetterModel bytecode, see the memory note on animation signs); 5.x files are used as they are.
 */
public final class Rig {

    /** Pack id, e.g. {@code door/default_3x3}: item model {@code scpaccess:<id>}, bones {@code scpaccess:<id>/<key>}. */
    public final String id;
    public final BbModel model;
    public final double[] origin;
    public final List<Bone> bones = new ArrayList<>();
    /** The whole rest pose as one model (inventory icon, held item) and its fit; null when nothing is left to draw. */
    public BbModel icon;
    public ModelGeometry iconGeometry;
    /** Bounds of everything that is drawn at rest, px relative to the model origin. */
    public double[] boundsMin = {0, 0, 0}, boundsMax = {0, 0, 0};
    /** Hitbox cubes (px, relative to the origin, axis aligned), may be empty. */
    public final List<double[][]> hitboxes = new ArrayList<>();
    /** Locators by lower-case name: position in px relative to the origin, at rest. */
    public final Map<String, double[]> locators = new HashMap<>();
    final Map<BbModel.Group, Matrix4d> restWorld = new HashMap<>();
    final double[] rotSign, posSign;

    Rig(String id, BbModel model, double[] origin) {
        this.id = id;
        this.model = model;
        this.origin = origin;
        int major = 4;
        try { major = Integer.parseInt(model.formatVersion.split("\\.")[0].trim()); } catch (RuntimeException ignored) { }
        boolean legacy = major < 5;
        rotSign = legacy ? new double[] {-1, -1, 1} : new double[] {1, 1, 1};
        posSign = legacy ? new double[] {-1, 1, 1} : new double[] {1, 1, 1};
    }

    public BbModel.Animation clip(String name) {
        BbModel.Animation exact = model.animations.get(name);
        if (exact != null) return exact;
        for (BbModel.Animation a : model.animations.values()) if (a.name.equalsIgnoreCase(name)) return a;
        return null;
    }

    public boolean hasClip(String name) { return clip(name) != null; }

    /** Seconds the clip runs; 0 when there is no such clip. */
    public double length(String name) {
        BbModel.Animation a = clip(name);
        return a == null ? 0 : a.length;
    }

    /**
     * Pose matrix of every bone (index = {@link Bone#index()}) at time {@code t} of a clip; the rest pose when
     * {@code clip} is null. {@code t} is clamped to the clip.
     */
    public Matrix4d[] pose(BbModel.Animation clip, double t) {
        Matrix4d[] out = new Matrix4d[bones.size()];
        Map<BbModel.Group, Matrix4d> world = new HashMap<>();
        double time = clip == null ? 0 : Math.max(0, Math.min(clip.length, t));
        for (Bone bone : bones) {
            if (bone.group() == null || clip == null) { out[bone.index()] = new Matrix4d(); continue; }
            Matrix4d now = worldAt(bone.group(), clip, time, world);
            out[bone.index()] = new Matrix4d(now).mul(new Matrix4d(restWorld.get(bone.group())).invert());
        }
        return out;
    }

    private Matrix4d worldAt(BbModel.Group g, BbModel.Animation clip, double t, Map<BbModel.Group, Matrix4d> cache) {
        Matrix4d cached = cache.get(g);
        if (cached != null) return cached;
        Matrix4d parent = g.parent == null ? new Matrix4d() : worldAt(g.parent, clip, t, cache);
        Matrix4d local = local(g, clip.animators.get(g.uuid), t);
        Matrix4d world = new Matrix4d(parent).mul(local);
        cache.put(g, world);
        return world;
    }

    /** {@code T(origin + pos) * Rzyx(rest + rot) * S(scale) * T(-origin)} */
    Matrix4d local(BbModel.Group g, BbModel.Animator animator, double t) {
        double[] pos = {0, 0, 0}, rot = {0, 0, 0}, scale = {1, 1, 1};
        if (animator != null) {
            sample(animator.position, t, pos);
            sample(animator.rotation, t, rot);
            sample(animator.scale, t, scale);
        }
        double[] o = g.origin;
        Matrix4d m = new Matrix4d().translation(o[0] + pos[0] * posSign[0], o[1] + pos[1] * posSign[1], o[2] + pos[2] * posSign[2]);
        m.rotateZYX(Math.toRadians(g.rotation[2] + rot[2] * rotSign[2]), Math.toRadians(g.rotation[1] + rot[1] * rotSign[1]), Math.toRadians(g.rotation[0] + rot[0] * rotSign[0]));
        m.scale(scale[0], scale[1], scale[2]);
        return m.translate(-o[0], -o[1], -o[2]);
    }

    static Matrix4d restLocal(BbModel.Group g) {
        double[] o = g.origin;
        return new Matrix4d().translation(o[0], o[1], o[2])
            .rotateZYX(Math.toRadians(g.rotation[2]), Math.toRadians(g.rotation[1]), Math.toRadians(g.rotation[0]))
            .translate(-o[0], -o[1], -o[2]);
    }

    /** Keyframe value at {@code t}; leaves {@code out} (the channel's default) when there are no keyframes. */
    static void sample(List<BbModel.Keyframe> frames, double t, double[] out) {
        if (frames.isEmpty()) return;
        BbModel.Keyframe first = frames.get(0), last = frames.get(frames.size() - 1);
        if (t <= first.time()) { System.arraycopy(first.value(), 0, out, 0, 3); return; }
        if (t >= last.time()) { System.arraycopy(last.value(), 0, out, 0, 3); return; }
        int i = 0;
        while (i + 1 < frames.size() - 1 && frames.get(i + 1).time() <= t) i++;
        BbModel.Keyframe a = frames.get(i), b = frames.get(i + 1);
        double span = b.time() - a.time();
        double f = span <= 1e-9 ? 1 : (t - a.time()) / span;
        if (a.interpolation().equals("step")) { System.arraycopy(a.value(), 0, out, 0, 3); return; }
        if (a.interpolation().equals("catmullrom") || b.interpolation().equals("catmullrom")) {
            double[] p0 = frames.get(Math.max(0, i - 1)).value(), p3 = frames.get(Math.min(frames.size() - 1, i + 2)).value();
            for (int k = 0; k < 3; k++) out[k] = catmull(p0[k], a.value()[k], b.value()[k], p3[k], f);
            return;
        }
        for (int k = 0; k < 3; k++) out[k] = a.value()[k] + (b.value()[k] - a.value()[k]) * f;
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    // ------------------------------------------------------------------ item display transformation

    /** What an item display needs to show one bone in a pose; all in blocks / unit-free. */
    public record DisplayPose(Vector3d translation, org.joml.Quaternionf left, org.joml.Vector3f scale) { }

    /**
     * The display transformation that draws {@code bone} posed by {@code delta} on an object turned to {@code yaw}
     * (Minecraft degrees). The derivation is ChairMath's: element coordinate g of an item_display lands at
     * {@code T + Rleft * S * Ry(180) * (g - 8)/16}; the bone's model wrote {@code g = 8 + k (x - c)}; the object wants
     * px x at {@code Ry(180 - yaw) * (M x - O)/16} with {@code M = delta * base}. So
     * {@code T = Ry(180-yaw) (M c - O)/16} and {@code Rleft * S = Ry(-yaw) * Ry(180) M_lin Ry(-180) / k}.
     */
    public DisplayPose displayPose(Bone bone, Matrix4d delta, float yaw) {
        return displayPose(bone.geometry(), bone.base(), delta, yaw);
    }

    /** Same for an item model that is not the bone's own (a keycard shown by a card bone): its fit and placement. */
    public DisplayPose displayPose(ModelGeometry g, Matrix4d base, Matrix4d delta, float yaw) {
        double[] c = g.fitCenter;
        double k = g.fitScale;
        Matrix4d m = new Matrix4d(delta).mul(base);
        Vector3d center = m.transformPosition(new Vector3d(c[0], c[1], c[2]));
        center.sub(origin[0], origin[1], origin[2]).div(16);
        Matrix4d turn = new Matrix4d().rotationY(Math.toRadians(180 - yaw));
        turn.transformDirection(center);

        // N = Ry(180) * M_lin * Ry(-180): the x and z axes of both sides flipped
        Matrix4d flip = new Matrix4d().rotationY(Math.PI);
        Matrix4d n = new Matrix4d(flip).mul(m.m30(0).m31(0).m32(0)).mul(new Matrix4d(flip).invert());
        Vector3d cx = n.getColumn(0, new Vector3d()), cy = n.getColumn(1, new Vector3d()), cz = n.getColumn(2, new Vector3d());
        double sx = cx.length(), sy = cy.length(), sz = cz.length();
        if (sx < 1e-9) sx = 1e-9;
        if (sy < 1e-9) sy = 1e-9;
        if (sz < 1e-9) sz = 1e-9;
        cx.div(sx); cy.div(sy); cz.div(sz);
        // a mirrored matrix (negative determinant) cannot be a rotation: keep the rotation proper and flip x
        if (cx.cross(cy, new Vector3d()).dot(cz) < 0) { cx.negate(); sx = -sx; }
        Matrix4d rot = new Matrix4d(cx.x, cx.y, cx.z, 0, cy.x, cy.y, cy.z, 0, cz.x, cz.y, cz.z, 0, 0, 0, 0, 1);
        Matrix4d left = new Matrix4d().rotationY(Math.toRadians(-yaw)).mul(rot);
        org.joml.Quaternionf q = left.getNormalizedRotation(new org.joml.Quaterniond()).get(new org.joml.Quaternionf());
        return new DisplayPose(center, q, new org.joml.Vector3f((float) (sx / k), (float) (sy / k), (float) (sz / k)));
    }

    /** Position of a point of the model (px, rest) in blocks relative to the anchor of an object turned to {@code yaw}. */
    public double[] toWorld(double[] px, float yaw) {
        Vector3d v = new Vector3d(px[0], px[1], px[2]);
        v.div(16);
        new Matrix4d().rotationY(Math.toRadians(180 - yaw)).transformDirection(v);
        return new double[] {v.x, v.y, v.z};
    }

    /** The position (blocks, relative to the anchor) a point of {@link #locators} (already relative to the origin) has. */
    public double[] locatorWorld(String name, float yaw) {
        double[] p = locators.get(name.toLowerCase(Locale.ROOT));
        return p == null ? null : toWorld(p, yaw);
    }

    /**
     * Click box of the object at rest, blocks relative to its anchor for an object turned to {@code yaw}:
     * {@code [minX, minY, minZ, maxX, maxY, maxZ]}. The model's "hitbox" cubes, else everything that is drawn.
     */
    public double[] worldBox(float yaw) {
        List<double[][]> boxes = new ArrayList<>(hitboxes);
        if (boxes.isEmpty()) boxes.add(new double[][] {boundsMin, boundsMax});
        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[][] b : boxes) {
            for (int corner = 0; corner < 8; corner++) {
                double[] px = {(corner & 1) == 0 ? b[0][0] : b[1][0], (corner & 2) == 0 ? b[0][1] : b[1][1], (corner & 4) == 0 ? b[0][2] : b[1][2]};
                double[] w = toWorld(px, yaw);
                for (int i = 0; i < 3; i++) { out[i] = Math.min(out[i], w[i]); out[i + 3] = Math.max(out[i + 3], w[i]); }
            }
        }
        return out;
    }
}

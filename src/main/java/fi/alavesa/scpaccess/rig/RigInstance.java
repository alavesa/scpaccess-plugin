package fi.alavesa.scpaccess.rig;

import fi.alavesa.scpaccess.model.ModelGeometry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Matrix4d;
import org.joml.Quaternionf;

import java.util.List;

/**
 * A placed rig: one non-persistent item display per bone, all at the object's anchor. The object's own
 * manager respawns it when the chunk loads (nothing of it is saved with the world).
 */
public final class RigInstance {

    /** What a card bone shows: the keycard's own item model and its fit. */
    public record CardLook(ItemStack stack, ModelGeometry geometry, double[] origin) { }

    public static final String NAMESPACE = "scpaccess";

    private final Rig rig;
    private final Location anchor;
    private final float yaw;
    private final ItemDisplay[] displays;
    private final float viewRange;
    private String state = "idle";
    private CardLook card;
    private boolean hidden;
    private Matrix4d[] pose;
    /** The clip that is running (cancelled by a new one), managed by {@link RigAnimator}. */
    RigAnimator.Playback playback;

    public RigInstance(Rig rig, Location anchor, float yaw, float viewRange) {
        this.rig = rig;
        this.anchor = anchor.clone();
        this.yaw = yaw;
        this.viewRange = viewRange;
        this.displays = new ItemDisplay[rig.bones.size()];
        this.pose = rest(rig);
    }

    private static Matrix4d[] rest(Rig rig) {
        Matrix4d[] m = new Matrix4d[rig.bones.size()];
        for (int i = 0; i < m.length; i++) m[i] = new Matrix4d();
        return m;
    }

    public Rig rig() { return rig; }

    public Location anchor() { return anchor.clone(); }

    public float yaw() { return yaw; }

    public String state() { return state; }

    /** The pack item for a bone ({@code scpaccess:<rig>/<bone key>}). */
    public static ItemStack stackFor(String path) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey(NAMESPACE, path));
        stack.setItemMeta(meta);
        return stack;
    }

    public void spawn() {
        World world = anchor.getWorld();
        double half = Math.max(Math.max(Math.abs(rig.boundsMin[0]), Math.abs(rig.boundsMax[0])), Math.max(Math.abs(rig.boundsMin[2]), Math.abs(rig.boundsMax[2]))) / 16;
        float width = (float) Math.max(2, half * 2 + 2), height = (float) Math.max(2, rig.boundsMax[1] / 16 + 2);
        for (Bone bone : rig.bones) {
            ItemDisplay d = world.spawn(anchor, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                display.setViewRange(viewRange);
                display.setDisplayWidth(width * 2);
                display.setDisplayHeight(height * 2);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(0);
                display.addScoreboardTag("scpaccess");
            });
            displays[bone.index()] = d;
        }
        refresh(0);
    }

    public boolean alive() {
        for (ItemDisplay d : displays) if (d == null || !d.isValid()) return false;
        return displays.length > 0;
    }

    public void remove() {
        RigAnimator.cancel(this);
        for (int i = 0; i < displays.length; i++) {
            if (displays[i] != null) displays[i].remove();
            displays[i] = null;
        }
    }

    public void setState(String newState) {
        if (newState.equals(state)) return;
        state = newState;
        refresh(0);
    }

    /** Hides every bone (a door without an "open" clip simply disappears while open). */
    public void hide(boolean hide) {
        if (hidden == hide) return;
        hidden = hide;
        refresh(0);
    }

    public void showCard(CardLook look) {
        card = look;
        refresh(0);
    }

    public void hideCard() {
        card = null;
        refresh(0);
    }

    /** Shows/hides bones by state and re-poses them. */
    private void refresh(int interpolation) { pose(pose, interpolation); }

    /**
     * Poses every bone.
     *
     * @param interpolation ticks the client takes to get there (0 = at once)
     */
    public void pose(Matrix4d[] deltas, int interpolation) {
        pose = deltas;
        for (Bone bone : rig.bones) {
            ItemDisplay d = displays[bone.index()];
            if (d == null || !d.isValid()) continue;
            boolean visible = !hidden && (bone.state() == null || bone.state().equals(state));
            if (bone.card()) {
                CardLook look = card;
                if (!visible || look == null) { d.setItemStack(new ItemStack(Material.AIR)); continue; }
                d.setItemStack(look.stack());
                Matrix4d base = new Matrix4d(bone.base()).translate(-look.origin()[0], -look.origin()[1], -look.origin()[2]);
                apply(d, rig.displayPose(look.geometry(), base, deltas[bone.index()], yaw), interpolation);
            } else {
                d.setItemStack(visible ? stackFor(rig.id + "/" + bone.key()) : new ItemStack(Material.AIR));
                if (visible) apply(d, rig.displayPose(bone, deltas[bone.index()], yaw), interpolation);
            }
        }
    }

    private static void apply(ItemDisplay d, Rig.DisplayPose p, int interpolation) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interpolation);
        d.setTransformation(new Transformation(
            new org.joml.Vector3f((float) p.translation().x, (float) p.translation().y, (float) p.translation().z),
            new Quaternionf(p.left()), new org.joml.Vector3f(p.scale()), new Quaternionf()));
    }

    /** All displays (to find which object an entity belongs to, or to clean up). */
    public List<ItemDisplay> displays() {
        java.util.ArrayList<ItemDisplay> out = new java.util.ArrayList<>();
        for (ItemDisplay d : displays) if (d != null) out.add(d);
        return out;
    }
}

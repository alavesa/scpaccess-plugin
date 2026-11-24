package fi.alavesa.scpaccess.rig;

import fi.alavesa.scpaccess.model.BbModel;
import fi.alavesa.scpaccess.model.ModelException;
import fi.alavesa.scpaccess.model.ModelGeometry;
import fi.alavesa.scpaccess.model.ModelLog;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Splits a model into bones.
 * <ul>
 *   <li>A group is a bone when an animation moves it, or when its name starts with {@code state_} (visible only in
 *       that state: {@code state_granted}) or {@code card} (shows the swiped keycard).</li>
 *   <li>Every cube belongs to its nearest bone group; cubes in no bone group make the static "base" bone.</li>
 *   <li>Cubes named or grouped {@code hitbox} are never drawn; they size the click box.</li>
 * </ul>
 */
public final class RigBuilder {

    private RigBuilder() { }

    public static Rig build(String packId, BbModel model, ModelLog log) throws ModelException {
        double[] origin = model.format.toLowerCase(Locale.ROOT).startsWith("java") ? new double[] {8, 0, 8} : new double[] {0, 0, 0};
        Rig rig = new Rig(packId, model, origin);

        // rest transforms of every group
        for (BbModel.Group g : model.groups.values()) world(rig, g);

        Set<String> animated = new HashSet<>();
        for (BbModel.Animation a : model.animations.values()) {
            for (var e : a.animators.entrySet()) {
                BbModel.Animator an = e.getValue();
                if (!an.position.isEmpty() || !an.rotation.isEmpty() || !an.scale.isEmpty()) animated.add(e.getKey());
            }
        }

        // cube -> bone group (null = base)
        Map<BbModel.Group, List<BbModel.Cube>> byBone = new LinkedHashMap<>();
        List<BbModel.Cube> base = new ArrayList<>();
        List<BbModel.Cube> iconCubes = new ArrayList<>();
        for (BbModel.Cube cube : model.cubes) {
            boolean hitbox = cube.name.toLowerCase(Locale.ROOT).startsWith("hitbox") || (cube.group != null && cube.group.isOrIsUnder("hitbox"));
            if (hitbox) { iconCubes.add(cube); continue; }
            BbModel.Group bone = boneGroup(cube.group, animated);
            if (bone == null) base.add(cube); else byBone.computeIfAbsent(bone, k -> new ArrayList<>()).add(cube);
            if (!underPrefix(cube.group, "card") && visibleAtRest(cube.group)) iconCubes.add(cube);
        }

        int index = 0;
        if (!base.isEmpty()) index = addBone(rig, index, "base", null, base, log);
        for (var e : byBone.entrySet()) {
            BbModel.Group g = e.getKey();
            if (isCard(g)) {
                String stateOfCard = stateOf(g);
                Matrix4d placement = new Matrix4d(rig.restWorld.get(g)).translate(g.origin[0], g.origin[1], g.origin[2]);
                rig.bones.add(new Bone(index++, null, g.name, g, true, stateOfCard, null, null, placement));
                continue;
            }
            index = addBone(rig, index, g.name, g, e.getValue(), log);
        }

        // inventory icon / held item: the rest pose in the idle state
        if (iconCubes.stream().anyMatch(c -> c.visible)) {
            rig.icon = copy(model, iconCubes);
            rig.iconGeometry = ModelGeometry.of(rig.icon, log);
            ModelGeometry.Box b = rig.iconGeometry.bounds;
            rig.boundsMin = new double[] {b.min()[0] - origin[0], b.min()[1] - origin[1], b.min()[2] - origin[2]};
            rig.boundsMax = new double[] {b.max()[0] - origin[0], b.max()[1] - origin[1], b.max()[2] - origin[2]};
            if (rig.iconGeometry.hitboxFromModel)
                for (ModelGeometry.Box h : rig.iconGeometry.hitboxes)
                    rig.hitboxes.add(new double[][] {
                        {h.min()[0] - origin[0], h.min()[1] - origin[1], h.min()[2] - origin[2]},
                        {h.max()[0] - origin[0], h.max()[1] - origin[1], h.max()[2] - origin[2]}});
        }
        if (rig.bones.stream().noneMatch(b -> !b.card()) && rig.icon == null)
            throw new ModelException("there is nothing to draw (every cube is a hitbox helper, hidden or a card placeholder)");

        for (BbModel.Locator l : model.locators) {
            Matrix4d w = l.group == null ? new Matrix4d() : rig.restWorld.get(l.group);
            Vector3d p = w.transformPosition(new Vector3d(l.position[0], l.position[1], l.position[2]));
            rig.locators.put(l.name.toLowerCase(Locale.ROOT), new double[] {p.x - origin[0], p.y - origin[1], p.z - origin[2]});
        }
        return rig;
    }

    private static int addBone(Rig rig, int index, String name, BbModel.Group group, List<BbModel.Cube> cubes, ModelLog log) {
        BbModel sub = copy(rig.model, cubes);
        try {
            ModelGeometry geometry = ModelGeometry.of(sub, log);
            String key = "b" + index + "_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
            rig.bones.add(new Bone(index, key, name, group, false, stateOf(group), sub, geometry, new Matrix4d()));
            return index + 1;
        } catch (ModelException e) {
            log.warn("group '" + name + "'", "has nothing to draw (" + e.getMessage() + ") and is skipped");
            return index;
        }
    }

    /** The same project (textures, resolution, display) with only these cubes. */
    static BbModel copy(BbModel model, List<BbModel.Cube> cubes) {
        BbModel sub = new BbModel(model.id);
        sub.format = model.format;
        sub.formatVersion = model.formatVersion;
        sub.resolutionWidth = model.resolutionWidth;
        sub.resolutionHeight = model.resolutionHeight;
        sub.display = model.display;
        sub.textures.addAll(model.textures);
        sub.cubes.addAll(cubes);
        sub.groups.putAll(model.groups);
        return sub;
    }

    private static Matrix4d world(Rig rig, BbModel.Group g) {
        Matrix4d cached = rig.restWorld.get(g);
        if (cached != null) return cached;
        Matrix4d parent = g.parent == null ? new Matrix4d() : world(rig, g.parent);
        Matrix4d m = new Matrix4d(parent).mul(Rig.restLocal(g));
        rig.restWorld.put(g, m);
        return m;
    }

    private static BbModel.Group boneGroup(BbModel.Group g, Set<String> animated) {
        for (; g != null; g = g.parent) {
            String n = g.name.toLowerCase(Locale.ROOT);
            if (animated.contains(g.uuid) || n.startsWith("state_") || n.startsWith("card")) return g;
        }
        return null;
    }

    private static boolean isCard(BbModel.Group g) { return underPrefix(g, "card"); }

    private static boolean underPrefix(BbModel.Group g, String prefix) {
        for (; g != null; g = g.parent) if (g.name.toLowerCase(Locale.ROOT).startsWith(prefix)) return true;
        return false;
    }

    /** "granted" for a cube inside a group "state_granted", else null. */
    static String stateOf(BbModel.Group g) {
        for (; g != null; g = g.parent) {
            String n = g.name.toLowerCase(Locale.ROOT);
            if (n.startsWith("state_")) return n.substring(6);
        }
        return null;
    }

    private static boolean visibleAtRest(BbModel.Group g) {
        String s = stateOf(g);
        return s == null || s.equals("idle");
    }
}

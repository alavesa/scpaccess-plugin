package fi.alavesa.scpaccess.content;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Writes a Blockbench project (Generic Model, embedded textures, groups, keyframe animations) so the
 * default models are real .bbmodel files that open in Blockbench and double as templates for your own.
 * Animation keyframes use the Blockbench 5 convention (the values are applied as they are).
 */
final class ProjectWriter {

    private final String name;
    private final int resolution;
    private final JsonArray elements = new JsonArray(), outliner = new JsonArray(), textures = new JsonArray(), animations = new JsonArray();
    private final java.util.Map<String, String> groupUuids = new java.util.LinkedHashMap<>();

    ProjectWriter(String name, int resolution) {
        this.name = name;
        this.resolution = resolution;
    }

    String uuid(String key) { return UUID.nameUUIDFromBytes((name + "/" + key).getBytes(StandardCharsets.UTF_8)).toString(); }

    /** @return the texture index */
    int texture(String textureName, byte[] png) {
        JsonObject t = new JsonObject();
        t.addProperty("name", textureName + ".png");
        t.addProperty("uuid", uuid("texture:" + textureName));
        t.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        t.addProperty("uv_width", resolution);
        t.addProperty("uv_height", resolution);
        textures.add(t);
        return textures.size() - 1;
    }

    /** A cube with the whole of texture {@code tex} laid over every face, 1 texel per pixel (clipped to the texture). */
    JsonElement cube(String cubeName, double[] from, double[] to, int tex) { return cube(cubeName, from, to, tex, 0); }

    JsonElement cube(String cubeName, double[] from, double[] to, int tex, int lightEmission) {
        JsonObject cube = new JsonObject();
        cube.addProperty("name", cubeName);
        cube.addProperty("type", "cube");
        cube.addProperty("uuid", uuid("cube:" + cubeName));
        cube.add("from", array(from));
        cube.add("to", array(to));
        cube.add("origin", array((from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2));
        cube.addProperty("box_uv", false);
        if (lightEmission > 0) cube.addProperty("light_emission", lightEmission);
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        JsonObject faces = new JsonObject();
        faces.add("north", face(dx, dy, tex));
        faces.add("east", face(dz, dy, tex));
        faces.add("south", face(dx, dy, tex));
        faces.add("west", face(dz, dy, tex));
        faces.add("up", face(dx, dz, tex));
        faces.add("down", face(dx, dz, tex));
        cube.add("faces", faces);
        elements.add(cube);
        return new JsonPrimitive(uuid("cube:" + cubeName));
    }

    /** A cube whose faces show given rectangles of the texture (pixel coordinates), e.g. a card's front and back. */
    JsonElement cubeUv(String cubeName, double[] from, double[] to, int tex, double[] front, double[] back, double[] edge, int lightEmission) {
        JsonElement id = cube(cubeName, from, to, tex, lightEmission);
        JsonObject cube = elements.get(elements.size() - 1).getAsJsonObject();
        JsonObject faces = cube.getAsJsonObject("faces");
        faces.add("north", face(front, tex));
        faces.add("south", face(back, tex));
        for (String f : new String[] {"east", "west", "up", "down"}) faces.add(f, face(edge, tex));
        return id;
    }

    private static JsonObject face(double[] uv, int tex) {
        JsonObject f = new JsonObject();
        f.add("uv", array(uv));
        f.addProperty("texture", tex);
        return f;
    }

    private JsonObject face(double w, double h, int tex) {
        return face(new double[] {0, 0, Math.min(resolution, Math.max(0.5, w)), Math.min(resolution, Math.max(0.5, h))}, tex);
    }

    void locator(String locatorName, double x, double y, double z) {
        JsonObject l = new JsonObject();
        l.addProperty("name", locatorName);
        l.addProperty("type", "locator");
        l.addProperty("uuid", uuid("locator:" + locatorName));
        l.add("position", array(x, y, z));
        l.add("rotation", array(0, 0, 0));
        elements.add(l);
        outliner.add(uuid("locator:" + locatorName));
    }

    /** Adds a top-level group holding the given children (cube uuids or nested groups from {@link #nested}). */
    void group(String groupName, double[] origin, double[] rotation, JsonElement... children) {
        outliner.add(nested(groupName, origin, rotation, children));
    }

    JsonObject nested(String groupName, double[] origin, double[] rotation, JsonElement... children) {
        JsonObject g = new JsonObject();
        g.addProperty("name", groupName);
        g.addProperty("uuid", uuid("group:" + groupName));
        groupUuids.put(groupName, uuid("group:" + groupName));
        g.add("origin", array(origin));
        g.add("rotation", array(rotation));
        g.addProperty("isOpen", true);
        JsonArray kids = new JsonArray();
        for (JsonElement c : children) kids.add(c);
        g.add("children", kids);
        return g;
    }

    /** Cubes outside every group. */
    void loose(JsonElement cubeId) { outliner.add(cubeId); }

    /** Keyframe: {@code time} seconds, {@code v} = x, y, z. */
    record Key(String channel, double time, double... v) { }

    void animation(String animationName, double length, String loop, java.util.Map<String, Key[]> byGroup) {
        JsonObject a = new JsonObject();
        a.addProperty("uuid", uuid("animation:" + animationName));
        a.addProperty("name", animationName);
        a.addProperty("loop", loop);
        a.addProperty("length", length);
        JsonObject animators = new JsonObject();
        for (var e : byGroup.entrySet()) {
            String groupUuid = groupUuids.get(e.getKey());
            if (groupUuid == null) throw new IllegalStateException("no group " + e.getKey() + " for animation " + animationName);
            JsonObject animator = new JsonObject();
            animator.addProperty("name", e.getKey());
            animator.addProperty("type", "bone");
            JsonArray keyframes = new JsonArray();
            int n = 0;
            for (Key k : e.getValue()) {
                JsonObject kf = new JsonObject();
                kf.addProperty("channel", k.channel());
                kf.addProperty("uuid", uuid("kf:" + animationName + e.getKey() + n++));
                kf.addProperty("time", k.time());
                kf.addProperty("interpolation", "linear");
                JsonObject point = new JsonObject();
                point.addProperty("x", k.v()[0]);
                point.addProperty("y", k.v()[1]);
                point.addProperty("z", k.v()[2]);
                JsonArray points = new JsonArray();
                points.add(point);
                kf.add("data_points", points);
                keyframes.add(kf);
            }
            animator.add("keyframes", keyframes);
            animators.add(groupUuid, animator);
        }
        a.add("animators", animators);
        animations.add(a);
    }

    String toJson() {
        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "5.0");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
        JsonObject res = new JsonObject();
        res.addProperty("width", resolution);
        res.addProperty("height", resolution);
        JsonObject root = new JsonObject();
        root.add("meta", meta);
        root.addProperty("name", name);
        root.add("resolution", res);
        root.add("elements", elements);
        root.add("outliner", outliner);
        root.add("textures", textures);
        root.add("animations", animations);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    static JsonArray array(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }

    static double[] v(double x, double y, double z) { return new double[] {x, y, z}; }
}

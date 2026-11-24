package fi.alavesa.scpaccess.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.alavesa.scpaccess.util.Png;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * .bbmodel JSON -> {@link BbModel}. Reads Blockbench 4.x and 5.x projects of any cube-based format (Java
 * block/item, Generic, Bedrock, modded entity). Anything the plugin cannot use is skipped with a warning
 * naming the element; only a file that is not a usable model at all throws.
 */
public final class BbModelParser {

    public static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    /** Reads files that sit next to the .bbmodel (textures that are not embedded, .mcmeta sidecars). */
    @FunctionalInterface
    public interface SiblingFiles {
        /** @return the file's bytes, or null when there is no such file */
        byte[] read(String relativePath);
    }

    private BbModelParser() { }

    public static BbModel parse(String id, String json, SiblingFiles siblings, ModelLog log) throws ModelException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) throw new ModelException("the file is not a JSON object");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ModelException("the file is not valid JSON (" + e.getMessage() + ")");
        }
        if (!isArray(root, "elements")) throw new ModelException("no 'elements' array - is this a Blockbench project (.bbmodel)?");

        BbModel model = new BbModel(id);
        JsonObject meta = isObject(root, "meta") ? root.getAsJsonObject("meta") : new JsonObject();
        model.format = str(meta.get("model_format"), "");
        model.formatVersion = str(meta.get("format_version"), "");
        boolean projectBoxUv = bool(meta.get("box_uv"), false);
        if (isObject(root, "resolution")) {
            JsonObject r = root.getAsJsonObject("resolution");
            model.resolutionWidth = positive(num(r.get("width"), 16), 16);
            model.resolutionHeight = positive(num(r.get("height"), 16), 16);
        }
        if (isObject(root, "display")) model.display = root.getAsJsonObject("display");

        Map<String, Integer> textureIds = readTextures(root, model, siblings, log);

        Map<String, BbModel.Cube> cubesByUuid = new HashMap<>();
        Map<String, BbModel.Locator> locatorsByUuid = new HashMap<>();
        int index = 0;
        for (JsonElement el : root.getAsJsonArray("elements")) {
            index++;
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String type = str(e.get("type"), "cube");
            String name = str(e.get("name"), type + " #" + index);
            String uuid = str(e.get("uuid"), "element-" + index);
            switch (type) {
                case "cube" -> {
                    BbModel.Cube cube = readCube(e, name, uuid, model, textureIds, projectBoxUv, log);
                    if (cube != null) { model.cubes.add(cube); cubesByUuid.put(uuid, cube); }
                }
                case "locator", "null_object" -> {
                    BbModel.Locator locator = new BbModel.Locator();
                    locator.name = name;
                    // null objects were saved with "from" by old Blockbench versions
                    double[] position = vec3(e.get("position"));
                    if (position == null) position = vec3(e.get("from"));
                    if (position == null) { log.warn(type + " '" + name + "'", "has no position and was skipped"); break; }
                    locator.position = position;
                    double[] rotation = vec3(e.get("rotation"));
                    if (rotation != null) locator.rotation = rotation;
                    model.locators.add(locator);
                    locatorsByUuid.put(uuid, locator);
                }
                case "mesh" -> log.warn("mesh '" + name + "'", "meshes cannot be part of a Java item model (cubes only) and are skipped - rebuild it from cubes");
                default -> log.warn("element '" + name + "'", "type '" + type + "' is not supported and was skipped");
            }
        }

        // Blockbench 5 keeps a group's name/origin/rotation in a top-level "groups" table and only its uuid
        // and children in the outliner; 4.x keeps everything in the outliner node itself.
        Map<String, JsonObject> groupTable = new HashMap<>();
        if (isArray(root, "groups"))
            for (JsonElement g : root.getAsJsonArray("groups"))
                if (g.isJsonObject() && g.getAsJsonObject().has("uuid")) groupTable.put(str(g.getAsJsonObject().get("uuid"), ""), g.getAsJsonObject());
        if (isArray(root, "outliner"))
            for (JsonElement node : root.getAsJsonArray("outliner"))
                readOutliner(node, null, model, groupTable, cubesByUuid, locatorsByUuid, 0);

        if (isArray(root, "animations")) readAnimations(root.getAsJsonArray("animations"), model, log);
        if (model.cubes.stream().noneMatch(c -> c.visible)) throw new ModelException("the model has no visible cube elements");
        return model;
    }

    // ------------------------------------------------------------------ textures

    /** @return texture uuid/id -> index, for faces that reference a texture by uuid instead of by index */
    private static Map<String, Integer> readTextures(JsonObject root, BbModel model, SiblingFiles siblings, ModelLog log) {
        Map<String, Integer> ids = new HashMap<>();
        if (!isArray(root, "textures")) return ids;
        Set<String> usedKeys = new HashSet<>();
        for (JsonElement el : root.getAsJsonArray("textures")) {
            int index = model.textures.size();
            JsonObject t = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            String rawName = str(t.get("name"), "texture" + index);
            String key = rawName.replaceAll("(?i)\\.png$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (key.isEmpty()) key = "texture" + index;
            if (!usedKeys.add(key)) { key = key + "_" + index; usedKeys.add(key); }

            byte[] png = null;
            String source = str(t.get("source"), "");
            int comma = source.indexOf("base64,");
            if (source.startsWith("data:") && comma > 0) {
                try {
                    png = Base64.getDecoder().decode(source.substring(comma + 7).trim());
                } catch (IllegalArgumentException ex) {
                    log.warn("texture '" + rawName + "'", "the embedded image is not valid base64");
                }
            }
            String fileName = rawName.toLowerCase(Locale.ROOT).endsWith(".png") ? rawName : rawName + ".png";
            if (png == null && siblings != null) {
                // not embedded: look next to the .bbmodel by relative path, by name, then by the file name of the saved path
                String path = str(t.get("path"), "").replace('\\', '/');
                String[] candidates = {str(t.get("relative_path"), ""), fileName, path.substring(path.lastIndexOf('/') + 1)};
                for (String candidate : candidates) {
                    if (candidate.isEmpty()) continue;
                    png = siblings.read(candidate);
                    if (png != null) break;
                }
            }
            int[] size = Png.size(png);
            if (png != null && size == null) {
                log.warn("texture '" + rawName + "'", "is not a PNG image - faces using it show the missing texture");
                png = null;
            } else if (png == null) {
                log.warn("texture '" + rawName + "'", "is not embedded in the file and no '" + fileName + "' was found next to it - faces using it show the missing texture");
            }

            double uvWidth = positive(num(t.get("uv_width"), model.resolutionWidth), model.resolutionWidth);
            double uvHeight = positive(num(t.get("uv_height"), model.resolutionHeight), model.resolutionHeight);
            byte[] mcmeta = siblings == null ? null : siblings.read(fileName + ".mcmeta");

            // Flipbook textures: Blockbench stacks the frames vertically, and the UV space is ONE frame, so
            // frames = image aspect / UV aspect = (pngH * uvW) / (pngW * uvH). Only trusted when the texture
            // declares its own UV size (Blockbench 4.9+): against the project resolution a tall atlas of an
            // old file would be mistaken for a flipbook.
            int frames = 1;
            if (size != null && t.has("uv_width") && t.has("uv_height")) {
                double ratio = (size[1] * uvWidth) / (size[0] * uvHeight);
                long rounded = Math.round(ratio);
                if (rounded >= 2 && Math.abs(ratio - rounded) < 1e-6) frames = (int) rounded;
            }
            int frameTime = Math.max(1, (int) num(t.get("frame_time"), 1));
            boolean interpolate = bool(t.get("frame_interpolate"), false);

            model.textures.add(new BbModel.Texture(key, png, uvWidth, uvHeight, size == null ? 0 : size[0], size == null ? 0 : size[1], frames, frameTime, interpolate, mcmeta));
            ids.put(String.valueOf(index), index);
            for (String field : new String[] {"uuid", "id"}) {
                String v = str(t.get(field), "");
                if (!v.isEmpty()) ids.putIfAbsent(v, index);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ cubes

    private static BbModel.Cube readCube(JsonObject e, String name, String uuid, BbModel model, Map<String, Integer> textureIds, boolean projectBoxUv, ModelLog log) {
        double[] from = vec3(e.get("from")), to = vec3(e.get("to"));
        if (from == null || to == null) { log.warn("cube '" + name + "'", "has no valid from/to and was skipped"); return null; }
        BbModel.Cube cube = new BbModel.Cube();
        cube.uuid = uuid;
        cube.name = name;
        for (int i = 0; i < 3; i++) { cube.from[i] = Math.min(from[i], to[i]); cube.to[i] = Math.max(from[i], to[i]); }
        double[] origin = vec3(e.get("origin"));
        if (origin != null) cube.origin = origin;
        double[] rotation = vec3(e.get("rotation"));
        if (rotation != null) cube.rotation = rotation;
        cube.inflate = num(e.get("inflate"), 0);
        cube.visible = bool(e.get("visibility"), true) && bool(e.get("export"), true);
        cube.shade = bool(e.get("shade"), true);
        cube.lightEmission = (int) Math.max(0, Math.min(15, num(e.get("light_emission"), 0)));

        JsonObject faces = isObject(e, "faces") ? e.getAsJsonObject("faces") : new JsonObject();
        boolean boxUv = e.has("box_uv") ? bool(e.get("box_uv"), false) : projectBoxUv;
        Map<String, double[]> boxLayout = null;
        for (String side : FACES) {
            if (!isObject(faces, side)) continue;
            JsonObject f = faces.getAsJsonObject(side);
            Integer texture = textureIndex(f.get("texture"), textureIds);
            if (texture == null) continue; // a face without a texture is not rendered in Blockbench either
            if (texture >= model.textures.size()) {
                log.warn("cube '" + name + "'", "face '" + side + "' uses texture #" + texture + " which does not exist - face skipped");
                continue;
            }
            BbModel.Texture tex = model.textures.get(texture);
            double[] uv = vec4(f.get("uv"));
            if (uv == null && boxUv) {
                if (boxLayout == null) boxLayout = boxUvLayout(cube, vec2(e.get("uv_offset")), bool(e.get("mirror_uv"), false));
                uv = boxLayout.get(side);
            }
            if (uv == null) uv = new double[] {0, 0, tex.uvWidth(), tex.uvHeight()};
            int rot = ((int) num(f.get("rotation"), 0) % 360 + 360) % 360;
            if (rot % 90 != 0) { log.warn("cube '" + name + "'", "face '" + side + "' has a UV rotation of " + rot + " degrees; only 0/90/180/270 exist - using 0"); rot = 0; }
            String cull = str(f.get("cullface"), "");
            int tint = f.has("tint") ? (int) num(f.get("tint"), -1) : -1;
            cube.faces.put(side, new BbModel.Face(uv, texture, rot, isFaceName(cull) ? cull : null, tint));
        }
        return cube;
    }

    private static Integer textureIndex(JsonElement e, Map<String, Integer> ids) {
        if (e == null || !e.isJsonPrimitive()) return null;          // null / missing = no texture
        if (e.getAsJsonPrimitive().isBoolean()) return null;         // old files use "false"
        if (e.getAsJsonPrimitive().isNumber()) return e.getAsInt() < 0 ? null : e.getAsInt();
        return ids.get(e.getAsString());
    }

    /**
     * Blockbench's box-UV unwrap for a cube of size (w, h, d) at {@code uv_offset} (u, v) - the classic
     * Minecraft entity layout. Only used for old files whose faces carry no "uv" of their own.
     * <pre>
     *            u+d      u+d+w    u+d+2w
     *   v         | up     | down   |
     *   v+d  east | north  | west   | south
     * </pre>
     * Sizes are floored like Blockbench does. Up and down are stored flipped (u2 &lt; u1 / v2 &lt; v1).
     */
    static Map<String, double[]> boxUvLayout(BbModel.Cube c, double[] offset, boolean mirror) {
        double u = offset == null ? 0 : offset[0], v = offset == null ? 0 : offset[1];
        double w = Math.floor(c.to[0] - c.from[0] + 1e-7), h = Math.floor(c.to[1] - c.from[1] + 1e-7), d = Math.floor(c.to[2] - c.from[2] + 1e-7);
        Map<String, double[]> uv = new HashMap<>();
        uv.put("east", rect(u, v + d, d, h));
        uv.put("west", rect(u + d + w, v + d, d, h));
        uv.put("up", rect(u + d + w, v + d, -w, -d));
        uv.put("down", rect(u + d + 2 * w, v, -w, d));
        uv.put("south", rect(u + 2 * d + w, v + d, w, h));
        uv.put("north", rect(u + d, v + d, w, h));
        if (mirror) {
            // every face is flipped horizontally and the two sides trade places
            for (double[] r : uv.values()) { double t = r[0]; r[0] = r[2]; r[2] = t; }
            double[] east = uv.get("east");
            uv.put("east", uv.get("west"));
            uv.put("west", east);
        }
        return uv;
    }

    private static double[] rect(double u, double v, double w, double h) { return new double[] {u, v, u + w, v + h}; }

    // ------------------------------------------------------------------ animations

    private static void readAnimations(JsonArray animations, BbModel model, ModelLog log) {
        for (JsonElement el : animations) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            BbModel.Animation animation = new BbModel.Animation();
            animation.name = str(a.get("name"), "animation");
            animation.length = Math.max(0.05, num(a.get("length"), 1));
            animation.loop = str(a.get("loop"), "once").toLowerCase(Locale.ROOT);
            if (isObject(a, "animators")) {
                for (var entry : a.getAsJsonObject("animators").entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject animatorJson = entry.getValue().getAsJsonObject();
                    // only bones: effects, locators and cubes are not animated here
                    String type = str(animatorJson.get("type"), "bone");
                    if (!type.equals("bone") || !isArray(animatorJson, "keyframes")) continue;
                    BbModel.Animator animator = new BbModel.Animator();
                    for (JsonElement k : animatorJson.getAsJsonArray("keyframes")) {
                        if (!k.isJsonObject()) continue;
                        JsonObject key = k.getAsJsonObject();
                        if (!isArray(key, "data_points") || key.getAsJsonArray("data_points").isEmpty()) continue;
                        JsonElement first = key.getAsJsonArray("data_points").get(0);
                        if (!first.isJsonObject()) continue;
                        JsonObject point = first.getAsJsonObject();
                        double[] value = {num(point.get("x"), Double.NaN), num(point.get("y"), Double.NaN), num(point.get("z"), Double.NaN)};
                        String channel = str(key.get("channel"), "");
                        boolean scale = channel.equals("scale");
                        for (int i = 0; i < 3; i++) if (Double.isNaN(value[i])) value[i] = scale ? 1 : 0; // molang expressions are not evaluated
                        BbModel.Keyframe frame = new BbModel.Keyframe(num(key.get("time"), 0), value, str(key.get("interpolation"), "linear").toLowerCase(Locale.ROOT));
                        switch (channel) {
                            case "position" -> animator.position.add(frame);
                            case "rotation" -> animator.rotation.add(frame);
                            case "scale" -> animator.scale.add(frame);
                            default -> { }
                        }
                    }
                    Comparator<BbModel.Keyframe> byTime = Comparator.comparingDouble(BbModel.Keyframe::time);
                    animator.position.sort(byTime);
                    animator.rotation.sort(byTime);
                    animator.scale.sort(byTime);
                    animation.animators.put(entry.getKey(), animator);
                }
            }
            if (model.animations.containsKey(animation.name)) log.warn("animation '" + animation.name + "'", "the name is used twice - the later one wins");
            model.animations.put(animation.name, animation);
        }
    }

    // ------------------------------------------------------------------ outliner

    private static void readOutliner(JsonElement node, BbModel.Group parent, BbModel model, Map<String, JsonObject> groupTable,
                                     Map<String, BbModel.Cube> cubes, Map<String, BbModel.Locator> locators, int depth) {
        if (depth > 64) return; // a corrupt, self-referencing outliner must not overflow the stack
        if (node.isJsonPrimitive()) {
            String uuid = node.getAsString();
            BbModel.Cube cube = cubes.get(uuid);
            if (cube != null) cube.group = parent;
            BbModel.Locator locator = locators.get(uuid);
            if (locator != null) locator.group = parent;
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject o = node.getAsJsonObject();
        BbModel.Group group = new BbModel.Group();
        group.uuid = str(o.get("uuid"), "group-" + model.groups.size());
        JsonObject table = groupTable.get(group.uuid);
        JsonObject source = table != null && !o.has("name") ? table : o;
        group.name = str(source.get("name"), "group");
        double[] origin = vec3(source.get("origin"));
        if (origin != null) group.origin = origin;
        double[] rotation = vec3(source.get("rotation"));
        if (rotation != null) group.rotation = rotation;
        group.parent = parent;
        model.groups.put(group.uuid, group);
        if (isArray(o, "children"))
            for (JsonElement child : o.getAsJsonArray("children"))
                readOutliner(child, group, model, groupTable, cubes, locators, depth + 1);
    }

    // ------------------------------------------------------------------ lenient JSON helpers

    private static boolean isObject(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject(); }

    private static boolean isArray(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonArray(); }

    private static boolean isFaceName(String s) {
        for (String f : FACES) if (f.equals(s)) return true;
        return false;
    }

    /** Blockbench sometimes writes numbers as strings ("0.5"); non-finite or unreadable values become {@code def}. */
    static double num(JsonElement e, double def) {
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            double v = e.getAsJsonPrimitive().isString() ? Double.parseDouble(e.getAsString().trim()) : e.getAsDouble();
            return Double.isFinite(v) ? v : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double positive(double v, double def) { return v > 0 ? v : def; }

    static String str(JsonElement e, String def) {
        return e == null || !e.isJsonPrimitive() ? def : e.getAsString();
    }

    static boolean bool(JsonElement e, boolean def) {
        if (e == null || !e.isJsonPrimitive()) return def;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if (e.getAsJsonPrimitive().isString()) return Boolean.parseBoolean(e.getAsString());
        return def;
    }

    /** @return the first {@code n} numbers of a JSON array, or null if it is not an array of at least n numbers */
    private static double[] vec(JsonElement e, int n) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < n) return null;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double v = num(a.get(i), Double.NaN);
            if (Double.isNaN(v)) return null;
            out[i] = v;
        }
        return out;
    }

    static double[] vec2(JsonElement e) { return vec(e, 2); }

    static double[] vec3(JsonElement e) { return vec(e, 3); }

    static double[] vec4(JsonElement e) { return vec(e, 4); }

    /** Convenience for callers holding the raw file. */
    public static BbModel parse(String id, byte[] file, SiblingFiles siblings, ModelLog log) throws ModelException {
        return parse(id, new String(file, StandardCharsets.UTF_8), siblings, log);
    }
}

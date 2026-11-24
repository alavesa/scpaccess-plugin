package fi.alavesa.scpaccess.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * {@link ModelGeometry} -> the Java item model JSON ({@code assets/<ns>/models/item/<id>.json}).
 * <p>
 * Written for the 26.3 model format (verified against the 26.3 client's {@code CuboidModelElement}
 * deserializer): {@code from}/{@code to} within -16..32, {@code rotation} either
 * {@code {origin, axis, angle}} or {@code {origin, x, y, z}} with free angles, faces with {@code uv},
 * {@code texture}, {@code rotation}, {@code cullface}, {@code tintindex}; {@code light_emission} 0..15.
 * The old per-element {@code "shade": false} no longer exists - {@code shade_direction_override} replaces it.
 */
public final class JavaModelWriter {

    private final String namespace;

    public JavaModelWriter(String namespace) { this.namespace = namespace; }

    /** Resource location of a model texture, without the "textures/" prefix and ".png" suffix. */
    public String textureLocation(BbModel model, BbModel.Texture texture) {
        return namespace + ":item/" + model.id + "/" + texture.key();
    }

    public JsonObject write(BbModel model, ModelGeometry geometry, ModelLog log) {
        JsonObject out = new JsonObject();

        JsonObject textures = new JsonObject();
        for (int i = 0; i < model.textures.size(); i++) {
            String location = textureLocation(model, model.textures.get(i));
            if (i == 0) textures.addProperty("particle", location);
            textures.addProperty(String.valueOf(i), location);
        }
        out.add("textures", textures);

        JsonArray elements = new JsonArray();
        for (ModelGeometry.Element element : geometry.elements) {
            JsonObject json = element(model, geometry, element);
            if (json == null) {
                log.warn("cube '" + element.cube().name + "'", "has no textured face and was left out");
                continue;
            }
            elements.add(json);
        }
        out.add("elements", elements);
        out.add("display", DisplayTransforms.build(model.display, geometry, log));
        return out;
    }

    private JsonObject element(BbModel model, ModelGeometry geometry, ModelGeometry.Element element) {
        BbModel.Cube cube = element.cube();
        JsonObject faces = new JsonObject();
        for (Map.Entry<String, BbModel.Face> entry : cube.faces.entrySet()) {
            BbModel.Face face = entry.getValue();
            BbModel.Texture texture = model.textures.get(face.texture());
            JsonObject f = new JsonObject();
            f.add("uv", array(scaleUv(face.uv(), texture.uvWidth(), texture.uvHeight())));
            f.addProperty("texture", "#" + face.texture());
            if (face.rotation() != 0) f.addProperty("rotation", face.rotation());
            if (face.cullface() != null) f.addProperty("cullface", face.cullface());
            if (face.tint() >= 0) f.addProperty("tintindex", face.tint());
            faces.add(entry.getKey(), f);
        }
        if (faces.isEmpty()) return null; // the client rejects an element with 0 faces

        JsonObject json = new JsonObject();
        json.add("from", array(geometry.toElementSpace(element.from())));
        json.add("to", array(geometry.toElementSpace(element.to())));
        double[] r = element.rotation();
        int nonZero = 0, axis = 0;
        for (int i = 0; i < 3; i++) if (Math.abs(r[i]) > 1e-6) { nonZero++; axis = i; }
        if (nonZero > 0) {
            JsonObject rotation = new JsonObject();
            rotation.add("origin", array(geometry.toElementSpace(element.pivot())));
            if (nonZero == 1) {
                // the classic single-axis form, kept where it is enough because every tool can read it
                rotation.addProperty("axis", axis == 0 ? "x" : axis == 1 ? "y" : "z");
                rotation.addProperty("angle", round(r[axis]));
            } else {
                rotation.addProperty("x", round(r[0]));
                rotation.addProperty("y", round(r[1]));
                rotation.addProperty("z", round(r[2]));
            }
            json.add("rotation", rotation);
        }
        if (!cube.shade) json.addProperty("shade_direction_override", "up");
        if (cube.lightEmission > 0) json.addProperty("light_emission", cube.lightEmission);
        json.add("faces", faces);
        return json;
    }

    /**
     * Blockbench stores face UVs in the texture's own UV space ({@code uvWidth} x {@code uvHeight}, e.g.
     * 64 x 32); a Java model always addresses a texture as 0..16 on both axes, whatever its pixel size.
     * So {@code u' = u * 16 / uvWidth} and {@code v' = v * 16 / uvHeight}. The corner order
     * {@code [u1, v1, u2, v2]} is kept as it is - a flipped face (u2 &lt; u1) stays flipped.
     * For a flipbook texture the UV space is one frame, and so is the client's 0..16.
     */
    public static double[] scaleUv(double[] uv, double uvWidth, double uvHeight) {
        return new double[] {uv[0] * 16 / uvWidth, uv[1] * 16 / uvHeight, uv[2] * 16 / uvWidth, uv[3] * 16 / uvHeight};
    }

    static JsonArray array(double[] values) {
        JsonArray a = new JsonArray();
        for (double v : values) a.add(round(v));
        return a;
    }

    /** 4 decimals is far below a texel and keeps the JSON (and so the pack hash) stable. */
    static double round(double v) {
        double r = Math.round(v * 10000.0) / 10000.0;
        return r == 0 ? 0 : r; // no "-0.0"
    }
}

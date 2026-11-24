package fi.alavesa.scpaccess.model;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of a Blockbench project the plugin uses, as plain data. All coordinates are Blockbench pixels
 * (16 px = 1 block), all rotations are degrees in Blockbench's order (see
 * {@link fi.alavesa.scpaccess.util.Affine#rotationZYX}).
 */
public final class BbModel {

    /** Chair id: the sanitized, lower-case file name. */
    public final String id;
    /** {@code meta.model_format}, e.g. "java_block", "free", "bedrock". Empty when missing. */
    public String format = "";
    public String formatVersion = "";
    /** Project texture resolution - the UV space of textures that do not declare their own. */
    public double resolutionWidth = 16, resolutionHeight = 16;

    public final List<Texture> textures = new ArrayList<>();
    public final List<Cube> cubes = new ArrayList<>();
    public final Map<String, Group> groups = new LinkedHashMap<>();
    public final List<Locator> locators = new ArrayList<>();
    /** Animations by name (lower-case ids are looked up by exact Blockbench name first, then case-insensitively). */
    public final Map<String, Animation> animations = new LinkedHashMap<>();
    /** The project's own {@code display} section (Java block/item format only), or null. */
    public JsonObject display;

    public BbModel(String id) { this.id = id; }

    /**
     * @param key        file-name-safe texture name, unique in the model
     * @param png        the image, or null when it could not be found (renders as the missing texture)
     * @param uvWidth    width of this texture's UV space (per-texture in Blockbench 4.9+, else the project resolution)
     * @param uvHeight   height of this texture's UV space (one animation frame)
     * @param frames     number of animation frames stacked vertically in the image (1 = not animated)
     * @param frameTime  ticks per frame
     * @param mcmeta     a ready .png.mcmeta found next to the model, or null
     */
    public record Texture(String key, byte[] png, double uvWidth, double uvHeight, int pngWidth, int pngHeight,
                          int frames, int frameTime, boolean interpolate, byte[] mcmeta) { }

    /** One animation: per group (by uuid) the keyframes of the three channels. */
    public static final class Animation {
        public String name = "";
        /** seconds */
        public double length;
        /** "once", "hold" or "loop" */
        public String loop = "once";
        public final Map<String, Animator> animators = new LinkedHashMap<>();
    }

    public static final class Animator {
        public final List<Keyframe> position = new ArrayList<>(), rotation = new ArrayList<>(), scale = new ArrayList<>();
    }

    /** @param time seconds; @param value x, y, z; @param interpolation "linear", "catmullrom", "step" or "bezier" */
    public record Keyframe(double time, double[] value, String interpolation) { }

    public static final class Group {
        public String uuid = "";
        public String name = "";
        public double[] origin = {0, 0, 0};
        public double[] rotation = {0, 0, 0};
        public Group parent;

        /** Is this group, or any group above it, named so that {@code name} starts with the prefix? */
        public boolean isOrIsUnder(String lowerCasePrefix) {
            for (Group g = this; g != null; g = g.parent)
                if (g.name.toLowerCase(java.util.Locale.ROOT).startsWith(lowerCasePrefix)) return true;
            return false;
        }
    }

    public static final class Cube {
        public String uuid = "";
        public String name = "";
        public double[] from = {0, 0, 0};
        public double[] to = {0, 0, 0};
        public double[] origin = {0, 0, 0};
        public double[] rotation = {0, 0, 0};
        public double inflate;
        /** Blockbench's eye icon / "export" checkbox: hidden cubes are left out of the model. */
        public boolean visible = true;
        /** false = Blockbench "shade" unticked (flat lighting). */
        public boolean shade = true;
        /** 0..15. */
        public int lightEmission;
        /** Faces by direction name ("north", "east", "south", "west", "up", "down"); faces without a texture are absent. */
        public final Map<String, Face> faces = new LinkedHashMap<>();
        public Group group;
    }

    /**
     * @param uv       {@code [u1, v1, u2, v2]} in the texture's UV space
     * @param texture  index into {@link BbModel#textures}
     * @param rotation 0, 90, 180 or 270
     * @param cullface direction name or null
     * @param tint     tint index, -1 = none
     */
    public record Face(double[] uv, int texture, int rotation, String cullface, int tint) { }

    /** A Blockbench locator or null object. */
    public static final class Locator {
        public String name = "";
        public double[] position = {0, 0, 0};
        public double[] rotation = {0, 0, 0};
        public Group group;
    }
}

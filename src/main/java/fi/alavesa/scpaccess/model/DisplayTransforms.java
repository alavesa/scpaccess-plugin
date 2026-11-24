package fi.alavesa.scpaccess.model;

import com.google.gson.JsonObject;
import fi.alavesa.scpaccess.util.Affine;

/**
 * The {@code display} section of the item model: how the chair ITEM looks in the hand, the inventory, on
 * the ground and in an item frame. (A placed chair does not use it - its item_display renders with the
 * {@code none} context and is positioned by the entity's own transformation.)
 *
 * <h2>Compensating for the fit</h2>
 * The client applies a display transform to the model as {@code t + R * S * (g - 8)/16}, where {@code g} is
 * the element coordinate. The author's transform was meant for the original coordinates {@code x} around an
 * anchor {@code A}: {@code t + R * S * f * (x - A)/16}. Our elements are {@code g = 8 + k * (x - c)} (fit
 * centre c, fit scale k), so writing
 * <pre>   S' = S * f / k        t' = t + R * (S * f * (c - A)) </pre>
 * gives the same picture. With a display section from the file, {@code A} is the block centre (8,8,8) and
 * {@code f} is 1. Without one, vanilla's block-item defaults are used around the model's own centre, and
 * {@code f} shrinks models larger than a block so they still fit the inventory slot.
 */
final class DisplayTransforms {

    private static final String[] CONTEXTS = {"thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand",
        "firstperson_lefthand", "gui", "head", "ground", "fixed"};
    /** The client clamps display translation to +-80 px (5 blocks) and scale to +-4. */
    private static final double MAX_TRANSLATION = 80, MAX_SCALE = 4;

    private record Transform(double[] rotation, double[] translation, double[] scale) { }

    private DisplayTransforms() { }

    static JsonObject build(JsonObject fileDisplay, ModelGeometry g, ModelLog log) {
        boolean fromFile = fileDisplay != null && !fileDisplay.isEmpty();
        ModelGeometry.Box b = g.bounds;
        double[] anchor = fromFile ? new double[] {8, 8, 8} : new double[] {b.center(0), b.center(1), b.center(2)};
        double extent = Math.max(b.size(0), Math.max(b.size(1), b.size(2)));
        double f = fromFile || extent <= 16 ? 1 : 16 / extent;

        JsonObject out = new JsonObject();
        boolean clamped = false;
        for (String context : CONTEXTS) {
            Transform t = fromFile ? read(fileDisplay, context) : defaults(context);
            if (t == null) continue; // not set in the file: the client's identity default, as in Blockbench

            Affine rotation = Affine.rotationX(t.rotation()[0]).mul(Affine.rotationY(t.rotation()[1])).mul(Affine.rotationZ(t.rotation()[2]));
            double[] offset = new double[3], scale = new double[3];
            for (int i = 0; i < 3; i++) {
                offset[i] = t.scale()[i] * f * (g.fitCenter[i] - anchor[i]);
                scale[i] = t.scale()[i] * f / g.fitScale;
            }
            double[] translation = rotation.applyDirection(offset);
            for (int i = 0; i < 3; i++) {
                translation[i] += t.translation()[i];
                if (Math.abs(translation[i]) > MAX_TRANSLATION || Math.abs(scale[i]) > MAX_SCALE) clamped = true;
                translation[i] = Math.max(-MAX_TRANSLATION, Math.min(MAX_TRANSLATION, translation[i]));
                scale[i] = Math.max(-MAX_SCALE, Math.min(MAX_SCALE, scale[i]));
            }
            JsonObject json = new JsonObject();
            json.add("rotation", JavaModelWriter.array(t.rotation()));
            json.add("translation", JavaModelWriter.array(translation));
            json.add("scale", JavaModelWriter.array(scale));
            out.add(context, json);
        }
        if (clamped) log.warn("", "the model is so large or so far from the origin that the item's hand/inventory view hits the client's display limits (80 px, scale 4) - the held item may look off; the placed chair is not affected");
        return out;
    }

    private static Transform read(JsonObject display, String context) {
        if (!display.has(context) || !display.get(context).isJsonObject()) return null;
        JsonObject o = display.getAsJsonObject(context);
        return new Transform(vec(o, "rotation", 0), vec(o, "translation", 0), vec(o, "scale", 1));
    }

    private static double[] vec(JsonObject o, String key, double def) {
        double[] v = BbModelParser.vec3(o.get(key));
        return v != null ? v : new double[] {def, def, def};
    }

    /** Vanilla's {@code block/block.json} transforms: a chair in the hand looks like a block item. */
    private static Transform defaults(String context) {
        return switch (context) {
            case "gui" -> uniform(30, 225, 0, 0, 0, 0, 0.625);
            case "ground" -> uniform(0, 0, 0, 0, 3, 0, 0.25);
            case "fixed" -> uniform(0, 0, 0, 0, 0, 0, 0.5);
            case "thirdperson_righthand", "thirdperson_lefthand" -> uniform(75, 45, 0, 0, 2.5, 0, 0.375);
            case "firstperson_righthand" -> uniform(0, 45, 0, 0, 0, 0, 0.4);
            case "firstperson_lefthand" -> uniform(0, 225, 0, 0, 0, 0, 0.4);
            default -> uniform(0, 0, 0, 0, 0, 0, 1);
        };
    }

    private static Transform uniform(double rx, double ry, double rz, double tx, double ty, double tz, double s) {
        return new Transform(new double[] {rx, ry, rz}, new double[] {tx, ty, tz}, new double[] {s, s, s});
    }
}

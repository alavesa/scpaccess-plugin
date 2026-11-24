package fi.alavesa.scpaccess.content;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;

import static fi.alavesa.scpaccess.content.ProjectWriter.Key;
import static fi.alavesa.scpaccess.content.ProjectWriter.v;

/**
 * The models written into an empty plugin folder. All of them are real Blockbench projects and double as
 * templates: group names are how the plugin finds things (see the README).
 * <ul>
 *   <li>doors: static frame + sliding leaves, clips {@code open} / {@code close}, lamps {@code state_closed / state_open / state_locked}</li>
 *   <li>reader: body (clip {@code denied} shakes it), {@code card} group (clip {@code swipe}), lamps {@code state_idle / state_granted / state_denied}, locator {@code screen}</li>
 *   <li>sensor: head (clip {@code detect} spins it), lamps {@code state_idle / state_active}</li>
 *   <li>cards, link tool</li>
 * </ul>
 */
public final class DefaultModels {

    private DefaultModels() { }

    /** Door leaves slide into the posts; 1-wide doors have one leaf. */
    public static String door(int width, int height) {
        ProjectWriter p = new ProjectWriter("door_" + width + "x" + height, 64);
        int metal = p.texture("metal", DefaultTextures.metal());
        int frame = p.texture("frame", DefaultTextures.frame());
        int lamps = p.texture("lamps", DefaultTextures.lamps());
        double half = width * 8, top = height * 16;

        p.loose(p.cube("post_left", v(-half, 0, -2.5), v(-half + 2, top, 2.5), frame));
        p.loose(p.cube("post_right", v(half - 2, 0, -2.5), v(half, top, 2.5), frame));
        p.loose(p.cube("beam", v(-half + 2, top - 3, -2.5), v(half - 2, top, 2.5), frame));

        double lampX = half - 1.6;
        double[][] swatches = {{0, 0, 5, 5}, {5, 0, 10, 5}, {10, 0, 15, 5}};
        String[] states = {"state_closed", "state_open", "state_locked"};
        for (int i = 0; i < 3; i++) {
            JsonElement lamp = p.cubeUv("lamp_" + i, v(lampX, 21, -2.7), v(lampX + 1.2, 24, -2.5), lamps, swatches[i], swatches[i], swatches[i], 15);
            p.group(states[i], v(lampX, 21, -2.5), v(0, 0, 0), lamp);
        }

        Map<String, Key[]> open = new LinkedHashMap<>(), close = new LinkedHashMap<>();
        double leafTop = top - 3;
        if (width == 1) {
            p.group("leaf", v(0, 0, 0), v(0, 0, 0), p.cube("leaf", v(-half + 2, 0, -1), v(half - 2, leafTop, 1), metal));
            double d = 2 * half - 5;
            open.put("leaf", slide(-d, false));
            close.put("leaf", slide(-d, true));
        } else {
            double d = half - 3.5;
            p.group("leaf_left", v(0, 0, 0), v(0, 0, 0), p.cube("leaf_left", v(-half + 2, 0, -1), v(0, leafTop, 1), metal));
            p.group("leaf_right", v(0, 0, 0), v(0, 0, 0), p.cube("leaf_right", v(0, 0, -1), v(half - 2, leafTop, 1), metal));
            open.put("leaf_left", slide(-d, false));
            open.put("leaf_right", slide(d, false));
            close.put("leaf_left", slide(-d, true));
            close.put("leaf_right", slide(d, true));
        }
        p.animation("open", 0.8, "hold", open);
        p.animation("close", 0.8, "hold", close);
        return p.toJson();
    }

    private static Key[] slide(double distance, boolean reverse) {
        double[] t = {0, 0.3, 0.8}, f = {0, 0.25, 1};
        Key[] keys = new Key[3];
        for (int i = 0; i < 3; i++) {
            int j = reverse ? 2 - i : i;
            keys[i] = new Key("position", t[i], f[j] * distance, 0, 0);
        }
        return keys;
    }

    public static String reader() {
        ProjectWriter p = new ProjectWriter("reader", 32);
        int atlas = p.texture("reader", DefaultTextures.readerAtlas());
        double[] body = {0, 0, 16, 16}, glass = {16, 0, 32, 16}, black = {0, 16, 16, 32};
        double[][] lamp = {{16, 16, 24, 24}, {24, 16, 32, 24}, {16, 24, 24, 32}};
        double[] white = {24, 24, 32, 32};

        JsonElement b = p.cubeUv("body", v(-5, -7, -2.2), v(5, 7, 0), atlas, body, body, body, 0);
        JsonElement g = p.cubeUv("glass", v(-4, 1.5, -2.4), v(4, 5.5, -2.2), atlas, glass, glass, glass, 0);
        JsonElement s = p.cubeUv("slot", v(-4.5, -1.5, -2.4), v(4.5, -0.5, -2.2), atlas, black, black, black, 0);
        p.group("body", v(0, 0, -1), v(0, 0, 0), b, g, s);
        String[] states = {"state_idle", "state_granted", "state_denied"};
        for (int i = 0; i < 3; i++)
            p.group(states[i], v(0, -4.75, -2.3), v(0, 0, 0), p.cubeUv("lamp_" + i, v(-0.8, -5.5, -2.55), v(0.8, -4, -2.2), atlas, lamp[i], lamp[i], lamp[i], 15));
        // the card is only a marker: the plugin draws the keycard that was swiped at this place
        p.group("card", v(0, 0, -3.2), v(0, 0, 0), p.cubeUv("card_marker", v(-4.28, -2.7, -3.4), v(4.28, 2.7, -3.0), atlas, white, white, white, 0));
        p.locator("screen", 0, 3.5, -2.45);

        Map<String, Key[]> swipe = new LinkedHashMap<>();
        swipe.put("card", new Key[] {
            new Key("position", 0, 0, 14, 0), new Key("position", 0.25, -5, -1, 0),
            new Key("position", 0.5, 5, -1, 0), new Key("position", 0.75, 5, 14, 0)});
        p.animation("swipe", 0.75, "once", swipe);
        Map<String, Key[]> denied = new LinkedHashMap<>();
        denied.put("body", new Key[] {
            new Key("position", 0, 0, 0, 0), new Key("position", 0.06, 0.6, 0, 0), new Key("position", 0.12, -0.6, 0, 0),
            new Key("position", 0.18, 0.6, 0, 0), new Key("position", 0.24, -0.6, 0, 0), new Key("position", 0.3, 0, 0, 0)});
        p.animation("denied", 0.3, "once", denied);
        return p.toJson();
    }

    public static String sensor() {
        ProjectWriter p = new ProjectWriter("sensor", 32);
        int atlas = p.texture("sensor", DefaultTextures.sensorAtlas());
        double[] body = {0, 0, 16, 16}, lens = {16, 0, 32, 16}, green = {0, 16, 8, 24}, red = {8, 16, 16, 24};
        p.loose(p.cubeUv("base", v(-3, 0, -3), v(3, 1.5, 3), atlas, body, body, body, 0));
        p.group("head", v(0, 4, 0), v(0, 0, 0),
            p.cubeUv("dome", v(-2.5, 1.5, -2.5), v(2.5, 6.5, 2.5), atlas, body, body, body, 0),
            p.cubeUv("lens", v(-1.5, 3, -2.7), v(1.5, 5.2, -2.5), atlas, lens, lens, lens, 0));
        p.group("state_idle", v(0, 0.75, -2.9), v(0, 0, 0), p.cubeUv("lamp_idle", v(-0.6, 0.4, -3.1), v(0.6, 1.1, -2.9), atlas, green, green, green, 15));
        p.group("state_active", v(0, 0.75, -2.9), v(0, 0, 0), p.cubeUv("lamp_active", v(-0.6, 0.4, -3.1), v(0.6, 1.1, -2.9), atlas, red, red, red, 15));
        Map<String, Key[]> detect = new LinkedHashMap<>();
        detect.put("head", new Key[] {new Key("rotation", 0, 0, 0, 0), new Key("rotation", 0.5, 0, 180, 0), new Key("rotation", 1, 0, 360, 0)});
        p.animation("detect", 1.0, "once", detect);
        return p.toJson();
    }

    /** @param level 1..5, 6 = omni */
    public static String card(int level) {
        ProjectWriter p = new ProjectWriter(level == 6 ? "card_omni" : "card_level" + level, 32);
        int tex = p.texture("card", DefaultTextures.card(level));
        double[] front = {0, 0, 32, 20}, back = {0, 20, 32, 32}, edge = {0, 20, 2, 22};
        p.loose(p.cubeUv("card", v(-4.28, -2.7, -0.2), v(4.28, 2.7, 0.2), tex, front, back, edge, 0));
        return p.toJson();
    }

    public static String linkTool() {
        ProjectWriter p = new ProjectWriter("link_tool", 16);
        int tex = p.texture("tool", DefaultTextures.tool());
        double[] all = {0, 0, 16, 16};
        p.loose(p.cubeUv("tablet", v(-3, -4, -0.5), v(3, 4, 0.5), tex, all, all, all, 0));
        return p.toJson();
    }
}

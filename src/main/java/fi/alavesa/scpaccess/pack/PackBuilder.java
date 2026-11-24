package fi.alavesa.scpaccess.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.alavesa.scpaccess.content.ModelLibrary;
import fi.alavesa.scpaccess.model.BbModel;
import fi.alavesa.scpaccess.model.JavaModelWriter;
import fi.alavesa.scpaccess.model.ModelLog;
import fi.alavesa.scpaccess.rig.Bone;
import fi.alavesa.scpaccess.rig.Rig;
import fi.alavesa.scpaccess.util.DeterministicZip;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The resource pack: item models for every bone / icon / card, the sounds, the two post effects and the core/text
 * shader. It is only WRITTEN to disk - delivering it to players is the server owner's job (put the zip on the server
 * resource pack, in a launcher, or merge it into your own pack).
 *
 * <pre>
 *   assets/scpaccess/items/&lt;rig&gt;.json                  the held / inventory item (the whole model at rest)
 *   assets/scpaccess/items/&lt;rig&gt;/&lt;bone&gt;.json         one bone of a placed object
 *   assets/scpaccess/models/item/...                 their geometry, textures/item/&lt;rig&gt;/&lt;texture&gt;.png
 *   assets/scpaccess/sounds.json, sounds/*.ogg       events scpaccess:reader.granted etc.
 *   assets/scpaccess/post_effect/*.json + shaders/post/access_tint.fsh
 *   assets/minecraft/shaders/core/text.vsh|fsh       (optional) reader screens
 * </pre>
 */
public final class PackBuilder {

    public static final String NAMESPACE = "scpaccess";
    public static final String ZIP_NAME = "ScpAccess-pack.zip";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** event -> {vanilla fallback event, pitch}; used when sounds/&lt;event&gt;.ogg is not provided */
    public static final Map<String, String[]> SOUND_EVENTS = new LinkedHashMap<>();
    static {
        SOUND_EVENTS.put("reader.swipe", new String[] {"minecraft:item.armor.equip_chain", "1.3"});
        SOUND_EVENTS.put("reader.granted", new String[] {"minecraft:block.note_block.pling", "1.6"});
        SOUND_EVENTS.put("reader.denied", new String[] {"minecraft:block.note_block.bass", "0.6"});
        SOUND_EVENTS.put("door.open", new String[] {"minecraft:block.piston.extend", "1.5"});
        SOUND_EVENTS.put("door.close", new String[] {"minecraft:block.piston.contract", "1.5"});
        SOUND_EVENTS.put("door.locked", new String[] {"minecraft:block.chest.locked", "1.0"});
        SOUND_EVENTS.put("sensor.detect", new String[] {"minecraft:block.note_block.hat", "1.8"});
        SOUND_EVENTS.put("breach.alarm", new String[] {"minecraft:block.bell.use", "0.5"});
    }

    public record Result(byte[] zip, int files, String textShader, List<String> warnings) { }

    private PackBuilder() { }

    /**
     * @param soundsDir        plugins/ScpAccess/sounds: any {@code <event>.ogg} there replaces the vanilla fallback
     * @param textShader       ship the core/text shader (reader screens)
     * @param textSources      other plugins' pack zips to build the shader on (name -> file), most complete first
     * @param formatMin        pack format range for pack.mcmeta (26.3 = 97)
     */
    public static Result build(ModelLibrary lib, Path soundsDir, boolean textShader, List<Map.Entry<String, File>> textSources,
                               int formatMin, int formatMax, String description, boolean postEffects) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        files.put("pack.mcmeta", mcmeta(formatMin, formatMax, description));

        JavaModelWriter writer = new JavaModelWriter(NAMESPACE);
        for (Rig rig : lib.rigs.values()) {
            ModelLog log = new ModelLog(rig.id);
            String assets = "assets/" + NAMESPACE + "/";
            for (Bone bone : rig.bones) {
                if (bone.card()) continue;
                files.put(assets + "models/item/" + rig.id + "/" + bone.key() + ".json", json(writer.write(bone.sub(), bone.geometry(), log)));
                files.put(assets + "items/" + rig.id + "/" + bone.key() + ".json", json(itemDefinition("item/" + rig.id + "/" + bone.key())));
            }
            if (rig.icon != null) {
                files.put(assets + "models/item/" + rig.id + "/icon.json", json(writer.write(rig.icon, rig.iconGeometry, log)));
                files.put(assets + "items/" + rig.id + ".json", json(itemDefinition("item/" + rig.id + "/icon")));
            }
            for (BbModel.Texture texture : rig.model.textures) {
                if (texture.png() == null) continue;
                String path = assets + "textures/item/" + rig.id + "/" + texture.key() + ".png";
                files.put(path, texture.png());
                if (texture.mcmeta() != null) {
                    files.put(path + ".mcmeta", texture.mcmeta());
                } else if (texture.frames() > 1) {
                    JsonObject animation = new JsonObject();
                    animation.addProperty("frametime", texture.frameTime());
                    if (texture.interpolate()) animation.addProperty("interpolate", true);
                    animation.addProperty("width", texture.pngWidth());
                    animation.addProperty("height", texture.pngHeight() / texture.frames());
                    JsonObject root = new JsonObject();
                    root.add("animation", animation);
                    files.put(path + ".mcmeta", json(root));
                }
            }
            warnings.addAll(log.warnings());
        }

        files.put("assets/" + NAMESPACE + "/sounds.json", soundsJson(soundsDir, files, warnings));

        if (postEffects) {
            for (String name : new String[] {"granted", "denied", "breach"})
                files.put("assets/" + NAMESPACE + "/post_effect/" + name + ".json", resource("pack/assets/scpaccess/post_effect/" + name + ".json"));
            files.put("assets/" + NAMESPACE + "/shaders/post/access_tint.fsh", resource("pack/assets/scpaccess/shaders/post/access_tint.fsh"));
        }

        String shaderNote = "off";
        if (textShader) {
            TextShaderMerge.Result merged = TextShaderMerge.build(
                new String(resource("shaders/vanilla_text.vsh"), StandardCharsets.UTF_8),
                new String(resource("shaders/vanilla_text.fsh"), StandardCharsets.UTF_8), textSources, warnings::add);
            shaderNote = merged.description();
            if (merged.shipped()) {
                files.putAll(merged.files());
                files.put("assets/" + NAMESPACE + "/shaders/include/scpaccess_text_vertex.glsl", resource("pack/assets/scpaccess/shaders/include/scpaccess_text_vertex.glsl"));
                files.put("assets/" + NAMESPACE + "/shaders/include/scpaccess_text_fragment.glsl", resource("pack/assets/scpaccess/shaders/include/scpaccess_text_fragment.glsl"));
            }
        }
        return new Result(DeterministicZip.write(files, false), files.size(), shaderNote, warnings);
    }

    private static JsonObject itemDefinition(String model) {
        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", NAMESPACE + ":" + model);
        JsonObject definition = new JsonObject();
        definition.add("model", reference);
        return definition;
    }

    static byte[] mcmeta(int min, int max, String description) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("pack_format", min);
        pack.addProperty("min_format", min);
        pack.addProperty("max_format", max);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return json(root);
    }

    private static byte[] soundsJson(Path soundsDir, Map<String, byte[]> files, List<String> warnings) throws IOException {
        JsonObject root = new JsonObject();
        for (var e : SOUND_EVENTS.entrySet()) {
            String event = e.getKey();
            Path ogg = soundsDir.resolve(event + ".ogg");
            JsonObject entry = new JsonObject();
            JsonArray sounds = new JsonArray();
            JsonObject sound = new JsonObject();
            if (Files.isRegularFile(ogg)) {
                byte[] bytes = Files.readAllBytes(ogg);
                if (bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S') {
                    warnings.add("[sounds/" + event + ".ogg] is not an Ogg Vorbis file - the vanilla fallback sound is used");
                } else {
                    files.put("assets/" + NAMESPACE + "/sounds/" + event + ".ogg", bytes);
                    sound.addProperty("name", NAMESPACE + ":" + event);
                    sounds.add(sound);
                    entry.add("sounds", sounds);
                    root.add(event, entry);
                    continue;
                }
            }
            sound.addProperty("name", e.getValue()[0]);
            sound.addProperty("type", "event");
            sound.addProperty("pitch", Double.parseDouble(e.getValue()[1]));
            sounds.add(sound);
            entry.add("sounds", sounds);
            root.add(event, entry);
        }
        return json(root);
    }

    static byte[] resource(String path) throws IOException {
        try (InputStream in = PackBuilder.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing resource " + path + " in the plugin jar");
            return in.readAllBytes();
        }
    }

    private static byte[] json(JsonObject o) { return GSON.toJson(o).getBytes(StandardCharsets.UTF_8); }
}

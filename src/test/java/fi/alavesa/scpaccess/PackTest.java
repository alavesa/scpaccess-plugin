package fi.alavesa.scpaccess;

import fi.alavesa.scpaccess.content.ModelLibrary;
import fi.alavesa.scpaccess.pack.PackBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackTest {

    @TempDir Path dir;

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            var e = in.getNextEntry();
            while (e != null) { out.put(e.getName(), in.readAllBytes()); e = in.getNextEntry(); }
        }
        return out;
    }

    @Test
    void emptyFolderGetsDefaultsAndABuildablePack() throws Exception {
        ModelLibrary lib = ModelLibrary.load(dir);
        assertEquals(List.of(), lib.warnings);
        assertEquals(3, lib.doorTypes.size());
        assertEquals("3x3", lib.doorTypes.get("standard_3x3").size());
        assertEquals(6, lib.doorTypes.get("standard_6x3").width());
        assertNotNull(lib.reader());
        assertNotNull(lib.sensor());
        for (int level = 1; level <= 6; level++) assertNotNull(lib.card(level), "card " + level);
        assertTrue(Files.isRegularFile(dir.resolve("models/cards/omni.bbmodel")));
        assertTrue(Files.isRegularFile(dir.resolve("doortypes/standard_1x2/door.bbmodel")));

        PackBuilder.Result pack = PackBuilder.build(lib, dir.resolve("sounds"), true, List.of(), 97, 97, "test", true);
        assertEquals(List.of(), pack.warnings());
        Map<String, byte[]> files = unzip(pack.zip());
        assertTrue(files.containsKey("pack.mcmeta"));
        assertTrue(files.containsKey("assets/scpaccess/items/card/level3.json"));
        assertTrue(files.containsKey("assets/scpaccess/items/card/omni.json"));
        assertTrue(files.containsKey("assets/scpaccess/items/door/standard_3x3.json"));
        assertTrue(files.containsKey("assets/scpaccess/models/item/door/standard_3x3/icon.json"));
        assertTrue(files.keySet().stream().anyMatch(f -> f.startsWith("assets/scpaccess/models/item/door/standard_3x3/b") && f.contains("leaf_left")));
        assertTrue(files.containsKey("assets/scpaccess/textures/item/reader/reader/reader.png"));
        assertTrue(files.containsKey("assets/scpaccess/sounds.json"));
        assertTrue(files.containsKey("assets/scpaccess/post_effect/denied.json"));
        assertTrue(files.containsKey("assets/scpaccess/shaders/post/access_tint.fsh"));
        String vsh = new String(files.get("assets/minecraft/shaders/core/text.vsh"), StandardCharsets.UTF_8);
        String fsh = new String(files.get("assets/minecraft/shaders/core/text.fsh"), StandardCharsets.UTF_8);
        assertTrue(vsh.contains("scpaccess_text_vertex(Color, vertexColor)"));
        assertTrue(vsh.indexOf("texCoord0 = UV0;") < vsh.indexOf("scpaccess_text_vertex(Color"));
        assertTrue(fsh.contains("color = scpaccess_text_fragment(color);"));
        assertTrue(files.containsKey("assets/scpaccess/shaders/include/scpaccess_text_vertex.glsl"));
        // every item definition points at a model that exists
        for (var e : files.entrySet()) {
            if (!e.getKey().startsWith("assets/scpaccess/items/")) continue;
            String json = new String(e.getValue(), StandardCharsets.UTF_8);
            String model = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("model").get("model").getAsString();
            assertTrue(files.containsKey("assets/scpaccess/models/" + model.substring("scpaccess:".length()) + ".json"), model);
        }
        // deterministic
        assertArrayEquals(pack.zip(), PackBuilder.build(lib, dir.resolve("sounds"), true, List.of(), 97, 97, "test", true).zip());
    }

    @Test
    void aBrokenModelIsSkippedAndTheRestLoads() throws Exception {
        ModelLibrary.load(dir);
        Files.writeString(dir.resolve("models/sensor.bbmodel"), "{ not json");
        ModelLibrary lib = ModelLibrary.load(dir);
        assertNull(lib.sensor());
        assertNotNull(lib.reader());
        assertTrue(lib.warnings.stream().anyMatch(w -> w.contains("sensor.bbmodel") && w.contains("SKIPPED")), lib.warnings.toString());
    }

    @Test
    void customDoorTypeNeedsAKnownSize() throws Exception {
        ModelLibrary.writeDoorType(dir.resolve("doortypes"), "vault", "6x3");
        Files.writeString(dir.resolve("doortypes/vault/type.yml"), "size: 5x5\n");
        ModelLibrary lib = ModelLibrary.load(dir);
        assertFalse(lib.doorTypes.containsKey("vault"));
        assertTrue(lib.warnings.stream().anyMatch(w -> w.contains("vault") && w.contains("5x5")));
    }
}

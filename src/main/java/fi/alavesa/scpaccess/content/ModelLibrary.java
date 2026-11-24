package fi.alavesa.scpaccess.content;

import fi.alavesa.scpaccess.model.BbModel;
import fi.alavesa.scpaccess.model.BbModelParser;
import fi.alavesa.scpaccess.model.ModelException;
import fi.alavesa.scpaccess.model.ModelLog;
import fi.alavesa.scpaccess.rig.Rig;
import fi.alavesa.scpaccess.rig.RigBuilder;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every model the plugin uses, read from its folder (missing defaults are written first, existing files are never
 * touched):
 * <pre>
 *   models/cards/level1.bbmodel ... level5.bbmodel, omni.bbmodel
 *   models/reader.bbmodel   models/sensor.bbmodel   models/link_tool.bbmodel
 *   doortypes/&lt;name&gt;/door.bbmodel + type.yml (size: 3x3 | 1x2 | 6x3)
 * </pre>
 * A model that cannot be read is skipped with a warning naming the file; the others still load.
 */
public final class ModelLibrary {

    /** The three door sizes: width x height in blocks. */
    public static final Map<String, int[]> DOOR_SIZES = Map.of("3x3", new int[] {3, 3}, "1x2", new int[] {1, 2}, "6x3", new int[] {6, 3});

    public static final String[] CARD_IDS = {"level1", "level2", "level3", "level4", "level5", "omni"};

    public record DoorType(String id, int width, int height, Rig rig, Map<String, String> sounds, double stayOpenSeconds) {
        public String size() { return width + "x" + height; }
    }

    /** packId ("card/level1", "reader/reader", "sensor/sensor", "tool/link_tool", "door/<type>") -> rig */
    public final Map<String, Rig> rigs = new LinkedHashMap<>();
    public final Map<String, DoorType> doorTypes = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();
    /** Raw bytes of every file that went in (pack fingerprint, texture siblings). */
    public final Map<String, byte[]> sources = new LinkedHashMap<>();

    public Rig card(int level) { return rigs.get("card/" + CARD_IDS[Math.max(1, Math.min(6, level)) - 1]); }

    public Rig reader() { return rigs.get("reader/reader"); }

    public Rig sensor() { return rigs.get("sensor/sensor"); }

    public Rig linkTool() { return rigs.get("tool/link_tool"); }

    public static ModelLibrary load(Path dataDir) throws IOException {
        ModelLibrary lib = new ModelLibrary();
        Path models = dataDir.resolve("models"), cards = models.resolve("cards"), doors = dataDir.resolve("doortypes");
        Files.createDirectories(cards);
        Files.createDirectories(doors);

        writeIfMissing(models.resolve("reader.bbmodel"), DefaultModels.reader());
        writeIfMissing(models.resolve("sensor.bbmodel"), DefaultModels.sensor());
        writeIfMissing(models.resolve("link_tool.bbmodel"), DefaultModels.linkTool());
        for (int i = 0; i < CARD_IDS.length; i++) writeIfMissing(cards.resolve(CARD_IDS[i] + ".bbmodel"), DefaultModels.card(i + 1));
        boolean noDoorTypes;
        try (Stream<Path> s = Files.list(doors)) { noDoorTypes = s.noneMatch(Files::isDirectory); }
        if (noDoorTypes) for (String size : new String[] {"3x3", "1x2", "6x3"}) writeDoorType(doors, "standard_" + size, size);

        lib.read("reader/reader", models.resolve("reader.bbmodel"));
        lib.read("sensor/sensor", models.resolve("sensor.bbmodel"));
        lib.read("tool/link_tool", models.resolve("link_tool.bbmodel"));
        for (String id : CARD_IDS) lib.read("card/" + id, cards.resolve(id + ".bbmodel"));

        List<Path> dirs;
        try (Stream<Path> s = Files.list(doors)) { dirs = s.filter(Files::isDirectory).sorted().toList(); }
        for (Path dir : dirs) lib.readDoorType(dir);
        return lib;
    }

    /** Creates doortypes/&lt;id&gt;/ with a starter model and type.yml. */
    public static Path writeDoorType(Path doorsDir, String id, String size) throws IOException {
        int[] wh = DOOR_SIZES.get(size);
        Path dir = doorsDir.resolve(id);
        Files.createDirectories(dir);
        writeIfMissing(dir.resolve("door.bbmodel"), DefaultModels.door(wh[0], wh[1]));
        writeIfMissing(dir.resolve("type.yml"),
            "# Door type '" + id + "'. door.bbmodel is the model: replace it with your own Blockbench project.\n"
                + "# Groups named leaf*/anything with an 'open' and 'close' animation move; groups named state_closed, state_open\n"
                + "# and state_locked are shown only in that state. Origin = bottom centre of the door, front = north (-Z).\n"
                + "size: " + size + "\n"
                + "# Seconds the door stays open after a keycard opened it (empty = config.yml doors.stay-open-seconds).\n"
                + "# stay-open-seconds: 6\n"
                + "# Sounds of this type (sound event ids from the pack); empty = the plugin-wide ones.\n"
                + "# sounds:\n#   open: scpaccess:door.open\n#   close: scpaccess:door.close\n");
        return dir;
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Rig read(String packId, Path file) {
        ModelLog log = new ModelLog(file.getParent().getFileName() + "/" + file.getFileName());
        try {
            byte[] bytes = Files.readAllBytes(file);
            sources.put(packId, bytes);
            Path dir = file.getParent();
            BbModelParser.SiblingFiles siblings = relative -> {
                try {
                    Path f = dir.resolve(relative.replace('\\', '/')).normalize();
                    if (!f.startsWith(dir) || !Files.isRegularFile(f)) return null;
                    byte[] data = Files.readAllBytes(f);
                    sources.put(packId + ":" + relative, data);
                    return data;
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            };
            BbModel model = BbModelParser.parse(packId, bytes, siblings, log);
            Rig rig = RigBuilder.build(packId, model, log);
            rigs.put(packId, rig);
            warnings.addAll(log.warnings());
            return rig;
        } catch (ModelException e) {
            log.warn("", "SKIPPED - " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("", "SKIPPED - unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        warnings.addAll(log.warnings());
        return null;
    }

    private void readDoorType(Path dir) {
        String id = sanitize(dir.getFileName().toString());
        Path file = dir.resolve("door.bbmodel");
        if (id.isEmpty() || !Files.isRegularFile(file)) {
            warnings.add("[doortypes/" + dir.getFileName() + "] has no door.bbmodel - skipped");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        Path typeFile = dir.resolve("type.yml");
        if (Files.isRegularFile(typeFile)) {
            try (Reader r = Files.newBufferedReader(typeFile, StandardCharsets.UTF_8)) {
                yaml.load(r);
            } catch (IOException | InvalidConfigurationException e) {
                warnings.add("[doortypes/" + dir.getFileName() + "/type.yml] cannot be read (" + e.getMessage() + ") - skipped");
                return;
            }
        }
        String size = yaml.getString("size", "3x3").toLowerCase(Locale.ROOT).trim();
        int[] wh = DOOR_SIZES.get(size);
        if (wh == null) {
            warnings.add("[doortypes/" + dir.getFileName() + "/type.yml] size '" + size + "' is not one of " + String.join(", ", DOOR_SIZES.keySet()) + " - skipped");
            return;
        }
        Rig rig = read("door/" + id, file);
        if (rig == null) return;
        Map<String, String> sounds = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("sounds");
        if (section != null) for (String k : section.getKeys(false)) sounds.put(k, section.getString(k, ""));
        double stay = yaml.getDouble("stay-open-seconds", -1);
        doorTypes.put(id, new DoorType(id, wh[0], wh[1], rig, sounds, stay));
    }

    /** Lower-case, a-z 0-9 _ only: a valid resource location path and command word. */
    public static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }
}

package fi.alavesa.scpaccess;

import fi.alavesa.scpaccess.card.Items;
import fi.alavesa.scpaccess.command.AccessCommand;
import fi.alavesa.scpaccess.config.Messages;
import fi.alavesa.scpaccess.content.ModelLibrary;
import fi.alavesa.scpaccess.door.DoorManager;
import fi.alavesa.scpaccess.link.Scp079Link;
import fi.alavesa.scpaccess.pack.PackBuilder;
import fi.alavesa.scpaccess.reader.ReaderManager;
import fi.alavesa.scpaccess.rig.RigAnimator;
import fi.alavesa.scpaccess.sensor.SensorManager;
import fi.alavesa.scpaccess.store.Data;
import fi.alavesa.scpaccess.store.DataStore;
import fi.alavesa.scpaccess.util.Hashing;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * ScpAccess: SCP-style keycard readers, keycards, doors and player sensors from Blockbench models, wired to SCP-079.
 * <p>
 * Everything lives on the server thread; the only off-thread thing is nothing - model reading and pack building are
 * small and run at start / reload.
 */
public final class ScpAccessPlugin extends JavaPlugin {

    public enum Mode { CONTAINED, BREACH }

    private Data data;
    private DataStore store;
    private ModelLibrary library;
    private Messages messages;
    private Items items;
    private DoorManager doors;
    private ReaderManager readers;
    private SensorManager sensors;
    private Scp079Link link;
    private BukkitTask saver;
    private boolean dirty;
    private PackBuilder.Result pack;
    private final List<String> warnings = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        store = new DataStore(getDataFolder().toPath().resolve("data.json"), getLogger());
        data = store.load();
        items = new Items(this, () -> library);

        loadLibrary();
        RigAnimator.start(this, Math.max(1, getConfig().getInt("animation.step-ticks", 2)));

        doors = new DoorManager(this);
        readers = new ReaderManager(this);
        sensors = new SensorManager(this);
        doors.load();
        readers.load();
        sensors.load();

        var listener = new fi.alavesa.scpaccess.command.AccessListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        AccessCommand command = new AccessCommand(this, listener);
        var cmd = getCommand("scpaccess");
        if (cmd != null) { cmd.setExecutor(command); cmd.setTabCompleter(command); }

        saver = getServer().getScheduler().runTaskTimer(this, () -> { if (dirty) flush(); }, 100, 100);
        sensors.start();
        connect079();
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onEnable(org.bukkit.event.server.PluginEnableEvent e) {
                if (e.getPlugin().getName().equals("SCP079")) connect079();
            }
        }, this);

        getLogger().info("Mode " + mode() + ", " + data.doors.size() + " door(s), " + data.readers.size() + " reader(s), " + data.sensors.size()
            + " sensor(s), " + library.doorTypes.size() + " door type(s); pack " + (pack == null ? "NOT built" : pack.files() + " files, text shader: " + pack.textShader())
            + (link != null ? ", connected to SCP-079" : ", SCP-079 not found"));
        for (String w : warnings) getLogger().warning(w);
    }

    @Override
    public void onDisable() {
        if (saver != null) saver.cancel();
        if (sensors != null) sensors.stop();
        if (link != null) link.close();
        if (doors != null) doors.shutdown();
        if (readers != null) readers.shutdown();
        if (sensors != null) sensors.shutdown();
        RigAnimator.stop();
        if (store != null && data != null) store.save(data);
    }

    // ------------------------------------------------------------------ 079

    private void connect079() {
        if (link != null || getServer().getPluginManager().getPlugin("SCP079") == null) return;
        try {
            link = Scp079Link.tryCreate(this);
            if (link != null) getLogger().info("Connected to SCP-079: it can now open, close and lock doors and tell where the sensors saw someone.");
        } catch (LinkageError | RuntimeException e) {
            getLogger().log(Level.WARNING, "SCP079 is installed but its API is missing (SCP079 1.3.0 or newer is needed): " + e);
        }
    }

    public Scp079Link link() { return link; }

    // ------------------------------------------------------------------ files

    private void loadMessages() {
        YamlConfiguration defaults = new YamlConfiguration();
        try (var in = getResource("messages.yml")) {
            if (in != null) defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            getLogger().warning("bundled messages.yml unreadable: " + e);
        }
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.isFile()) saveResource("messages.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.setDefaults(defaults);
        messages = new Messages(yaml);
    }

    /** Reads the models and (re)builds the pack zip. */
    private void loadLibrary() {
        warnings.clear();
        try {
            library = ModelLibrary.load(getDataFolder().toPath());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "the models could not be read", e);
            library = new ModelLibrary();
        }
        warnings.addAll(library.warnings);
        try {
            pack = buildPack();
            warnings.addAll(pack.warnings());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "the pack could not be built", e);
            pack = null;
        }
    }

    public Path packFile() { return getDataFolder().toPath().resolve("pack").resolve(PackBuilder.ZIP_NAME); }

    private PackBuilder.Result buildPack() throws IOException {
        var cfg = getConfig();
        List<Map.Entry<String, File>> sources = new ArrayList<>();
        var section = cfg.getConfigurationSection("pack.text-shader-sources");
        if (section != null) for (String name : section.getKeys(false))
            sources.add(Map.entry(name, new File(getDataFolder().getParentFile(), section.getString(name, ""))));
        PackBuilder.Result result = PackBuilder.build(library, getDataFolder().toPath().resolve("sounds"), cfg.getBoolean("pack.core-text-shader", false), sources,
            cfg.getInt("pack.format.min", 97), cfg.getInt("pack.format.max", 97), cfg.getString("pack.description", "ScpAccess"), cfg.getBoolean("pack.post-effects", true));
        Files.createDirectories(getDataFolder().toPath().resolve("sounds"));
        Path zip = packFile();
        Files.createDirectories(zip.getParent());
        boolean same = Files.isRegularFile(zip) && Hashing.sha1Hex(Files.readAllBytes(zip)).equals(Hashing.sha1Hex(result.zip()));
        if (!same) Files.write(zip, result.zip());
        return result;
    }

    /** /scpaccess reload: config, messages, models, pack; every object is respawned from its model. */
    public void reload() {
        reloadConfig();
        loadMessages();
        doors.despawnAll();
        readers.despawnAll();
        sensors.despawnAll();
        loadLibrary();
        RigAnimator.start(this, Math.max(1, getConfig().getInt("animation.step-ticks", 2)));
        doors.load();
        readers.load();
        sensors.load();
        for (String w : warnings) getLogger().warning(w);
    }

    public List<String> warnings() { return warnings; }

    public PackBuilder.Result pack() { return pack; }

    /** /scpaccess pack build */
    public void rebuildPack() throws IOException { pack = buildPack(); }

    // ------------------------------------------------------------------ state

    public Data data() { return data; }

    public ModelLibrary library() { return library; }

    public Messages messages() { return messages; }

    public Items items() { return items; }

    public DoorManager doors() { return doors; }

    public ReaderManager readers() { return readers; }

    public SensorManager sensors() { return sensors; }

    public int nextId() { return data.nextId++; }

    public void markDirty() { dirty = true; }

    public void flush() {
        dirty = false;
        store.save(data);
    }

    public Mode mode() { return "breach".equalsIgnoreCase(data.mode) ? Mode.BREACH : Mode.CONTAINED; }

    /** Switches the mode; announces a breach. @return false when it already was that mode */
    public boolean setMode(Mode mode) {
        if (mode == mode()) return false;
        data.mode = mode == Mode.BREACH ? "breach" : "contained";
        markDirty();
        flush();
        if (link != null) link.modeChanged(mode);
        boolean breach = mode == Mode.BREACH;
        var cfg = getConfig();
        if (cfg.getBoolean("breach.broadcast", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                messages.send(p, breach ? "mode.breach-announce" : "mode.contained-announce");
                if (breach && cfg.getBoolean("breach.alarm", true)) p.playSound(p.getLocation(), "scpaccess:breach.alarm", 1f, 1f);
            }
            if (breach && cfg.getBoolean("pack.post-effects", true) && cfg.getBoolean("breach.flash", true))
                for (Player p : Bukkit.getOnlinePlayers()) fi.alavesa.scpaccess.reader.PostFx.flash(this, p, "breach", 60);
        }
        return true;
    }
}

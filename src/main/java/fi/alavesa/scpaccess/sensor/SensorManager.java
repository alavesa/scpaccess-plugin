package fi.alavesa.scpaccess.sensor;

import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.api.SensorDetectEvent;
import fi.alavesa.scpaccess.rig.Rig;
import fi.alavesa.scpaccess.rig.RigAnimator;
import fi.alavesa.scpaccess.rig.RigInstance;
import fi.alavesa.scpaccess.store.Data;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Player sensors: placed models that notice players within a radius and tell SCP-079, which keeps what they saw in its
 * memory. Each sensor is named after the place it watches ("Hallway B").
 */
public final class SensorManager {

    public static final class Sensor {
        public final Data.SensorData data;
        RigInstance rig;
        final Set<UUID> present = new HashSet<>();
        long lastNote;

        Sensor(Data.SensorData data) { this.data = data; }

        public int id() { return data.id; }

        public String name() { return data.name; }

        public World world() { return Bukkit.getWorld(UUID.fromString(data.world)); }

        public Location anchor() { return new Location(world(), data.x, data.y, data.z, data.yaw, 0); }

        public boolean loaded() { return rig != null && rig.alive(); }

        /** Names of the players inside the radius right now. */
        public List<String> presentNames() {
            List<String> out = new ArrayList<>();
            for (UUID id : present) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p.getName()); }
            return out;
        }
    }

    private final ScpAccessPlugin plugin;
    private final Map<Integer, Sensor> sensors = new LinkedHashMap<>();
    private BukkitTask task;

    public SensorManager(ScpAccessPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ lifecycle

    public void load() {
        sensors.clear();
        for (Data.SensorData data : plugin.data().sensors) {
            Sensor s = new Sensor(data);
            sensors.put(data.id, s);
            spawn(s);
        }
    }

    public void start() {
        stop();
        int interval = Math.max(2, plugin.getConfig().getInt("sensors.interval-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public void despawnAll() { for (Sensor s : sensors.values()) despawn(s); }

    public void shutdown() { despawnAll(); }

    public void onChunkLoad(Chunk chunk) {
        for (Sensor s : sensors.values()) if (!s.loaded() && inChunk(s, chunk)) spawn(s);
    }

    public void onChunkUnload(Chunk chunk) {
        for (Sensor s : sensors.values()) if (inChunk(s, chunk)) despawn(s);
    }

    private static boolean inChunk(Sensor s, Chunk c) {
        return c.getWorld() == s.world() && ((int) Math.floor(s.data.x)) >> 4 == c.getX() && ((int) Math.floor(s.data.z)) >> 4 == c.getZ();
    }

    private void spawn(Sensor s) {
        World world = s.world();
        Rig rig = plugin.library().sensor();
        if (world == null || rig == null || s.loaded()) return;
        if (!world.isChunkLoaded(((int) Math.floor(s.data.x)) >> 4, ((int) Math.floor(s.data.z)) >> 4)) return;
        s.rig = new RigInstance(rig, s.anchor(), s.data.yaw, (float) plugin.getConfig().getDouble("display.view-range", 1.5));
        s.rig.spawn();
        s.rig.setState(s.present.isEmpty() ? "idle" : "active");
    }

    private void despawn(Sensor s) {
        if (s.rig != null) s.rig.remove();
        s.rig = null;
        s.present.clear();
    }

    // ------------------------------------------------------------------ queries

    public Sensor get(int id) { return sensors.get(id); }

    public List<Sensor> all() { return new ArrayList<>(sensors.values()); }

    // ------------------------------------------------------------------ placing

    public record PlaceResult(Sensor sensor, String error) { }

    public PlaceResult place(Player player, String name, ItemStack item, EquipmentSlot hand, Block clicked, BlockFace face, Location point) {
        Rig rig = plugin.library().sensor();
        if (rig == null) return new PlaceResult(null, "place.no-model");
        double nx = face.getModX(), nz = face.getModZ();
        double px = point == null ? clicked.getX() + 0.5 + nx * 0.5 : point.getX();
        double py = point == null ? clicked.getY() + (face == BlockFace.UP ? 1 : 0.5) : point.getY();
        double pz = point == null ? clicked.getZ() + 0.5 + nz * 0.5 : point.getZ();
        float yaw;
        if (face.getModY() == 0) {
            yaw = fi.alavesa.scpaccess.door.DoorGeometry.snap(Math.toDegrees(Math.atan2(-nx, nz)));
            double back = Math.max(0, rig.boundsMax[2]) / 16;      // the model's back plate meets the wall
            px += nx * (back + 0.01);
            pz += nz * (back + 0.01);
        } else {
            yaw = fi.alavesa.scpaccess.door.DoorGeometry.snap(player.getLocation().getYaw() + 180);
            if (face == BlockFace.DOWN) py -= rig.boundsMax[1] / 16;  // hangs from the ceiling
        }
        Location anchor = new Location(clicked.getWorld(), px, py, pz, yaw, 0);
        for (Sensor other : sensors.values())
            if (other.world() == anchor.getWorld() && other.anchor().distanceSquared(anchor) < 0.3 * 0.3) return new PlaceResult(null, "place.occupied");

        if (plugin.getConfig().getBoolean("placement.fire-block-events", true)) {
            Block at = clicked.getRelative(face);
            BlockPlaceEvent build = new BlockPlaceEvent(at, at.getState(), clicked, item, player, true, hand);
            Bukkit.getPluginManager().callEvent(build);
            if (build.isCancelled() || !build.canBuild()) return new PlaceResult(null, "place.protected");
        }
        Sensor sensor = create(name, anchor.getWorld(), anchor.getX(), anchor.getY(), anchor.getZ(), yaw);
        if (player.getGameMode() != GameMode.CREATIVE) item.subtract(1);
        return new PlaceResult(sensor, null);
    }


    /** Registers and spawns a sensor at an exact anchor; no checks. */
    public Sensor create(String name, World world, double x, double y, double z, float yaw) {
        Data.SensorData data = new Data.SensorData();
        data.id = plugin.nextId();
        data.name = name == null || name.isBlank() ? "Sensor " + data.id : name.strip();
        data.world = world.getUID().toString();
        data.x = x;
        data.y = y;
        data.z = z;
        data.yaw = yaw;
        data.radius = plugin.getConfig().getDouble("sensors.radius", 6.0);
        plugin.data().sensors.add(data);
        Sensor sensor = new Sensor(data);
        sensors.put(data.id, sensor);
        spawn(sensor);
        plugin.markDirty();
        return sensor;
    }

    public void remove(Sensor s) {
        despawn(s);
        sensors.remove(s.id());
        plugin.data().sensors.remove(s.data);
        plugin.markDirty();
        if (plugin.link() != null) plugin.link().forgetSensor(s.id());
    }

    public void rename(Sensor s, String name) {
        s.data.name = name.strip();
        plugin.markDirty();
    }

    public void setRadius(Sensor s, double radius) {
        s.data.radius = Math.max(1, Math.min(64, radius));
        plugin.markDirty();
    }

    // ------------------------------------------------------------------ detecting

    private void tick() {
        var cfg = plugin.getConfig();
        long now = System.currentTimeMillis();
        long refresh = Math.max(1, cfg.getInt("sensors.refresh-seconds", 5)) * 1000L;
        Set<GameMode> ignored = new HashSet<>();
        for (String m : cfg.getStringList("sensors.ignore-gamemodes")) {
            try { ignored.add(GameMode.valueOf(m.toUpperCase(java.util.Locale.ROOT))); } catch (IllegalArgumentException ignoredMode) { }
        }
        for (Sensor s : sensors.values()) {
            World world = s.world();
            if (world == null || !s.loaded()) continue;
            Location at = s.anchor().add(0, 0.5, 0);
            Set<UUID> now_ = new HashSet<>();
            List<Player> seen = new ArrayList<>();
            double r2 = s.data.radius * s.data.radius;
            for (Player p : world.getPlayers()) {
                if (ignored.contains(p.getGameMode()) || p.hasPermission("scpaccess.sensor.ignore") || p.isDead()) continue;
                if (p.getLocation().distanceSquared(at) > r2) continue;
                now_.add(p.getUniqueId());
                seen.add(p);
            }
            boolean entered = false;
            for (Player p : seen) {
                if (s.present.contains(p.getUniqueId())) continue;
                entered = true;
                Bukkit.getPluginManager().callEvent(new SensorDetectEvent(s.id(), s.name(), p, at));
            }
            boolean wasEmpty = s.present.isEmpty();
            s.present.clear();
            s.present.addAll(now_);
            if (entered) {
                String sound = cfg.getString("sounds.sensor-detect", "scpaccess:sensor.detect");
                if (!sound.isBlank()) world.playSound(at, sound, SoundCategory.BLOCKS, 0.6f, 1f);
                s.rig.setState("active");
                RigAnimator.play(s.rig, "detect", false, null);
            } else if (s.present.isEmpty() && !wasEmpty) {
                s.rig.setState("idle");
            }
            if (!seen.isEmpty() && (entered || now - s.lastNote >= refresh)) {
                s.lastNote = now;
                if (plugin.link() != null) plugin.link().noteSensor(s, seen.stream().map(Player::getName).toList());
            }
        }
    }
}

package fi.alavesa.scpaccess.door;

import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.content.ModelLibrary;
import fi.alavesa.scpaccess.rig.RigAnimator;
import fi.alavesa.scpaccess.rig.RigInstance;
import fi.alavesa.scpaccess.store.Data;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The doors: a rig (animated model) for the look, BARRIER blocks for the walls of the footprint while closed.
 * The barriers are real blocks, so a closed door stops players, mobs and projectiles with no per-tick work, and they
 * stay in the world across restarts; the rig's displays are not saved and are respawned when the chunk loads.
 */
public final class DoorManager {

    public enum State { CLOSED, OPENING, OPEN, CLOSING }

    public static final class Door {
        public final Data.DoorData data;
        public ModelLibrary.DoorType type;
        public final List<int[]> cells = new ArrayList<>();
        RigInstance rig;
        State state = State.CLOSED;
        BukkitTask autoClose;
        boolean wantOpen, wantClose;

        Door(Data.DoorData data) { this.data = data; }

        public int id() { return data.id; }

        public String name() { return data.name; }

        public boolean locked() { return data.locked; }

        public State state() { return state; }

        public boolean isOpen() { return state == State.OPEN || state == State.OPENING; }

        public World world() { return Bukkit.getWorld(UUID.fromString(data.world)); }

        public Location anchor() { return new Location(world(), data.x, data.y, data.z, data.yaw, 0); }

        public BoundingBox box() {
            BoundingBox box = null;
            for (int[] c : cells) {
                BoundingBox b = new BoundingBox(c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1);
                box = box == null ? b : box.union(b);
            }
            return box == null ? new BoundingBox() : box;
        }

        public boolean loaded() { return rig != null && rig.alive(); }
    }

    private final ScpAccessPlugin plugin;
    private final Map<Integer, Door> doors = new LinkedHashMap<>();
    private final Map<String, Door> byCell = new java.util.HashMap<>();

    public DoorManager(ScpAccessPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ lifecycle

    public void load() {
        doors.clear();
        byCell.clear();
        for (Data.DoorData data : plugin.data().doors) {
            Door door = new Door(data);
            door.type = plugin.library().doorTypes.get(data.type);
            doors.put(data.id, door);
            if (door.type == null) {
                plugin.getLogger().warning("Door " + data.id + " '" + data.name + "' uses the door type '" + data.type + "' which does not exist (doortypes/" + data.type + "/) - the door is inactive until it is back");
                continue;
            }
            index(door);
            spawn(door);
        }
    }

    private void index(Door door) {
        door.cells.clear();
        door.cells.addAll(DoorGeometry.cells(door.type.width(), door.type.height(), door.data.x, door.data.y, door.data.z, door.data.yaw));
        for (int[] c : door.cells) byCell.put(key(door.data.world, c[0], c[1], c[2]), door);
    }

    private static String key(String world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }

    public void despawnAll() {
        for (Door d : doors.values()) despawn(d);
    }

    public void shutdown() { despawnAll(); }

    public void onChunkLoad(Chunk chunk) {
        for (Door d : doors.values()) {
            if (d.type == null || d.loaded()) continue;
            World w = d.world();
            if (w == chunk.getWorld() && ((int) Math.floor(d.data.x)) >> 4 == chunk.getX() && ((int) Math.floor(d.data.z)) >> 4 == chunk.getZ()) spawn(d);
        }
    }

    public void onChunkUnload(Chunk chunk) {
        for (Door d : doors.values()) {
            World w = d.world();
            if (d.loaded() && w == chunk.getWorld() && ((int) Math.floor(d.data.x)) >> 4 == chunk.getX() && ((int) Math.floor(d.data.z)) >> 4 == chunk.getZ()) despawn(d);
        }
    }

    private void spawn(Door door) {
        World world = door.world();
        if (world == null || door.type == null) return;
        if (!world.isChunkLoaded(((int) Math.floor(door.data.x)) >> 4, ((int) Math.floor(door.data.z)) >> 4)) return;
        if (door.loaded()) return;
        door.rig = new RigInstance(door.type.rig(), door.anchor(), door.data.yaw, (float) plugin.getConfig().getDouble("display.view-range", 1.5));
        door.rig.spawn();
        door.state = door.data.open ? State.OPEN : State.CLOSED;
        steady(door);
        barriers(door, !door.data.open);
        if (door.data.open) scheduleClose(door);
    }

    private void despawn(Door door) {
        if (door.autoClose != null) door.autoClose.cancel();
        door.autoClose = null;
        if (door.rig != null) door.rig.remove();
        door.rig = null;
    }

    /** The resting look of the current state: lamps, and the end pose of the open / close clip. */
    private void steady(Door door) {
        if (door.rig == null) return;
        boolean open = door.state == State.OPEN || door.state == State.OPENING;
        door.rig.setState(open ? "open" : door.data.locked ? "locked" : "closed");
        if (open) {
            if (door.rig.rig().hasClip("open")) RigAnimator.snapTo(door.rig, "open", true);
            else door.rig.hide(true);
        } else {
            door.rig.hide(false);
            if (door.rig.rig().hasClip("close")) RigAnimator.snapTo(door.rig, "close", true);
            else if (door.rig.rig().hasClip("open")) RigAnimator.snapTo(door.rig, "open", false);
            else RigAnimator.snapTo(door.rig, "", false);
        }
    }

    private void barriers(Door door, boolean solid) {
        World world = door.world();
        if (world == null) return;
        for (int[] c : door.cells) {
            Block b = world.getBlockAt(c[0], c[1], c[2]);
            if (solid) { if (b.getType() != Material.BARRIER) b.setType(Material.BARRIER, false); }
            else if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        }
    }

    // ------------------------------------------------------------------ queries

    public Door get(int id) { return doors.get(id); }

    public List<Door> all() { return new ArrayList<>(doors.values()); }

    public Door atBlock(Block block) { return byCell.get(key(block.getWorld().getUID().toString(), block.getX(), block.getY(), block.getZ())); }

    /** Doors whose name matches what a player said. Active doors only. */
    public List<Door> match(String query) {
        List<Door> out = new ArrayList<>();
        for (Door d : doors.values()) if (d.type != null && NameMatch.matches(d.data.name, query)) out.add(d);
        return NameMatch.refine(out, d -> d.data.name, query);
    }

    /** The distinct names minus their numbers ("Cafeteria Door"), for SCP-079's knowledge of the facility. */
    public List<String> locations() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Door d : doors.values()) if (d.type != null) out.add(NameMatch.location(d.data.name));
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ placing

    public record PlaceResult(Door door, String error) { }

    public PlaceResult place(Player player, ModelLibrary.DoorType type, String location, ItemStack item, EquipmentSlot hand, Block clicked, BlockFace face, Location point) {
        Block base = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        float yaw = DoorGeometry.snap(player.getLocation().getYaw() + 180);
        double[] a = DoorGeometry.anchor(base.getX(), base.getY(), base.getZ(), type.width(), yaw, point == null ? base.getX() + 0.5 : point.getX(), point == null ? base.getZ() + 0.5 : point.getZ());
        World world = clicked.getWorld();
        List<int[]> cells = DoorGeometry.cells(type.width(), type.height(), a[0], a[1], a[2], yaw);

        BoundingBox footprint = null;
        for (int[] c : cells) {
            if (c[1] < world.getMinHeight() || c[1] >= world.getMaxHeight()) return new PlaceResult(null, "place.no-space");
            Block b = world.getBlockAt(c[0], c[1], c[2]);
            if (!(b.getType().isAir() || b.isReplaceable())) return new PlaceResult(null, "place.no-space");
            if (byCell.containsKey(key(world.getUID().toString(), c[0], c[1], c[2]))) return new PlaceResult(null, "place.no-space");
            BoundingBox cb = new BoundingBox(c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1);
            footprint = footprint == null ? cb : footprint.union(cb);
        }
        if (footprint != null && !world.getNearbyEntities(footprint, e -> e instanceof LivingEntity).isEmpty()) return new PlaceResult(null, "place.occupied");

        // protection plugins decide through the event they already listen to
        if (plugin.getConfig().getBoolean("placement.fire-block-events", true)) {
            BlockPlaceEvent build = new BlockPlaceEvent(base, base.getState(), clicked, item, player, true, hand);
            Bukkit.getPluginManager().callEvent(build);
            if (build.isCancelled() || !build.canBuild()) return new PlaceResult(null, "place.protected");
        }

        Door door = create(type, location, world, a[0], a[1], a[2], yaw);
        if (player.getGameMode() != GameMode.CREATIVE) item.subtract(1);

        return new PlaceResult(door, null);
    }


    /** Registers and spawns a door at an exact anchor (bottom centre); no checks - callers validate. */
    public Door create(ModelLibrary.DoorType type, String location, World world, double x, double y, double z, float yaw) {
        Data.DoorData data = new Data.DoorData();
        data.id = plugin.nextId();
        data.type = type.id();
        data.name = uniqueName(location);
        data.world = world.getUID().toString();
        data.x = x;
        data.y = y;
        data.z = z;
        data.yaw = yaw;
        plugin.data().doors.add(data);
        Door door = new Door(data);
        door.type = type;
        doors.put(data.id, door);
        index(door);
        spawn(door);
        plugin.markDirty();
        return door;
    }

    /** "Cafeteria" -> "Cafeteria Door 1", then 2, 3...; no location -> "Door N". */
    public String uniqueName(String location) {
        String loc = location == null ? "" : location.strip();
        String base = loc.isEmpty() ? "Door" : loc.toLowerCase(java.util.Locale.ROOT).contains("door") ? loc : loc + " Door";
        int max = 0;
        for (Door d : doors.values()) {
            String n = d.data.name;
            if (n.equalsIgnoreCase(base)) max = Math.max(max, 0);
            if (n.toLowerCase(java.util.Locale.ROOT).startsWith(base.toLowerCase(java.util.Locale.ROOT) + " ")) {
                String tail = n.substring(base.length()).trim();
                try { max = Math.max(max, Integer.parseInt(tail)); } catch (NumberFormatException ignored) { }
            }
        }
        return base + " " + (max + 1);
    }

    public void remove(Door door) {
        despawn(door);
        barriers(door, false);
        for (int[] c : door.cells) byCell.remove(key(door.data.world, c[0], c[1], c[2]));
        doors.remove(door.id());
        plugin.data().doors.remove(door.data);
        for (Data.ReaderData r : plugin.data().readers) r.doors.remove((Integer) door.id());
        plugin.markDirty();

    }

    public void rename(Door door, String name) {
        door.data.name = name.strip();
        plugin.markDirty();

    }

    public void setLocked(Door door, boolean locked) {
        if (door.data.locked == locked) return;
        door.data.locked = locked;
        plugin.markDirty();
        if (door.loaded() && !door.isOpen()) steady(door);
    }

    /** A wall of the footprint was destroyed (explosion): the barriers of that door are put back. */
    public void restore(Door door) { if (!door.data.open) barriers(door, true); }

    // ------------------------------------------------------------------ opening and closing

    public boolean open(Door door) {
        if (door.type == null) return false;
        switch (door.state) {
            case OPEN -> { scheduleClose(door); return true; }
            case OPENING -> { door.wantClose = false; return true; }
            case CLOSING -> { door.wantOpen = true; return true; }
            default -> { }
        }
        door.wantOpen = door.wantClose = false;
        door.state = State.OPENING;
        door.data.open = true;
        plugin.markDirty();
        barriers(door, false);
        play(door, "door-open", "door.open");
        if (door.rig == null) { door.state = State.OPEN; scheduleClose(door); return true; }
        door.rig.setState("open");
        door.rig.hide(false);
        if (!RigAnimator.play(door.rig, "open", false, () -> opened(door))) opened(door);
        return true;
    }

    private void opened(Door door) {
        if (door.state != State.OPENING) return;
        door.state = State.OPEN;
        steady(door);
        if (door.wantClose) { door.wantClose = false; close(door); return; }
        scheduleClose(door);
    }

    /** @return false when the door could not start closing (someone stands in it) */
    public boolean close(Door door) {
        if (door.type == null) return false;
        switch (door.state) {
            case CLOSED, CLOSING -> { door.wantOpen = false; return true; }
            case OPENING -> { door.wantClose = true; return true; }
            default -> { }
        }
        if (plugin.getConfig().getBoolean("doors.wait-for-people", true) && occupied(door)) {
            scheduleCloseIn(door, 20);
            return false;
        }
        if (door.autoClose != null) door.autoClose.cancel();
        door.autoClose = null;
        door.wantOpen = door.wantClose = false;
        door.state = State.CLOSING;
        door.data.open = false;
        plugin.markDirty();
        barriers(door, true);
        play(door, "door-close", "door.close");
        if (door.rig == null) { door.state = State.CLOSED; return true; }
        door.rig.setState(door.data.locked ? "locked" : "closed");
        door.rig.hide(false);
        boolean played = door.rig.rig().hasClip("close") ? RigAnimator.play(door.rig, "close", false, () -> closed(door))
            : RigAnimator.play(door.rig, "open", true, () -> closed(door));
        if (!played) closed(door);
        return true;
    }

    private void closed(Door door) {
        if (door.state != State.CLOSING) return;
        door.state = State.CLOSED;
        steady(door);
        if (door.wantOpen) { door.wantOpen = false; open(door); }
    }

    public void toggle(Door door) {
        if (door.isOpen()) close(door); else open(door);
    }

    private boolean occupied(Door door) {
        World world = door.world();
        if (world == null) return false;
        return !world.getNearbyEntities(door.box().expand(0.05), e -> e instanceof LivingEntity).isEmpty();
    }

    private void scheduleClose(Door door) {
        double seconds = door.type.stayOpenSeconds() >= 0 ? door.type.stayOpenSeconds() : plugin.getConfig().getDouble("doors.stay-open-seconds", 5);
        if (seconds <= 0) { if (door.autoClose != null) door.autoClose.cancel(); door.autoClose = null; return; } // stays open until closed by hand
        scheduleCloseIn(door, (int) Math.max(1, Math.round(seconds * 20)));
    }

    private void scheduleCloseIn(Door door, int ticks) {
        if (door.autoClose != null) door.autoClose.cancel();
        door.autoClose = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            door.autoClose = null;
            if (door.state == State.OPEN) close(door);
        }, ticks);
    }

    private void play(Door door, String configKey, String event) {
        World world = door.world();
        if (world == null) return;
        String sound = door.type.sounds().getOrDefault(configKey.equals("door-open") ? "open" : "close", "");
        if (sound == null || sound.isBlank()) sound = plugin.getConfig().getString("sounds." + configKey, "scpaccess:" + event);
        if (sound.isBlank()) return;
        world.playSound(door.box().getCenter().toLocation(world), sound, SoundCategory.BLOCKS, 1f, 1f);
    }

    /** Plays the "locked" sound at the door (a reader refused because the door is locked). */
    public void playLocked(Door door) {
        World world = door.world();
        String sound = plugin.getConfig().getString("sounds.door-locked", "scpaccess:door.locked");
        if (world != null && !sound.isBlank()) world.playSound(door.box().getCenter().toLocation(world), sound, SoundCategory.BLOCKS, 1f, 1f);
    }
}

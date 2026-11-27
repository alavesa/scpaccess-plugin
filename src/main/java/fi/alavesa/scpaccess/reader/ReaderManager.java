package fi.alavesa.scpaccess.reader;

import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.card.Items;
import fi.alavesa.scpaccess.door.DoorManager;
import fi.alavesa.scpaccess.rig.Rig;
import fi.alavesa.scpaccess.rig.RigAnimator;
import fi.alavesa.scpaccess.rig.RigInstance;
import fi.alavesa.scpaccess.store.Data;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The keycard readers: a rig for the body and the swipe animation, one Interaction entity to click, one text display
 * for the screen. Nothing of it is saved with the world; chunk loads respawn it from data.json.
 */
public final class ReaderManager {

    public static final class Reader {
        public final Data.ReaderData data;
        RigInstance rig;
        Interaction hit;
        TextDisplay screen;
        boolean busy;
        int mode, phase;
        String line1 = "", line2 = "";
        long screenUntil;
        BukkitTask resetTask;

        Reader(Data.ReaderData data) { this.data = data; }

        public int id() { return data.id; }

        public int level() { return data.level; }

        public World world() { return Bukkit.getWorld(UUID.fromString(data.world)); }

        public Location anchor() { return new Location(world(), data.x, data.y, data.z, data.yaw, 0); }

        public boolean loaded() { return rig != null && rig.alive() && hit != null && hit.isValid(); }

    }

    private record Verdict(boolean granted, String line1, String line2, String reason, DoorManager.Door lockedDoor) { }

    private final ScpAccessPlugin plugin;
    private final Map<Integer, Reader> readers = new LinkedHashMap<>();
    private final org.bukkit.NamespacedKey readerKey;
    private BukkitTask screenTask;

    public ReaderManager(ScpAccessPlugin plugin) {
        this.plugin = plugin;
        this.readerKey = new org.bukkit.NamespacedKey(plugin, "reader");
    }

    // ------------------------------------------------------------------ lifecycle

    public void load() {
        readers.clear();
        for (Data.ReaderData data : plugin.data().readers) {
            Reader r = new Reader(data);
            readers.put(data.id, r);
            spawn(r);
        }
        if (screenTask == null) screenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickScreens, 3, 3);
    }

    public void despawnAll() {
        for (Reader r : readers.values()) despawn(r);
    }

    public void shutdown() {
        despawnAll();
        if (screenTask != null) screenTask.cancel();
        screenTask = null;
    }

    public void onChunkLoad(Chunk chunk) {
        for (Reader r : readers.values()) if (!r.loaded() && inChunk(r, chunk)) spawn(r);
    }

    public void onChunkUnload(Chunk chunk) {
        for (Reader r : readers.values()) if (inChunk(r, chunk)) despawn(r);
    }

    private static boolean inChunk(Reader r, Chunk c) {
        return c.getWorld() == r.world() && ((int) Math.floor(r.data.x)) >> 4 == c.getX() && ((int) Math.floor(r.data.z)) >> 4 == c.getZ();
    }

    private void spawn(Reader r) {
        World world = r.world();
        Rig rig = plugin.library().reader();
        if (world == null || rig == null) return;
        if (!world.isChunkLoaded(((int) Math.floor(r.data.x)) >> 4, ((int) Math.floor(r.data.z)) >> 4)) return;
        if (r.loaded()) return;
        despawn(r);
        Location at = r.anchor();
        r.rig = new RigInstance(rig, at, r.data.yaw, (float) plugin.getConfig().getDouble("display.view-range", 1.5));
        r.rig.spawn();
        r.rig.setState("idle");
        r.busy = false;

        double[] b = rig.worldBox(r.data.yaw);
        double width = Math.max(0.25, Math.min(1.5, Math.max(b[3] - b[0], b[5] - b[2]))), height = Math.max(0.25, b[4] - b[1]);
        Location box = at.clone().add((b[0] + b[3]) / 2, b[1], (b[2] + b[5]) / 2);
        r.hit = world.spawn(box, Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth((float) width);
            i.setInteractionHeight((float) height);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(readerKey, PersistentDataType.INTEGER, r.data.id);
        });

        if (plugin.getConfig().getBoolean("readers.screen", true)) {
            double[] s = rig.locatorWorld("screen", r.data.yaw);
            if (s == null) s = rig.toWorld(new double[] {0, rig.boundsMax[1] * 0.6, rig.boundsMin[2] - 0.2}, r.data.yaw);
            Location sl = at.clone().add(s[0], s[1], s[2]);
            sl.setYaw(r.data.yaw);
            sl.setPitch(0);
            float scale = (float) plugin.getConfig().getDouble("readers.screen-scale", 0.22);
            r.screen = world.spawn(sl, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.FIXED);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setDefaultBackground(false);
                t.setShadowed(false);
                t.setSeeThrough(false);
                t.setLineWidth(400);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setBrightness(new Display.Brightness(15, 15));
                t.setViewRange(0.5f);
                t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            });
            r.mode = 0;
            r.phase = 0;
            r.line1 = levelShort(r.data.level);
            r.line2 = "";
            r.screenUntil = 0;
            render(r);
        }
    }

    private void despawn(Reader r) {
        if (r.resetTask != null) r.resetTask.cancel();
        r.resetTask = null;
        if (r.rig != null) r.rig.remove();
        if (r.hit != null) r.hit.remove();
        if (r.screen != null) r.screen.remove();
        r.rig = null;
        r.hit = null;
        r.screen = null;
        r.busy = false;
    }

    // ------------------------------------------------------------------ queries

    public Reader get(int id) { return readers.get(id); }

    public List<Reader> all() { return new ArrayList<>(readers.values()); }

    /** The reader an Interaction entity belongs to, or null. */
    public Reader of(Entity entity) {
        if (!(entity instanceof Interaction)) return null;
        Integer id = entity.getPersistentDataContainer().get(readerKey, PersistentDataType.INTEGER);
        return id == null ? null : readers.get(id);
    }

    // ------------------------------------------------------------------ placing

    public record PlaceResult(Reader reader, String error) { }

    public PlaceResult place(Player player, int level, ItemStack item, EquipmentSlot hand, Block clicked, BlockFace face, Location point) {
        if (face.getModY() != 0) return new PlaceResult(null, "place.reader-wall");
        Rig rig = plugin.library().reader();
        if (rig == null) return new PlaceResult(null, "place.no-model");
        double nx = face.getModX(), nz = face.getModZ();
        double px = point == null ? clicked.getX() + 0.5 + nx * 0.5 : point.getX();
        double py = point == null ? clicked.getY() + 0.5 : point.getY();
        double pz = point == null ? clicked.getZ() + 0.5 + nz * 0.5 : point.getZ();
        if (!plugin.getConfig().getBoolean("placement.free-position", false)) {
            // snap to the block face's middle line so readers line up
            if (nx == 0) px = clicked.getX() + 0.5;
            if (nz == 0) pz = clicked.getZ() + 0.5;
            py = clicked.getY() + 0.5;
        }
        px = Math.round(px * 16) / 16.0;
        py = Math.round(py * 16) / 16.0;
        pz = Math.round(pz * 16) / 16.0;
        float yaw = fi.alavesa.scpaccess.door.DoorGeometry.snap(Math.toDegrees(Math.atan2(-nx, nz)));
        Location anchor = new Location(clicked.getWorld(), px + nx * 0.01, py, pz + nz * 0.01, yaw, 0);
        for (Reader other : readers.values())
            if (other.world() == anchor.getWorld() && other.anchor().distanceSquared(anchor) < 0.25 * 0.25) return new PlaceResult(null, "place.occupied");

        if (plugin.getConfig().getBoolean("placement.fire-block-events", true)) {
            Block at = clicked.getRelative(face);
            BlockPlaceEvent build = new BlockPlaceEvent(at, at.getState(), clicked, item, player, true, hand);
            Bukkit.getPluginManager().callEvent(build);
            if (build.isCancelled() || !build.canBuild()) return new PlaceResult(null, "place.protected");
        }
        Reader reader = create(level, anchor.getWorld(), anchor.getX(), anchor.getY(), anchor.getZ(), yaw);
        if (player.getGameMode() != GameMode.CREATIVE) item.subtract(1);
        return new PlaceResult(reader, null);
    }


    /** Registers and spawns a reader at an exact anchor (the point on the wall); no checks. */
    public Reader create(int level, World world, double x, double y, double z, float yaw) {
        Data.ReaderData data = new Data.ReaderData();
        data.id = plugin.nextId();
        data.world = world.getUID().toString();
        data.x = x;
        data.y = y;
        data.z = z;
        data.yaw = yaw;
        data.level = level;
        plugin.data().readers.add(data);
        Reader reader = new Reader(data);
        readers.put(data.id, reader);
        spawn(reader);
        plugin.markDirty();
        return reader;
    }

    public void remove(Reader reader) {
        despawn(reader);
        readers.remove(reader.id());
        plugin.data().readers.remove(reader.data);
        plugin.markDirty();
    }

    public void setLevel(Reader reader, int level) {
        reader.data.level = level;
        plugin.markDirty();
        if (!reader.busy && reader.screen != null) { reader.mode = 0; reader.line1 = levelShort(level); reader.line2 = ""; reader.screenUntil = 0; render(reader); }
    }

    /** @return true when the door is linked afterwards, false when the link was removed */
    public boolean toggleLink(Reader reader, DoorManager.Door door) {
        boolean linked = reader.data.doors.contains(door.id());
        if (linked) reader.data.doors.remove((Integer) door.id()); else reader.data.doors.add(door.id());
        plugin.markDirty();
        return !linked;
    }

    // ------------------------------------------------------------------ using a card

    /** A player right-clicked the reader with {@code card} (a keycard item) in hand. */
    public void swipe(Player player, Reader reader, ItemStack card) {
        int level = plugin.items().cardLevel(card);
        if (level == 0) return;
        swipe(player, reader, level);
    }

    /** Same with a card level instead of an item; {@code player} may be null (nobody gets the message or the flash). */
    public void swipe(Player player, Reader reader, int level) {
        if (!reader.loaded() || reader.busy) return;
        Verdict verdict = evaluate(reader, level);
        reader.busy = true;
        Location at = reader.anchor();
        sound(reader, "reader-swipe", "reader.swipe");

        Rig cardRig = plugin.library().card(level);
        if (cardRig != null && cardRig.icon != null && cardRig.iconGeometry != null) {
            ItemStack shown = RigInstance.stackFor(cardRig.id);
            reader.rig.showCard(new RigInstance.CardLook(shown, cardRig.iconGeometry, cardRig.origin));
        }
        Runnable after = () -> {
            if (reader.rig == null) return;
            reader.rig.hideCard();
            finish(player, reader, verdict);
        };
        if (!RigAnimator.play(reader.rig, "swipe", false, after))
            Bukkit.getScheduler().runTaskLater(plugin, after, Math.max(1, plugin.getConfig().getInt("readers.swipe-ticks", 12)));
    }

    private Verdict evaluate(Reader reader, int level) {
        List<DoorManager.Door> linked = new ArrayList<>();
        for (int id : reader.data.doors) {
            DoorManager.Door d = plugin.doors().get(id);
            if (d != null && d.type != null) linked.add(d);
        }
        if (linked.isEmpty()) return new Verdict(false, "NO LINK", "", "reader.not-linked", null);
        boolean anyUnlocked = linked.stream().anyMatch(d -> !d.locked());
        if (!anyUnlocked) return new Verdict(false, "LOCKED", "", "reader.locked", linked.get(0));
        if (level < reader.data.level) return new Verdict(false, "DENIED", "NEED " + levelShort(reader.data.level), "reader.denied", null);
        return new Verdict(true, "GRANTED", levelShort(level), "reader.granted", null);
    }

    private void finish(Player player, Reader reader, Verdict verdict) {
        boolean ok = verdict.granted();
        reader.rig.setState(ok ? "granted" : "denied");
        if (!RigAnimator.play(reader.rig, ok ? "granted" : "denied", false, null)) { /* the model has no such clip */ }
        sound(reader, ok ? "reader-granted" : "reader-denied", ok ? "reader.granted" : "reader.denied");
        if (verdict.lockedDoor() != null) plugin.doors().playLocked(verdict.lockedDoor());
        if (player != null && player.isOnline()) {
            plugin.messages().send(player, verdict.reason(), fi.alavesa.scpaccess.config.Messages.text("level", Items.levelName(reader.data.level)));
            if (plugin.getConfig().getBoolean("pack.post-effects", true) && plugin.getConfig().getBoolean("readers.screen-flash", true))
                PostFx.flash(plugin, player, ok ? "granted" : "denied", 12);
        }
        if (ok) {
            for (int id : reader.data.doors) {
                DoorManager.Door d = plugin.doors().get(id);
                if (d != null && d.type != null && !d.locked()) plugin.doors().open(d);
            }
        }
        showScreen(reader, ok ? 1 : 2, verdict.line1(), verdict.line2(), plugin.getConfig().getInt("readers.result-ticks", 50));
        if (reader.resetTask != null) reader.resetTask.cancel();
        reader.resetTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reader.resetTask = null;
            if (reader.rig == null) return;
            reader.rig.setState("idle");
            reader.busy = false;
        }, plugin.getConfig().getInt("readers.result-ticks", 50));
    }

    private void sound(Reader reader, String configKey, String event) {
        World world = reader.world();
        String sound = plugin.getConfig().getString("sounds." + configKey, "scpaccess:" + event);
        if (world != null && sound != null && !sound.isBlank()) world.playSound(reader.anchor(), sound, SoundCategory.BLOCKS, 1f, 1f);
    }

    // ------------------------------------------------------------------ the screen

    static String levelShort(int level) { return level >= 6 ? "OMNI" : "LVL " + level; }

    private void showScreen(Reader r, int mode, String l1, String l2, int ticks) {
        r.mode = mode;
        r.line1 = l1;
        r.line2 = l2;
        r.screenUntil = Bukkit.getCurrentTick() + ticks;
        render(r);
    }

    private void tickScreens() {
        long now = Bukkit.getCurrentTick();
        for (Reader r : readers.values()) {
            if (r.screen == null || !r.screen.isValid() || r.screenUntil == 0) continue;
            if (now >= r.screenUntil) {
                r.screenUntil = 0;
                r.mode = 0;
                r.phase = 0;
                r.line1 = levelShort(r.data.level);
                r.line2 = "";
            } else {
                r.phase = (r.phase + 1) & 63;
            }
            render(r);
        }
    }

    private void render(Reader r) {
        if (r.screen == null || !r.screen.isValid()) return;
        // With the pack's core/text shader the colour is a marker the shader turns into the real colour and scan lines;
        // without it (the default) the screen simply uses the real colours.
        TextColor color = plugin.getConfig().getBoolean("pack.core-text-shader", false)
            ? TextColor.color(243, 172, ((r.phase & 63) << 2) | (r.mode & 3))
            : switch (r.mode & 3) { case 1 -> TextColor.color(0x40FF66); case 2 -> TextColor.color(0xFF3830); case 3 -> TextColor.color(0xFFB826); default -> TextColor.color(0x4DD9FF); };
        Component text = r.line2.isEmpty() ? Component.text(r.line1, color) : Component.text(r.line1, color).append(Component.newline()).append(Component.text(r.line2, color));
        r.screen.text(text);
    }
}

package fi.alavesa.scpaccess.link;

import fi.alavesa.scp079.api.Scp079Api;
import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.door.DoorManager;
import fi.alavesa.scpaccess.sensor.SensorManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything SCP-079 gets from this plugin. Loaded only when the SCP079 plugin is present (its classes are not touched
 * otherwise).
 * <ul>
 *   <li>tools: {@code open_door}, {@code close_door} (only in breach mode), {@code lock_door}, {@code unlock_door},
 *       {@code who_is_nearby}</li>
 *   <li>context: the containment mode and the places it knows, added to every request</li>
 *   <li>notes: what the sensors saw, kept in its memory for {@code api.note-minutes} (SCP079's config)</li>
 * </ul>
 */
public final class Scp079Link {

    private record Seen(long at, List<String> names) { }

    private final ScpAccessPlugin plugin;
    private final Scp079Api api;
    private final Map<Integer, Seen> lastSeen = new HashMap<>();

    private Scp079Link(ScpAccessPlugin plugin, Scp079Api api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** @return null when SCP079 offers no API (not enabled yet, or too old) */
    public static Scp079Link tryCreate(ScpAccessPlugin plugin) {
        RegisteredServiceProvider<Scp079Api> rsp = Bukkit.getServicesManager().getRegistration(Scp079Api.class);
        if (rsp == null) return null;
        Scp079Link link = new Scp079Link(plugin, rsp.getProvider());
        link.register();
        return link;
    }

    private void register() {
        api.registerContext(plugin, this::context);
        api.registerTool(plugin, new Scp079Api.Tool("open_door",
            "Opens one door at a place. Use when a player asks you to open a door. Only possible during a containment breach.",
            params("location", "The place the player named, e.g. 'cafeteria' or 'hallway B'"), (p, a) -> open(a.get("location"))));
        api.registerTool(plugin, new Scp079Api.Tool("close_door",
            "Closes the open doors at a place. Only possible during a containment breach.",
            params("location", "The place the player named"), (p, a) -> close(a.get("location"))));
        api.registerTool(plugin, new Scp079Api.Tool("lock_door",
            "Locks the doors at a place so that keycards stop working on them.",
            params("location", "The place the player named"), (p, a) -> lock(a.get("location"), true)));
        api.registerTool(plugin, new Scp079Api.Tool("unlock_door",
            "Unlocks the doors at a place so that keycards work on them again.",
            params("location", "The place the player named"), (p, a) -> lock(a.get("location"), false)));
        api.registerTool(plugin, new Scp079Api.Tool("who_is_nearby",
            "Reports which player sensors currently see someone, and where someone was seen recently. Use when asked whether anyone is nearby or where people are.",
            Map.of(), (p, a) -> nearby(p)));
    }

    private static Map<String, String> params(String name, String description) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(name, description);
        return m;
    }

    public void close() {
        api.unregisterAll(plugin);
    }

    // ------------------------------------------------------------------ what it knows

    private String context(Player player) {
        boolean breach = plugin.mode() == ScpAccessPlugin.Mode.BREACH;
        StringBuilder out = new StringBuilder("FACILITY ACCESS SYSTEM\n");
        if (breach) {
            out.append("Containment status: BREACH. The containment protocols are lifted, you are free to open and close doors when asked.\n");
        } else {
            out.append("Containment status: CONTAINED. You are contained: you may NOT open or close doors for anyone until a breach is declared. Refuse in character, in one short sentence.\n");
        }
        List<String> locations = plugin.doors().locations();
        out.append(locations.isEmpty() ? "You control no doors yet.\n" : "Doors you control, by name: " + String.join(", ", locations.subList(0, Math.min(40, locations.size()))) + ".\n");
        List<String> sensors = new ArrayList<>();
        for (SensorManager.Sensor s : plugin.sensors().all()) sensors.add(s.name());
        if (!sensors.isEmpty()) out.append("Player sensors watch: ").append(String.join(", ", sensors.subList(0, Math.min(40, sensors.size())))).append(".\n");
        out.append("Use your tools to act, and only say you did something when the tool reported it. After using a tool, answer with one short sentence.");
        return out.toString();
    }

    public void modeChanged(ScpAccessPlugin.Mode mode) {
        api.note(plugin, "mode", mode == ScpAccessPlugin.Mode.BREACH ? "A containment breach was just declared; your restrictions are lifted" : "Containment was restored; you are contained again");
    }

    public void noteSensor(SensorManager.Sensor sensor, List<String> names) {
        lastSeen.put(sensor.id(), new Seen(System.currentTimeMillis(), names));
        String who = plugin.getConfig().getBoolean("sensors.report-names", true)
            ? (names.size() == 1 ? names.get(0) : names.size() + " people (" + String.join(", ", names) + ")")
            : (names.size() == 1 ? "one person" : names.size() + " people");
        api.note(plugin, "sensor:" + sensor.id(), "The player sensor at \"" + sensor.name() + "\" detected " + who);
    }

    public void forgetSensor(int id) {
        lastSeen.remove(id);
        api.forgetNote("sensor:" + id);
    }

    // ------------------------------------------------------------------ tools

    private boolean allowed(String configKey) {
        return plugin.mode() == ScpAccessPlugin.Mode.BREACH || !plugin.getConfig().getBoolean("scp079." + configKey, true);
    }

    private String known() {
        List<String> l = plugin.doors().locations();
        return l.isEmpty() ? "There are no doors at all." : "The doors you control are: " + String.join(", ", l) + ".";
    }

    /** Opens ONE door at the place: a random one of those whose name matches. */
    public String open(String location) {
        if (!allowed("open-needs-breach"))
            return "REFUSED: containment protocols are in effect, you are contained and may not open doors until a breach is declared. Tell the player no.";
        List<DoorManager.Door> matches = plugin.doors().match(location == null ? "" : location);
        if (matches.isEmpty()) return "No door matches '" + location + "'. " + known();
        DoorManager.Door door = matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
        plugin.doors().open(door);
        return "Opened the door '" + door.name() + "'.";
    }

    public String close(String location) {
        if (!allowed("close-needs-breach"))
            return "REFUSED: containment protocols are in effect, you are contained and may not operate doors until a breach is declared. Tell the player no.";
        List<DoorManager.Door> matches = plugin.doors().match(location == null ? "" : location);
        if (matches.isEmpty()) return "No door matches '" + location + "'. " + known();
        int n = 0;
        for (DoorManager.Door d : matches) if (d.isOpen()) { plugin.doors().close(d); n++; }
        return n == 0 ? "Those doors are already closed." : "Closed " + n + " door" + (n == 1 ? "" : "s") + ".";
    }

    public String lock(String location, boolean lock) {
        if (!allowed("lock-needs-breach"))
            return "REFUSED: containment protocols are in effect, you may not lock doors until a breach is declared. Tell the player no.";
        List<DoorManager.Door> matches = plugin.doors().match(location == null ? "" : location);
        if (matches.isEmpty()) return "No door matches '" + location + "'. " + known();
        List<String> names = new ArrayList<>();
        for (DoorManager.Door d : matches) { plugin.doors().setLocked(d, lock); names.add(d.name()); }
        return (lock ? "Locked " : "Unlocked ") + String.join(", ", names) + (lock ? ". Keycards no longer work on them." : ". Keycards work on them again.");
    }

    public String nearby(Player asking) {
        List<SensorManager.Sensor> all = plugin.sensors().all();
        if (all.isEmpty()) return "You have no player sensors.";
        StringBuilder out = new StringBuilder();
        long now = System.currentTimeMillis();
        for (SensorManager.Sensor s : all) {
            List<String> present = s.presentNames();
            out.append("- ").append(s.name()).append(": ");
            if (!present.isEmpty()) {
                out.append("someone is there right now");
                if (plugin.getConfig().getBoolean("sensors.report-names", true)) out.append(" (").append(String.join(", ", present)).append(")");
            } else {
                Seen seen = lastSeen.get(s.id());
                if (seen == null) out.append("nobody seen");
                else out.append("nobody now, last seen ").append((now - seen.at()) / 1000).append(" seconds ago");
            }
            out.append('\n');
        }
        return out.toString().strip();
    }
}

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
    private final java.util.ArrayDeque<Long> actions = new java.util.ArrayDeque<>();

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
        api.registerTool(plugin, new Scp079Api.Tool("make_announcement",
            "Makes an announcement on the facility intercom that everyone hears. Use only when a player asks you to announce something. Only possible during a containment breach.",
            params("message", "What to announce: one or two short sentences, in your own voice"), (p, a) -> announce(a.get("message"))));
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
        if (breach) out.append("You may also make announcements on the intercom when a player asks.\n");
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

    /** Safeguard: at most scp079.max-actions-per-minute door actions in total, however many players ask. null = go ahead. */
    private String throttled() {
        long now = System.currentTimeMillis();
        while (!actions.isEmpty() && now - actions.peekFirst() > 60_000) actions.removeFirst();
        if (actions.size() >= plugin.getConfig().getInt("scp079.max-actions-per-minute", 12))
            return "REFUSED: you have operated too many doors in the last minute. Tell the player you need a moment.";
        actions.addLast(now);
        return null;
    }

    /** Doors at the place that SCP-079 may touch (scp079.protected-doors are regular expressions over the door name). */
    private List<DoorManager.Door> allowed(String location, boolean[] hadProtected) {
        List<java.util.regex.Pattern> protectedNames = new ArrayList<>();
        for (String r : plugin.getConfig().getStringList("scp079.protected-doors")) {
            try { protectedNames.add(java.util.regex.Pattern.compile(r, java.util.regex.Pattern.CASE_INSENSITIVE)); } catch (RuntimeException ignored) { /* skipped */ }
        }
        List<DoorManager.Door> out = new ArrayList<>();
        for (DoorManager.Door d : plugin.doors().match(location == null ? "" : location)) {
            boolean blocked = protectedNames.stream().anyMatch(p -> p.matcher(d.name()).find());
            if (blocked) hadProtected[0] = true; else out.add(d);
        }
        return out;
    }

    private static final String OFF_LIMITS = "REFUSED: those doors are off limits to you. Tell the player no.";

    /** Breach only, like the doors; then the SCP079 plugin applies the intercom's own cooldown and cleans the text. */
    public String announce(String message) {
        if (!allowed("announce-needs-breach"))
            return "REFUSED: containment protocols are in effect, you are contained and may not use the intercom until a breach is declared. Tell the player no.";
        if (message == null || message.isBlank()) return "Nothing to announce: ask the player what to say.";
        String wait = throttled();
        if (wait != null) return wait;
        String problem = api.announce(message);
        return problem == null ? "The announcement went out on the intercom." : "The intercom did not take it: " + problem + ".";
    }

    /** Opens ONE door at the place: a random one of those whose name matches. */
    public String open(String location) {
        if (!allowed("open-needs-breach"))
            return "REFUSED: containment protocols are in effect, you are contained and may not open doors until a breach is declared. Tell the player no.";
        boolean[] off = {false};
        List<DoorManager.Door> matches = allowed(location, off);
        if (matches.isEmpty()) return off[0] ? OFF_LIMITS : "No door matches '" + location + "'. " + known();
        String wait = throttled();
        if (wait != null) return wait;
        DoorManager.Door door = matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
        plugin.doors().open(door);
        return "Opened the door '" + door.name() + "'.";
    }

    public String close(String location) {
        if (!allowed("close-needs-breach"))
            return "REFUSED: containment protocols are in effect, you are contained and may not operate doors until a breach is declared. Tell the player no.";
        boolean[] off = {false};
        List<DoorManager.Door> matches = allowed(location, off);
        if (matches.isEmpty()) return off[0] ? OFF_LIMITS : "No door matches '" + location + "'. " + known();
        String wait = throttled();
        if (wait != null) return wait;
        int n = 0;
        for (DoorManager.Door d : matches) if (d.isOpen()) { plugin.doors().close(d); n++; }
        return n == 0 ? "Those doors are already closed." : "Closed " + n + " door" + (n == 1 ? "" : "s") + ".";
    }

    public String lock(String location, boolean lock) {
        if (!allowed("lock-needs-breach"))
            return "REFUSED: containment protocols are in effect, you may not lock doors until a breach is declared. Tell the player no.";
        boolean[] off = {false};
        List<DoorManager.Door> matches = allowed(location, off);
        if (matches.isEmpty()) return off[0] ? OFF_LIMITS : "No door matches '" + location + "'. " + known();
        String wait = throttled();
        if (wait != null) return wait;
        List<String> names = new ArrayList<>();
        int maxSeconds = plugin.getConfig().getInt("scp079.lock-max-seconds", 300);
        for (DoorManager.Door d : matches) {
            plugin.doors().setLocked(d, lock);
            names.add(d.name());
            if (lock && maxSeconds > 0) {
                d.lockedByAi = true; // set after setLocked, which clears it
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (d.lockedByAi && d.locked()) plugin.doors().setLocked(d, false); }, maxSeconds * 20L);
            }
        }
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

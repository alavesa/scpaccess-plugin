package fi.alavesa.scpaccess.command;

import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.card.Items;
import fi.alavesa.scpaccess.content.ModelLibrary;
import fi.alavesa.scpaccess.door.DoorManager;
import fi.alavesa.scpaccess.reader.ReaderManager;
import fi.alavesa.scpaccess.sensor.SensorManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** {@code /scpaccess} (alias {@code /sa}): everything an admin does. */
public final class AccessCommand implements CommandExecutor, TabCompleter {

    private static final String[] TOP = {"help", "status", "mode", "give", "doortype", "door", "reader", "sensor", "place", "remove", "reload", "pack"};

    private final ScpAccessPlugin plugin;
    private final AccessListener listener;

    public AccessCommand(ScpAccessPlugin plugin, AccessListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    private static void say(CommandSender to, String text) { to.sendMessage(Component.text("[ScpAccess] ", NamedTextColor.DARK_AQUA).append(Component.text(text, NamedTextColor.GRAY))); }

    private static void fail(CommandSender to, String text) { to.sendMessage(Component.text("[ScpAccess] ", NamedTextColor.DARK_AQUA).append(Component.text(text, NamedTextColor.RED))); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scpaccess.admin")) { plugin.messages().send(sender, "no-permission"); return true; }
        if (args.length == 0) { help(sender); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help" -> help(sender);
                case "status" -> status(sender);
                case "mode" -> mode(sender, args);
                case "give" -> give(sender, args);
                case "doortype" -> doortype(sender, args);
                case "door" -> door(sender, args);
                case "reader" -> reader(sender, args);
                case "sensor" -> sensor(sender, args);
                case "remove" -> {
                    if (!(sender instanceof Player p)) { fail(sender, "Players only."); return true; }
                    listener.removeLooked(p);
                }
                case "reload" -> {
                    plugin.reload();
                    say(sender, "Reloaded: config, messages, models and the pack (" + plugin.warnings().size() + " warning(s)" + (plugin.warnings().isEmpty() ? "" : ", see the console") + ").");
                }
                case "pack" -> pack(sender, args);
                case "place" -> place(sender, args);
                case "test" -> test(sender, args);
                default -> help(sender);
            }
        } catch (RuntimeException e) {
            fail(sender, "That did not work: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "command failed", e);
        }
        return true;
    }

    private void help(CommandSender s) {
        say(s, "/scpaccess status | mode [breach|contained] | reload | remove");
        say(s, "  give [player] card <1-5|omni> | reader [level] | door <type> [location] | sensor [location] | linktool");
        say(s, "  doortype create <name> <3x3|1x2|6x3> | list");
        say(s, "  door [id] list | info | name <name> | lock | unlock | open | close | toggle | remove");
        say(s, "  reader [id] list | info | level <1-5|omni> | link <door id> | unlink | remove      sensor [id] list | info | name <name> | radius <n> | remove");
        say(s, "  pack build | info      (door/reader/sensor without an id = the one you are looking at)");
        say(s, "  place door <type> <x> <y> <z> <yaw> [location] | reader <level> <x> <y> <z> <yaw> | sensor <x> <y> <z> <yaw> [location]   (no item needed; yaw 0/90/180/-90 = the way the front faces)");
    }

    private void status(CommandSender s) {
        say(s, "Mode: " + plugin.mode() + " | doors " + plugin.data().doors.size() + ", readers " + plugin.data().readers.size() + ", sensors " + plugin.data().sensors.size()
            + " | door types " + plugin.library().doorTypes.size() + " | SCP-079: " + (plugin.link() != null ? "connected" : "not connected"));
        var pack = plugin.pack();
        say(s, pack == null ? "Pack: not built" : "Pack: " + plugin.packFile() + " (" + pack.files() + " files, text shader " + pack.textShader() + ")");
    }

    private void mode(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "Mode: " + plugin.mode() + ". Change with /scpaccess mode breach|contained."); return; }
        ScpAccessPlugin.Mode mode = switch (a[1].toLowerCase(Locale.ROOT)) {
            case "breach" -> ScpAccessPlugin.Mode.BREACH;
            case "contained", "contain" -> ScpAccessPlugin.Mode.CONTAINED;
            default -> null;
        };
        if (mode == null) { fail(s, "Use breach or contained."); return; }
        say(s, plugin.setMode(mode) ? "Mode is now " + mode + "." : "Already " + mode + ".");
    }

    // ------------------------------------------------------------------ give

    private void give(CommandSender sender, String[] a) {
        int i = 1;
        Player target = sender instanceof Player p ? p : null;
        if (a.length > 2) {
            Player named = Bukkit.getPlayerExact(a[1]);
            if (named != null) { target = named; i = 2; }
        }
        if (a.length <= i) { fail(sender, "Give what? card, reader, door, sensor or linktool."); return; }
        if (target == null) { fail(sender, "Name an online player."); return; }
        String what = a[i].toLowerCase(Locale.ROOT);
        String rest = String.join(" ", Arrays.copyOfRange(a, i + 1, a.length)).strip();
        ItemStack item;
        switch (what) {
            case "card" -> {
                int level = rest.isEmpty() ? 0 : Items.parseLevel(rest.split(" ")[0]);
                if (level == 0) { fail(sender, "Card level: 1, 2, 3, 4, 5 or omni."); return; }
                item = plugin.items().card(level);
            }
            case "reader" -> {
                int level = rest.isEmpty() ? 1 : Items.parseLevel(rest.split(" ")[0]);
                if (level == 0) { fail(sender, "Reader level: 1, 2, 3, 4, 5 or omni."); return; }
                item = plugin.items().reader(level);
            }
            case "door" -> {
                String[] p = rest.split(" ", 2);
                var type = p[0].isEmpty() ? null : plugin.library().doorTypes.get(ModelLibrary.sanitize(p[0]));
                if (type == null) { fail(sender, "Door type: " + String.join(", ", plugin.library().doorTypes.keySet()) + "."); return; }
                item = plugin.items().door(type, p.length > 1 ? p[1].strip() : "");
            }
            case "sensor" -> item = plugin.items().sensor(rest);
            case "linktool", "link" -> item = plugin.items().linkTool();
            default -> { fail(sender, "Give what? card, reader, door, sensor or linktool."); return; }
        }
        final Player to = target;
        to.getInventory().addItem(item).values().forEach(left -> to.getWorld().dropItemNaturally(to.getLocation(), left));
        say(sender, "Gave " + to.getName() + " " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName()) + ".");
    }

    private void doortype(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            if (plugin.library().doorTypes.isEmpty()) say(s, "No door types.");
            plugin.library().doorTypes.values().forEach(t -> say(s, t.id() + "  " + t.size() + "  " + t.rig().model.animations.keySet()));
            return;
        }
        if (a.length < 4 || !a[1].equalsIgnoreCase("create")) { fail(s, "/scpaccess doortype create <name> <3x3|1x2|6x3>"); return; }
        String id = ModelLibrary.sanitize(a[2]);
        String size = a[3].toLowerCase(Locale.ROOT);
        if (id.isEmpty()) { fail(s, "The name needs letters or digits."); return; }
        if (!ModelLibrary.DOOR_SIZES.containsKey(size)) { fail(s, "Size must be one of " + String.join(", ", ModelLibrary.DOOR_SIZES.keySet()) + "."); return; }
        if (plugin.library().doorTypes.containsKey(id)) { fail(s, "A door type '" + id + "' already exists."); return; }
        try {
            var dir = ModelLibrary.writeDoorType(plugin.getDataFolder().toPath().resolve("doortypes"), id, size);
            plugin.reload();
            say(s, "Created door type '" + id + "' (" + size + ") in " + dir + ". Put your own door.bbmodel there and run /scpaccess reload; get the door with /scpaccess give door " + id + " <location>.");
        } catch (IOException e) {
            fail(s, "Could not create the folder: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ objects

    /** "door 5 lock" -> id 5; "door lock" -> the one the sender looks at. */
    private record Sel(Integer id, int next) { }

    private static Sel selection(String[] a) {
        if (a.length >= 3) {
            try { return new Sel(Integer.parseInt(a[1]), 2); } catch (NumberFormatException ignored) { }
        }
        return new Sel(null, 1);
    }

    private Integer looked(CommandSender s, Targeting.Kind kind) {
        if (!(s instanceof Player p)) { fail(s, "From the console give an id: /scpaccess " + kind.name().toLowerCase(Locale.ROOT) + " <id> ..."); return null; }
        Targeting.Hit hit = Targeting.lookingAt(plugin, p, 8);
        if (hit == null || hit.kind() != kind) { fail(s, "Look at a " + kind.name().toLowerCase(Locale.ROOT) + " (within 8 blocks) or give its id."); return null; }
        return hit.id();
    }

    private void door(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        if (a[1].equalsIgnoreCase("list")) {
            if (plugin.doors().all().isEmpty()) say(s, "No doors.");
            for (DoorManager.Door d : plugin.doors().all()) say(s, "#" + d.id() + " " + d.name() + " [" + (d.type == null ? "MISSING TYPE " + d.data.type : d.type.id()) + "] " + d.state() + (d.locked() ? " LOCKED" : "")
                + " at " + (int) d.data.x + " " + (int) d.data.y + " " + (int) d.data.z);
            return;
        }
        Sel sel = selection(a);
        Integer id = sel.id() != null ? sel.id() : looked(s, Targeting.Kind.DOOR);
        if (id == null) return;
        DoorManager.Door d = plugin.doors().get(id);
        if (d == null) { fail(s, "No door #" + id); return; }
        String sub = a[sel.next()].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> say(s, "#" + d.id() + " '" + d.name() + "' type " + d.data.type + " " + d.state() + (d.locked() ? ", LOCKED" : "") + ", " + d.cells.size() + " blocks, readers: " + readersOf(d));
            case "name" -> {
                String name = String.join(" ", Arrays.copyOfRange(a, sel.next() + 1, a.length)).strip();
                if (name.isEmpty()) { fail(s, "/scpaccess door name <name>  e.g. \"Cafeteria Door 1\""); return; }
                plugin.doors().rename(d, name);
                say(s, "Door #" + d.id() + " is now called '" + name + "'.");
            }
            case "lock" -> { plugin.doors().setLocked(d, true); say(s, d.name() + " locked: keycards do nothing on it."); }
            case "unlock" -> { plugin.doors().setLocked(d, false); say(s, d.name() + " unlocked."); }
            case "open" -> { plugin.doors().open(d); say(s, "Opening " + d.name() + "."); }
            case "close" -> { plugin.doors().close(d); say(s, "Closing " + d.name() + "."); }
            case "toggle" -> { plugin.doors().toggle(d); say(s, "Toggled " + d.name() + "."); }
            case "remove" -> {
                String n = d.name();
                plugin.doors().remove(d);
                say(s, "Removed " + n + ".");
            }
            default -> help(s);
        }
    }

    private String readersOf(DoorManager.Door d) {
        List<String> ids = new ArrayList<>();
        for (ReaderManager.Reader r : plugin.readers().all()) if (r.data.doors.contains(d.id())) ids.add("#" + r.id());
        return ids.isEmpty() ? "none" : String.join(", ", ids);
    }

    private void reader(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        if (a[1].equalsIgnoreCase("list")) {
            if (plugin.readers().all().isEmpty()) say(s, "No readers.");
            for (ReaderManager.Reader r : plugin.readers().all()) say(s, "#" + r.id() + " needs " + Items.levelName(r.level()) + ", doors " + r.data.doors + " at " + (int) r.data.x + " " + (int) r.data.y + " " + (int) r.data.z);
            return;
        }
        Sel sel = selection(a);
        Integer id = sel.id() != null ? sel.id() : looked(s, Targeting.Kind.READER);
        if (id == null) return;
        ReaderManager.Reader r = plugin.readers().get(id);
        if (r == null) { fail(s, "No reader #" + id); return; }
        String sub = a[sel.next()].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> say(s, "Reader #" + r.id() + " needs " + Items.levelName(r.level()) + ", opens: " + (r.data.doors.isEmpty() ? "nothing (not linked)" : r.data.doors.stream().map(i -> plugin.doors().get(i) == null ? "#" + i + "?" : plugin.doors().get(i).name()).toList()));
            case "level" -> {
                int level = a.length > sel.next() + 1 ? Items.parseLevel(a[sel.next() + 1]) : 0;
                if (level == 0) { fail(s, "Level: 1, 2, 3, 4, 5 or omni."); return; }
                plugin.readers().setLevel(r, level);
                say(s, "Reader #" + r.id() + " now needs " + Items.levelName(level) + ".");
            }
            case "link" -> {
                DoorManager.Door d = a.length > sel.next() + 1 ? plugin.doors().get(parseInt(a[sel.next() + 1])) : null;
                if (d == null) { fail(s, "/scpaccess reader <id> link <door id>   (or use the link tool)"); return; }
                boolean linked = plugin.readers().toggleLink(r, d);
                say(s, (linked ? "Linked " : "Unlinked ") + d.name() + (linked ? " to" : " from") + " reader #" + r.id() + ".");
            }
            case "unlink" -> { r.data.doors.clear(); plugin.markDirty(); say(s, "Reader #" + r.id() + " is not linked to any door now."); }
            case "remove" -> { plugin.readers().remove(r); say(s, "Removed reader #" + id + "."); }
            default -> help(s);
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.replace("#", "")); } catch (NumberFormatException e) { return -1; }
    }

    private void sensor(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        if (a[1].equalsIgnoreCase("list")) {
            if (plugin.sensors().all().isEmpty()) say(s, "No sensors.");
            for (SensorManager.Sensor x : plugin.sensors().all()) say(s, "#" + x.id() + " '" + x.name() + "' radius " + x.data.radius + (x.presentNames().isEmpty() ? "" : " - sees " + x.presentNames()));
            return;
        }
        Sel sel = selection(a);
        Integer id = sel.id() != null ? sel.id() : looked(s, Targeting.Kind.SENSOR);
        if (id == null) return;
        SensorManager.Sensor x = plugin.sensors().get(id);
        if (x == null) { fail(s, "No sensor #" + id); return; }
        String sub = a[sel.next()].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> say(s, "Sensor #" + x.id() + " '" + x.name() + "', radius " + x.data.radius + ", sees " + (x.presentNames().isEmpty() ? "nobody" : x.presentNames()));
            case "name" -> {
                String name = String.join(" ", Arrays.copyOfRange(a, sel.next() + 1, a.length)).strip();
                if (name.isEmpty()) { fail(s, "/scpaccess sensor name <location>  e.g. \"Hallway B\""); return; }
                plugin.sensors().rename(x, name);
                say(s, "Sensor #" + x.id() + " now watches '" + name + "'.");
            }
            case "radius" -> {
                double r;
                try { r = Double.parseDouble(a[sel.next() + 1]); } catch (RuntimeException e) { fail(s, "/scpaccess sensor radius <blocks>"); return; }
                plugin.sensors().setRadius(x, r);
                say(s, "Radius of '" + x.name() + "' is " + x.data.radius + ".");
            }
            case "remove" -> { String n = x.name(); plugin.sensors().remove(x); say(s, "Removed sensor '" + n + "'."); }
            default -> help(s);
        }
    }

    private org.bukkit.World worldOf(CommandSender s) {
        return s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
    }

    private static double num(String s) { return Double.parseDouble(s.replace(',', '.')); }

    /** Placing without an item: scripts, tests, rebuilding a map. Coordinates are the anchor (bottom centre of a door, the point on the wall of a reader). */
    private void place(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        var world = worldOf(s);
        try {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "door" -> {
                    var type = plugin.library().doorTypes.get(ModelLibrary.sanitize(a[2]));
                    if (type == null) { fail(s, "Door type: " + String.join(", ", plugin.library().doorTypes.keySet()) + "."); return; }
                    float yaw = fi.alavesa.scpaccess.door.DoorGeometry.snap(num(a[6]));
                    String location = a.length > 7 ? String.join(" ", Arrays.copyOfRange(a, 7, a.length)) : "";
                    var d = plugin.doors().create(type, location, world, num(a[3]), num(a[4]), num(a[5]), yaw);
                    say(s, "Placed door #" + d.id() + " '" + d.name() + "'.");
                }
                case "reader" -> {
                    int level = Items.parseLevel(a[2]);
                    if (level == 0) { fail(s, "Level: 1, 2, 3, 4, 5 or omni."); return; }
                    var r = plugin.readers().create(level, world, num(a[3]), num(a[4]), num(a[5]), fi.alavesa.scpaccess.door.DoorGeometry.snap(num(a[6])));
                    say(s, "Placed reader #" + r.id() + " (" + Items.levelName(level) + ").");
                }
                case "sensor" -> {
                    String name = a.length > 6 ? String.join(" ", Arrays.copyOfRange(a, 6, a.length)) : "";
                    var x = plugin.sensors().create(name, world, num(a[2]), num(a[3]), num(a[4]), fi.alavesa.scpaccess.door.DoorGeometry.snap(num(a[5])));
                    say(s, "Placed sensor #" + x.id() + " '" + x.name() + "'.");
                }
                default -> help(s);
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            fail(s, "Missing or wrong number - see /scpaccess help.");
        }
    }

    /** Hidden: drives the flows that normally need a player and a client (swiping, SCP-079's tools). */
    private void test(CommandSender s, String[] a) {
        if (a.length < 2) return;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "swipe" -> {
                var r = plugin.readers().get(Integer.parseInt(a[2]));
                if (r == null) { fail(s, "No reader #" + a[2]); return; }
                plugin.readers().swipe(s instanceof Player p ? p : null, r, Items.parseLevel(a[3]));
                say(s, "Swiped a level " + a[3] + " card on reader #" + r.id() + ".");
            }
            case "079" -> {
                var link = plugin.link();
                if (link == null) { fail(s, "SCP-079 is not connected."); return; }
                String arg = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "";
                String out = switch (a[2]) {
                    case "open_door" -> link.open(arg);
                    case "close_door" -> link.close(arg);
                    case "lock_door" -> link.lock(arg, true);
                    case "unlock_door" -> link.lock(arg, false);
                    case "who_is_nearby" -> link.nearby(s instanceof Player p ? p : null);
                    case "make_announcement" -> link.announce(arg);
                    default -> "unknown tool";
                };
                say(s, "079 tool " + a[2] + " -> " + out);
            }
            default -> { }
        }
    }

    private void pack(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("build")) {
            try {
                plugin.rebuildPack();
                say(s, "Pack written to " + plugin.packFile() + " - put it on your resource pack by hand.");
            } catch (IOException e) {
                fail(s, "Could not build the pack: " + e.getMessage());
            }
            return;
        }
        var p = plugin.pack();
        say(s, "The pack is not sent to anyone: install " + plugin.packFile() + " yourself (server resource pack, or in the players' packs folder).");
        if (p != null) say(s, p.files() + " files, text shader: " + p.textShader() + ", sounds you can replace: " + String.join(", ", fi.alavesa.scpaccess.pack.PackBuilder.SOUND_EVENTS.keySet()) + " (plugins/ScpAccess/sounds/<event>.ogg).");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("scpaccess.admin")) return List.of();
        List<String> out = new ArrayList<>();
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) out.addAll(Arrays.asList(TOP));
        else switch (a[0].toLowerCase(Locale.ROOT)) {
            case "mode" -> { if (a.length == 2) out.addAll(List.of("breach", "contained")); }
            case "give" -> {
                if (a.length == 2) { Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName())); out.addAll(List.of("card", "reader", "door", "sensor", "linktool")); }
                else {
                    int w = Bukkit.getPlayerExact(a[1]) != null ? 2 : 1;
                    if (a.length == w + 1) out.addAll(List.of("card", "reader", "door", "sensor", "linktool"));
                    else if (a.length == w + 2) {
                        String what = a[w].toLowerCase(Locale.ROOT);
                        if (what.equals("card") || what.equals("reader")) out.addAll(List.of("1", "2", "3", "4", "5", "omni"));
                        else if (what.equals("door")) out.addAll(plugin.library().doorTypes.keySet());
                    }
                }
            }
            case "doortype" -> {
                if (a.length == 2) out.addAll(List.of("create", "list"));
                else if (a.length == 4 && a[1].equalsIgnoreCase("create")) out.addAll(ModelLibrary.DOOR_SIZES.keySet());
            }
            case "door" -> { if (a.length == 2) out.addAll(List.of("list", "info", "name", "lock", "unlock", "open", "close", "toggle", "remove")); }
            case "reader" -> {
                if (a.length == 2) out.addAll(List.of("list", "info", "level", "link", "unlink", "remove"));
                else if (a.length == 3 && a[1].equalsIgnoreCase("level")) out.addAll(List.of("1", "2", "3", "4", "5", "omni"));
            }
            case "sensor" -> { if (a.length == 2) out.addAll(List.of("list", "info", "name", "radius", "remove")); }
            case "pack" -> { if (a.length == 2) out.addAll(List.of("build", "info")); }
            default -> { }
        }
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}

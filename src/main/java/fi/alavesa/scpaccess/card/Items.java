package fi.alavesa.scpaccess.card;

import fi.alavesa.scpaccess.content.ModelLibrary;
import fi.alavesa.scpaccess.rig.Rig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Supplier;

/**
 * Every item of the plugin. An item is recognised ONLY by its persistent data (never by name, lore or model), so
 * renaming something in an anvil cannot forge a keycard. Its look is the {@code item_model} component, which the
 * resource pack answers - without the pack the items are plain paper.
 */
public final class Items {

    public static final String[] LEVEL_NAMES = {"Level 1", "Level 2", "Level 3", "Level 4", "Level 5", "Omni"};
    private static final TextColor[] LEVEL_COLORS = {
        TextColor.color(0x9FA8B3), TextColor.color(0x4CAF50), TextColor.color(0x2196F3), TextColor.color(0xFF9800), TextColor.color(0xE53935), TextColor.color(0xD4AF37)};

    /** What an item is: {@code kind} = card, reader, door, sensor or linktool; the rest depends on the kind. */
    public record Parsed(String kind, int level, String type, String name) { }

    private final NamespacedKey key;
    private final Supplier<ModelLibrary> library;

    public Items(Plugin plugin, Supplier<ModelLibrary> library) {
        this.key = new NamespacedKey(plugin, "item");
        this.library = library;
    }

    public static String levelName(int level) { return LEVEL_NAMES[Math.max(1, Math.min(6, level)) - 1]; }

    public static TextColor levelColor(int level) { return LEVEL_COLORS[Math.max(1, Math.min(6, level)) - 1]; }

    /** "1".."5" or "omni" (also "6", "level3", "l3") -> 1..6, or 0 when it is not a level. */
    public static int parseLevel(String s) {
        String t = s.toLowerCase(java.util.Locale.ROOT).replace("level", "").replace("lvl", "").replace("l", "").trim();
        if (s.equalsIgnoreCase("omni") || t.equals("omni")) return 6;
        try {
            int n = Integer.parseInt(t.isEmpty() ? s : t);
            return n >= 1 && n <= 6 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ItemStack make(Material material, String rigId, Component name, List<Component> lore, String data) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        Rig rig = library.get() == null ? null : library.get().rigs.get(rigId);
        if (rig != null && rig.icon != null) meta.setItemModel(new NamespacedKey("scpaccess", rigId));
        meta.customName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, data);
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack card(int level) {
        return make(Material.PAPER, "card/" + ModelLibrary.CARD_IDS[level - 1], Component.text(levelName(level) + " Keycard", levelColor(level)),
            List.of(Component.text("Hold it and right-click a reader", NamedTextColor.DARK_GRAY)), "card|" + level);
    }

    public ItemStack reader(int level) {
        return make(Material.PAPER, "reader/reader", Component.text("Keycard Reader", NamedTextColor.WHITE),
            List.of(Component.text("Needs " + levelName(level), levelColor(level)), Component.text("Place on a wall, link it to doors with the link tool", NamedTextColor.DARK_GRAY)), "reader|" + level);
    }

    public ItemStack door(ModelLibrary.DoorType type, String location) {
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text(type.size() + " door - " + type.id(), NamedTextColor.GRAY));
        lore.add(location.isBlank() ? Component.text("Unnamed: it will be called \"Door N\"", NamedTextColor.DARK_GRAY) : Component.text("Location: " + location, NamedTextColor.GRAY));
        return make(Material.PAPER, "door/" + type.id(), Component.text("Door (" + type.size() + ")", NamedTextColor.WHITE), lore, "door|" + type.id() + "|" + location);
    }

    public ItemStack sensor(String location) {
        return make(Material.PAPER, "sensor/sensor", Component.text("Player Sensor", NamedTextColor.WHITE),
            List.of(location.isBlank() ? Component.text("Unnamed: name it with /scpaccess sensor name", NamedTextColor.DARK_GRAY) : Component.text("Location: " + location, NamedTextColor.GRAY)), "sensor|" + location);
    }

    public ItemStack linkTool() {
        return make(Material.STICK, "tool/link_tool", Component.text("Link Tool", NamedTextColor.AQUA),
            List.of(Component.text("Right-click a reader, then the doors it opens", NamedTextColor.GRAY),
                Component.text("Right-click a linked door again to unlink", NamedTextColor.DARK_GRAY),
                Component.text("Shift + right-click: forget the reader", NamedTextColor.DARK_GRAY),
                Component.text("Shift + left-click an object: remove it", NamedTextColor.DARK_GRAY)), "linktool");
    }

    /** @return null when the stack is not one of ours */
    public Parsed parse(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return null;
        String data = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (data == null) return null;
        String[] p = data.split("\\|", -1);
        try {
            return switch (p[0]) {
                case "card" -> new Parsed("card", Integer.parseInt(p[1]), "", "");
                case "reader" -> new Parsed("reader", Integer.parseInt(p[1]), "", "");
                case "door" -> new Parsed("door", 0, p[1], p.length > 2 ? p[2] : "");
                case "sensor" -> new Parsed("sensor", 0, "", p.length > 1 ? p[1] : "");
                case "linktool" -> new Parsed("linktool", 0, "", "");
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The keycard level of a stack, 0 when it is not a keycard. */
    public int cardLevel(ItemStack stack) {
        Parsed p = parse(stack);
        return p != null && p.kind().equals("card") ? Math.max(1, Math.min(6, p.level())) : 0;
    }
}

package fi.alavesa.scpaccess.command;

import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.card.Items;
import fi.alavesa.scpaccess.config.Messages;
import fi.alavesa.scpaccess.door.DoorManager;
import fi.alavesa.scpaccess.reader.ReaderManager;
import fi.alavesa.scpaccess.sensor.SensorManager;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Placing the items, swiping cards, the link tool, and keeping the doors' barriers safe. */
public final class AccessListener implements Listener {

    private final ScpAccessPlugin plugin;
    /** Link tool: the reader each player selected. */
    private final Map<UUID, Integer> selected = new HashMap<>();
    private final Map<UUID, Long> hinted = new HashMap<>();

    public AccessListener(ScpAccessPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ chunks

    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        plugin.doors().onChunkLoad(e.getChunk());
        plugin.readers().onChunkLoad(e.getChunk());
        plugin.sensors().onChunkLoad(e.getChunk());
    }

    @EventHandler
    public void onEntitiesUnload(org.bukkit.event.world.EntitiesUnloadEvent e) {
        plugin.doors().onChunkUnload(e.getChunk());
        plugin.readers().onChunkUnload(e.getChunk());
        plugin.sensors().onChunkUnload(e.getChunk());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selected.remove(e.getPlayer().getUniqueId());
        hinted.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ clicking

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack stack = event.getItem();
        Items.Parsed item = plugin.items().parse(stack);
        Action action = event.getAction();
        if (item == null) return;

        if (item.kind().equals("linktool")) {
            event.setCancelled(true);
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) useLinkTool(player);
            else if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) && player.isSneaking()) removeLooked(player);
            return;
        }
        if (action != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        switch (item.kind()) {
            case "door" -> {
                event.setCancelled(true);
                if (!may(player, "scpaccess.place")) return;
                var type = plugin.library().doorTypes.get(item.type());
                if (type == null) { plugin.messages().send(player, "place.unknown-type", Messages.text("type", item.type())); return; }
                var result = plugin.doors().place(player, type, item.name(), stack, event.getHand(), clicked, event.getBlockFace(), event.getInteractionPoint());
                if (result.error() != null) plugin.messages().send(player, result.error());
                else plugin.messages().send(player, "place.door", Messages.text("name", result.door().name()));
            }
            case "reader" -> {
                event.setCancelled(true);
                if (!may(player, "scpaccess.place")) return;
                var result = plugin.readers().place(player, item.level(), stack, event.getHand(), clicked, event.getBlockFace(), event.getInteractionPoint());
                if (result.error() != null) plugin.messages().send(player, result.error());
                else plugin.messages().send(player, "place.reader", Messages.text("level", Items.levelName(item.level())));
            }
            case "sensor" -> {
                event.setCancelled(true);
                if (!may(player, "scpaccess.place")) return;
                var result = plugin.sensors().place(player, item.name(), stack, event.getHand(), clicked, event.getBlockFace(), event.getInteractionPoint());
                if (result.error() != null) plugin.messages().send(player, result.error());
                else plugin.messages().send(player, "place.sensor", Messages.text("name", result.sensor().name()));
            }
            case "card" -> event.setCancelled(true); // a card is not a block item
            default -> { }
        }
    }

    private boolean may(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        plugin.messages().send(player, "no-permission");
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ReaderManager.Reader reader = plugin.readers().of(event.getRightClicked());
        if (reader == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Items.Parsed item = plugin.items().parse(hand);
        if (item != null && item.kind().equals("card")) {
            if (!player.hasPermission("scpaccess.use")) { plugin.messages().send(player, "no-permission"); return; }
            plugin.readers().swipe(player, reader, hand);
        } else if (item != null && item.kind().equals("linktool")) {
            useLinkTool(player);
        } else {
            long now = System.currentTimeMillis();
            if (now - hinted.getOrDefault(player.getUniqueId(), 0L) > 1500) {
                hinted.put(player.getUniqueId(), now);
                plugin.messages().send(player, "reader.need-card", Messages.text("level", Items.levelName(reader.level())));
            }
        }
    }

    /** Left-click on a reader's hitbox: never damages it; shift + left-click with the link tool removes it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        ReaderManager.Reader reader = plugin.readers().of(event.getAttacked());
        if (reader == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Items.Parsed item = plugin.items().parse(player.getInventory().getItemInMainHand());
        if (item != null && item.kind().equals("linktool") && player.isSneaking()) removeLooked(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.readers().of(event.getEntity()) != null) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ the link tool

    private void useLinkTool(Player player) {
        if (!may(player, "scpaccess.admin")) return;
        if (player.isSneaking()) {
            if (selected.remove(player.getUniqueId()) != null) plugin.messages().send(player, "link.cleared");
            return;
        }
        Targeting.Hit hit = Targeting.lookingAt(plugin, player, 6);
        if (hit == null) { plugin.messages().send(player, "link.look-at"); return; }
        switch (hit.kind()) {
            case READER -> {
                selected.put(player.getUniqueId(), hit.id());
                var reader = plugin.readers().get(hit.id());
                plugin.messages().send(player, "link.reader-selected", Messages.text("id", hit.id()), Messages.text("level", Items.levelName(reader.level())),
                    Messages.text("count", reader.data.doors.size()));
            }
            case DOOR -> {
                Integer readerId = selected.get(player.getUniqueId());
                var reader = readerId == null ? null : plugin.readers().get(readerId);
                var door = plugin.doors().get(hit.id());
                if (reader == null) { plugin.messages().send(player, "link.select-first"); return; }
                boolean linked = plugin.readers().toggleLink(reader, door);
                plugin.messages().send(player, linked ? "link.linked" : "link.unlinked", Messages.text("name", door.name()), Messages.text("id", reader.id()),
                    Messages.text("count", reader.data.doors.size()));
            }
            case SENSOR -> plugin.messages().send(player, "link.is-sensor", Messages.text("name", plugin.sensors().get(hit.id()).name()));
        }
    }

    /** Shift + left-click with the link tool, or /scpaccess remove. */
    void removeLooked(Player player) {
        if (!may(player, "scpaccess.admin")) return;
        Targeting.Hit hit = Targeting.lookingAt(plugin, player, 6);
        if (hit == null) { plugin.messages().send(player, "link.look-at"); return; }
        remove(player, hit);
    }

    public void remove(Player player, Targeting.Hit hit) {
        ItemStack back = null;
        String what = "";
        switch (hit.kind()) {
            case DOOR -> {
                var d = plugin.doors().get(hit.id());
                what = d.name();
                if (d.type != null) back = plugin.items().door(d.type, NameLocation.of(d.name()));
                plugin.doors().remove(d);
            }
            case READER -> {
                var r = plugin.readers().get(hit.id());
                what = "reader #" + r.id();
                back = plugin.items().reader(r.level());
                plugin.readers().remove(r);
            }
            case SENSOR -> {
                var s = plugin.sensors().get(hit.id());
                what = s.name();
                back = plugin.items().sensor(s.name());
                plugin.sensors().remove(s);
            }
        }
        if (back != null && player.getGameMode() != GameMode.CREATIVE) player.getInventory().addItem(back).values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        plugin.messages().send(player, "removed", Messages.text("name", what));
    }

    // ------------------------------------------------------------------ keeping the barriers

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.doors().atBlock(event.getBlock()) == null) return;
        event.setCancelled(true);
        if (event.getPlayer().hasPermission("scpaccess.admin")) plugin.messages().send(event.getPlayer(), "door.protected");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.doors().atBlock(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (plugin.doors().atBlock(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.doors().atBlock(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.doors().atBlock(b) != null);
    }

    /** Small helper: the location part of a door name, to put it on the returned item. */
    static final class NameLocation {
        static String of(String name) { return fi.alavesa.scpaccess.door.NameMatch.location(name).replaceAll("(?i)\\s+door$", ""); }
    }
}

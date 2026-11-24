package fi.alavesa.scpaccess.reader;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The screen flash of a reader / a breach: one of the pack's post effects ({@code scpaccess:granted},
 * {@code denied}, {@code breach}) on one player's client for a moment. A client without the pack ignores the id.
 */
public final class PostFx {

    private PostFx() { }

    public static void flash(Plugin plugin, Player player, String name, int ticks) {
        Key key = Key.key("scpaccess", name);
        try {
            player.postEffects().add(key);
        } catch (RuntimeException e) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                try { player.postEffects().remove(key); } catch (RuntimeException ignored) { }
            }
        }, ticks);
    }
}

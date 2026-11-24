package fi.alavesa.scpaccess.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.Configuration;

/**
 * Every player-facing string, from messages.yml, in MiniMessage format. A message set to an empty string
 * is simply not sent, which is how a server owner silences one. {@code <prefix>} works in every message.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Configuration yaml;
    private final TagResolver prefix;

    /** @param yaml messages.yml, with the bundled file as its defaults so that new keys work after an update */
    public Messages(Configuration yaml) {
        this.yaml = yaml;
        this.prefix = Placeholder.parsed("prefix", yaml.getString("prefix", ""));
    }

    /** @return the message, or null when it is missing or blanked out */
    public Component component(String key, TagResolver... placeholders) {
        String raw = yaml.getString(key, "");
        if (raw == null || raw.isBlank()) return null;
        return MINI.deserialize(raw, TagResolver.builder().resolver(prefix).resolvers(placeholders).build());
    }

    public void send(Audience to, String key, TagResolver... placeholders) {
        Component message = component(key, placeholders);
        if (message != null) to.sendMessage(message);
    }

    /** Parses a MiniMessage string that did not come from messages.yml (chair names, the pack prompt). */
    public static Component parse(String miniMessage) {
        return MINI.deserialize(miniMessage == null ? "" : miniMessage);
    }

    /** A placeholder whose value is inserted as plain text (never parsed as tags - safe for player names). */
    public static TagResolver text(String name, String value) { return Placeholder.unparsed(name, value); }

    public static TagResolver text(String name, long value) { return Placeholder.unparsed(name, String.valueOf(value)); }

    /** A placeholder that inserts a ready component (a chair's display name). */
    public static TagResolver tag(String name, Component value) { return Placeholder.component(name, value); }
}

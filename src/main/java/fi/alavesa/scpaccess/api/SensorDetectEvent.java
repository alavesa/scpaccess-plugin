package fi.alavesa.scpaccess.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A player sensor just noticed a player (called when they come into its range, not every check). */
public final class SensorDetectEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int sensorId;
    private final String location;
    private final Player player;
    private final Location sensorAt;

    public SensorDetectEvent(int sensorId, String location, Player player, Location sensorAt) {
        this.sensorId = sensorId;
        this.location = location;
        this.player = player;
        this.sensorAt = sensorAt;
    }

    public int sensorId() { return sensorId; }

    /** The sensor's name: the place it watches. */
    public String location() { return location; }

    public Player player() { return player; }

    public Location sensorLocation() { return sensorAt; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}

package fi.alavesa.scpaccess.command;

import fi.alavesa.scpaccess.ScpAccessPlugin;
import fi.alavesa.scpaccess.door.DoorManager;
import fi.alavesa.scpaccess.reader.ReaderManager;
import fi.alavesa.scpaccess.rig.Rig;
import fi.alavesa.scpaccess.sensor.SensorManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** What a player is looking at: the nearest door, reader or sensor along the view ray (open doors included). */
public final class Targeting {

    public enum Kind { DOOR, READER, SENSOR }

    public record Hit(Kind kind, int id, double distance) { }

    private Targeting() { }

    public static Hit lookingAt(ScpAccessPlugin plugin, Player player, double range) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector(), dir = eye.getDirection();
        Hit best = null;
        for (DoorManager.Door d : plugin.doors().all()) {
            if (d.type == null || d.world() != world) continue;
            best = better(best, Kind.DOOR, d.id(), d.box(), from, dir, range);
        }
        for (ReaderManager.Reader r : plugin.readers().all()) {
            if (r.world() != world || !r.loaded()) continue;
            best = better(best, Kind.READER, r.id(), box(plugin.library().reader(), r.anchor(), r.data.yaw), from, dir, range);
        }
        for (SensorManager.Sensor s : plugin.sensors().all()) {
            if (s.world() != world || !s.loaded()) continue;
            best = better(best, Kind.SENSOR, s.id(), box(plugin.library().sensor(), s.anchor(), s.data.yaw), from, dir, range);
        }
        if (best == null) return null;
        // something solid in between (a wall, but not the door's own barriers) hides it
        RayTraceResult block = world.rayTraceBlocks(eye, eye.getDirection(), best.distance(), FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitBlock() != null && block.getHitPosition().distance(from) < best.distance() - 0.15
            && plugin.doors().atBlock(block.getHitBlock()) == null) return null;
        return best;
    }

    private static Hit better(Hit best, Kind kind, int id, BoundingBox box, Vector from, Vector dir, double range) {
        if (box == null) return best;
        RayTraceResult hit = box.rayTrace(from, dir, range);
        if (hit == null) return best;
        double d = hit.getHitPosition().distance(from);
        return best == null || d < best.distance() ? new Hit(kind, id, d) : best;
    }

    private static BoundingBox box(Rig rig, Location anchor, float yaw) {
        if (rig == null) return null;
        double[] b = rig.worldBox(yaw);
        return new BoundingBox(anchor.getX() + b[0], anchor.getY() + b[1], anchor.getZ() + b[2], anchor.getX() + b[3], anchor.getY() + b[4], anchor.getZ() + b[5]).expand(0.04);
    }
}

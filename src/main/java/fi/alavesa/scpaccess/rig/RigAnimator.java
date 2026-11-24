package fi.alavesa.scpaccess.rig;

import fi.alavesa.scpaccess.model.BbModel;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Plays a clip on a rig: every {@code step} ticks the bones get the clip's pose for the NEXT step and the client
 * interpolates in between, so a 20-tick door costs a handful of packets per bone, not one per tick.
 */
public final class RigAnimator {

    static final class Playback {
        final RigInstance instance;
        final BbModel.Animation clip;
        final boolean reverse;
        final int total;
        final Runnable done;
        int elapsed;

        Playback(RigInstance instance, BbModel.Animation clip, boolean reverse, int total, Runnable done) {
            this.instance = instance;
            this.clip = clip;
            this.reverse = reverse;
            this.total = total;
            this.done = done;
        }
    }

    private static final List<Playback> ACTIVE = new ArrayList<>();
    private static BukkitTask task;
    private static int step = 2;

    private RigAnimator() { }

    public static void start(Plugin plugin, int stepTicks) {
        step = Math.max(1, stepTicks);
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, RigAnimator::tick, 1, 1);
    }

    public static void stop() {
        if (task != null) task.cancel();
        task = null;
        ACTIVE.clear();
    }

    /** Stops whatever the instance is playing without calling its completion. */
    static void cancel(RigInstance instance) {
        if (instance.playback != null) ACTIVE.remove(instance.playback);
        instance.playback = null;
    }

    /**
     * Plays {@code clipName}; {@code done} runs when it has finished (or at once for a missing clip).
     *
     * @param reverse play the clip backwards (a door closes by running "open" backwards when it has no "close")
     * @return false when the rig has no such clip - {@code done} was NOT called
     */
    public static boolean play(RigInstance instance, String clipName, boolean reverse, Runnable done) {
        BbModel.Animation clip = instance.rig().clip(clipName);
        if (clip == null) return false;
        cancel(instance);
        int total = Math.max(1, (int) Math.round(clip.length * 20));
        Playback p = new Playback(instance, clip, reverse, total, done);
        instance.playback = p;
        ACTIVE.add(p);
        advance(p, 0);
        return true;
    }

    /** The pose a clip ends in, at once - the steady state of an open or closed door. */
    public static void snapTo(RigInstance instance, String clipName, boolean end) {
        cancel(instance);
        BbModel.Animation clip = instance.rig().clip(clipName);
        Matrix4d[] pose = instance.rig().pose(clip, clip == null ? 0 : end ? clip.length : 0);
        instance.pose(pose, 0);
    }

    private static void tick() {
        Iterator<Playback> it = ACTIVE.iterator();
        List<Runnable> finished = new ArrayList<>();
        while (it.hasNext()) {
            Playback p = it.next();
            if (!p.instance.alive()) { it.remove(); p.instance.playback = null; continue; }
            p.elapsed++;
            if (p.elapsed >= p.total) {
                it.remove();
                p.instance.playback = null;
                if (p.done != null) finished.add(p.done);
            } else if (p.elapsed % step == 0) {
                advance(p, p.elapsed);
            }
        }
        for (Runnable r : finished) {
            try { r.run(); } catch (RuntimeException e) { org.bukkit.Bukkit.getLogger().warning("[ScpAccess] animation callback failed: " + e); }
        }
    }

    /** Sends the pose {@code step} ticks after {@code from}. */
    private static void advance(Playback p, int from) {
        int to = Math.min(p.total, from + step);
        double seconds = to / 20.0;
        double t = p.reverse ? p.clip.length - Math.min(p.clip.length, seconds * (p.clip.length / (p.total / 20.0))) : Math.min(p.clip.length, seconds * (p.clip.length / (p.total / 20.0)));
        p.instance.pose(p.instance.rig().pose(p.clip, t), to - from);
    }
}

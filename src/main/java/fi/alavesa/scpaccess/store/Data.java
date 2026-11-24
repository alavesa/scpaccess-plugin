package fi.alavesa.scpaccess.store;

import java.util.ArrayList;
import java.util.List;

/** Everything the plugin remembers, as saved in plugins/ScpAccess/data.json. */
public final class Data {

    /** "contained" or "breach". */
    public String mode = "contained";
    public int nextId = 1;
    public final List<DoorData> doors = new ArrayList<>();
    public final List<ReaderData> readers = new ArrayList<>();
    public final List<SensorData> sensors = new ArrayList<>();

    public static final class DoorData {
        public int id;
        public String type = "";
        /** Named after its location: "Cafeteria Door 2". */
        public String name = "";
        public String world = "";
        /** Bottom centre of the door. */
        public double x, y, z;
        /** 0, 90, 180 or -90: the way the front faces (Minecraft yaw). */
        public float yaw;
        public boolean open, locked;
    }

    public static final class ReaderData {
        public int id;
        public String world = "";
        public double x, y, z;
        public float yaw;
        /** 1..5, 6 = omni: the lowest card level that opens the linked doors. */
        public int level = 1;
        public List<Integer> doors = new ArrayList<>();
    }

    public static final class SensorData {
        public int id;
        /** The location it is named after: "Hallway B". */
        public String name = "";
        public String world = "";
        public double x, y, z;
        public float yaw;
        public double radius;
    }
}

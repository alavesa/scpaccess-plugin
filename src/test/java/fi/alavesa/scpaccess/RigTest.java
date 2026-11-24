package fi.alavesa.scpaccess;

import fi.alavesa.scpaccess.content.DefaultModels;
import fi.alavesa.scpaccess.model.BbModel;
import fi.alavesa.scpaccess.model.BbModelParser;
import fi.alavesa.scpaccess.model.ModelLog;
import fi.alavesa.scpaccess.rig.Bone;
import fi.alavesa.scpaccess.rig.Rig;
import fi.alavesa.scpaccess.rig.RigBuilder;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RigTest {

    private static Rig rig(String id, String json) throws Exception {
        ModelLog log = new ModelLog(id);
        BbModel model = BbModelParser.parse(id, json, null, log);
        Rig rig = RigBuilder.build(id, model, log);
        assertTrue(log.warnings().stream().noneMatch(w -> w.contains("SKIPPED")), log.warnings().toString());
        return rig;
    }

    private static Bone bone(Rig rig, String name) {
        return rig.bones.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void doorModelsSplitIntoBonesAndClips() throws Exception {
        Rig d = rig("door/t", DefaultModels.door(3, 3));
        assertTrue(d.hasClip("open") && d.hasClip("close"));
        assertEquals(0.8, d.length("open"), 1e-9);
        assertNotNull(bone(d, "leaf_left"));
        assertNotNull(bone(d, "leaf_right"));
        assertNotNull(bone(d, "base"));
        assertEquals("open", bone(d, "state_open").state());
        assertEquals("locked", bone(d, "state_locked").state());
        // the icon holds the rest pose: frame + leaves + only the idle (here: no) lamps
        assertNotNull(d.icon);
        assertEquals(-24, d.boundsMin[0], 1e-6);
        assertEquals(24, d.boundsMax[0], 1e-6);
        assertEquals(48, d.boundsMax[1], 1e-6);
    }

    @Test
    void leafSlidesByTheKeyframedDistance() throws Exception {
        Rig d = rig("door/t", DefaultModels.door(3, 3));
        Bone left = bone(d, "leaf_left");
        Matrix4d rest = d.pose(null, 0)[left.index()];
        assertTrue(rest.equals(new Matrix4d(), 1e-12));
        Matrix4d open = d.pose(d.clip("open"), 0.8)[left.index()];
        Vector3d t = open.getTranslation(new Vector3d());
        assertEquals(-20.5, t.x, 1e-9); // half - 3.5 = 24 - 3.5
        assertEquals(0, t.y, 1e-9);
        // halfway through the clip it is somewhere between (keyframes: 0 -> 25% at 0.3 s -> 100% at 0.8 s)
        double mid = d.pose(d.clip("open"), 0.3)[left.index()].getTranslation(new Vector3d()).x;
        assertEquals(-20.5 * 0.25, mid, 1e-9);
        // close is the same motion backwards
        assertEquals(-20.5, d.pose(d.clip("close"), 0)[left.index()].getTranslation(new Vector3d()).x, 1e-9);
        assertEquals(0, d.pose(d.clip("close"), 0.8)[left.index()].getTranslation(new Vector3d()).x, 1e-9);
    }

    @Test
    void displayPoseMovesTheRightWayForEveryYaw() throws Exception {
        Rig d = rig("door/t", DefaultModels.door(3, 3));
        Bone left = bone(d, "leaf_left");
        Matrix4d open = d.pose(d.clip("open"), 0.8)[left.index()];
        double[] c = left.geometry().fitCenter;
        // yaw 0: the model's x axis points to world -x (front = +z), so a -20.5 px slide is +20.5/16 blocks in world x
        Rig.DisplayPose rest0 = d.displayPose(left, new Matrix4d(), 0f), open0 = d.displayPose(left, open, 0f);
        assertEquals(20.5 / 16, open0.translation().x - rest0.translation().x, 1e-6);
        assertEquals(0, open0.translation().z - rest0.translation().z, 1e-6);
        // yaw 90: the width runs along z; model +x -> world +z... the same slide must be a pure z move
        Rig.DisplayPose rest90 = d.displayPose(left, new Matrix4d(), 90f), open90 = d.displayPose(left, open, 90f);
        assertEquals(0, open90.translation().x - rest90.translation().x, 1e-6);
        assertEquals(1.28125, Math.abs(open90.translation().z - rest90.translation().z), 1e-6);
        // no rotation and no scale change for a slide: scale is 1/fitScale = 1 (small model)
        assertEquals(1, open0.scale().x, 1e-6);
        assertEquals(c.length, 3);
    }

    @Test
    void restPoseMatchesTheChairMaths() throws Exception {
        // ChairMath.display: translation = Ry(180 - yaw) * fitCenter(chair space), left rotation = Ry(-yaw), scale = 1/fit
        Rig d = rig("door/t", DefaultModels.door(3, 3));
        Bone base = bone(d, "base");
        double[] c = base.geometry().fitCenter;
        Rig.DisplayPose p = d.displayPose(base, new Matrix4d(), 90f);
        double ang = Math.toRadians(180 - 90);
        double cx = c[0] / 16, cz = c[2] / 16;
        assertEquals(cx * Math.cos(ang) + cz * Math.sin(ang), p.translation().x, 1e-6);
        assertEquals(-cx * Math.sin(ang) + cz * Math.cos(ang), p.translation().z, 1e-6);
        org.joml.Quaternionf expected = new org.joml.Quaternionf().rotationY((float) Math.toRadians(-90));
        assertTrue(Math.abs(Math.abs(p.left().dot(expected)) - 1) < 1e-5);
    }

    @Test
    void readerHasCardStateAndSwipeBones() throws Exception {
        Rig r = rig("reader/reader", DefaultModels.reader());
        Bone card = bone(r, "card");
        assertTrue(card.card());
        assertNull(card.geometry());
        assertEquals("granted", bone(r, "state_granted").state());
        assertTrue(r.hasClip("swipe") && r.hasClip("denied"));
        assertNotNull(r.locators.get("screen"));
        // the card travels from above the reader to the slot and across it
        double y0 = r.pose(r.clip("swipe"), 0)[card.index()].getTranslation(new Vector3d()).y;
        double y1 = r.pose(r.clip("swipe"), 0.25)[card.index()].getTranslation(new Vector3d()).y;
        assertEquals(14, y0, 1e-9);
        assertEquals(-1, y1, 1e-9);
        // the icon leaves out the card marker and the granted/denied lamps
        assertTrue(r.icon.cubes.stream().noneMatch(c -> c.name.equals("card_marker") || c.name.equals("lamp_1") || c.name.equals("lamp_2")));
    }

    @Test
    void sensorHeadRotatesAFullTurn() throws Exception {
        Rig s = rig("sensor/sensor", DefaultModels.sensor());
        Bone head = bone(s, "head");
        Matrix4d half = s.pose(s.clip("detect"), 0.5)[head.index()];
        Vector3d a = half.transformDirection(new Vector3d(0, 0, -1));
        assertEquals(1, a.z, 1e-9); // 180 degrees about y: front becomes back
        Matrix4d full = s.pose(s.clip("detect"), 1.0)[head.index()];
        assertEquals(-1, full.transformDirection(new Vector3d(0, 0, -1)).z, 1e-9);
    }

    @Test
    void cardsAndToolHaveNoBonesToAnimateButIcons() throws Exception {
        for (int level = 1; level <= 6; level++) {
            Rig c = rig("card/l" + level, DefaultModels.card(level));
            assertNotNull(c.icon);
            assertEquals(1, c.bones.size());
        }
        assertNotNull(rig("tool/link", DefaultModels.linkTool()).icon);
    }

    @Test
    void legacyKeyframesAreMirroredLikeBlockbench4() throws Exception {
        String json = DefaultModels.door(3, 3).replace("\"format_version\": \"5.0\"", "\"format_version\": \"4.10\"");
        Rig d = rig("door/legacy", json);
        Bone left = bone(d, "leaf_left");
        // Blockbench 4.x stores position x mirrored: the same keyframes now slide the other way
        assertEquals(20.5, d.pose(d.clip("open"), 0.8)[left.index()].getTranslation(new Vector3d()).x, 1e-9);
    }
}

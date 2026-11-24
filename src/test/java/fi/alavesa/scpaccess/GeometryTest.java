package fi.alavesa.scpaccess;

import fi.alavesa.scpaccess.door.DoorGeometry;
import fi.alavesa.scpaccess.door.NameMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeometryTest {

    @Test
    void yawSnapsToQuarterTurns() {
        assertEquals(0f, DoorGeometry.snap(10));
        assertEquals(90f, DoorGeometry.snap(80));
        assertEquals(180f, DoorGeometry.snap(-170));
        assertEquals(-90f, DoorGeometry.snap(-80));
        assertEquals(-90f, DoorGeometry.snap(260));
    }

    @Test
    void threeWideDoorCentresOnTheBlock() {
        double[] a = DoorGeometry.anchor(10, 64, 20, 3, 0f, 10.9, 20.0);
        assertArrayEquals(new double[] {10.5, 64, 20.5}, a, 1e-9);
        List<int[]> cells = DoorGeometry.cells(3, 3, a[0], a[1], a[2], 0f);
        assertEquals(9, cells.size());
        assertEquals(List.of(9, 10, 11), cells.stream().map(c -> c[0]).distinct().sorted().toList());
        assertEquals(List.of(64, 65, 66), cells.stream().map(c -> c[1]).distinct().sorted().toList());
        assertEquals(List.of(20), cells.stream().map(c -> c[2]).distinct().toList());
    }

    @Test
    void sixWideDoorSitsOnABlockEdgeAndSpansSixBlocks() {
        double[] a = DoorGeometry.anchor(10, 64, 20, 6, 0f, 10.8, 20.3);
        assertEquals(11.0, a[0], 1e-9); // the nearer edge of block 10
        List<int[]> cells = DoorGeometry.cells(6, 3, a[0], a[1], a[2], 0f);
        assertEquals(18, cells.size());
        assertEquals(List.of(8, 9, 10, 11, 12, 13), cells.stream().map(c -> c[0]).distinct().sorted().toList());
        double[] b = DoorGeometry.anchor(10, 64, 20, 6, 0f, 10.2, 20.3);
        assertEquals(10.0, b[0], 1e-9);
    }

    @Test
    void widthRunsAlongZWhenTheDoorFacesEastOrWest() {
        double[] a = DoorGeometry.anchor(5, 70, 7, 3, 90f, 5.5, 7.5);
        assertArrayEquals(new double[] {5.5, 70, 7.5}, a, 1e-9);
        List<int[]> cells = DoorGeometry.cells(3, 2, a[0], a[1], a[2], 90f);
        assertEquals(6, cells.size());
        assertEquals(List.of(6, 7, 8), cells.stream().map(c -> c[2]).distinct().sorted().toList());
        assertEquals(List.of(5), cells.stream().map(c -> c[0]).distinct().toList());
        assertTrue(DoorGeometry.widthAlongX(180f));
        assertFalse(DoorGeometry.widthAlongX(-90f));
    }

    @Test
    void oneByTwoDoorIsOneBlockWide() {
        List<int[]> cells = DoorGeometry.cells(1, 2, 3.5, 40, 3.5, 0f);
        assertEquals(2, cells.size());
    }

    @Test
    void namesMatchByPhraseAndByWords() {
        assertTrue(NameMatch.matches("Cafeteria Door 2", "cafeteria"));
        assertTrue(NameMatch.matches("Cafeteria Door 2", "the door in the cafeteria"));
        assertTrue(NameMatch.matches("Hallway B Door 1", "hallway b"));
        assertTrue(NameMatch.matches("Server Room Door 3", "server room door"));
        assertEquals(List.of("Hallway B Door 1"), NameMatch.refine(List.of("Hallway A Door 1", "Hallway B Door 1"), n -> n, "hallway b"));
        assertEquals(List.of(), NameMatch.refine(List.of("Hallway B Door 1"), n -> n, "hallway c"));
        assertEquals(List.of("Hallway B Door 1"), NameMatch.refine(List.of("Hallway B Door 1"), n -> n, "open a door in hallway"));
        assertEquals(List.of(), NameMatch.refine(List.of("Hallway B Door 1"), n -> n, "hallway a"));
        assertFalse(NameMatch.matches("Cafeteria Door 2", ""));
        assertFalse(NameMatch.matches("Cafeteria Door 2", "open"));
        assertEquals("Cafeteria Door", NameMatch.location("Cafeteria Door 12"));
    }
}

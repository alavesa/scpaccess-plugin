package fi.alavesa.scpaccess.content;

import fi.alavesa.scpaccess.util.Png;

/** Procedural textures for the default models (no image files in the jar, deterministic bytes). */
final class DefaultTextures {

    private DefaultTextures() { }

    private static final int[] LEVEL_COLORS = {0x9FA8B3, 0x4CAF50, 0x2196F3, 0xFF9800, 0xE53935, 0x1A1A1A};

    static int levelColor(int level) { return LEVEL_COLORS[Math.max(0, Math.min(5, level - 1))]; }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static int shade(int rgb, int d) {
        return 0xFF000000 | clamp((rgb >> 16 & 255) + d) << 16 | clamp((rgb >> 8 & 255) + d) << 8 | clamp((rgb & 255) + d);
    }

    private static int noise(int x, int y, int seed) {
        long h = x * 73856093L ^ y * 19349663L ^ seed * 83492791L;
        h = (h ^ (h >>> 13)) * 0x5DEECE66DL;
        return (int) ((h >>> 20) & 0xF) - 8;
    }

    /** Brushed steel door leaf, 64x64: panel seams, rivets and a hazard stripe along the bottom. */
    static byte[] metal() {
        int[] px = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int base = shade(0x5C636B, noise(x / 1, y / 8, 1));
                boolean seam = x % 32 == 0 || y % 32 == 0 || x % 32 == 31 && false;
                if (seam) base = shade(0x5C636B, -34);
                boolean rivet = (x % 32 == 3 || x % 32 == 28) && (y % 32 == 3 || y % 32 == 28);
                if (rivet) base = shade(0x5C636B, 28);
                if (y >= 56) base = ((x + y) / 4) % 2 == 0 ? 0xFFE0B000 : 0xFF1C1C1C;
                px[y * 64 + x] = base;
            }
        }
        return Png.encode(64, 64, px);
    }

    /** Dark door frame, 32x32, with a yellow warning band. */
    static byte[] frame() {
        int[] px = new int[32 * 32];
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                int c = shade(0x2D3135, noise(x, y, 2));
                if (y < 4 && ((x + y) / 4) % 2 == 0) c = 0xFFE0B000;
                if (x == 0 || y == 31) c = shade(0x2D3135, -14);
                px[y * 32 + x] = c;
            }
        return Png.encode(32, 32, px);
    }

    /** 16x16 with three 5x5 lamps: amber [0,0], green [5,0], red [10,0], bright centres. */
    static byte[] lamps() {
        int[] px = new int[16 * 16];
        int[] colors = {0xFFB000, 0x2EE05A, 0xFF3B30};
        for (int i = 0; i < 3; i++)
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 5; x++) {
                    boolean core = x >= 1 && x <= 3 && y >= 1 && y <= 3;
                    px[y * 16 + i * 5 + x] = core ? 0xFF000000 | colors[i] : shade(colors[i], -70);
                }
        return Png.encode(16, 16, px);
    }

    /**
     * Reader atlas 32x32: body (0,0,16,16), glass (16,0,32,16), black slot (0,16,16,32), then 8x8 swatches:
     * blue (16,16), green (24,16), red (16,24), white (24,24).
     */
    static byte[] readerAtlas() {
        int[] px = new int[32 * 32];
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                int c;
                if (x < 16 && y < 16) c = shade(0x3A3F45, noise(x, y, 3));
                else if (y < 16) c = shade(0x0E2A2F, (y % 2 == 0 ? 6 : -4) + noise(x, y, 4) / 2);
                else if (x < 16) c = 0xFF0A0A0C;
                else if (y < 24) c = x < 24 ? 0xFF3AA0FF : 0xFF2EE05A;
                else c = x < 24 ? 0xFFFF3B30 : 0xFFF2F2F2;
                px[y * 32 + x] = c;
            }
        return Png.encode(32, 32, px);
    }

    /** Sensor atlas 32x32: body (0,0,16,16), lens (16,0,32,16), green swatch (0,16,8,24), red (8,16,16,24). */
    static byte[] sensorAtlas() {
        int[] px = new int[32 * 32];
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                int c;
                if (x < 16 && y < 16) c = shade(0xC9CDD1, noise(x, y, 5));
                else if (y < 16) { int d = (int) Math.hypot(x - 24, y - 8); c = shade(0x111B24, Math.max(-20, 26 - d * 6)); }
                else if (y < 24 && x < 8) c = 0xFF2EE05A;
                else if (y < 24 && x < 16) c = 0xFFFF3B30;
                else c = 0x00000000;
                px[y * 32 + x] = c;
            }
        return Png.encode(32, 32, px);
    }

    /** Link tool 16x16: a dark tablet with a blue "link" glyph. */
    static byte[] tool() {
        int[] px = new int[16 * 16];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int c = shade(0x24282D, noise(x, y, 6));
                boolean glyph = (y == 7 && x >= 4 && x <= 11) || (x == 4 && y >= 5 && y <= 9) || (x == 11 && y >= 5 && y <= 9);
                if (glyph) c = 0xFF3AA0FF;
                px[y * 16 + x] = c;
            }
        return Png.encode(16, 16, px);
    }

    /**
     * A keycard, 32x32: front in (0,0,32,20) - level colour, darker border, gold chip, level pips (an omni card
     * gets a rainbow bar), magnetic stripe; back in (0,20,..) is just the stripe side.
     */
    static byte[] card(int level) {
        int[] px = new int[32 * 32];
        int color = levelColor(level);
        for (int y = 0; y < 20; y++)
            for (int x = 0; x < 32; x++) {
                int c = shade(color, noise(x, y, 7) / 2);
                if (x == 0 || x == 31 || y == 0 || y == 19) c = shade(color, -45);
                if (x >= 3 && x <= 8 && y >= 5 && y <= 10) c = (x + y) % 3 == 0 ? 0xFFB88A1E : 0xFFE8C04A; // chip
                if (y >= 14 && y <= 16 && x >= 2 && x <= 29) c = 0xFF161616;                                   // stripe
                if (level == 6) {
                    if (y >= 4 && y <= 6 && x >= 13 && x <= 28) c = rainbow(x - 13);
                } else if (y >= 4 && y <= 7) {
                    for (int pip = 0; pip < level; pip++) if (x >= 13 + pip * 3 && x <= 14 + pip * 3) c = level == 1 ? 0xFF303840 : 0xFFF4F4F4;
                }
                px[y * 32 + x] = c;
            }
        for (int y = 20; y < 32; y++) for (int x = 0; x < 32; x++) px[y * 32 + x] = shade(color, -30 + noise(x, y, 8));
        return Png.encode(32, 32, px);
    }

    private static int rainbow(int i) {
        int[] stops = {0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0};
        return stops[Math.min(5, i * 6 / 16)];
    }
}

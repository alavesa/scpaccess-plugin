package fi.alavesa.scpaccess.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The two PNG operations the plugin needs, without java.awt / ImageIO (a headless server JRE may not ship
 * the desktop module): reading the size from the header and writing a small RGBA image.
 */
public final class Png {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private Png() { }

    public static boolean isPng(byte[] data) {
        if (data == null || data.length < 24) return false;
        for (int i = 0; i < SIGNATURE.length; i++) if (data[i] != SIGNATURE[i]) return false;
        return true;
    }

    /** @return {@code [width, height]} from the IHDR chunk, or null if this is not a PNG. */
    public static int[] size(byte[] data) {
        if (!isPng(data)) return null;
        ByteBuffer b = ByteBuffer.wrap(data);
        int w = b.getInt(16), h = b.getInt(20);
        return w > 0 && h > 0 ? new int[] {w, h} : null;
    }

    /**
     * Encodes 8-bit RGBA pixels as a PNG.
     *
     * @param argb row-major pixels, {@code 0xAARRGGBB}
     */
    public static byte[] encode(int width, int height, int[] argb) {
        byte[] raw = new byte[height * (1 + width * 4)];
        int o = 0;
        for (int y = 0; y < height; y++) {
            raw[o++] = 0; // filter type "none" for every scanline
            for (int x = 0; x < width; x++) {
                int p = argb[y * width + x];
                raw[o++] = (byte) (p >> 16);
                raw[o++] = (byte) (p >> 8);
                raw[o++] = (byte) p;
                raw[o++] = (byte) (p >>> 24);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf));
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}

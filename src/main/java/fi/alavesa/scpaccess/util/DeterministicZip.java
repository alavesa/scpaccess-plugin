package fi.alavesa.scpaccess.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.SortedMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip whose bytes depend on the content only: entries in sorted order, one fixed timestamp (set as
 * local date-time fields, so the server's time zone does not leak in), no extra fields. The same files
 * therefore always give the same SHA-1, and clients re-download the pack only when it really changed.
 * <p>
 * Entries are STORED (uncompressed) by default: deflate output can differ between zlib builds, which would
 * change the hash when the server moves to another machine or JDK. Chair packs are a few kilobytes of JSON
 * plus already-compressed PNGs, so there is little to gain from compression anyway.
 */
public final class DeterministicZip {

    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

    private DeterministicZip() { }

    public static byte[] write(SortedMap<String, byte[]> files, boolean compress) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                byte[] data = file.getValue();
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(TIMESTAMP);
                if (compress) {
                    entry.setMethod(ZipEntry.DEFLATED);
                } else {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // cannot happen with an in-memory stream
        }
        return bytes.toByteArray();
    }
}

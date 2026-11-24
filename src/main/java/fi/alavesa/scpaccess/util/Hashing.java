package fi.alavesa.scpaccess.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Hex digests. SHA-1 is what the client verifies a server resource pack with. */
public final class Hashing {

    private Hashing() { }

    public static String sha1Hex(byte[] data) { return hex("SHA-1", data); }

    public static String sha256Hex(byte[] data) { return hex("SHA-256", data); }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE ships SHA-256
        }
    }

    private static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

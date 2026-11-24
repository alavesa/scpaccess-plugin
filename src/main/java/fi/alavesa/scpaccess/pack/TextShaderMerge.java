package fi.alavesa.scpaccess.pack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds the pack's {@code minecraft:core/text} shader - the reader screens' scan lines and colours.
 * <p>
 * Minecraft runs only ONE copy of a core shader: the one in the topmost pack. Other plugins' packs (SCP079's
 * captions, Intercom, IdCards, Radio, SuperbVehicles, CCTV) replace the same two files, so this builds on the
 * first of their zips that exists and adds the two ScpAccess hooks, copying their include files along. With none of
 * them installed the base is the vanilla 26.3 shader bundled in the jar. A hook is inserted after a line of the
 * vanilla shader; when that line is missing NO shader is shipped, because a core shader that does not compile
 * makes the client drop every server pack.
 */
public final class TextShaderMerge {

    /**
     * @param files       path -> bytes to put into the pack; empty when no shader is shipped
     * @param description how it came about, for the log and /scp079 status
     */
    public record Result(Map<String, byte[]> files, String description) {
        public boolean shipped() { return !files.isEmpty(); }
    }

    private static final String CORE = "assets/minecraft/shaders/core/text.";
    private static final String V_INCLUDE_AFTER = "#include <minecraft:projection.glsl>\n";
    /** The last line of vanilla's main(): the hook replaces gl_Position and must not be overwritten afterwards. */
    private static final String V_CALL_AFTER = "texCoord0 = UV0;\n";
    private static final String F_INCLUDE_AFTER = "#include <minecraft:oit.glsl>\n";
    private static final String F_CALL_AFTER = "vec4 color = texColor * vertexColor * ColorModulator;\n";

    private TextShaderMerge() { }

    private record Source(String name, String vertex, String fragment, Map<String, byte[]> includes) { }

    /**
     * @param vanillaVertex   minecraft:core/text.vsh of 26.3, verbatim
     * @param vanillaFragment minecraft:core/text.fsh of 26.3, verbatim
     * @param candidates      other plugins' pack zips, most complete first; missing files are skipped
     */
    public static Result build(String vanillaVertex, String vanillaFragment, List<Map.Entry<String, File>> candidates, Consumer<String> warn) {
        Source base = new Source("vanilla", vanillaVertex.replace("\r\n", "\n"), vanillaFragment.replace("\r\n", "\n"), Map.of());
        for (Map.Entry<String, File> candidate : candidates) {
            Source source = read(candidate.getKey(), candidate.getValue(), warn);
            if (source != null) { base = source; break; }
        }
        try {
            Map<String, byte[]> files = new LinkedHashMap<>(base.includes);
            String vertex = base.vertex, fragment = base.fragment;
            if (!(vertex.contains("scpaccess_text_vertex") && fragment.contains("scpaccess_text_fragment"))) {
                vertex = insertAfter(vertex, V_INCLUDE_AFTER, "// ===== SCPACCESS BEGIN =====\n#include <scpaccess:scpaccess_text_vertex.glsl>\n// ===== SCPACCESS END =====\n");
                vertex = insertAfter(vertex, V_CALL_AFTER, "    // ===== SCPACCESS BEGIN =====\n    vertexColor = scpaccess_text_vertex(Color, vertexColor);\n    // ===== SCPACCESS END =====\n");
                fragment = insertAfter(fragment, F_INCLUDE_AFTER, "// ===== SCPACCESS BEGIN =====\n#include <scpaccess:scpaccess_text_fragment.glsl>\n// ===== SCPACCESS END =====\n");
                fragment = insertAfter(fragment, F_CALL_AFTER, "    // ===== SCPACCESS BEGIN =====\n    color = scpaccess_text_fragment(color);\n    // ===== SCPACCESS END =====\n");
            }
            files.put(CORE + "vsh", vertex.getBytes(StandardCharsets.UTF_8));
            files.put(CORE + "fsh", fragment.getBytes(StandardCharsets.UTF_8));
            return new Result(files, base.name.equals("vanilla") ? "vanilla + ScpAccess" : "merged into " + base.name + "'s");
        } catch (IllegalStateException e) {
            warn.accept("The reader screen text shader is not shipped: the text shader of " + base.name + " cannot be merged (" + e.getMessage()
                + "). The screen shows plain text instead. Update ScpAccess, or set pack.text-shader: false to silence this.");
            return new Result(Map.of(), "none (merge with " + base.name + " refused)");
        }
    }

    private static String insertAfter(String s, String anchor, String add) {
        int i = s.indexOf(anchor);
        if (i < 0) throw new IllegalStateException("line not found: " + anchor.trim());
        if (s.indexOf(anchor, i + 1) >= 0) throw new IllegalStateException("line found twice: " + anchor.trim());
        return s.substring(0, i + anchor.length()) + add + s.substring(i + anchor.length());
    }

    /**
     * The 26.3 text shader and the non-vanilla shader includes of a pack zip; null when the zip is missing or
     * has no 26.3 text shader. An overlay directory (CCTV's cctv_26_3/) wins over the pack root.
     */
    private static Source read(String name, File zip, Consumer<String> warn) {
        if (!zip.isFile()) return null;
        try (ZipFile z = new ZipFile(zip)) {
            String vertex = null, fragment = null;
            Map<String, byte[]> root = new LinkedHashMap<>(), overlay = new LinkedHashMap<>();
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.isDirectory()) continue;
                String path = e.getName();
                int assets = path.indexOf("assets/");
                if (assets < 0 || (assets > 0 && path.lastIndexOf('/', assets - 2) >= 0)) continue; // root or one overlay folder only
                boolean inOverlay = assets > 0;
                String rel = path.substring(assets);
                if (rel.equals(CORE + "vsh") || rel.equals(CORE + "fsh")) {
                    String text = text(z, e);
                    if (!text.contains("#include <minecraft:")) continue; // 26.2 syntax: not usable on 26.3
                    boolean isVertex = rel.endsWith("vsh");
                    if (isVertex && (vertex == null || inOverlay)) vertex = text;
                    if (!isVertex && (fragment == null || inOverlay)) fragment = text;
                } else if (rel.matches("assets/[a-z0-9_.-]+/shaders/include/.+") && !rel.startsWith("assets/minecraft/") && !rel.startsWith("assets/scpaccess/")) {
                    try (InputStream in = z.getInputStream(e)) {
                        (inOverlay ? overlay : root).put(rel, in.readAllBytes());
                    }
                }
            }
            if (vertex == null || fragment == null) return null;
            root.putAll(overlay);
            return new Source(name, vertex, fragment, root);
        } catch (IOException e) {
            warn.accept("Could not read " + zip + " (" + e.getMessage() + "); " + name + "'s text shader is not merged");
            return null;
        }
    }

    private static String text(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}

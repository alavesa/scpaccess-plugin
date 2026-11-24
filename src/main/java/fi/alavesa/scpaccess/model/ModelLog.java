package fi.alavesa.scpaccess.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects the warnings of one model's trip through the pipeline, each already prefixed with the file name
 * so a server owner can find the culprit. The pipeline never logs directly: it runs off the main thread and
 * in unit tests, and the caller decides where the lines go.
 */
public final class ModelLog {

    private final String file;
    private final List<String> warnings = new ArrayList<>();

    public ModelLog(String file) { this.file = file; }

    /**
     * @param subject what the problem is about, e.g. {@code cube 'leg_1'} or {@code texture 'oak'}; may be empty
     * @param reason  what is wrong and what happens instead
     */
    public void warn(String subject, String reason) {
        warnings.add("[" + file + "] " + (subject == null || subject.isEmpty() ? "" : subject + ": ") + reason);
    }

    public String file() { return file; }

    public List<String> warnings() { return Collections.unmodifiableList(warnings); }
}

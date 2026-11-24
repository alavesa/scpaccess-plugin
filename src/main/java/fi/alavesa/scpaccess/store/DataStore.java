package fi.alavesa.scpaccess.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/** data.json, written atomically (temp file + move). A corrupt file is kept as data.json.broken, never overwritten. */
public final class DataStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger log;

    public DataStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public Data load() {
        if (!Files.isRegularFile(file)) return new Data();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            return data == null ? new Data() : data;
        } catch (IOException | RuntimeException e) {
            log.severe("data.json cannot be read (" + e.getMessage() + ") - it is kept as data.json.broken and the plugin starts empty");
            try { Files.move(file, file.resolveSibling("data.json.broken"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            return new Data();
        }
    }

    public void save(Data data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("data.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { GSON.toJson(data, writer); }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("data.json could not be saved: " + e.getMessage());
        }
    }
}

package dev.willtda.simpleschematics.schematic;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.util.DataPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything in the schematics folder, indexed but not loaded until needed.
 *
 * <p>Both {@code .sschem} and {@code .litematic} files sit side by side. Drop a
 * litematic in the folder and it simply appears, no import step required.</p>
 */
public final class SchematicLibrary {

    public static final SchematicLibrary INSTANCE = new SchematicLibrary();

    /** One row in the library, cheap to create because the blocks stay on disk. */
    public static final class Entry {
        public final Path file;
        public final String displayName;
        public final boolean litematic;
        public final long fileSize;
        public final long lastModified;
        private Schematic loaded;
        private String loadError;

        Entry(Path file, boolean litematic, long fileSize, long lastModified) {
            this.file = file;
            this.litematic = litematic;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            String raw = file.getFileName().toString();
            int dot = raw.lastIndexOf('.');
            this.displayName = dot > 0 ? raw.substring(0, dot) : raw;
        }

        public boolean isLoaded() {
            return loaded != null;
        }

        /**
         * Identifies this version of the file. Anything remembered against a
         * schematic, such as the layer you were on, is checked against it so
         * a file replaced while you were away does not carry stale state.
         */
        public String stamp() {
            return fileSize + ":" + lastModified;
        }

        public String loadError() {
            return loadError;
        }

        /** Loads on first use. Returns null and records a message if it fails. */
        public Schematic get() {
            if (loaded == null && loadError == null) {
                try {
                    loaded = litematic ? LitematicImporter.read(file) : SchematicIO.read(file);
                } catch (Exception e) {
                    loadError = e.getMessage() == null ? e.toString() : e.getMessage();
                    SimpleSchematics.LOG.error("Could not load {}", file, e);
                }
            }
            return loaded;
        }

        public void unload() {
            loaded = null;
            loadError = null;
        }

        public String key() {
            return file.getFileName().toString();
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byKey = new HashMap<>();
    private boolean scanned;

    private SchematicLibrary() {
    }

    public List<Entry> entries() {
        if (!scanned) {
            refresh();
        }
        return entries;
    }

    public Entry byKey(String key) {
        if (!scanned) {
            refresh();
        }
        return byKey.get(key);
    }

    /** Rebuilds the index from disk. Cheap enough to call whenever a screen opens. */
    public void refresh() {
        entries.clear();
        byKey.clear();
        scanned = true;

        Path dir = DataPaths.schematics();
        try (Stream<Path> stream = Files.walk(dir, 4)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean litematic = name.endsWith(LitematicImporter.EXTENSION);
                boolean own = name.endsWith(SchematicIO.EXTENSION);
                if (!litematic && !own) {
                    return;
                }
                try {
                    Entry entry = new Entry(path, litematic, Files.size(path),
                            Files.getLastModifiedTime(path).toMillis());
                    entries.add(entry);
                    byKey.put(entry.key(), entry);
                } catch (IOException e) {
                    SimpleSchematics.LOG.warn("Skipping {}", path, e);
                }
            });
        } catch (IOException e) {
            SimpleSchematics.LOG.error("Could not read the schematics folder", e);
        }

        entries.sort(Comparator.comparingLong((Entry e) -> e.lastModified).reversed());
    }

    /**
     * Saves a schematic, adding a numeric suffix rather than quietly overwriting
     * something you already had.
     */
    public Entry save(Schematic schematic, String requestedName) throws IOException {
        String base = DataPaths.sanitise(requestedName);
        Path dir = DataPaths.schematics();
        Path target = dir.resolve(base + SchematicIO.EXTENSION);
        int suffix = 2;
        while (Files.exists(target)) {
            target = dir.resolve(base + " (" + suffix + ")" + SchematicIO.EXTENSION);
            suffix++;
        }
        schematic.meta().modified = System.currentTimeMillis();
        SchematicIO.write(schematic, target);
        refresh();
        return byKey.get(target.getFileName().toString());
    }

    public boolean delete(Entry entry) {
        try {
            Files.deleteIfExists(entry.file);
            refresh();
            return true;
        } catch (IOException e) {
            SimpleSchematics.LOG.error("Could not delete {}", entry.file, e);
            return false;
        }
    }

    /** Converts a litematic entry into the native format so it loads faster next time. */
    public Entry convertToNative(Entry entry) throws IOException {
        Schematic schematic = entry.get();
        if (schematic == null) {
            throw new IOException(entry.loadError() == null ? "The file could not be read" : entry.loadError());
        }
        return save(schematic, schematic.meta().name);
    }
}

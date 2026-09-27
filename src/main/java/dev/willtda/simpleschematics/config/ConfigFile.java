package dev.willtda.simpleschematics.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The settings file, read and written by the mod itself.
 *
 * <p>Forge's config spec did this before, but NeoForge has its own and Fabric
 * has none, so one file format the mod owns is one less thing that differs
 * between loaders. It reads and writes the same TOML Forge wrote, in the same
 * place with the same sections, keys and comments, so a settings file from an
 * earlier version loads exactly as it was.</p>
 *
 * <p>Only the part of TOML that file ever held is understood: sections, and
 * keys holding a boolean, a number or a string. A value that is missing,
 * mistyped or out of range falls back to its default, and the file is written
 * back corrected, as Forge did. Keys the mod no longer defines are dropped on
 * the next save.</p>
 *
 * <p>No game types in here, so it can be checked on its own and carried to any
 * loader unchanged.</p>
 */
public final class ConfigFile {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigFile.class);

    private final List<Section> sections;
    private final List<Value<?>> values;
    private Path file;
    private long seenModified = Long.MIN_VALUE;
    private long seenSize = -1;

    private ConfigFile(List<Section> sections) {
        this.sections = sections;
        List<Value<?>> all = new ArrayList<>();
        for (Section section : sections) {
            all.addAll(section.values);
        }
        this.values = Collections.unmodifiableList(all);
    }

    public List<Value<?>> values() {
        return values;
    }

    /**
     * Reads the file, falling back to defaults for anything missing or wrong,
     * and writes it back if it had to correct anything or did not exist yet.
     */
    public void load(Path path) {
        this.file = path;
        boolean corrected = read();
        if (corrected) {
            save();
        }
    }

    /**
     * Picks up an edit made to the file while the game is running. Cheap enough
     * to call once a second: it only reads the file when its size or modified
     * time has changed since the mod last read or wrote it.
     *
     * @return whether the values were read again
     */
    public boolean reloadIfChanged() {
        if (file == null) {
            return false;
        }
        try {
            if (!Files.exists(file)) {
                return false;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            if (modified == seenModified && size == seenSize) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        if (read()) {
            save();
        }
        return true;
    }

    /** Writes every value, with its comments, over the file. */
    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, write(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            remember();
        } catch (IOException e) {
            LOG.warn("Could not save {}", file, e);
        }
    }

    /** @return whether anything had to be corrected */
    private boolean read() {
        Map<String, String> raw;
        boolean existed = Files.exists(file);
        try {
            raw = existed ? parse(Files.readString(file, StandardCharsets.UTF_8)) : Map.of();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}, using defaults", file, e);
            raw = Map.of();
            existed = false;
        }
        boolean corrected = !existed;
        for (Value<?> value : values) {
            String text = raw.get(value.path());
            if (text == null || !value.accept(text)) {
                value.reset();
                corrected = true;
            }
        }
        remember();
        return corrected;
    }

    private void remember() {
        try {
            seenModified = Files.getLastModifiedTime(file).toMillis();
            seenSize = Files.size(file);
        } catch (IOException e) {
            seenModified = Long.MIN_VALUE;
            seenSize = -1;
        }
    }

    /** The file as Forge laid it out, so a hand edit or a diff looks the same as before. */
    String write() {
        StringBuilder out = new StringBuilder();
        for (Section section : sections) {
            out.append('\n');
            for (String line : section.comments) {
                out.append('#').append(line).append('\n');
            }
            out.append('[').append(section.name).append("]\n");
            for (Value<?> value : section.values) {
                for (String line : value.comments) {
                    out.append("\t#").append(line).append('\n');
                }
                String hint = value.hint();
                if (hint != null) {
                    out.append("\t#").append(hint).append('\n');
                }
                out.append('\t').append(value.key).append(" = ").append(value.text()).append('\n');
            }
        }
        out.append('\n');
        return out.toString();
    }

    // ---- reading --------------------------------------------------------------

    /** Section-qualified keys to their raw value text, strings still quoted. */
    static Map<String, String> parse(String text) {
        Map<String, String> result = new HashMap<>();
        String section = "";
        String[] lines = text.replace("﻿", "").split("\r?\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("[")) {
                int end = trimmed.indexOf(']');
                if (end > 0) {
                    section = unquoteKey(trimmed.substring(1, end).trim());
                }
                continue;
            }
            int equals = keyEnd(trimmed);
            if (equals < 0) {
                continue;
            }
            String key = unquoteKey(trimmed.substring(0, equals).trim());
            String value = valueText(trimmed.substring(equals + 1).trim());
            if (!key.isEmpty() && value != null) {
                result.put(section.isEmpty() ? key : section + "." + key, value);
            }
        }
        return result;
    }

    /** Where the key ends: the first '=' outside quotes. */
    private static int keyEnd(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    private static String unquoteKey(String key) {
        if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'"))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /** The value itself, without a trailing comment. Strings keep their quotes. */
    private static String valueText(String rest) {
        if (rest.isEmpty()) {
            return null;
        }
        char first = rest.charAt(0);
        if (first == '"' || first == '\'') {
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (first == '"' && c == '\\') {
                    i++;
                } else if (c == first) {
                    return rest.substring(0, i + 1);
                }
            }
            return null;
        }
        int hash = rest.indexOf('#');
        return (hash < 0 ? rest : rest.substring(0, hash)).trim();
    }

    /** A basic or literal TOML string to its content, or null if it is not one. */
    static String unquote(String text) {
        if (text.length() < 2) {
            return null;
        }
        char quote = text.charAt(0);
        if ((quote != '"' && quote != '\'') || text.charAt(text.length() - 1) != quote) {
            return null;
        }
        String body = text.substring(1, text.length() - 1);
        if (quote == '\'') {
            return body;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                out.append(c);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'u' -> {
                    if (i + 4 >= body.length()) return null;
                    out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                // A Windows path typed by hand, "C:\Users\...", is kept as written
                // rather than thrown away for not being valid TOML.
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    // ---- values ---------------------------------------------------------------

    /** One setting. Read with {@link #get()}, changed with {@link #set}, written with {@link ConfigFile#save()}. */
    public abstract static class Value<T> {
        final String section;
        final String key;
        final List<String> comments;
        final T defaultValue;
        private volatile T value;

        Value(String section, String key, List<String> comments, T defaultValue) {
            this.section = section;
            this.key = key;
            this.comments = comments;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }

        public T getDefault() {
            return defaultValue;
        }

        public String path() {
            return section.isEmpty() ? key : section + "." + key;
        }

        void reset() {
            value = defaultValue;
        }

        /** Takes the value from its text in the file, or refuses it. */
        abstract boolean accept(String text);

        abstract String text();

        /** The extra comment Forge wrote under the description, a range or the allowed values. */
        String hint() {
            return null;
        }
    }

    public static final class BooleanValue extends Value<Boolean> {
        BooleanValue(String section, String key, List<String> comments, boolean defaultValue) {
            super(section, key, comments, defaultValue);
        }

        @Override
        boolean accept(String text) {
            if (!text.equals("true") && !text.equals("false")) return false;
            set(text.equals("true"));
            return true;
        }

        @Override
        String text() {
            return String.valueOf(get());
        }
    }

    public static final class StringValue extends Value<String> {
        StringValue(String section, String key, List<String> comments, String defaultValue) {
            super(section, key, comments, defaultValue);
        }

        @Override
        boolean accept(String text) {
            String content = unquote(text);
            if (content == null) return false;
            set(content);
            return true;
        }

        @Override
        String text() {
            return quote(get() == null ? "" : get());
        }
    }

    public static final class IntValue extends Value<Integer> {
        private final int min;
        private final int max;

        IntValue(String section, String key, List<String> comments, int defaultValue, int min, int max) {
            super(section, key, comments, defaultValue);
            this.min = min;
            this.max = max;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        boolean accept(String text) {
            try {
                long parsed = Long.parseLong(text.replace("_", ""));
                if (parsed < min || parsed > max) return false;
                set((int) parsed);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        String text() {
            return String.valueOf(get());
        }

        @Override
        String hint() {
            return "Range: " + min + " ~ " + max;
        }
    }

    public static final class DoubleValue extends Value<Double> {
        private final double min;
        private final double max;

        DoubleValue(String section, String key, List<String> comments, double defaultValue, double min, double max) {
            super(section, key, comments, defaultValue);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean accept(String text) {
            try {
                double parsed = Double.parseDouble(text.replace("_", ""));
                if (Double.isNaN(parsed) || parsed < min || parsed > max) return false;
                set(parsed);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        String text() {
            return String.valueOf(get());
        }

        @Override
        String hint() {
            return "Range: " + min + " ~ " + max;
        }
    }

    public static final class EnumValue<E extends Enum<E>> extends Value<E> {
        private final Class<E> type;

        EnumValue(String section, String key, List<String> comments, E defaultValue) {
            super(section, key, comments, defaultValue);
            this.type = defaultValue.getDeclaringClass();
        }

        @Override
        boolean accept(String text) {
            String content = unquote(text);
            if (content == null) return false;
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(content)) {
                    set(constant);
                    return true;
                }
            }
            return false;
        }

        @Override
        String text() {
            return quote(get().name());
        }

        @Override
        String hint() {
            StringBuilder allowed = new StringBuilder("Allowed Values: ");
            E[] constants = type.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) allowed.append(", ");
                allowed.append(constants[i].name());
            }
            return allowed.toString();
        }
    }

    // ---- building ---------------------------------------------------------------

    private record Section(String name, List<String> comments, List<Value<?>> values) {
    }

    /**
     * Declares the settings the same way Forge's builder did: a comment applies to
     * the next section or value, and values belong to the section last pushed.
     */
    public static final class Builder {
        private final Map<String, Section> sections = new LinkedHashMap<>();
        private final List<String> pending = new ArrayList<>();
        private String current = "";

        public Builder comment(String... lines) {
            for (String line : lines) {
                pending.addAll(List.of(line.split("\n")));
            }
            return this;
        }

        public Builder push(String section) {
            if (!current.isEmpty()) {
                throw new IllegalStateException("Sections are one level deep: " + current + " is still open");
            }
            current = section;
            sections.computeIfAbsent(section, name -> new Section(name, takeComments(), new ArrayList<>()));
            return this;
        }

        public Builder pop() {
            current = "";
            return this;
        }

        public BooleanValue define(String key, boolean defaultValue) {
            return add(new BooleanValue(current, key, takeComments(), defaultValue));
        }

        public StringValue define(String key, String defaultValue) {
            return add(new StringValue(current, key, takeComments(), defaultValue));
        }

        public IntValue defineInRange(String key, int defaultValue, int min, int max) {
            return add(new IntValue(current, key, takeComments(), defaultValue, min, max));
        }

        public DoubleValue defineInRange(String key, double defaultValue, double min, double max) {
            return add(new DoubleValue(current, key, takeComments(), defaultValue, min, max));
        }

        public <E extends Enum<E>> EnumValue<E> defineEnum(String key, E defaultValue) {
            return add(new EnumValue<>(current, key, takeComments(), defaultValue));
        }

        public ConfigFile build() {
            return new ConfigFile(List.copyOf(sections.values()));
        }

        private <V extends Value<?>> V add(V value) {
            if (current.isEmpty()) {
                throw new IllegalStateException("Push a section before defining " + value.key);
            }
            for (Value<?> existing : sections.get(current).values) {
                if (existing.key.equals(value.key)) {
                    throw new IllegalStateException("Defined twice: " + value.path());
                }
            }
            sections.get(current).values.add(value);
            return value;
        }

        private List<String> takeComments() {
            List<String> taken = List.copyOf(pending);
            pending.clear();
            return taken;
        }
    }
}

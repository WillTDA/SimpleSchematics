package dev.willtda.simpleschematics.printing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a Creative paste has already done with saved data, kept on disk so a
 * paste that is interrupted and continued neither repeats nor loses it.
 *
 * <p>One line per step, an id and then what happened to it. A summoned entity
 * is written down before its command is sent, so a crash straight after can
 * never summon it twice. A container is written down when it is placed, so its
 * contents still go in if the paste stops before it gets that far. Both are
 * marked done once their data is in. Lines are only ever appended; a later step
 * outranks an earlier one when the file is read back.</p>
 *
 * <p>No game types in here, so it can be checked on its own. Journals written
 * before containers were tracked hold bare ids, which read as attempted.</p>
 */
final class PrintJournal {

    enum Step {
        /** An entity whose summon command may have been sent. */
        ATTEMPTED,
        /** A container or other data block placed by the paste, waiting for its data. */
        PLACED,
        /** The data is in. Never touched again. */
        DONE
    }

    private final Path file;
    private final Map<UUID, Step> steps = new HashMap<>();
    private final boolean readable;

    private PrintJournal(Path file, boolean readable) {
        this.file = file;
        this.readable = readable;
    }

    static PrintJournal open(Path file) {
        if (!Files.exists(file)) {
            return new PrintJournal(file, true);
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            PrintJournal journal = new PrintJournal(file, true);
            for (String line : lines) {
                journal.remember(line);
            }
            return journal;
        } catch (IOException | RuntimeException e) {
            return new PrintJournal(file, false);
        }
    }

    /** False when the file could not be read, in which case nothing may be summoned. */
    boolean readable() {
        return readable;
    }

    /** The furthest step recorded for an id, or null if it has never been seen. */
    Step step(UUID id) {
        return steps.get(id);
    }

    boolean record(UUID id, Step step) {
        return recordAll(List.of(id), step);
    }

    /** @return false if the file could not be written, in which case nothing is remembered */
    boolean recordAll(Collection<UUID> ids, Step step) {
        if (ids.isEmpty()) {
            return true;
        }
        if (!readable) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        for (UUID id : ids) {
            text.append(id);
            if (step != Step.ATTEMPTED) {
                text.append(' ').append(step.name().toLowerCase(java.util.Locale.ROOT));
            }
            text.append(System.lineSeparator());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            return false;
        }
        for (UUID id : ids) {
            advance(id, step);
        }
        return true;
    }

    private void remember(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int space = trimmed.indexOf(' ');
        String id = space < 0 ? trimmed : trimmed.substring(0, space);
        String word = space < 0 ? "" : trimmed.substring(space + 1).trim();
        Step step = switch (word) {
            case "placed" -> Step.PLACED;
            case "done" -> Step.DONE;
            default -> Step.ATTEMPTED;
        };
        try {
            advance(UUID.fromString(id), step);
        } catch (IllegalArgumentException ignored) {
            // A damaged line cannot name anything, so it is skipped.
        }
    }

    private void advance(UUID id, Step step) {
        steps.merge(id, step, (old, next) -> next.ordinal() > old.ordinal() ? next : old);
    }
}

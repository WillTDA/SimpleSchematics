package dev.willtda.simpleschematics.util;

import dev.willtda.simpleschematics.SimpleSchematics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Bundles the whole data folder into one zip and back out again.
 *
 * <p>Copying the folder by hand works just as well, but a single file is easier
 * to email to yourself or drop onto a memory stick, and importing merges rather
 * than replaces so you never lose the schematics already on the other machine.</p>
 */
public final class DataTransfer {

    private DataTransfer() {
    }

    public static Path export() throws IOException {
        Path root = DataPaths.root();
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmm").format(new Date());
        Path target = root.resolve("simpleschematics-export-" + stamp + ".zip");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".zip"))
                        .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                        .toList();
                for (Path file : files) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        }
        SimpleSchematics.LOG.info("Exported the data folder to {}", target);
        return target;
    }

    /**
     * Unpacks a bundle into the data folder. Anything that would collide gets a
     * numbered suffix, so an import never overwrites work.
     *
     * @return how many files were brought in
     */
    public static int importFrom(Path zipFile) throws IOException {
        Path root = DataPaths.root();
        int imported = 0;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = root.resolve(entry.getName()).normalize();
                // refuse anything trying to escape the data folder
                if (!target.startsWith(root)) {
                    SimpleSchematics.LOG.warn("Skipping {}, it points outside the data folder", entry.getName());
                    continue;
                }
                Files.createDirectories(target.getParent());
                target = uniqueName(target);
                try (OutputStream out = Files.newOutputStream(target)) {
                    copy(zip, out);
                }
                imported++;
            }
        }
        SimpleSchematics.LOG.info("Imported {} file(s) from {}", imported, zipFile);
        return imported;
    }

    private static Path uniqueName(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 2;
        Path candidate;
        do {
            candidate = target.resolveSibling(base + " (" + suffix + ")" + extension);
            suffix++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    /** Copies a single file into the schematics folder, used by drag and drop. */
    public static Path adopt(Path source) throws IOException {
        Path target = uniqueName(DataPaths.schematics().resolve(source.getFileName().toString()));
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }
}

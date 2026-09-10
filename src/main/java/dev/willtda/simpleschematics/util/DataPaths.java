package dev.willtda.simpleschematics.util;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.config.SSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One folder holds everything the mod creates, which is the whole trick to
 * moving your data between computers. Copy the folder, or point the config at a
 * synced folder and it looks after itself.
 *
 * <pre>
 * simpleschematics/
 *   schematics/          your saved and imported builds
 *   placements.json      where each schematic sits, per world and per server
 *   resource-lists/      gathering progress, keyed by schematic
 * </pre>
 */
public final class DataPaths {

    private DataPaths() {
    }

    public static Path root() {
        String configured = SSConfig.INSTANCE.dataDirectory.get();
        Path path;
        if (configured != null && !configured.isBlank()) {
            path = Paths.get(configured.trim());
        } else {
            path = FMLPaths.GAMEDIR.get().resolve(SimpleSchematics.MOD_ID);
        }
        return ensure(path);
    }

    public static Path schematics() {
        return ensure(root().resolve("schematics"));
    }

    public static Path resourceLists() {
        return ensure(root().resolve("resource-lists"));
    }

    public static Path placementsFile() {
        return root().resolve("placements.json");
    }

    /**
     * A stable key for the world or server you are currently on, so placements
     * come back exactly where you left them after a relog.
     */
    public static String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "single/" + sanitise(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        ServerData data = mc.getCurrentServer();
        if (data != null) {
            return "server/" + sanitise(data.ip);
        }
        return "unknown";
    }

    public static String sanitise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        String cleaned = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.length() > 96) {
            cleaned = cleaned.substring(0, 96);
        }
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }

    private static Path ensure(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            SimpleSchematics.LOG.error("Could not create the folder {}", path, e);
        }
        return path;
    }
}

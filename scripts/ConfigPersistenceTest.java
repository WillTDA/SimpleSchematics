package dev.willtda.simpleschematics.printing;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.EditMode;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises real TOML saves/reloads and the same session reset used when leaving a world. */
public final class ConfigPersistenceTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        PrintTestBootstrap.initialise();
        Path folder = Files.createTempDirectory(Path.of("."), "settings-check-").toAbsolutePath();
        Path file = folder.resolve("simpleschematics-client.toml");
        SSConfig config = SSConfig.INSTANCE;
        ClientState state = ClientState.INSTANCE;
        SchematicVerifier verifier = SchematicVerifier.INSTANCE;
        try (CommentedFileConfig saved = open(file)) {
            config.dataDirectory.set(folder.toString());
            config.actionBarFeedback.set(false);
            check(state.renderHolograms(), "Old configs must retain the default hologram visibility");
            check(state.isEnabled(), "Old configs must retain their enabled default");
            check(!state.toggleRenderHolograms(), "Hologram quick toggle switches off");
            check(!verifier.toggle(), "Highlight quick toggle switches off");
            state.setEnabled(false);
            state.setMode(EditMode.PRINT);
            state.setPendingSchematicKey("settings-test.sschem");
            state.setResourceListVisible(true);
            state.setBuildListVisible(true);
            config.hologramOpacity.set(.37D);
            config.printSource.set(SSConfig.PrintSource.LINKED_CHESTS);
            config.printDelay.set(9);
            // Settings may save before any world tick has had a chance to flush the mode.
            SSConfig.SPEC.save();
            try (CommentedFileConfig disk = CommentedFileConfig.of(file)) {
                disk.load();
                check("PRINT".equals(disk.get("general.lastMode")), "Title-screen mode changes must reach the saved file");
            }
            state.flushMode();
            state.reset();
            verifier.clear();
            check(!state.renderHolograms(), "World exit must not show hidden holograms again");
            check(!state.isEnabled(), "World exit must not switch the mod on");
            check(!verifier.isEnabled(), "Clearing world verification must not enable highlights");
        }
        try (CommentedFileConfig reloaded = open(file)) {
            check(!state.renderHolograms(), "Hologram visibility survives TOML reload");
            check(!state.isEnabled(), "Mod enabled state survives TOML reload");
            check(!verifier.isEnabled(), "Highlight visibility survives TOML reload");
            check(state.mode() == EditMode.PRINT, "Mode survives TOML reload");
            check(state.resourceListVisible(), "Pending resource-list preference survives world reset and reload");
            check(state.buildListVisible(), "Pending build-list preference survives world reset and reload");
            check(config.hologramOpacity.get() == .37D, "Numeric appearance setting survives reload");
            check(config.printSource.get() == SSConfig.PrintSource.LINKED_CHESTS, "Print source survives reload");
            check(config.printDelay.get() == 9, "Print pace survives reload");
            check(verifier.toggle(), "One shortcut press enables a disabled highlight setting");
            config.highlightMismatches.set(false);
            check(!verifier.isEnabled(), "The main setting and quick toggle must share one value");
            check(verifier.toggle(), "One press enables highlights after changing the main setting");
            config.enabledOnLaunch.set(true);
            check(state.isEnabled(), "Main-menu enabled setting must not be hidden by a cached session value");
            config.lastMode.set("BUILD");
            check(state.mode() == EditMode.BUILD, "A reloaded mode is visible after pending changes are saved");
            Placement placement = new Placement("settings-test.sschem", "Settings Test", BlockPos.ZERO);
            PlacementManager.INSTANCE.add(placement);
            check(!state.resourceListVisible() && !state.buildListVisible(), "Saved placement choices override new-placement defaults");
            state.setResourceListVisible(true);
            state.setBuildListVisible(true);
            Placement roundTrip = Placement.fromJson(placement.toJson());
            check(roundTrip.resourceList() && roundTrip.buildList(), "Existing per-placement list choices still persist");
            state.reset();
            check(state.resourceListVisible() && state.buildListVisible(), "World reset must not overwrite selected placement preferences");
        }
        System.out.println("Config persistence checks passed: " + checks);
    }

    private static CommentedFileConfig open(Path file) {
        CommentedFileConfig config = CommentedFileConfig.builder(file).sync().build();
        config.load();
        SSConfig.SPEC.correct(config);
        SSConfig.SPEC.setConfig(config);
        return config;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

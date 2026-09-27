package dev.willtda.simpleschematics.client;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.gui.SchematicPreview;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.printing.PrintManager;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.render.WorldRenderer;
import dev.willtda.simpleschematics.resource.BuildListManager;
import dev.willtda.simpleschematics.resource.ResourceListManager;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;

/**
 * What the client does when the game tells it something happened.
 *
 * <p>The loader's own events are listened to in {@code platform}, one class per
 * loader, and each simply calls through to here or to the class that owns the
 * behaviour. Nothing in this class knows which loader it is running on.</p>
 */
public final class SimpleSchematicsClient {

    private static int configCheck;

    private SimpleSchematicsClient() {
    }

    public static void onSetup() {
        SimpleSchematics.LOG.info("Simple Schematics is ready, client side only");
    }

    /** Once a client tick, at its end, whether or not a world is open. */
    public static void onClientTick() {
        // Forge's config watcher used to pick up a hand edit to the settings
        // file while the game ran. Checking once a second keeps that.
        if (++configCheck >= 20) {
            configCheck = 0;
            SSConfig.FILE.reloadIfChanged();
        }
        InputHandler.onClientTick();
    }

    /** Resource packs reloaded: baked geometry and previews hold old textures. */
    public static void onResourcesReloaded() {
        WorldRenderer.invalidateAll();
        SchematicPreview.clearThumbnails();
    }

    // ---- world lifecycle --------------------------------------------------

    public static void onJoin() {
        PrintManager.INSTANCE.reset();
        SchematicLibrary.INSTANCE.refresh();
        PlacementManager.INSTANCE.onJoinWorld();
        ResourceListManager.INSTANCE.invalidateAll();
        BuildListManager.INSTANCE.invalidateAll();
        WorldRenderer.invalidateAll();
        SchematicVerifier.INSTANCE.clear();
        ClientState.INSTANCE.cancelPending();
    }

    public static void onLeave() {
        PrintManager.INSTANCE.leaveWorld();
        ClientState.INSTANCE.flushMode();
        PlacementManager.INSTANCE.onLeaveWorld();
        WorldRenderer.invalidateAll();
        SchematicVerifier.INSTANCE.clear();
        SchematicPreview.clearThumbnails();
        ClientState.INSTANCE.reset();
    }

    /** Respawning or changing dimension rebuilds the level, so the baked geometry has to go. */
    public static void onRespawn() {
        PrintManager.INSTANCE.reset();
        WorldRenderer.invalidateAll();
        SchematicVerifier.INSTANCE.clear();
    }

    /** The player picked something up, so the resource list may already be out of date. */
    public static void onItemPickup() {
        if (ClientState.INSTANCE.resourceListVisible()) {
            ResourceListManager.INSTANCE.refreshNow();
        }
    }
}

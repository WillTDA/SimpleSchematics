package dev.willtda.simpleschematics.client;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.config.ConfigScreen;
import dev.willtda.simpleschematics.gui.SchematicPreview;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.render.WorldRenderer;
import dev.willtda.simpleschematics.render.HologramShader;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.resource.ResourceListManager;
import dev.willtda.simpleschematics.resource.ResourceListOverlay;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Client bootstrap. Registers the listeners, the keys, the overlay and the
 * Config button that appears next to the mod in the Mods list.
 */
public final class SimpleSchematicsClient {

    private SimpleSchematicsClient() {
    }

    public static void bootstrap() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(SimpleSchematicsClient::onClientSetup);
        modBus.addListener(SimpleSchematicsClient::onRegisterKeys);
        modBus.addListener(SimpleSchematicsClient::onRegisterOverlays);
        modBus.addListener(HologramShader::register);
        modBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    WorldRenderer.invalidateAll();
                    SchematicPreview.clearThumbnails();
                }));

        MinecraftForge.EVENT_BUS.register(InputHandler.class);
        MinecraftForge.EVENT_BUS.register(WorldRenderer.class);
        MinecraftForge.EVENT_BUS.register(SimpleSchematicsClient.class);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigScreen(parent))));
        SimpleSchematics.LOG.info("Simple Schematics is ready, client side only");
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        Keybinds.register(event);
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "resource_list", ResourceListOverlay.INSTANCE);
    }

    // ---- world lifecycle --------------------------------------------------

    @SubscribeEvent
    public static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        SchematicLibrary.INSTANCE.refresh();
        PlacementManager.INSTANCE.onJoinWorld();
        ResourceListManager.INSTANCE.invalidateAll();
        WorldRenderer.invalidateAll();
        SchematicVerifier.INSTANCE.clear();
        ClientState.INSTANCE.cancelPending();
    }

    @SubscribeEvent
    public static void onLeave(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.INSTANCE.flushMode();
        PlacementManager.INSTANCE.onLeaveWorld();
        WorldRenderer.invalidateAll();
        SchematicVerifier.INSTANCE.clear();
        SchematicPreview.clearThumbnails();
        ClientState.INSTANCE.reset();
    }

    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        // dimension changes rebuild the level, so the baked geometry has to go
        WorldRenderer.invalidateAll();
        SchematicVerifier.INSTANCE.clear();
    }

    /**
     * Dropping a .litematic, a .sschem or an exported zip onto any Simple
     * Schematics screen brings it straight into your library.
     */
    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        // nothing to do yet, kept so the listener list stays in one place
    }

    @SubscribeEvent
    public static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        if (ClientState.INSTANCE.resourceListVisible()) {
            ResourceListManager.INSTANCE.refreshNow();
        }
    }
}

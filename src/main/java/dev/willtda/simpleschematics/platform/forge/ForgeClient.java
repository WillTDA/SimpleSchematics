package dev.willtda.simpleschematics.platform.forge;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.client.InputHandler;
import dev.willtda.simpleschematics.client.Keybinds;
import dev.willtda.simpleschematics.client.SimpleSchematicsClient;
import dev.willtda.simpleschematics.config.ConfigScreen;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.platform.Platform;
import dev.willtda.simpleschematics.printing.PrintChat;
import dev.willtda.simpleschematics.printing.PrintManager;
import dev.willtda.simpleschematics.render.HologramShader;
import dev.willtda.simpleschematics.render.WorldRenderer;
import dev.willtda.simpleschematics.resource.BuildListOverlay;
import dev.willtda.simpleschematics.resource.ResourceListOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Every Forge event the mod listens to, and every registration it makes, in
 * one place. Each one does nothing but call through to the shared code, so a
 * port to another loader is this class written again against that loader.
 */
public final class ForgeClient {

    private ForgeClient() {
    }

    static void init() {
        SSConfig.FILE.load(Platform.configDir().resolve(SSConfig.FILE_NAME));

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(ForgeClient::onClientSetup);
        modBus.addListener(ForgeClient::onRegisterKeys);
        modBus.addListener(ForgeClient::onRegisterOverlays);
        modBus.addListener(ForgeClient::onRegisterShaders);
        modBus.addListener(ForgeClient::onRegisterReloadListeners);
        MinecraftForge.EVENT_BUS.register(ForgeClient.class);
    }

    // ---- registration, on the mod bus ------------------------------------

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigScreen(parent))));
        SimpleSchematicsClient.onSetup();
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : Keybinds.all()) {
            event.register(key);
        }
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "resource_list",
                (gui, graphics, partialTick, width, height) ->
                        ResourceListOverlay.INSTANCE.render(graphics, partialTick, width, height));
        // After the resource list, so it can stack past it when they share a corner.
        event.registerAbove(new ResourceLocation(SimpleSchematics.MOD_ID, "resource_list"), "build_list",
                (gui, graphics, partialTick, width, height) ->
                        BuildListOverlay.INSTANCE.render(graphics, partialTick, width, height));
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(HologramShader.create(event.getResourceProvider()), HologramShader::set);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the Simple Schematics hologram shader", e);
        }
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> SimpleSchematicsClient.onResourcesReloaded());
    }

    // ---- the game, on the Forge bus ---------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SimpleSchematicsClient.onClientTick();
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        InputHandler.onKey(event.getKey(), event.getScanCode(), event.getAction());
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (InputHandler.onScroll(event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (InputHandler.onInteraction(event.isUseItem(), event.isAttack())) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            WorldRenderer.render(event.getPoseStack(), event.getProjectionMatrix(), event.getCamera());
        }
    }

    @SubscribeEvent
    public static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        SimpleSchematicsClient.onJoin();
    }

    @SubscribeEvent
    public static void onLeave(ClientPlayerNetworkEvent.LoggingOut event) {
        SimpleSchematicsClient.onLeave();
    }

    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        SimpleSchematicsClient.onRespawn();
    }

    /**
     * In singleplayer Forge fires this on the integrated server's thread, so the
     * refresh is handed to the client thread rather than run where it lands.
     */
    @SubscribeEvent
    public static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        Minecraft.getInstance().execute(SimpleSchematicsClient::onItemPickup);
    }

    @SubscribeEvent
    public static void onSystemChat(ClientChatReceivedEvent.System event) {
        if (PrintChat.shouldHide(event.getMessage(), event.isOverlay())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        if (PrintManager.suppressPlacementSound()) {
            event.setSound(null);
        }
    }
}

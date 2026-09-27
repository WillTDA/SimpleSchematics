package dev.willtda.simpleschematics.platform.neoforge;

//? if neoforge {
/*import com.mojang.blaze3d.vertex.PoseStack;
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
import dev.willtda.simpleschematics.util.Ids;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

import java.io.IOException;
import java.io.UncheckedIOException;

/^*
 * Every NeoForge event the mod listens to, and every registration it makes, in
 * one place. It mirrors the Forge listener beside it event for event, and each
 * one does nothing but call through to the shared code.
 ^/
public final class NeoForgeClient {

    private NeoForgeClient() {
    }

    static void init(IEventBus modBus, ModContainer container) {
        SSConfig.FILE.load(Platform.configDir().resolve(SSConfig.FILE_NAME));

        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ConfigScreen(parent));
        modBus.addListener(NeoForgeClient::onClientSetup);
        modBus.addListener(NeoForgeClient::onRegisterKeys);
        modBus.addListener(NeoForgeClient::onRegisterLayers);
        modBus.addListener(NeoForgeClient::onRegisterShaders);
        modBus.addListener(NeoForgeClient::onRegisterReloadListeners);
        NeoForge.EVENT_BUS.register(NeoForgeClient.class);
    }

    // ---- registration, on the mod bus ------------------------------------

    private static void onClientSetup(FMLClientSetupEvent event) {
        SimpleSchematicsClient.onSetup();
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : Keybinds.all()) {
            event.register(key);
        }
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, Ids.mod("resource_list"),
                (graphics, delta) -> ResourceListOverlay.INSTANCE.render(graphics,
                        delta.getGameTimeDeltaPartialTick(false), graphics.guiWidth(), graphics.guiHeight()));
        // After the resource list, so it can stack past it when they share a corner.
        event.registerAbove(Ids.mod("resource_list"), Ids.mod("build_list"),
                (graphics, delta) -> BuildListOverlay.INSTANCE.render(graphics,
                        delta.getGameTimeDeltaPartialTick(false), graphics.guiWidth(), graphics.guiHeight()));
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

    // ---- the game, on the NeoForge bus -------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        SimpleSchematicsClient.onClientTick();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        InputHandler.onKey(event.getKey(), event.getScanCode(), event.getAction());
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (InputHandler.onScroll(event.getScrollDeltaY())) {
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

    /^*
     * From 1.21 the level is drawn with a bare pose and the camera's turn held on
     * the render system's model view instead. The renderer is written for the
     * turn to be in the pose, as it was on 1.20.1, so it is put back there.
     ^/
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            PoseStack pose = new PoseStack();
            pose.mulPose(event.getModelViewMatrix());
            WorldRenderer.render(pose, event.getProjectionMatrix(), event.getCamera());
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

    /^*
     * In singleplayer this fires on the integrated server's thread, so the
     * refresh is handed to the client thread rather than run where it lands.
     ^/
    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
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
*///?}

package dev.willtda.simpleschematics.resource;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.config.SSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * The list of materials that sits in the corner while you build.
 *
 * <p>The drawing lives in {@link OverlayPanel}, shared with the build list.
 * This decides whether the panel should be up and what goes on it.</p>
 */
public final class ResourceListOverlay implements IGuiOverlay {

    public static final ResourceListOverlay INSTANCE = new ResourceListOverlay();

    /** Where this frame's panel landed, so the build list can stack past it. */
    private OverlayPanel.Footprint footprint;

    private ResourceListOverlay() {
    }

    OverlayPanel.Footprint footprint() {
        return footprint;
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        footprint = null;
        Minecraft mc = Minecraft.getInstance();
        ClientState state = ClientState.INSTANCE;

        if (!state.isEnabled() || !state.resourceListVisible() || !SSConfig.INSTANCE.resourceListEnabled.get()) {
            return;
        }
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        List<ResourceListManager.Row> rows = ResourceListManager.INSTANCE.visibleRows();
        String name = SSConfig.INSTANCE.showBuildName.get() ? state.targetName() : null;

        footprint = OverlayPanel.render(graphics, screenWidth, screenHeight,
                new OverlayPanel.Place(SSConfig.INSTANCE.resourceListAnchor.get(),
                        SSConfig.INSTANCE.resourceListOffsetX.get(),
                        SSConfig.INSTANCE.resourceListOffsetY.get(),
                        SSConfig.INSTANCE.resourceListMaxRows.get(),
                        null),
                new OverlayPanel.Content<>(name,
                        Component.translatable("simpleschematics.resource.title").getString(),
                        ResourceListManager.INSTANCE.totalMissing(),
                        rows,
                        ResourceListOverlay::line,
                        Component.translatable("simpleschematics.resource.all_done").getString()));
    }

    private static OverlayPanel.Line line(ResourceListManager.Row row) {
        ItemStack stack = new ItemStack(row.item());
        int missing = row.missing();
        return new OverlayPanel.Line(stack,
                ResourceListManager.itemName(row.item()),
                OverlayPanel.breakdown(missing, stack),
                OverlayPanel.format(missing),
                row.complete());
    }
}

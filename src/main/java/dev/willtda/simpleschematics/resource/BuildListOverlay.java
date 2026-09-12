package dev.willtda.simpleschematics.resource;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * The panel that says what is still to place for the layer in front of you.
 *
 * <p>Drawn after the resource list so that, when both are sent to the same
 * corner, this one stacks past it rather than over it.</p>
 */
public final class BuildListOverlay implements IGuiOverlay {

    public static final BuildListOverlay INSTANCE = new BuildListOverlay();

    private BuildListOverlay() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        ClientState state = ClientState.INSTANCE;

        if (!state.overlaysActive() || !state.buildListVisible() || !SSConfig.INSTANCE.buildListEnabled.get()) {
            return;
        }
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        Schematic schematic = state.targetSchematic();
        if (schematic == null) {
            return;
        }

        OverlayPanel.render(graphics, screenWidth, screenHeight,
                new OverlayPanel.Place(SSConfig.INSTANCE.buildListAnchor.get(),
                        SSConfig.INSTANCE.buildListOffsetX.get(),
                        SSConfig.INSTANCE.buildListOffsetY.get(),
                        SSConfig.INSTANCE.buildListMaxRows.get(),
                        ResourceListOverlay.INSTANCE.footprint()),
                new OverlayPanel.Content<>(nameLine(state, schematic),
                        Component.translatable("simpleschematics.build_list.title").getString(),
                        BuildListManager.INSTANCE.total(),
                        BuildListManager.INSTANCE.rows(),
                        BuildListOverlay::line,
                        Component.translatable(state.layerView() == ClientState.LayerView.SINGLE
                                ? "simpleschematics.build_list.layer_done"
                                : "simpleschematics.build_list.all_done").getString()));
    }

    /**
     * The build and the slice, so a list that only covers one layer never
     * reads as the whole job. The name half follows the same setting as the
     * resource list; the layer half is always there.
     */
    private static String nameLine(ClientState state, Schematic schematic) {
        String range = state.layerView() == ClientState.LayerView.SINGLE
                ? Component.translatable("simpleschematics.build_list.layer",
                        Math.min(state.layer(), Math.max(0, schematic.height() - 1)) + 1,
                        schematic.height()).getString()
                : Component.translatable("simpleschematics.build_list.whole").getString();
        String name = SSConfig.INSTANCE.showBuildName.get() ? state.targetName() : null;
        return name == null ? range : name + ", " + range;
    }

    private static OverlayPanel.Line line(BuildListManager.Row row) {
        ItemStack stack = new ItemStack(row.item());
        return new OverlayPanel.Line(stack,
                ResourceListManager.itemName(row.item()),
                OverlayPanel.breakdown(row.remaining(), stack),
                OverlayPanel.format(row.remaining()),
                false);
    }
}

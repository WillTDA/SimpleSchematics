package dev.willtda.simpleschematics.resource;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.config.SSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * The list that sits in the corner while you build.
 *
 * <p>Sizing is worked out from the current scaled screen every frame and then
 * clamped, so it behaves at any GUI scale including auto, and it shrinks its own
 * row count rather than running off the edge on a small window.</p>
 */
public final class ResourceListOverlay implements IGuiOverlay {

    public static final ResourceListOverlay INSTANCE = new ResourceListOverlay();

    private static final int ROW_HEIGHT = 17;
    private static final int PADDING = 5;
    private static final int HEADER_HEIGHT = 22;
    private static final int ICON = 16;

    private ResourceListOverlay() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        ClientState state = ClientState.INSTANCE;

        if (!state.isEnabled() || !state.resourceListVisible() || !SSConfig.INSTANCE.resourceListEnabled.get()) {
            return;
        }
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        List<ResourceListManager.Row> rows = ResourceListManager.INSTANCE.visibleRows();
        Font font = mc.font;

        float scale = SSConfig.INSTANCE.resourceListScale.get().floatValue();
        // never let the panel take more than two thirds of the window in either direction
        int maxWidth = (int) (screenWidth * 0.66F / scale);
        int maxHeight = (int) (screenHeight * 0.66F / scale);

        int width = Math.min(SSConfig.INSTANCE.resourceListWidth.get(), Math.max(90, maxWidth));
        int configuredRows = SSConfig.INSTANCE.resourceListMaxRows.get();
        int roomForRows = Math.max(1, (maxHeight - HEADER_HEIGHT - PADDING * 2) / ROW_HEIGHT);
        int shownRows = Math.min(Math.min(configuredRows, roomForRows), Math.max(rows.size(), 1));

        int height = HEADER_HEIGHT + PADDING + shownRows * ROW_HEIGHT + PADDING;

        int offsetX = SSConfig.INSTANCE.resourceListOffsetX.get();
        int offsetY = SSConfig.INSTANCE.resourceListOffsetY.get();
        int scaledWidth = Math.round(width * scale);
        int scaledHeight = Math.round(height * scale);

        int x;
        int y;
        switch (SSConfig.INSTANCE.resourceListAnchor.get()) {
            case TOP_LEFT -> {
                x = offsetX;
                y = offsetY;
            }
            case TOP_RIGHT -> {
                x = screenWidth - scaledWidth - offsetX;
                y = offsetY;
            }
            case BOTTOM_LEFT -> {
                x = offsetX;
                y = screenHeight - scaledHeight - offsetY;
            }
            default -> {
                x = screenWidth - scaledWidth - offsetX;
                y = screenHeight - scaledHeight - offsetY;
            }
        }
        x = Math.max(0, Math.min(x, screenWidth - scaledWidth));
        y = Math.max(0, Math.min(y, screenHeight - scaledHeight));

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0F);

        int backgroundAlpha = (int) (SSConfig.INSTANCE.resourceListBackgroundOpacity.get() * 255) << 24;
        graphics.fill(0, 0, width, height, backgroundAlpha);
        graphics.fill(0, 0, width, 1, 0x33FFFFFF);
        graphics.fill(0, height - 1, width, height, 0x33FFFFFF);

        drawHeader(graphics, font, width, rows);

        if (rows.isEmpty()) {
            graphics.drawString(font, Component.translatable("simpleschematics.resource.all_done"),
                    PADDING, HEADER_HEIGHT + PADDING, 0xFF6EE7B7, false);
        } else {
            int drawn = 0;
            for (ResourceListManager.Row row : rows) {
                if (drawn >= shownRows) {
                    break;
                }
                drawRow(graphics, font, row, width, HEADER_HEIGHT + PADDING + drawn * ROW_HEIGHT);
                drawn++;
            }
            int hidden = rows.size() - drawn;
            if (hidden > 0) {
                String more = Component.translatable("simpleschematics.resource.more", hidden).getString();
                graphics.drawString(font, more, width - PADDING - font.width(more), height - PADDING - 8,
                        0xFF9CA3AF, false);
            }
        }

        graphics.pose().popPose();
    }

    private void drawHeader(GuiGraphics graphics, Font font, int width, List<ResourceListManager.Row> rows) {
        ResourceListManager manager = ResourceListManager.INSTANCE;
        String title = Component.translatable("simpleschematics.resource.title").getString();
        graphics.drawString(font, title, PADDING, PADDING, 0xFFFFFFFF, false);

        int missing = manager.totalMissing();
        String summary = missing == 0
                ? Component.translatable("simpleschematics.resource.complete").getString()
                : format(missing);
        int colour = missing == 0 ? 0xFF6EE7B7 : 0xFFE5E7EB;
        graphics.drawString(font, summary, width - PADDING - font.width(summary), PADDING, colour, false);
        graphics.fill(PADDING, HEADER_HEIGHT - 6, width - PADDING, HEADER_HEIGHT - 5, 0x33FFFFFF);
    }

    private void drawRow(GuiGraphics graphics, Font font, ResourceListManager.Row row, int width, int y) {
        ItemStack stack = new ItemStack(row.item());
        graphics.renderFakeItem(stack, PADDING, y);

        int missing = row.missing();
        String count = format(missing);
        String breakdown = SSConfig.INSTANCE.showStackBreakdown.get()
                ? MaterialResolver.stackBreakdown(missing, stack.getMaxStackSize())
                : "";

        int countWidth = font.width(count);
        int breakdownWidth = breakdown.isEmpty() ? 0 : font.width(breakdown) + 4;
        int textX = PADDING + ICON + 4;
        int available = width - PADDING - countWidth - breakdownWidth - textX - 4;

        String name = ResourceListManager.itemName(row.item());
        if (font.width(name) > available) {
            name = font.plainSubstrByWidth(name, Math.max(8, available - 6)) + "...";
        }

        int nameColour = row.complete() ? 0xFF6B7280 : 0xFFE5E7EB;
        graphics.drawString(font, name, textX, y + 4, nameColour, false);

        if (!breakdown.isEmpty()) {
            int bx = width - PADDING - countWidth - breakdownWidth;
            graphics.drawString(font, breakdown, bx, y + 4, 0xFF6B7280, false);
        }
        int countColour = missing == 0 ? 0xFF6EE7B7 : 0xFFFBBF24;
        graphics.drawString(font, count, width - PADDING - countWidth, y + 4, countColour, false);
    }

    private static String format(int value) {
        return String.format("%,d", value);
    }
}

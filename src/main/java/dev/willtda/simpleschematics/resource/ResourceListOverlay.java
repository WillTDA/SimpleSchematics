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

import java.util.ArrayList;
import java.util.List;

/**
 * The list that sits in the corner while you build.
 *
 * <p>The panel is measured from the text it is about to draw rather than given
 * a width up front. A fixed width had to be shared out between the name, the
 * stack breakdown and the total, and the name was the part that lost: at the
 * default width it came down to a letter and an ellipsis that then ran into the
 * figures beside it. Growing to fit, and trimming only once the panel reaches
 * its ceiling, keeps the names readable, which is the point of the list.</p>
 *
 * <p>Sizing is still worked out from the current scaled screen every frame and
 * clamped, so it behaves at any GUI scale including auto, and it drops rows
 * rather than running off the edge on a small window.</p>
 */
public final class ResourceListOverlay implements IGuiOverlay {

    public static final ResourceListOverlay INSTANCE = new ResourceListOverlay();

    private static final int ROW_HEIGHT = 18;
    private static final int PADDING = 4;
    /** The title, its breathing room, and the rule underneath it. */
    private static final int HEADER_HEIGHT = 20;
    private static final int ICON = 16;
    private static final int ICON_GAP = 4;
    /** Held between the name and the figures, so the two columns never touch. */
    private static final int COLUMN_GAP = 10;
    /** Held between the breakdown and the total. */
    private static final int FIGURE_GAP = 5;
    private static final int MIN_WIDTH = 100;

    private static final int TEXT = 0xFFE5E7EB;
    private static final int DONE = 0xFF6EE7B7;
    private static final int MUTED = 0xFF6B7280;
    private static final int WANTED = 0xFFFBBF24;

    private ResourceListOverlay() {
    }

    /** One row, resolved to the exact strings that get measured and then drawn. */
    private record Line(ItemStack stack, String name, String breakdown, String count, boolean complete) {
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
        int widthCap = Math.min(SSConfig.INSTANCE.resourceListMaxWidth.get(),
                Math.max(MIN_WIDTH, (int) (screenWidth * 0.66F / scale)));
        int maxHeight = (int) (screenHeight * 0.66F / scale);

        // The count of hidden rows takes a line of its own. Reserving it here is
        // what stops it being drawn across the last row it is counting.
        int roomForLines = Math.max(1, (maxHeight - HEADER_HEIGHT - PADDING) / ROW_HEIGHT);
        int drawn = Math.min(Math.min(SSConfig.INSTANCE.resourceListMaxRows.get(), rows.size()), roomForLines);
        if (rows.size() > drawn && drawn + 1 > roomForLines) {
            drawn = Math.max(1, roomForLines - 1);
        }
        int hidden = rows.size() - drawn;

        List<Line> lines = new ArrayList<>(drawn);
        for (int i = 0; i < drawn; i++) {
            lines.add(line(rows.get(i)));
        }

        String title = Component.translatable("simpleschematics.resource.title").getString();
        int missing = ResourceListManager.INSTANCE.totalMissing();
        String summary = missing == 0
                ? Component.translatable("simpleschematics.resource.complete").getString()
                : format(missing);
        String more = hidden > 0
                ? Component.translatable("simpleschematics.resource.more", hidden).getString()
                : "";
        String empty = lines.isEmpty()
                ? Component.translatable("simpleschematics.resource.all_done").getString()
                : "";

        int width = measure(font, lines, title, summary, more, empty, widthCap);
        int shown = Math.max(1, lines.size()) + (hidden > 0 ? 1 : 0);
        int height = HEADER_HEIGHT + shown * ROW_HEIGHT + PADDING;

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

        graphics.drawString(font, title, PADDING, PADDING, 0xFFFFFFFF, false);
        graphics.drawString(font, summary, width - PADDING - font.width(summary), PADDING,
                missing == 0 ? DONE : TEXT, false);
        graphics.fill(PADDING, HEADER_HEIGHT - 4, width - PADDING, HEADER_HEIGHT - 3, 0x33FFFFFF);

        if (lines.isEmpty()) {
            graphics.drawString(font, empty, PADDING, HEADER_HEIGHT + 5, DONE, false);
        } else {
            for (int i = 0; i < lines.size(); i++) {
                drawRow(graphics, font, lines.get(i), width, HEADER_HEIGHT + i * ROW_HEIGHT);
            }
            if (hidden > 0) {
                graphics.drawString(font, more, width - PADDING - font.width(more),
                        HEADER_HEIGHT + lines.size() * ROW_HEIGHT + 5, MUTED, false);
            }
        }

        graphics.pose().popPose();
    }

    private static Line line(ResourceListManager.Row row) {
        ItemStack stack = new ItemStack(row.item());
        int missing = row.missing();
        return new Line(stack,
                ResourceListManager.itemName(row.item()),
                SSConfig.INSTANCE.showStackBreakdown.get()
                        ? MaterialResolver.stackBreakdown(missing, stack.getMaxStackSize())
                        : "",
                format(missing),
                row.complete());
    }

    /** The width the panel would like, held to the ceiling it is allowed. */
    private static int measure(Font font, List<Line> lines, String title, String summary,
                               String more, String empty, int cap) {
        int widest = font.width(title) + COLUMN_GAP + font.width(summary);
        for (Line line : lines) {
            widest = Math.max(widest,
                    ICON + ICON_GAP + font.width(line.name()) + COLUMN_GAP + figures(font, line));
        }
        if (!more.isEmpty()) {
            widest = Math.max(widest, font.width(more));
        }
        if (!empty.isEmpty()) {
            widest = Math.max(widest, font.width(empty));
        }
        return Math.max(MIN_WIDTH, Math.min(widest + PADDING * 2, cap));
    }

    /** The breakdown and the total together, which always keep their full width. */
    private static int figures(Font font, Line line) {
        int width = font.width(line.count());
        if (!line.breakdown().isEmpty()) {
            width += font.width(line.breakdown()) + FIGURE_GAP;
        }
        return width;
    }

    private static void drawRow(GuiGraphics graphics, Font font, Line line, int width, int y) {
        graphics.renderFakeItem(line.stack(), PADDING, y + 1);

        int textX = PADDING + ICON + ICON_GAP;
        int figures = figures(font, line);
        // The figures are never trimmed, so what is left over is the name's.
        // Measuring the ellipsis rather than assuming it fits is what stops a
        // trimmed name overrunning the column beside it.
        int available = width - PADDING - figures - COLUMN_GAP - textX;
        String name = line.name();
        if (font.width(name) > available) {
            int room = available - font.width("...");
            name = room <= 0 ? "" : font.plainSubstrByWidth(name, room) + "...";
        }
        graphics.drawString(font, name, textX, y + 5, line.complete() ? MUTED : TEXT, false);

        int countWidth = font.width(line.count());
        if (!line.breakdown().isEmpty()) {
            graphics.drawString(font, line.breakdown(),
                    width - PADDING - countWidth - FIGURE_GAP - font.width(line.breakdown()),
                    y + 5, MUTED, false);
        }
        graphics.drawString(font, line.count(), width - PADDING - countWidth, y + 5,
                line.complete() ? DONE : WANTED, false);
    }

    private static String format(int value) {
        return String.format("%,d", value);
    }
}

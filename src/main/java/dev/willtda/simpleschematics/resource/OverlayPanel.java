package dev.willtda.simpleschematics.resource;

import dev.willtda.simpleschematics.config.SSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The corner panel the resource list and the build list are both drawn as.
 *
 * <p>An optional name line, a title with a figure beside it, a rule, then rows
 * of icon, name and count. Keeping the two lists on one drawer is what keeps
 * them looking like the same mod when they sit next to each other.</p>
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
final class OverlayPanel {

    private static final int ROW_HEIGHT = 18;
    private static final int PADDING = 4;
    /** The title, its breathing room, and the rule underneath it. */
    private static final int HEADER_HEIGHT = 20;
    /** Added above the title when the build's name is shown. */
    private static final int NAME_HEIGHT = 12;
    private static final int ICON = 16;
    private static final int ICON_GAP = 4;
    /** Held between the name and the figures, so the two columns never touch. */
    private static final int COLUMN_GAP = 10;
    /** Held between the breakdown and the total. */
    private static final int FIGURE_GAP = 5;
    private static final int MIN_WIDTH = 100;
    /** Kept between two panels stacked in the same corner. */
    private static final int STACK_GAP = 4;

    private static final int TEXT = 0xFFE5E7EB;
    private static final int DONE = 0xFF6EE7B7;
    private static final int MUTED = 0xFF6B7280;
    private static final int WANTED = 0xFFFBBF24;
    private static final int NAME = 0xFF60A5FA;

    private OverlayPanel() {
    }

    /** One row, resolved to the exact strings that get measured and then drawn. */
    record Line(ItemStack stack, String name, String breakdown, String count, boolean complete) {
    }

    /**
     * Where a panel sits on screen.
     *
     * @param stackedOn what is already drawn in that corner this frame, or null,
     *                  so a second panel in the same corner sits beside the
     *                  first rather than on top of it
     */
    record Place(SSConfig.Anchor anchor, int offsetX, int offsetY, int maxRows, Footprint stackedOn) {
    }

    /** The scaled box a panel took up, so the next one in the corner can avoid it. */
    record Footprint(SSConfig.Anchor anchor, int height) {
    }

    /**
     * What a panel says.
     *
     * @param name    the line above the title, or null for none
     * @param total   the figure beside the title; zero reads as done
     * @param rows    every row, in the order they should be drawn
     * @param line    turns a row into the strings that are measured and drawn
     * @param empty   what to say instead of rows when there are none
     */
    record Content<R>(String name, String title, int total, List<R> rows, Function<R, Line> line, String empty) {
    }

    /** Draws the panel and reports the box it took up, or null if nothing was drawn. */
    static <R> Footprint render(GuiGraphics graphics, int screenWidth, int screenHeight,
                                Place place, Content<R> content) {
        Font font = Minecraft.getInstance().font;
        List<R> rows = content.rows();

        float scale = SSConfig.INSTANCE.resourceListScale.get().floatValue();
        // never let the panel take more than two thirds of the window in either direction
        int widthCap = Math.min(SSConfig.INSTANCE.resourceListMaxWidth.get(),
                Math.max(MIN_WIDTH, (int) (screenWidth * 0.66F / scale)));
        int maxHeight = (int) (screenHeight * 0.66F / scale);

        int header = HEADER_HEIGHT + (content.name() != null ? NAME_HEIGHT : 0);

        // The count of hidden rows takes a line of its own. Reserving it here is
        // what stops it being drawn across the last row it is counting.
        int roomForLines = Math.max(1, (maxHeight - header - PADDING) / ROW_HEIGHT);
        int drawn = Math.min(Math.min(place.maxRows(), rows.size()), roomForLines);
        if (rows.size() > drawn && drawn + 1 > roomForLines) {
            drawn = Math.max(1, roomForLines - 1);
        }
        int hidden = rows.size() - drawn;

        List<Line> lines = new ArrayList<>(drawn);
        for (int i = 0; i < drawn; i++) {
            lines.add(content.line().apply(rows.get(i)));
        }

        int total = content.total();
        String summary = total == 0
                ? Component.translatable("simpleschematics.resource.complete").getString()
                : format(total);
        String more = hidden > 0
                ? Component.translatable("simpleschematics.resource.more", hidden).getString()
                : "";
        String empty = lines.isEmpty() ? content.empty() : "";

        int width = measure(font, lines, content.name(), content.title(), summary, more, empty, widthCap);
        int shown = Math.max(1, lines.size()) + (hidden > 0 ? 1 : 0);
        int height = header + shown * ROW_HEIGHT + PADDING;

        int offsetX = place.offsetX();
        int offsetY = place.offsetY();
        Footprint below = place.stackedOn();
        if (below != null && below.anchor() == place.anchor()) {
            offsetY += below.height() + STACK_GAP;
        }
        int scaledWidth = Math.round(width * scale);
        int scaledHeight = Math.round(height * scale);

        int x;
        int y;
        switch (place.anchor()) {
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

        int titleY = PADDING;
        if (content.name() != null) {
            graphics.drawString(font, fit(font, content.name(), width - PADDING * 2), PADDING, PADDING, NAME, false);
            titleY += NAME_HEIGHT;
        }
        graphics.drawString(font, content.title(), PADDING, titleY, 0xFFFFFFFF, false);
        graphics.drawString(font, summary, width - PADDING - font.width(summary), titleY,
                total == 0 ? DONE : TEXT, false);
        graphics.fill(PADDING, header - 4, width - PADDING, header - 3, 0x33FFFFFF);

        if (lines.isEmpty()) {
            graphics.drawString(font, empty, PADDING, header + 5, DONE, false);
        } else {
            for (int i = 0; i < lines.size(); i++) {
                drawRow(graphics, font, lines.get(i), width, header + i * ROW_HEIGHT);
            }
            if (hidden > 0) {
                graphics.drawString(font, more, width - PADDING - font.width(more),
                        header + lines.size() * ROW_HEIGHT + 5, MUTED, false);
            }
        }

        graphics.pose().popPose();

        // Report the whole column this corner now holds, so a third panel would
        // stack past both rather than only the last one.
        int occupied = scaledHeight + (below != null && below.anchor() == place.anchor()
                ? below.height() + STACK_GAP
                : 0);
        return new Footprint(place.anchor(), occupied);
    }

    /** The width the panel would like, held to the ceiling it is allowed. */
    private static int measure(Font font, List<Line> lines, String name, String title, String summary,
                               String more, String empty, int cap) {
        int widest = font.width(title) + COLUMN_GAP + font.width(summary);
        if (name != null) {
            widest = Math.max(widest, font.width(name));
        }
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
        String name = fit(font, line.name(), width - PADDING - figures - COLUMN_GAP - textX);
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

    /**
     * Trims text to a width with an ellipsis. Measuring the ellipsis rather than
     * assuming it fits is what stops a trimmed name overrunning the column
     * beside it.
     */
    private static String fit(Font font, String text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        int left = room - font.width("...");
        return left <= 0 ? "" : font.plainSubstrByWidth(text, left) + "...";
    }

    static String format(int value) {
        return String.format("%,d", value);
    }

    /** The stack breakdown beside a count, or nothing when that is switched off. */
    static String breakdown(int count, ItemStack stack) {
        return SSConfig.INSTANCE.showStackBreakdown.get()
                ? MaterialResolver.stackBreakdown(count, stack.getMaxStackSize())
                : "";
    }
}

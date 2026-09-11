package dev.willtda.simpleschematics.gui;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import dev.willtda.simpleschematics.resource.ResourceListManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The full list, where you can correct anything the automatic counting got
 * wrong. Left click ticks a row off, right click clears it again, and scrolling
 * on a row nudges the stored amount up or down.
 */
public final class ResourceListScreen extends Screen {

    private static final int ROW_HEIGHT = 20;

    private static final int TEXT = 0xFFE5E7EB;
    private static final int DONE = 0xFF6EE7B7;
    private static final int MUTED = 0xFF6B7280;
    private static final int WANTED = 0xFFFBBF24;
    private static final int NAME = 0xFF60A5FA;

    private final Screen parent;
    private int scroll;
    private Button resetButton;

    public ResourceListScreen(Screen parent) {
        super(Component.translatable("simpleschematics.gui.resource.title"));
        this.parent = parent;
    }

    private int listTop() {
        return 44;
    }

    /**
     * The list stops above the hint, and the hint above the buttons. The three
     * were sharing the same strip before, which is why the hint was reading
     * through the button row.
     */
    private int listBottom() {
        return Math.max(listTop() + ROW_HEIGHT, buttonY() - 6 - this.font.lineHeight - 4);
    }

    private int buttonY() {
        return this.height - 28;
    }

    private int hintY() {
        return buttonY() - 6 - this.font.lineHeight;
    }

    private int listX() {
        return Math.max(12, (this.width - contentWidth()) / 2);
    }

    private int contentWidth() {
        return Mth.clamp(this.width - 24, 180, 420);
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT);
    }

    @Override
    protected void init() {
        ResourceListManager.INSTANCE.refreshNow();

        int buttonWidth = Math.min(120, (contentWidth() - 4) / 2);
        int y = buttonY();
        // Centre the row on the window rather than hanging it off the list edge.
        int row = buttonWidth * 2 + 4;
        int x = (this.width - row) / 2;

        resetButton = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.resource.reset"),
                        b -> ResourceListManager.INSTANCE.resetProgress())
                .bounds(x, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.done")))
                .bounds(x + buttonWidth + 4, y, buttonWidth, 20).build());
    }

    /** One row, resolved to the exact strings that get measured and then drawn. */
    private record Line(ResourceListManager.Row row, ItemStack stack, String name,
                        String have, String breakdown, String count) {
    }

    private static Line line(ResourceListManager.Row row) {
        ItemStack stack = new ItemStack(row.item());
        int missing = row.missing();
        return new Line(row, stack,
                ResourceListManager.itemName(row.item()),
                String.format("%,d / %,d", row.have(), row.required()),
                SSConfig.INSTANCE.showStackBreakdown.get()
                        ? MaterialResolver.stackBreakdown(missing, stack.getMaxStackSize())
                        : "",
                String.format("%,d", missing));
    }

    /** Nothing to reset until you have ticked something off, so it says so. */
    private void updateButtons() {
        boolean canReset = ResourceListManager.INSTANCE.hasProgress();
        resetButton.active = canReset;
        resetButton.setTooltip(Tooltip.create(Component.translatable(canReset
                ? "simpleschematics.tip.reset"
                : "simpleschematics.tip.reset.none")));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        updateButtons();

        List<ResourceListManager.Row> rows = ResourceListManager.INSTANCE.rows();
        int x = listX();
        int width = contentWidth();
        int top = listTop();

        graphics.drawString(this.font, this.title, x, 14, 0xFFFFFFFF, false);
        String summary = Component.translatable("simpleschematics.gui.resource.summary",
                String.format("%,d", ResourceListManager.INSTANCE.totalMissing()),
                String.format("%,d", ResourceListManager.INSTANCE.totalRequired())).getString();
        graphics.drawString(this.font, summary, x + width - this.font.width(summary), 14, 0xFF9CA3AF, false);
        String build = SSConfig.INSTANCE.showBuildName.get() ? ClientState.INSTANCE.targetName() : null;
        if (build != null) {
            graphics.drawString(this.font, fit(build, width), x, 14 + this.font.lineHeight + 3, NAME, false);
        }

        graphics.fill(x, top, x + width, listBottom(), 0x40000000);

        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable(ClientState.INSTANCE.targetSchematicKey() == null
                            ? "simpleschematics.feedback.resource_needs_target"
                            : "simpleschematics.resource.all_done"),
                    x + width / 2, top + 12, 0xFF9CA3AF);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int rowCount = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - rowCount));

        // The columns are measured from the widest figure on the page, so they
        // line up down the list rather than each row finding its own.
        List<Line> lines = new ArrayList<>(rowCount);
        int haveWidth = 0;
        int breakdownWidth = 0;
        int countWidth = 0;
        for (int i = 0; i < rowCount && i + scroll < rows.size(); i++) {
            Line line = line(rows.get(i + scroll));
            lines.add(line);
            haveWidth = Math.max(haveWidth, this.font.width(line.have()));
            breakdownWidth = Math.max(breakdownWidth, this.font.width(line.breakdown()));
            countWidth = Math.max(countWidth, this.font.width(line.count()));
        }
        ResourceListColumns.Layout columns = ResourceListColumns.of(width, haveWidth, breakdownWidth, countWidth);

        graphics.enableScissor(x, top, x + width, listBottom());
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            ResourceListManager.Row row = line.row();
            int rowY = top + i * ROW_HEIGHT;
            int textY = rowY + 6;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x30FFFFFF);
            }

            graphics.renderFakeItem(line.stack(), x + 4, rowY + 2);

            String name = fit(line.name(), columns.nameRoom());
            int nameX = x + ResourceListColumns.NAME_X;
            graphics.drawString(this.font, name, nameX, textY, row.complete() ? MUTED : TEXT, false);
            if (row.ticked()) {
                graphics.fill(nameX, rowY + 10, nameX + this.font.width(name), rowY + 11, MUTED);
            }

            drawRightAligned(graphics, line.have(), x + columns.haveRight(), textY, 0xFF9CA3AF);
            if (columns.breakdownShown()) {
                drawRightAligned(graphics, line.breakdown(), x + columns.breakdownRight(), textY, MUTED);
            }
            drawRightAligned(graphics, line.count(), x + columns.countRight(), textY,
                    row.complete() ? DONE : WANTED);
        }
        graphics.disableScissor();

        graphics.drawCenteredString(this.font, Component.translatable("simpleschematics.gui.resource.hint"),
                this.width / 2, hintY(), 0xFF6B7280);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRightAligned(GuiGraphics graphics, String text, int right, int y, int colour) {
        graphics.drawString(this.font, text, right - this.font.width(text), y, colour, false);
    }

    /**
     * Trims text to a width with an ellipsis. Measuring the ellipsis rather than
     * assuming it fits is what stops a trimmed name reaching the column beside it.
     */
    private String fit(String text, int room) {
        if (this.font.width(text) <= room) {
            return text;
        }
        int left = room - this.font.width("...");
        return left <= 0 ? "" : this.font.plainSubstrByWidth(text, left) + "...";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ResourceListManager.Row row = rowAt(mouseX, mouseY);
        if (row != null) {
            if (button == 1) {
                ResourceListManager.INSTANCE.setManual(row.item(), 0);
            } else {
                ResourceListManager.INSTANCE.toggleTick(row.item());
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (hasShiftDown()) {
            ResourceListManager.Row row = rowAt(mouseX, mouseY);
            if (row != null) {
                int step = hasControlDown() ? 64 : 1;
                ResourceListManager.INSTANCE.adjustManual(row.item(), delta > 0 ? step : -step);
                return true;
            }
        }
        scroll -= (int) Math.signum(delta);
        return true;
    }

    private ResourceListManager.Row rowAt(double mouseX, double mouseY) {
        int x = listX();
        int width = contentWidth();
        int top = listTop();
        if (mouseX < x || mouseX >= x + width || mouseY < top || mouseY >= listBottom()) {
            return null;
        }
        int index = (int) ((mouseY - top) / ROW_HEIGHT) + scroll;
        List<ResourceListManager.Row> rows = ResourceListManager.INSTANCE.rows();
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

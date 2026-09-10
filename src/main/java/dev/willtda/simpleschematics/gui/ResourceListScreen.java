package dev.willtda.simpleschematics.gui;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.resource.MaterialResolver;
import dev.willtda.simpleschematics.resource.ResourceListManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The full list, where you can correct anything the automatic counting got
 * wrong. Left click ticks a row off, right click clears it again, and scrolling
 * on a row nudges the stored amount up or down.
 */
public final class ResourceListScreen extends Screen {

    private static final int ROW_HEIGHT = 20;

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

        graphics.enableScissor(x, top, x + width, listBottom());
        for (int i = 0; i < rowCount && i + scroll < rows.size(); i++) {
            ResourceListManager.Row row = rows.get(i + scroll);
            int rowY = top + i * ROW_HEIGHT;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x30FFFFFF);
            }

            ItemStack stack = new ItemStack(row.item());
            graphics.renderFakeItem(stack, x + 4, rowY + 2);

            int nameColour = row.complete() ? 0xFF6B7280 : 0xFFE5E7EB;
            String name = ResourceListManager.itemName(row.item());
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name, width - 190),
                    x + 24, rowY + 6, nameColour, false);

            String have = row.have() + " / " + row.required();
            graphics.drawString(this.font, have, x + width - 168, rowY + 6, 0xFF9CA3AF, false);

            int missing = row.missing();
            String count = String.format("%,d", missing);
            String breakdown = MaterialResolver.stackBreakdown(missing, stack.getMaxStackSize());
            if (!breakdown.isEmpty()) {
                graphics.drawString(this.font, breakdown, x + width - 96, rowY + 6, 0xFF6B7280, false);
            }
            int countColour = row.complete() ? 0xFF6EE7B7 : 0xFFFBBF24;
            graphics.drawString(this.font, count, x + width - 8 - this.font.width(count), rowY + 6,
                    countColour, false);

            if (row.ticked()) {
                graphics.fill(x + 24, rowY + 10, x + width - 172, rowY + 11, 0xFF6B7280);
            }
        }
        graphics.disableScissor();

        graphics.drawCenteredString(this.font, Component.translatable("simpleschematics.gui.resource.hint"),
                this.width / 2, hintY(), 0xFF6B7280);

        super.render(graphics, mouseX, mouseY, partialTick);
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

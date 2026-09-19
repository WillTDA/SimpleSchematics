package dev.willtda.simpleschematics.gui;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.EditMode;
import dev.willtda.simpleschematics.client.Feedback;
import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.placement.PlacementManager;
import dev.willtda.simpleschematics.printing.PrintManager;
import dev.willtda.simpleschematics.render.WorldRenderer;
import dev.willtda.simpleschematics.resource.BuildListManager;
import dev.willtda.simpleschematics.resource.ResourceListManager;
import dev.willtda.simpleschematics.schematic.Schematic;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import dev.willtda.simpleschematics.util.DataPaths;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one screen you spend most of your time in: everything you have saved,
 * with a preview you can turn around, and the buttons that actually do
 * something with it.
 *
 * <p>Every coordinate comes out of the block of layout methods near the top,
 * worked out from the window that is actually there. At a large GUI scale the
 * whole screen is only a few hundred units across, so the rows are stacked from
 * the top down and the buttons from the bottom up, and the list takes whatever
 * is left. Below a certain width the preview has nowhere to live and the screen
 * collapses to a single column.</p>
 */
public final class LibraryScreen extends Screen {

    public enum Tab {
        LIBRARY, PLACEMENTS
    }

    private static final int ROW_HEIGHT = 26;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    /** Under this, a list and a preview side by side would both be unusable. */
    private static final int TWO_COLUMN_WIDTH = 420;
    private static final int MIN_PREVIEW_HEIGHT = 48;

    private final Screen parent;
    private Tab tab;

    private EditBox search;
    private final List<SchematicLibrary.Entry> filtered = new ArrayList<>();
    private int selectedIndex = -1;
    private int scroll;

    private SchematicPreview preview;
    private String previewKey;
    private boolean draggingPreview;

    private Button placeButton;
    private Button printButton;
    private Button convertButton;
    private Button deleteButton;
    private Button resourceButton;

    public LibraryScreen(Screen parent) {
        this(parent, Tab.LIBRARY);
    }

    public LibraryScreen(Screen parent, Tab tab) {
        super(Component.translatable("simpleschematics.gui.library.title"));
        this.parent = parent;
        this.tab = tab;
    }

    // ---- layout -----------------------------------------------------------

    private int margin() {
        return this.width < 360 ? 6 : 12;
    }

    private boolean compact() {
        return this.width < TWO_COLUMN_WIDTH;
    }

    private int titleY() {
        return margin() - 4;
    }

    private int tabsY() {
        return titleY() + this.font.lineHeight + 6;
    }

    private int searchY() {
        return tabsY() + BUTTON_HEIGHT + GAP;
    }

    private int searchHeight() {
        return 18;
    }

    private int listX() {
        return margin();
    }

    private int listWidth() {
        return compact()
                ? this.width - margin() * 2
                : Mth.clamp((int) (this.width * 0.42F), 150, 320);
    }

    private int listTop() {
        return tab == Tab.LIBRARY
                ? searchY() + searchHeight() + GAP
                : tabsY() + BUTTON_HEIGHT + GAP;
    }

    /**
     * How many rows of buttons sit along the bottom. Two columns can put the
     * left and right stacks side by side; one column has to stack all of them.
     */
    private int footerRows() {
        if (tab == Tab.PLACEMENTS) {
            // Print and Delete share a row, above navigation in the compact view.
            return compact() ? 2 : 1;
        }
        return compact() ? 3 : 2;
    }

    /** The placements list explains its right click, so it reserves a line. */
    private int hintHeight() {
        return tab == Tab.PLACEMENTS ? this.font.lineHeight + 4 : 0;
    }

    private int hintY() {
        return footerTop() - GAP - this.font.lineHeight;
    }

    private int footerTop() {
        int rows = footerRows();
        return this.height - margin() - rows * BUTTON_HEIGHT - (rows - 1) * GAP;
    }

    private int listBottom() {
        return Math.max(listTop() + ROW_HEIGHT, footerTop() - GAP - hintHeight());
    }

    private int detailsX() {
        return listX() + listWidth() + GAP * 2;
    }

    private int detailsWidth() {
        return this.width - detailsX() - margin();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT);
    }

    /** Width of one of a pair of buttons sharing a span. */
    private static int half(int span) {
        return (span - GAP) / 2;
    }

    @Override
    protected void init() {
        SchematicLibrary.INSTANCE.refresh();

        int tabWidth = Math.min(120, half(this.width - margin() * 2));
        addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.tab.library"),
                        b -> switchTab(Tab.LIBRARY))
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.tab.library")))
                .bounds(margin(), tabsY(), tabWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.tab.placements"),
                        b -> switchTab(Tab.PLACEMENTS))
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.tab.placements")))
                .bounds(margin() + tabWidth + GAP, tabsY(), tabWidth, BUTTON_HEIGHT).build());

        String previousQuery = search == null ? "" : search.getValue();
        search = new EditBox(this.font, listX() + 1, searchY(), listWidth() - 2, searchHeight(),
                Component.translatable("simpleschematics.gui.search"));
        search.setHint(Component.translatable("simpleschematics.gui.search"));
        search.setValue(previousQuery);
        search.setResponder(value -> {
            scroll = 0;
            rebuildFiltered();
        });
        addRenderableWidget(search);

        buildFooter();

        rebuildFiltered();
        switchTab(tab);
    }

    /**
     * The action buttons. In two columns the pair that acts on the selection
     * sits under the preview and the pair that acts on the screen sits under
     * the list; in one column they queue up above each other.
     */
    private void buildFooter() {
        int bottomRow = this.height - margin() - BUTTON_HEIGHT;

        int actionX = compact() ? margin() : detailsX();
        int actionSpan = compact() ? this.width - margin() * 2 : detailsWidth();
        int actionWidth = Math.min(150, half(actionSpan));
        // The footer is exactly as tall as footerRows() says, and the list stops
        // just above it, so starting here is what keeps the two from meeting.
        int actionRow = footerTop();

        printButton = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.print"),
                        b -> printSelected())
                .bounds(actionX, actionRow, actionWidth, BUTTON_HEIGHT).build());
        convertButton = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.convert"),
                        b -> convertSelected())
                .bounds(actionX, actionRow, actionWidth, BUTTON_HEIGHT).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.delete"),
                        b -> deleteSelected())
                .bounds(actionX + actionWidth + GAP, actionRow, actionWidth, BUTTON_HEIGHT).build());
        placeButton = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.place"),
                        b -> placeSelected())
                .bounds(actionX, actionRow + BUTTON_HEIGHT + GAP, actionWidth, BUTTON_HEIGHT).build());
        resourceButton = addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.resource_list"),
                        b -> openResourceList())
                .bounds(actionX + actionWidth + GAP, actionRow + BUTTON_HEIGHT + GAP, actionWidth, BUTTON_HEIGHT).build());

        int navSpan = compact() ? this.width - margin() * 2 : listWidth();
        int navWidth = Math.min(120, half(navSpan));
        addRenderableWidget(Button.builder(Component.translatable("simpleschematics.gui.open_folder"),
                        b -> Util.getPlatform().openFile(DataPaths.schematics().toFile()))
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.open_folder")))
                .bounds(margin(), bottomRow, navWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.done")))
                .bounds(margin() + navWidth + GAP, bottomRow, navWidth, BUTTON_HEIGHT).build());
    }

    /**
     * Greys out anything that would do nothing right now and says why on the
     * tooltip, so a dead button is never a mystery. Cheap enough to redo every
     * frame, which keeps it honest as the selection changes.
     */
    private void updateActionButtons() {
        if (tab == Tab.PLACEMENTS) {
            Placement selected = PlacementManager.INSTANCE.selected();
            boolean any = selected != null;
            SchematicLibrary.Entry entry = any ? SchematicLibrary.INSTANCE.byKey(selected.schematicKey()) : null;
            boolean loaded = entry != null && entry.get() != null;
            boolean enabled = ClientState.INSTANCE.isEnabled();
            boolean canBuild = this.minecraft.player != null && this.minecraft.gameMode != null
                    && (this.minecraft.gameMode.getPlayerMode() == GameType.CREATIVE
                    || this.minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL);
            printButton.active = any && loaded && enabled && canBuild;
            printButton.setTooltip(Tooltip.create(Component.translatable(
                    !any ? "simpleschematics.tip.print.none"
                            : !loaded ? "simpleschematics.tip.print.broken"
                            : !enabled ? "simpleschematics.tip.live.off"
                            : !canBuild ? "simpleschematics.tip.print.mode"
                            : "simpleschematics.tip.print.start")));
            deleteButton.active = any;
            deleteButton.setTooltip(Tooltip.create(Component.translatable(any
                    ? "simpleschematics.tip.delete.placement"
                    : "simpleschematics.tip.delete.no_placement")));
            return;
        }

        SchematicLibrary.Entry entry = selectedEntry();
        boolean picked = entry != null;
        boolean loaded = picked && entry.get() != null;

        placeButton.active = loaded;
        placeButton.setTooltip(Tooltip.create(Component.translatable(
                !picked ? "simpleschematics.tip.place.none"
                        : !loaded ? "simpleschematics.tip.place.broken"
                        : "simpleschematics.tip.place")));

        resourceButton.active = loaded;
        resourceButton.setTooltip(Tooltip.create(Component.translatable(
                !picked ? "simpleschematics.tip.resource_list.none"
                        : !loaded ? "simpleschematics.tip.resource_list.broken"
                        : "simpleschematics.tip.resource_list")));

        boolean convertible = picked && entry.litematic;
        convertButton.active = convertible;
        convertButton.setTooltip(Tooltip.create(Component.translatable(
                !picked ? "simpleschematics.tip.convert.none"
                        : !convertible ? "simpleschematics.tip.convert.native"
                        : "simpleschematics.tip.convert")));

        deleteButton.active = picked;
        deleteButton.setTooltip(Tooltip.create(Component.translatable(picked
                ? "simpleschematics.tip.delete"
                : "simpleschematics.tip.delete.none")));
    }

    private void switchTab(Tab next) {
        boolean changed = this.tab != next;
        this.tab = next;
        boolean library = next == Tab.LIBRARY;
        search.setVisible(library);
        placeButton.visible = library;
        printButton.visible = !library;
        convertButton.visible = library;
        resourceButton.visible = library;
        if (changed) {
            selectedIndex = -1;
            scroll = 0;
            closePreview();
        }
        // The footer is a different shape per tab, so it has to be laid out again.
        if (changed) {
            rebuildWidgets();
        }
    }

    private void rebuildFiltered() {
        filtered.clear();
        String query = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        for (SchematicLibrary.Entry entry : SchematicLibrary.INSTANCE.entries()) {
            if (query.isEmpty() || entry.displayName.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(entry);
            }
        }
        if (selectedIndex >= filtered.size()) {
            selectedIndex = -1;
            closePreview();
        }
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        updateActionButtons();
        graphics.drawString(this.font, this.title, margin(), titleY(), 0xFFFFFFFF, false);

        if (tab == Tab.LIBRARY) {
            renderLibrary(graphics, mouseX, mouseY, partialTick);
        } else {
            renderPlacements(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderLibrary(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = listX();
        int top = listTop();
        int width = listWidth();
        int bottom = listBottom();
        graphics.fill(x, top, x + width, bottom, 0x40000000);

        if (filtered.isEmpty()) {
            String message = Component.translatable(SchematicLibrary.INSTANCE.entries().isEmpty()
                    ? "simpleschematics.gui.empty"
                    : "simpleschematics.gui.no_match").getString();
            drawWrapped(graphics, message, x + 6, top + 6, width - 12, bottom, 0xFF9CA3AF);
        }

        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, filtered.size() - rows));

        graphics.enableScissor(x, top, x + width, bottom);
        for (int i = 0; i < rows && i + scroll < filtered.size(); i++) {
            int index = i + scroll;
            SchematicLibrary.Entry entry = filtered.get(index);
            int rowY = top + i * ROW_HEIGHT;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (index == selectedIndex) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x8060A5FA);
            } else if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x30FFFFFF);
            }
            String name = this.font.plainSubstrByWidth(entry.displayName, width - 16);
            graphics.drawString(this.font, name, x + 6, rowY + 4, 0xFFE5E7EB, false);
            String sub = (entry.litematic ? "litematic" : "sschem") + "  " + formatSize(entry.fileSize);
            graphics.drawString(this.font, sub, x + 6, rowY + 15, 0xFF9CA3AF, false);
        }
        graphics.disableScissor();

        drawScrollbar(graphics, x + width - 3, top, bottom, filtered.size(), rows);

        if (compact()) {
            return;
        }
        renderDetails(graphics, detailsX(), top, detailsWidth(), listBottom() - top, partialTick);
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int top, int bottom, int total, int rows) {
        if (total <= rows) {
            return;
        }
        int trackHeight = bottom - top;
        int barHeight = Math.max(16, trackHeight * rows / total);
        int barY = top + (trackHeight - barHeight) * scroll / Math.max(1, total - rows);
        graphics.fill(x, barY, x + 2, barY + barHeight, 0x80FFFFFF);
    }

    /**
     * Splits the panel into a preview box and a block of text underneath, giving
     * the text only the lines it can actually fit and the preview the rest.
     */
    private void renderDetails(GuiGraphics graphics, int x, int y, int width, int height, float partialTick) {
        SchematicLibrary.Entry entry = selectedEntry();
        if (entry == null) {
            graphics.fill(x, y, x + width, y + height, 0x40000000);
            drawWrapped(graphics, Component.translatable("simpleschematics.gui.pick_one").getString(),
                    x + 6, y + height / 2 - 4, width - 12, y + height, 0xFF9CA3AF);
            return;
        }

        Schematic schematic = entry.get();
        if (schematic == null) {
            graphics.fill(x, y, x + width, y + height, 0x40000000);
            int textY = drawWrapped(graphics, Component.translatable("simpleschematics.gui.load_failed").getString(),
                    x + 6, y + height / 2 - 12, width - 12, y + height, 0xFFF87272);
            if (entry.loadError() != null) {
                drawWrapped(graphics, entry.loadError(), x + 6, textY + 2, width - 12, y + height, 0xFF9CA3AF);
            }
            return;
        }

        ensurePreview(entry, schematic);
        List<String> lines = detailLines(schematic);
        int lineStep = this.font.lineHeight + 2;
        int textHeight = lines.size() * lineStep;
        // The preview keeps whatever the text does not need, and never less
        // than a box worth looking at; the text is trimmed to suit if it must be.
        int previewHeight = height - textHeight - GAP;
        while (previewHeight < MIN_PREVIEW_HEIGHT && lines.size() > 1) {
            lines.remove(lines.size() - 1);
            textHeight = lines.size() * lineStep;
            previewHeight = height - textHeight - GAP;
        }
        previewHeight = Math.max(0, previewHeight);

        preview.render(graphics, x, y, width, previewHeight, partialTick);

        int textY = y + previewHeight + GAP;
        for (int i = 0; i < lines.size(); i++) {
            int colour = i == 0 ? 0xFFFFFFFF : (i == lines.size() - 1 ? 0xFF6B7280 : 0xFF9CA3AF);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(lines.get(i), width),
                    x, textY + i * lineStep, colour, false);
        }
    }

    private List<String> detailLines(Schematic schematic) {
        List<String> lines = new ArrayList<>();
        lines.add(schematic.meta().name);
        lines.add(schematic.width() + " x " + schematic.height() + " x " + schematic.length());
        lines.add(Component.translatable("simpleschematics.gui.blocks",
                String.format("%,d", schematic.blockCount())).getString());
        if (!schematic.meta().author.isBlank()) {
            lines.add(Component.translatable("simpleschematics.gui.by", schematic.meta().author).getString());
        }
        if (preview != null && preview.interactive()) {
            lines.add(Component.translatable("simpleschematics.gui.drag_hint").getString());
        }
        return lines;
    }

    /** Wraps to the given width and stops at the bottom rather than spilling. */
    private int drawWrapped(GuiGraphics graphics, String text, int x, int y, int width, int bottom, int colour) {
        int cursor = y;
        for (var line : this.font.split(Component.literal(text), Math.max(16, width))) {
            if (cursor + this.font.lineHeight > bottom) {
                break;
            }
            graphics.drawString(this.font, line, x, cursor, colour, false);
            cursor += this.font.lineHeight + 1;
        }
        return cursor;
    }

    private void renderPlacements(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Placement> placements = PlacementManager.INSTANCE.current();
        int x = listX();
        int top = listTop();
        int width = this.width - margin() * 2;
        int bottom = listBottom();
        graphics.fill(x, top, x + width, bottom, 0x40000000);

        if (placements.isEmpty()) {
            drawWrapped(graphics, Component.translatable("simpleschematics.gui.no_placements").getString(),
                    x + 6, top + 6, width - 12, bottom, 0xFF9CA3AF);
            return;
        }


        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, placements.size() - rows));

        graphics.enableScissor(x, top, x + width, bottom);
        for (int i = 0; i < rows && i + scroll < placements.size(); i++) {
            int index = i + scroll;
            Placement placement = placements.get(index);
            int rowY = top + i * ROW_HEIGHT;
            boolean selected = index == PlacementManager.INSTANCE.selectedIndex();
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selected) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x8060A5FA);
            } else if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x30FFFFFF);
            }

            String hint = Component.translatable(placement.visible()
                    ? "simpleschematics.gui.shown" : "simpleschematics.gui.hidden").getString();
            // A build the corner list is following is worth spotting at a glance.
            String following = placement.resourceList()
                    ? Component.translatable("simpleschematics.gui.following").getString() : "";
            int followingWidth = following.isEmpty() ? 0 : this.font.width(following) + 8;
            int hintWidth = this.font.width(hint) + 12 + followingWidth;
            int textWidth = Math.max(24, width - 12 - hintWidth);

            graphics.drawString(this.font, this.font.plainSubstrByWidth(placement.displayName(), textWidth),
                    x + 6, rowY + 4, placement.visible() ? 0xFFE5E7EB : 0xFF6B7280, false);
            String detail = placement.origin().getX() + ", " + placement.origin().getY() + ", "
                    + placement.origin().getZ() + "   " + placement.rotation().name();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(detail, textWidth),
                    x + 6, rowY + 15, 0xFF9CA3AF, false);
            graphics.drawString(this.font, hint, x + width - 6 - this.font.width(hint), rowY + 9,
                    placement.visible() ? 0xFF6EE7B7 : 0xFF6B7280, false);
            if (!following.isEmpty()) {
                graphics.drawString(this.font, following,
                        x + width - 6 - this.font.width(hint) - followingWidth, rowY + 9, 0xFFFBBF24, false);
            }
        }
        graphics.disableScissor();

        drawScrollbar(graphics, x + width - 3, top, bottom, placements.size(), rows);

        graphics.drawCenteredString(this.font, Component.translatable("simpleschematics.gui.placements_hint"),
                this.width / 2, hintY(), 0xFF6B7280);
    }

    /** Bytes the way a file manager would put it, so a small file is not "0 kB". */
    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f kB", bytes / 1024.0D);
        }
        return String.format("%.1f MB", bytes / (1024.0D * 1024.0D));
    }

    // ---- interaction ------------------------------------------------------

    private boolean overList(double mouseX, double mouseY) {
        int width = tab == Tab.LIBRARY ? listWidth() : this.width - margin() * 2;
        return mouseX >= listX() && mouseX < listX() + width
                && mouseY >= listTop() && mouseY < listBottom();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == Tab.LIBRARY && preview != null && preview.contains(mouseX, mouseY)) {
            if (button == 1) {
                preview.resetView();
            } else {
                draggingPreview = true;
                preview.beginDrag();
            }
            return true;
        }

        if (overList(mouseX, mouseY)) {
            int row = (int) ((mouseY - listTop()) / ROW_HEIGHT) + scroll;
            if (tab == Tab.LIBRARY) {
                if (row >= 0 && row < filtered.size()) {
                    if (selectedIndex == row && button == 0) {
                        placeSelected();
                    } else {
                        selectedIndex = row;
                        closePreview();
                    }
                    return true;
                }
            } else {
                List<Placement> placements = PlacementManager.INSTANCE.current();
                if (row >= 0 && row < placements.size()) {
                    if (button == 1) {
                        placements.get(row).toggleVisible();
                        PlacementManager.INSTANCE.markDirty();
                    } else {
                        PlacementManager.INSTANCE.select(row);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingPreview && preview != null) {
            // Pulling down tips the model down, the way a real turntable would.
            preview.drag((float) dragX, (float) dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingPreview) {
            draggingPreview = false;
            if (preview != null) {
                // Letting go mid flick leaves it coasting.
                preview.endDrag();
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tab == Tab.LIBRARY && preview != null && preview.contains(mouseX, mouseY)) {
            preview.zoom((float) delta);
            return true;
        }
        if (overList(mouseX, mouseY)) {
            scroll -= (int) Math.signum(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        if (tab == Tab.PLACEMENTS && keyCode == 261) {
            Placement placement = PlacementManager.INSTANCE.selected();
            if (placement != null) {
                PlacementManager.INSTANCE.remove(placement);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- actions ----------------------------------------------------------

    private SchematicLibrary.Entry selectedEntry() {
        if (selectedIndex < 0 || selectedIndex >= filtered.size()) {
            return null;
        }
        return filtered.get(selectedIndex);
    }

    private void placeSelected() {
        SchematicLibrary.Entry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        if (entry.get() == null) {
            Feedback.error(Component.translatable("simpleschematics.gui.load_failed"));
            return;
        }
        ClientState.INSTANCE.setMode(EditMode.BUILD);
        ClientState.INSTANCE.setPendingSchematicKey(entry.key());
        onClose();
        Feedback.info(Component.translatable("simpleschematics.feedback.point_and_place"));
    }

    private void printSelected() {
        if (PlacementManager.INSTANCE.selected() == null || !ClientState.INSTANCE.isEnabled()) {
            return;
        }
        ClientState.INSTANCE.cancelPending();
        ClientState.INSTANCE.setMode(EditMode.PRINT);
        closePreview();
        this.minecraft.setScreen(null);
        PrintManager.INSTANCE.requestPrint();
    }

    private void convertSelected() {
        SchematicLibrary.Entry entry = selectedEntry();
        if (entry == null || !entry.litematic) {
            Feedback.info(Component.translatable("simpleschematics.gui.convert_only_litematic"));
            return;
        }
        try {
            SchematicLibrary.INSTANCE.convertToNative(entry);
            rebuildFiltered();
            Feedback.success(Component.translatable("simpleschematics.feedback.converted"));
        } catch (Exception e) {
            Feedback.error(Component.literal(String.valueOf(e.getMessage())));
        }
    }

    private void deleteSelected() {
        if (tab == Tab.PLACEMENTS) {
            Placement placement = PlacementManager.INSTANCE.selected();
            if (placement != null) {
                PlacementManager.INSTANCE.remove(placement);
                WorldRenderer.pruneCache();
            }
            return;
        }
        SchematicLibrary.Entry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        if (SchematicLibrary.INSTANCE.delete(entry)) {
            WorldRenderer.invalidate(entry.key());
            ResourceListManager.INSTANCE.invalidate(entry.key());
            BuildListManager.INSTANCE.invalidate();
            selectedIndex = -1;
            closePreview();
            rebuildFiltered();
            Feedback.success(Component.translatable("simpleschematics.feedback.deleted"));
        }
    }

    /**
     * Reads the selected schematic's list without picking it up. Putting it on
     * the crosshair to do this left a ghost following you out of the screen,
     * and moved the corner lists off the build you were working on.
     */
    private void openResourceList() {
        SchematicLibrary.Entry entry = selectedEntry();
        if (entry == null || entry.get() == null) {
            return;
        }
        this.minecraft.setScreen(new ResourceListScreen(this, entry.key()));
    }

    private void ensurePreview(SchematicLibrary.Entry entry, Schematic schematic) {
        if (preview == null || !entry.key().equals(previewKey)) {
            closePreview();
            preview = new SchematicPreview(entry.key(), schematic);
            previewKey = entry.key();
        }
    }

    private void closePreview() {
        draggingPreview = false;
        if (preview != null) {
            preview.close();
            preview = null;
            previewKey = null;
        }
    }

    @Override
    public void removed() {
        closePreview();
    }

    @Override
    public void onClose() {
        closePreview();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

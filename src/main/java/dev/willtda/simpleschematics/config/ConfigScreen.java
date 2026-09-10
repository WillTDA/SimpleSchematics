package dev.willtda.simpleschematics.config;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.client.Feedback;
import dev.willtda.simpleschematics.render.WorldRenderer;
import dev.willtda.simpleschematics.util.DataPaths;
import dev.willtda.simpleschematics.util.DataTransfer;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * All of the settings in one scrolling list.
 *
 * <p>Reached from the Config button next to Simple Schematics in the Mods list.
 * The layout is derived from the window size every time it opens, so it works
 * at any GUI scale rather than assuming a fixed width.</p>
 */
public final class ConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private String notice;
    private long noticeUntil;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("simpleschematics.config.title"));
        this.parent = parent;
    }

    private record Row(String labelKey, AbstractWidget widget, boolean heading) {
    }

    private int contentWidth() {
        return Math.max(80, Math.min(this.width - 24, 420));
    }

    private int contentX() {
        return (this.width - contentWidth()) / 2;
    }

    private int listTop() {
        return 40;
    }

    /**
     * The list stops above the notice, and the notice above the buttons. All
     * three used to share the same strip, which is why the notice read through
     * the button row.
     */
    private int listBottom() {
        return Math.max(listTop() + ROW_HEIGHT, noticeY() - 4);
    }

    private int buttonY() {
        return this.height - 28;
    }

    private int noticeY() {
        return buttonY() - 6 - this.font.lineHeight;
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_HEIGHT - (listBottom() - listTop()));
    }

    @Override
    protected void init() {
        rows.clear();
        SSConfig c = SSConfig.INSTANCE;
        int w = contentWidth();
        int fieldWidth = Math.max(36, w / 2 - 8);
        int x = contentX() + w - fieldWidth;

        heading("simpleschematics.config.section.general");
        text("simpleschematics.config.toolItem", x, fieldWidth, c.toolItem);
        toggle("simpleschematics.config.enabledOnLaunch", x, fieldWidth, c.enabledOnLaunch);
        toggle("simpleschematics.config.toolRequiredForHotkeys", x, fieldWidth, c.toolRequiredForHotkeys);
        toggle("simpleschematics.config.actionBarFeedback", x, fieldWidth, c.actionBarFeedback);
        toggle("simpleschematics.config.invertScroll", x, fieldWidth, c.invertScroll);
        intSlider("simpleschematics.config.maxSelectionReach", x, fieldWidth, c.maxSelectionReach, 8, 512);
        text("simpleschematics.config.dataDirectory", x, fieldWidth, c.dataDirectory);

        heading("simpleschematics.config.section.scan");
        text("simpleschematics.config.startCornerColour", x, fieldWidth, c.startCornerColour);
        text("simpleschematics.config.endCornerColour", x, fieldWidth, c.endCornerColour);
        text("simpleschematics.config.selectionBoxColour", x, fieldWidth, c.selectionBoxColour);
        doubleSlider("simpleschematics.config.selectionFillOpacity", x, fieldWidth, c.selectionFillOpacity, 0.0, 0.6);
        toggle("simpleschematics.config.swapScanCorners", x, fieldWidth, c.swapScanCorners);
        toggle("simpleschematics.config.showTargetBlockOutline", x, fieldWidth, c.showTargetBlockOutline);
        toggle("simpleschematics.config.saveEntitiesByDefault", x, fieldWidth, c.saveEntitiesByDefault);
        toggle("simpleschematics.config.saveContainerContentsByDefault", x, fieldWidth, c.saveContainerContentsByDefault);

        heading("simpleschematics.config.section.build");
        doubleSlider("simpleschematics.config.hologramOpacity", x, fieldWidth, c.hologramOpacity, 0.0, 1.0);
        toggle("simpleschematics.config.hologramOutline", x, fieldWidth, c.hologramOutline);
        toggle("simpleschematics.config.hologramBlockOutline", x, fieldWidth, c.hologramBlockOutline);
        text("simpleschematics.config.hologramBlockOutlineColour", x, fieldWidth, c.hologramBlockOutlineColour);
        doubleSlider("simpleschematics.config.hologramBlockOutlineOpacity", x, fieldWidth,
                c.hologramBlockOutlineOpacity, 0.0, 1.0);
        intSlider("simpleschematics.config.hologramBlockOutlineDistance", x, fieldWidth,
                c.hologramBlockOutlineDistance, 4, 64);
        toggle("simpleschematics.config.hologramNearFade", x, fieldWidth, c.hologramNearFade);
        doubleSlider("simpleschematics.config.hologramFadeDistance", x, fieldWidth, c.hologramFadeDistance, 0.5, 8.0);
        action("simpleschematics.config.resetAppearance", x, fieldWidth, "simpleschematics.config.reset", () -> {
            c.hologramOpacity.set(0.35D);
            c.hologramOutline.set(false);
            c.hologramBlockOutline.set(false);
            c.hologramBlockOutlineColour.set("FFFFFF");
            c.hologramBlockOutlineOpacity.set(0.30D);
            c.hologramBlockOutlineDistance.set(24);
            c.hologramNearFade.set(true);
            c.hologramFadeDistance.set(2.0D);
            c.showTargetBlockOutline.set(false);
            SSConfig.SPEC.save();
            WorldRenderer.invalidateAll();
            rebuildWidgets();
        });
        intSlider("simpleschematics.config.hologramRenderDistance", x, fieldWidth, c.hologramRenderDistance, 32, 512);
        toggle("simpleschematics.config.layerScrollSound", x, fieldWidth, c.layerScrollSound);
        doubleSlider("simpleschematics.config.layerScrollVolume", x, fieldWidth, c.layerScrollVolume, 0.0, 1.0);
        toggle("simpleschematics.config.snapPlacementToGrid", x, fieldWidth, c.snapPlacementToGrid);

        heading("simpleschematics.config.section.highlight");
        toggle("simpleschematics.config.highlightMismatches", x, fieldWidth, c.highlightMismatches);
        toggle("simpleschematics.config.highlightExtraBlocks", x, fieldWidth, c.highlightExtraBlocks);
        toggle("simpleschematics.config.hideCorrectBlocks", x, fieldWidth, c.hideCorrectBlocks);
        toggle("simpleschematics.config.strictStateMatch", x, fieldWidth, c.strictStateMatch);
        text("simpleschematics.config.mismatchColour", x, fieldWidth, c.mismatchColour);
        text("simpleschematics.config.extraBlockColour", x, fieldWidth, c.extraBlockColour);
        doubleSlider("simpleschematics.config.highlightFillOpacity", x, fieldWidth, c.highlightFillOpacity, 0.0, 0.8);
        intSlider("simpleschematics.config.maxHighlights", x, fieldWidth, c.maxHighlights, 64, 100000);
        intSlider("simpleschematics.config.verifyBlocksPerTick", x, fieldWidth, c.verifyBlocksPerTick, 500, 200000);

        heading("simpleschematics.config.section.resource_list");
        toggle("simpleschematics.config.resourceListEnabled", x, fieldWidth, c.resourceListEnabled);
        anchor("simpleschematics.config.resourceListAnchor", x, fieldWidth, c.resourceListAnchor);
        intSlider("simpleschematics.config.resourceListOffsetX", x, fieldWidth, c.resourceListOffsetX, 0, 200);
        intSlider("simpleschematics.config.resourceListOffsetY", x, fieldWidth, c.resourceListOffsetY, 0, 200);
        doubleSlider("simpleschematics.config.resourceListScale", x, fieldWidth, c.resourceListScale, 0.4, 2.0);
        intSlider("simpleschematics.config.resourceListWidth", x, fieldWidth, c.resourceListWidth, 90, 400);
        intSlider("simpleschematics.config.resourceListMaxRows", x, fieldWidth, c.resourceListMaxRows, 1, 40);
        doubleSlider("simpleschematics.config.resourceListBackgroundOpacity", x, fieldWidth,
                c.resourceListBackgroundOpacity, 0.0, 1.0);
        toggle("simpleschematics.config.removeCollectedItems", x, fieldWidth, c.removeCollectedItems);
        toggle("simpleschematics.config.countOpenContainers", x, fieldWidth, c.countOpenContainers);
        toggle("simpleschematics.config.countEnderChest", x, fieldWidth, c.countEnderChest);
        toggle("simpleschematics.config.showStackBreakdown", x, fieldWidth, c.showStackBreakdown);
        toggle("simpleschematics.config.hideCompletedRows", x, fieldWidth, c.hideCompletedRows);

        heading("simpleschematics.config.section.data");
        action("simpleschematics.config.openFolder", x, fieldWidth, "simpleschematics.config.open",
                () -> Util.getPlatform().openFile(DataPaths.root().toFile()));
        action("simpleschematics.config.exportData", x, fieldWidth, "simpleschematics.config.export", () -> {
            try {
                Path file = DataTransfer.export();
                notify(Component.translatable("simpleschematics.config.exported",
                        file.getFileName().toString()).getString());
                Util.getPlatform().openFile(file.getParent().toFile());
            } catch (Exception e) {
                notify(String.valueOf(e.getMessage()));
            }
        });
        action("simpleschematics.config.importData", x, fieldWidth, "simpleschematics.config.import",
                () -> notify(Component.translatable("simpleschematics.config.import_hint").getString()));

        int buttonWidth = Math.min(120, contentWidth() / 2 - 4);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.done")))
                .bounds(this.width / 2 - buttonWidth - 2, buttonY(), buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("simpleschematics.config.reset_view"),
                        b -> {
                            WorldRenderer.invalidateAll();
                            notify(Component.translatable("simpleschematics.config.view_reset").getString());
                        })
                .tooltip(Tooltip.create(Component.translatable("simpleschematics.tip.rebuild")))
                .bounds(this.width / 2 + 2, buttonY(), buttonWidth, 20).build());
    }

    // ---- row builders -----------------------------------------------------

    private void heading(String key) {
        rows.add(new Row(key, null, true));
    }

    private void toggle(String key, int x, int width, ForgeConfigSpec.BooleanValue value) {
        Button button = Button.builder(state(value.get()), b -> {
            value.set(!value.get());
            SSConfig.SPEC.save();
            b.setMessage(state(value.get()));
        }).bounds(x, 0, width, 20).build();
        rows.add(new Row(key, addWidget(button), false));
    }

    private void anchor(String key, int x, int width, ForgeConfigSpec.EnumValue<SSConfig.Anchor> value) {
        Button button = Button.builder(Component.literal(pretty(value.get().name())), b -> {
            SSConfig.Anchor[] all = SSConfig.Anchor.values();
            value.set(all[(value.get().ordinal() + 1) % all.length]);
            SSConfig.SPEC.save();
            b.setMessage(Component.literal(pretty(value.get().name())));
        }).bounds(x, 0, width, 20).build();
        rows.add(new Row(key, addWidget(button), false));
    }

    private void text(String key, int x, int width, ForgeConfigSpec.ConfigValue<String> value) {
        EditBox box = new EditBox(this.font, x, 0, width, 20, Component.translatable(key));
        box.setMaxLength(256);
        box.setValue(value.get());
        box.setResponder(v -> {
            value.set(v);
            SSConfig.SPEC.save();
        });
        rows.add(new Row(key, addWidget(box), false));
    }

    private void intSlider(String key, int x, int width, ForgeConfigSpec.IntValue cfg, int min, int max) {
        AbstractSliderButton slider = new AbstractSliderButton(x, 0, width, 20,
                Component.literal(String.valueOf(cfg.get())),
                ((double) cfg.get() - min) / (double) (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.valueOf(min + (int) Math.round(this.value * (max - min)))));
            }

            @Override
            protected void applyValue() {
                cfg.set(min + (int) Math.round(this.value * (max - min)));
                SSConfig.SPEC.save();
            }
        };
        rows.add(new Row(key, addWidget(slider), false));
    }

    private void doubleSlider(String key, int x, int width, ForgeConfigSpec.DoubleValue cfg,
                              double min, double max) {
        AbstractSliderButton slider = new AbstractSliderButton(x, 0, width, 20,
                Component.literal(String.format("%.2f", cfg.get())),
                (cfg.get() - min) / (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.format("%.2f", min + this.value * (max - min))));
            }

            @Override
            protected void applyValue() {
                cfg.set(min + this.value * (max - min));
                SSConfig.SPEC.save();
            }
        };
        rows.add(new Row(key, addWidget(slider), false));
    }

    private void action(String key, int x, int width, String buttonKey, Runnable action) {
        Button button = Button.builder(Component.translatable(buttonKey), b -> {
            try {
                action.run();
            } catch (Exception e) {
                SimpleSchematics.LOG.error("A config action failed", e);
            }
        }).bounds(x, 0, width, 20).build();
        rows.add(new Row(key, addWidget(button), false));
    }

    private <T extends AbstractWidget> T addWidget(T widget) {
        super.addWidget(widget);
        return widget;
    }

    private static Component state(boolean on) {
        return Component.translatable(on ? "simpleschematics.state.on" : "simpleschematics.state.off");
    }

    private static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private void notify(String message) {
        this.notice = message;
        this.noticeUntil = System.currentTimeMillis() + 6000L;
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        scroll = Mth.clamp(scroll, 0, maxScroll());

        int top = listTop();
        int bottom = listBottom();
        int x = contentX();

        // park every widget at its scrolled position and hide anything off screen
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.widget() == null) {
                continue;
            }
            int y = top + i * ROW_HEIGHT - scroll;
            row.widget().setY(y + 2);
            row.widget().visible = y + ROW_HEIGHT > top && y < bottom;
            row.widget().active = y + 2 >= top && y + 22 <= bottom;
        }

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        graphics.fill(x - 4, top, x + contentWidth() + 4, bottom, 0x30000000);

        graphics.enableScissor(0, top, this.width, bottom);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = top + i * ROW_HEIGHT - scroll;
            if (y + ROW_HEIGHT <= top || y >= bottom) {
                continue;
            }
            if (row.heading()) {
                graphics.drawString(this.font, Component.translatable(row.labelKey()).getString().toUpperCase(java.util.Locale.ROOT),
                        x, y + 8, 0xFF60A5FA, false);
                graphics.fill(x, y + 19, x + contentWidth(), y + 20, 0x3060A5FA);
            } else {
                int labelWidth = contentWidth() - (row.widget() == null ? 0 : row.widget().getWidth()) - 12;
                String label = this.font.plainSubstrByWidth(
                        Component.translatable(row.labelKey()).getString(), Math.max(20, labelWidth));
                graphics.drawString(this.font, label, x, y + 8, 0xFFE5E7EB, false);
                row.widget().render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();

        super.render(graphics, mouseX, mouseY, partialTick);

        if (maxScroll() > 0) {
            int trackHeight = bottom - top;
            int barHeight = Math.max(20, trackHeight * trackHeight / (rows.size() * ROW_HEIGHT));
            int barY = top + (trackHeight - barHeight) * scroll / maxScroll();
            graphics.fill(x + contentWidth() + 6, barY, x + contentWidth() + 9, barY + barHeight, 0x80FFFFFF);
        }

        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(notice, this.width - 20),
                    this.width / 2, noticeY(), 0xFFFBBF24);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (super.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        scroll -= (int) (delta * ROW_HEIGHT);
        return true;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        int adopted = 0;
        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            try {
                if (name.endsWith(".zip")) {
                    adopted += DataTransfer.importFrom(file);
                } else if (name.endsWith(".litematic") || name.endsWith(".sschem")) {
                    DataTransfer.adopt(file);
                    adopted++;
                }
            } catch (Exception e) {
                SimpleSchematics.LOG.error("Could not import {}", file, e);
            }
        }
        if (adopted > 0) {
            dev.willtda.simpleschematics.schematic.SchematicLibrary.INSTANCE.refresh();
            notify(Component.translatable("simpleschematics.config.imported", adopted).getString());
            Feedback.success(Component.translatable("simpleschematics.config.imported", adopted));
        }
    }

    @Override
    public void onClose() {
        SSConfig.SPEC.save();
        WorldRenderer.invalidateAll();
        this.minecraft.setScreen(parent);
    }
}

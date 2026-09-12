package dev.willtda.simpleschematics.config;

import dev.willtda.simpleschematics.SimpleSchematics;
import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.Feedback;
import dev.willtda.simpleschematics.render.SchematicVerifier;
import dev.willtda.simpleschematics.render.ShaderPackCompat;
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
import java.util.function.BooleanSupplier;

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

    /**
     * A row of the list. {@code usable} is how a row that depends on the live
     * state says it cannot do anything right now, and is asked every frame
     * because the mode and the selected placement can both change underneath
     * an open screen.
     */
    private record Row(String labelKey, AbstractWidget widget, boolean heading, BooleanSupplier usable) {
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

        ClientState live = ClientState.INSTANCE;
        heading("simpleschematics.config.section.toggles");
        // Switching the mod off greys out everything below it, so the rows are
        // rebuilt rather than left explaining themselves out of date.
        liveToggle("simpleschematics.config.modEnabled", x, fieldWidth,
                live::isEnabled, () -> {
                    live.toggleEnabled();
                    rebuildWidgets();
                },
                "simpleschematics.tip.live.mod", null, null);
        modeRow("simpleschematics.config.mode", x, fieldWidth);
        liveToggle("simpleschematics.config.renderHolograms", x, fieldWidth,
                live::renderHolograms, live::toggleRenderHolograms,
                "simpleschematics.tip.live.holograms", live::isEnabled, "simpleschematics.tip.live.off");
        liveToggle("simpleschematics.config.resourceListVisible", x, fieldWidth,
                live::resourceListVisible, live::toggleResourceList,
                "simpleschematics.tip.live.resource_list",
                () -> live.isEnabled() && live.targetSchematicKey() != null,
                live.isEnabled()
                        ? "simpleschematics.tip.live.resource_list.none"
                        : "simpleschematics.tip.live.off");
        liveToggle("simpleschematics.config.buildListVisible", x, fieldWidth,
                live::buildListVisible, live::toggleBuildList,
                "simpleschematics.tip.live.build_list",
                () -> live.isEnabled() && live.targetSchematicKey() != null,
                live.isEnabled()
                        ? "simpleschematics.tip.live.build_list.none"
                        : "simpleschematics.tip.live.off");
        liveToggle("simpleschematics.config.highlightVisible", x, fieldWidth,
                SchematicVerifier.INSTANCE::isEnabled, SchematicVerifier.INSTANCE::toggle,
                "simpleschematics.tip.live.highlight", live::isEnabled, "simpleschematics.tip.live.off");

        heading("simpleschematics.config.section.general");
        text("simpleschematics.config.toolItem", x, fieldWidth, c.toolItem);
        toggle("simpleschematics.config.enabledOnLaunch", x, fieldWidth, c.enabledOnLaunch);
        toggle("simpleschematics.config.toolRequiredForHotkeys", x, fieldWidth, c.toolRequiredForHotkeys);
        toggle("simpleschematics.config.actionBarFeedback", x, fieldWidth, c.actionBarFeedback);
        toggle("simpleschematics.config.invertScroll", x, fieldWidth, c.invertScroll);
        toggle("simpleschematics.config.menuKeyBlocksOtherMods", x, fieldWidth, c.menuKeyBlocksOtherMods,
                "simpleschematics.tip.menu_key");
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
        toggle("simpleschematics.config.hologramBreathe", x, fieldWidth, c.hologramBreathe,
                "simpleschematics.tip.breathe");
        doubleSlider("simpleschematics.config.hologramBreatheDepth", x, fieldWidth, c.hologramBreatheDepth, 0.1, 1.0);
        doubleSlider("simpleschematics.config.hologramBreathePeriod", x, fieldWidth, c.hologramBreathePeriod, 0.5, 10.0);
        action("simpleschematics.config.resetAppearance", x, fieldWidth, "simpleschematics.config.reset", () -> {
            // the defaults from SSConfig, kept in step by hand
            c.hologramOpacity.set(0.65D);
            c.hologramOutline.set(false);
            c.hologramBlockOutline.set(true);
            c.hologramBlockOutlineColour.set("FFFFFF");
            c.hologramBlockOutlineOpacity.set(0.20D);
            c.hologramBlockOutlineDistance.set(4);
            c.hologramNearFade.set(true);
            c.hologramFadeDistance.set(2.0D);
            c.hologramBreathe.set(true);
            c.hologramBreatheDepth.set(0.6D);
            c.hologramBreathePeriod.set(1.5D);
            c.showTargetBlockOutline.set(true);
            SSConfig.SPEC.save();
            WorldRenderer.invalidateAll();
            rebuildWidgets();
        });
        intSlider("simpleschematics.config.hologramRenderDistance", x, fieldWidth, c.hologramRenderDistance, 32, 512);
        toggle("simpleschematics.config.layerScrollSound", x, fieldWidth, c.layerScrollSound);
        doubleSlider("simpleschematics.config.layerScrollVolume", x, fieldWidth, c.layerScrollVolume, 0.0, 1.0);
        toggle("simpleschematics.config.snapPlacementToGrid", x, fieldWidth, c.snapPlacementToGrid);
        toggle("simpleschematics.config.autoSelectLookedAt", x, fieldWidth, c.autoSelectLookedAt);
        choice("simpleschematics.config.shaderPackCompat", x, fieldWidth, c.shaderPackCompat,
                ShaderPackCompat.Mode.values(), ShaderPackCompat.shaderModPresent()
                        ? "simpleschematics.tip.shader_pack"
                        : "simpleschematics.tip.shader_pack.none");

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
        choice("simpleschematics.config.resourceListAnchor", x, fieldWidth, c.resourceListAnchor, SSConfig.Anchor.values());
        intSlider("simpleschematics.config.resourceListOffsetX", x, fieldWidth, c.resourceListOffsetX, 0, 200);
        intSlider("simpleschematics.config.resourceListOffsetY", x, fieldWidth, c.resourceListOffsetY, 0, 200);
        doubleSlider("simpleschematics.config.resourceListScale", x, fieldWidth, c.resourceListScale, 0.4, 2.0);
        intSlider("simpleschematics.config.resourceListMaxWidth", x, fieldWidth, c.resourceListMaxWidth, 120, 500);
        intSlider("simpleschematics.config.resourceListMaxRows", x, fieldWidth, c.resourceListMaxRows, 1, 40);
        doubleSlider("simpleschematics.config.resourceListBackgroundOpacity", x, fieldWidth,
                c.resourceListBackgroundOpacity, 0.0, 1.0);
        toggle("simpleschematics.config.removeCollectedItems", x, fieldWidth, c.removeCollectedItems);
        toggle("simpleschematics.config.countOpenContainers", x, fieldWidth, c.countOpenContainers);
        toggle("simpleschematics.config.countEnderChest", x, fieldWidth, c.countEnderChest);
        toggle("simpleschematics.config.showStackBreakdown", x, fieldWidth, c.showStackBreakdown);
        toggle("simpleschematics.config.hideCompletedRows", x, fieldWidth, c.hideCompletedRows);
        toggle("simpleschematics.config.countPlacedBlocks", x, fieldWidth, c.countPlacedBlocks,
                "simpleschematics.tip.count_placed");
        toggle("simpleschematics.config.showBuildName", x, fieldWidth, c.showBuildName);

        heading("simpleschematics.config.section.build_list");
        toggle("simpleschematics.config.buildListEnabled", x, fieldWidth, c.buildListEnabled,
                "simpleschematics.tip.build_list");
        choice("simpleschematics.config.buildListAnchor", x, fieldWidth, c.buildListAnchor, SSConfig.Anchor.values(),
                "simpleschematics.tip.build_list.anchor");
        intSlider("simpleschematics.config.buildListOffsetX", x, fieldWidth, c.buildListOffsetX, 0, 200);
        intSlider("simpleschematics.config.buildListOffsetY", x, fieldWidth, c.buildListOffsetY, 0, 200);
        intSlider("simpleschematics.config.buildListMaxRows", x, fieldWidth, c.buildListMaxRows, 1, 40);

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
                () -> importFiles(DataTransfer.pickFiles()), "simpleschematics.tip.import");

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
        rows.add(new Row(key, null, true, null));
    }

    private void toggle(String key, int x, int width, ForgeConfigSpec.BooleanValue value) {
        toggle(key, x, width, value, null);
    }

    private void toggle(String key, int x, int width, ForgeConfigSpec.BooleanValue value, String tipKey) {
        Button button = Button.builder(state(value.get()), b -> {
            value.set(!value.get());
            SSConfig.SPEC.save();
            b.setMessage(state(value.get()));
        }).bounds(x, 0, width, 20).build();
        if (tipKey != null) {
            button.setTooltip(Tooltip.create(Component.translatable(tipKey)));
        }
        rows.add(new Row(key, addWidget(button), false, null));
    }

    /**
     * A toggle over live session state rather than a stored setting. These are
     * the things that otherwise only answer to a chord, so the label is read
     * back from the state after every click, and a row that the state cannot
     * take right now greys out and says what it is waiting for.
     */
    private void liveToggle(String key, int x, int width, BooleanSupplier get, Runnable flip,
                            String tipKey, BooleanSupplier usable, String blockedKey) {
        Button button = Button.builder(state(get.getAsBoolean()), b -> {
            flip.run();
            b.setMessage(state(get.getAsBoolean()));
        }).bounds(x, 0, width, 20).build();
        boolean ready = usable == null || usable.getAsBoolean();
        button.setTooltip(Tooltip.create(Component.translatable(ready ? tipKey : blockedKey)));
        rows.add(new Row(key, addWidget(button), false, usable));
    }

    /** The one live row that is not a yes or no, so it cycles rather than flips. */
    private void modeRow(String key, int x, int width) {
        ClientState live = ClientState.INSTANCE;
        Button button = Button.builder(live.mode().plainLabel(), b -> {
            live.cycleMode();
            b.setMessage(live.mode().plainLabel());
        }).bounds(x, 0, width, 20).build();
        button.setTooltip(Tooltip.create(Component.translatable(live.isEnabled()
                ? "simpleschematics.tip.live.mode"
                : "simpleschematics.tip.live.off")));
        rows.add(new Row(key, addWidget(button), false, live::isEnabled));
    }

    private <E extends Enum<E>> void choice(String key, int x, int width,
                                            ForgeConfigSpec.EnumValue<E> value, E[] all) {
        choice(key, x, width, value, all, null);
    }

    /** Steps through the values of an enum setting, one click at a time. */
    private <E extends Enum<E>> void choice(String key, int x, int width,
                                            ForgeConfigSpec.EnumValue<E> value, E[] all, String tipKey) {
        Button button = Button.builder(Component.literal(pretty(value.get().name())), b -> {
            value.set(all[(value.get().ordinal() + 1) % all.length]);
            SSConfig.SPEC.save();
            b.setMessage(Component.literal(pretty(value.get().name())));
            WorldRenderer.invalidateAll();
        }).bounds(x, 0, width, 20).build();
        if (tipKey != null) {
            button.setTooltip(Tooltip.create(Component.translatable(tipKey)));
        }
        rows.add(new Row(key, addWidget(button), false, null));
    }

    private void text(String key, int x, int width, ForgeConfigSpec.ConfigValue<String> value) {
        EditBox box = new EditBox(this.font, x, 0, width, 20, Component.translatable(key));
        box.setMaxLength(256);
        box.setValue(value.get());
        box.setResponder(v -> {
            value.set(v);
            SSConfig.SPEC.save();
        });
        rows.add(new Row(key, addWidget(box), false, null));
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
        rows.add(new Row(key, addWidget(slider), false, null));
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
        rows.add(new Row(key, addWidget(slider), false, null));
    }

    private void action(String key, int x, int width, String buttonKey, Runnable action) {
        action(key, x, width, buttonKey, action, null);
    }

    private void action(String key, int x, int width, String buttonKey, Runnable action, String tipKey) {
        Button button = Button.builder(Component.translatable(buttonKey), b -> {
            try {
                action.run();
            } catch (Exception e) {
                SimpleSchematics.LOG.error("A config action failed", e);
            }
        }).bounds(x, 0, width, 20).build();
        if (tipKey != null) {
            button.setTooltip(Tooltip.create(Component.translatable(tipKey)));
        }
        rows.add(new Row(key, addWidget(button), false, null));
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
            row.widget().active = y + 2 >= top && y + 22 <= bottom
                    && (row.usable() == null || row.usable().getAsBoolean());
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
        importFiles(files);
    }

    /** Dropped on the screen or chosen from the dialogue, the files go the same way. */
    private void importFiles(List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        int adopted = DataTransfer.importAll(files);
        if (adopted > 0) {
            dev.willtda.simpleschematics.schematic.SchematicLibrary.INSTANCE.refresh();
            notify(Component.translatable("simpleschematics.config.imported", adopted).getString());
            Feedback.success(Component.translatable("simpleschematics.config.imported", adopted));
        } else {
            notify(Component.translatable("simpleschematics.config.import_nothing").getString());
        }
    }

    @Override
    public void onClose() {
        SSConfig.SPEC.save();
        WorldRenderer.invalidateAll();
        this.minecraft.setScreen(parent);
    }
}

package dev.willtda.simpleschematics.gui;

import dev.willtda.simpleschematics.client.ClientState;
import dev.willtda.simpleschematics.client.Feedback;
import dev.willtda.simpleschematics.client.ScanManager;
import dev.willtda.simpleschematics.client.ScanSelection;
import dev.willtda.simpleschematics.config.SSConfig;
import dev.willtda.simpleschematics.schematic.SchematicLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Name it, decide what to include, save it. The whole point is that this is the
 * only step between selecting a region and having a reusable schematic.
 */
public final class SaveSchematicScreen extends Screen {

    private final Screen parent;
    private EditBox nameBox;
    private EditBox descriptionBox;
    private Checkbox entitiesBox;
    private Checkbox containersBox;
    private Button saveButton;
    private String error;

    public SaveSchematicScreen(Screen parent) {
        super(Component.translatable("simpleschematics.gui.save.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = Mth.clamp(this.width - 40, 180, 300);
        int x = (this.width - width) / 2;
        int y = Math.max(30, this.height / 2 - 84);

        String previousName = nameBox == null ? suggestName() : nameBox.getValue();
        nameBox = new EditBox(this.font, x, y + 24, width, 20,
                Component.translatable("simpleschematics.gui.save.name"));
        nameBox.setMaxLength(96);
        nameBox.setValue(previousName);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        String previousDescription = descriptionBox == null ? "" : descriptionBox.getValue();
        descriptionBox = new EditBox(this.font, x, y + 62, width, 20,
                Component.translatable("simpleschematics.gui.save.description"));
        descriptionBox.setMaxLength(256);
        descriptionBox.setValue(previousDescription);
        descriptionBox.setHint(Component.translatable("simpleschematics.gui.save.description_hint"));
        addRenderableWidget(descriptionBox);

        entitiesBox = new Checkbox(x, y + 92, width, 20,
                Component.translatable("simpleschematics.gui.save.entities"),
                entitiesBox != null ? entitiesBox.selected() : SSConfig.INSTANCE.saveEntitiesByDefault.get());
        addRenderableWidget(entitiesBox);

        containersBox = new Checkbox(x, y + 116, width, 20,
                Component.translatable("simpleschematics.gui.save.containers"),
                containersBox != null ? containersBox.selected()
                        : SSConfig.INSTANCE.saveContainerContentsByDefault.get());
        addRenderableWidget(containersBox);

        int half = (width - 4) / 2;
        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("simpleschematics.gui.save.confirm"), b -> save())
                .bounds(x, y + 150, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x + half + 4, y + 150, half, 20).build());
    }

    private String suggestName() {
        ScanSelection selection = ClientState.INSTANCE.selection();
        Vec3i size = selection.size();
        return "Build " + size.getX() + "x" + size.getY() + "x" + size.getZ();
    }

    private void save() {
        ScanSelection selection = ClientState.INSTANCE.selection();
        if (!selection.isComplete()) {
            error = Component.translatable("simpleschematics.feedback.need_both_corners").getString();
            return;
        }
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            error = Component.translatable("simpleschematics.gui.save.needs_name").getString();
            return;
        }

        saveButton.active = false;
        try {
            ScanManager.Options options = new ScanManager.Options(name, descriptionBox.getValue().trim(),
                    entitiesBox.selected(), containersBox.selected());
            ScanManager.Result result = ScanManager.capture(selection, options);
            if (result == null) {
                error = Component.translatable("simpleschematics.gui.save.failed").getString();
                saveButton.active = true;
                return;
            }
            SchematicLibrary.INSTANCE.save(result.schematic(), name);
            ClientState.INSTANCE.selection().clear();
            Feedback.success(Component.translatable("simpleschematics.feedback.saved",
                    Component.literal(name), Component.literal(String.format("%,d", result.blockCount()))));
            onClose();
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            saveButton.active = true;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int width = Mth.clamp(this.width - 40, 180, 300);
        int x = (this.width - width) / 2;
        int y = Math.max(30, this.height / 2 - 84);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, y - 16, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("simpleschematics.gui.save.name"),
                x, y + 12, 0xFF9CA3AF, false);
        graphics.drawString(this.font, Component.translatable("simpleschematics.gui.save.description"),
                x, y + 50, 0xFF9CA3AF, false);

        ScanSelection selection = ClientState.INSTANCE.selection();
        Vec3i size = selection.size();
        String summary = size.getX() + " x " + size.getY() + " x " + size.getZ()
                + "   " + String.format("%,d", selection.volume()) + " positions";
        graphics.drawCenteredString(this.font, summary, this.width / 2, y + 176, 0xFF9CA3AF);

        if (containersBox != null && containersBox.selected()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("simpleschematics.gui.save.container_note"),
                    this.width / 2, y + 188, 0xFFFBBF24);
        }

        if (error != null) {
            graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(error, this.width - 20),
                    this.width / 2, y + 202, 0xFFF87272);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
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

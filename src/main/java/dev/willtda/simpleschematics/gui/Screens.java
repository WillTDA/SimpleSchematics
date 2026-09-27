package dev.willtda.simpleschematics.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The parts of drawing a screen that changed between game versions. */
public final class Screens {

    private Screens() {
    }

    /** The backdrop behind a screen: dimmed on 1.20.1, blurred from 1.21. */
    public static void background(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if >=1.21 {
        /*screen.renderBackground(graphics, mouseX, mouseY, partialTick);
        *///?} else {
        screen.renderBackground(graphics);
        //?}
    }

    /**
     * A screen's widgets, without its backdrop. From 1.20.2 {@code Screen.render}
     * draws the backdrop itself, over any panels drawn before it, and 1.21 throws
     * if the blur runs twice in a frame. On 1.20.1 this is all it ever drew.
     */
    public static void widgets(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (Renderable renderable : screen.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** A checkbox sitting in a row {@code height} tall, which on 1.21 is taller than the box. */
    public static Checkbox checkbox(Font font, int x, int y, int width, int height, Component label, boolean selected) {
        //? if >=1.21 {
        /*return Checkbox.builder(label, font)
                .pos(x, y + Math.max(0, (height - Checkbox.getBoxSize(font)) / 2))
                .maxWidth(width)
                .selected(selected)
                .build();
        *///?} else {
        return new Checkbox(x, y, width, height, label, selected);
        //?}
    }
}

package dev.willtda.simpleschematics.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** The available tools. Ctrl and scroll moves between them. */
public enum EditMode {

    SCAN("simpleschematics.mode.scan", ChatFormatting.AQUA),
    BUILD("simpleschematics.mode.build", ChatFormatting.GREEN),
    PRINT("simpleschematics.mode.print", ChatFormatting.GOLD);

    private final String key;
    private final ChatFormatting colour;

    EditMode(String key, ChatFormatting colour) {
        this.key = key;
        this.colour = colour;
    }

    public Component label() {
        return Component.translatable(key).withStyle(colour);
    }

    public Component plainLabel() {
        return Component.translatable(key);
    }

    public ChatFormatting colour() {
        return colour;
    }

    /** Tolerant of a config file someone has edited by hand. */
    public static EditMode byName(String name) {
        for (EditMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return SCAN;
    }

    public EditMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public EditMode previous() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }
}

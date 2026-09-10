package dev.willtda.simpleschematics.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** The two things you can be doing. Ctrl and scroll swaps between them. */
public enum EditMode {

    SCAN("simpleschematics.mode.scan", ChatFormatting.AQUA),
    BUILD("simpleschematics.mode.build", ChatFormatting.GREEN);

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
        return this == SCAN ? BUILD : SCAN;
    }

    public EditMode previous() {
        return next();
    }
}

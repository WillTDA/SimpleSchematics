package dev.willtda.simpleschematics.client;

import dev.willtda.simpleschematics.config.SSConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Confirmations go above the hotbar, never in chat.
 *
 * <p>Every line is built the same way so it reads at a glance: a marker saying
 * what kind of message this is, a bold label, and then the part that actually
 * changed picked out in colour. Nothing else is coloured, so the eye lands on
 * the value rather than swimming through a red sentence.</p>
 *
 * <pre>
 *   &#8226; Mode &#187; Build
 *   &#10003; Placed Villager House at -1008, 69, -666
 *   &#10007; Nothing in Range, Point at a Block
 * </pre>
 */
public final class Feedback {

    private static final String SEPARATOR = " » ";
    private static final String MARK_INFO = "• ";
    private static final String MARK_GOOD = "✔ ";
    private static final String MARK_BAD = "✖ ";

    private Feedback() {
    }

    /** A neutral status change. */
    public static void info(Component message) {
        send(marked(MARK_INFO, ChatFormatting.AQUA).append(message.copy().withStyle(ChatFormatting.WHITE)));
    }

    /** Something worked. */
    public static void success(Component message) {
        send(marked(MARK_GOOD, ChatFormatting.GREEN).append(message.copy().withStyle(ChatFormatting.WHITE)));
    }

    /** Something did not. */
    public static void error(Component message) {
        send(marked(MARK_BAD, ChatFormatting.RED).append(message.copy().withStyle(ChatFormatting.WHITE)));
    }

    /**
     * A label and a value, where only the value is coloured. Reads better than
     * painting a whole sentence.
     */
    public static void value(Component label, Component value) {
        send(marked(MARK_INFO, ChatFormatting.AQUA)
                .append(label.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD))
                .append(Component.literal(SEPARATOR).withStyle(ChatFormatting.DARK_GRAY))
                .append(value));
    }

    /**
     * The common case of a label that has just been switched one way or the
     * other. Keeps On and Off looking identical everywhere they appear.
     */
    public static void state(Component label, boolean on) {
        value(label, Component.translatable(on
                        ? "simpleschematics.state.on"
                        : "simpleschematics.state.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static MutableComponent marked(String mark, ChatFormatting colour) {
        return Component.literal(mark).withStyle(colour);
    }

    private static void send(Component message) {
        if (!SSConfig.INSTANCE.actionBarFeedback.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(message, true);
        }
    }

    /**
     * The layer tick. Looked up by id rather than through the constant, because
     * the note block sounds are wrapped in holders and this keeps the call site
     * simple.
     */
    public static void playLayerTick(float pitch) {
        if (!SSConfig.INSTANCE.layerScrollSound.get()) {
            return;
        }
        float volume = SSConfig.INSTANCE.layerScrollVolume.get().floatValue();
        if (volume <= 0.0F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation("minecraft", "block.note_block.hat"));
        if (sound == null) {
            return;
        }
        mc.player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    /** A slightly duller tick for hitting the top or bottom of the stack of layers. */
    public static void playLayerLimit() {
        playLayerTick(0.6F);
    }
}

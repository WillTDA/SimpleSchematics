package dev.willtda.simpleschematics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;

import java.util.List;

/**
 * Every key the mod claims, laid out the way Litematica does it.
 *
 * <p>{@link #MENU} on its own opens the library. Held down it becomes a prefix,
 * and the {@linkplain #chords() chord keys} hang off it: M and P for placements,
 * M and L for the resource list, and so on. Nothing in that second group does
 * anything on its own, which is what keeps the mod down to a single key on an
 * already crowded keyboard.</p>
 *
 * <p>All of it still shows up in the vanilla Controls screen and all of it is
 * still rebindable. {@link InputHandler} does the actual chord matching, because
 * a plain {@link KeyMapping} has no concept of one key leading to another.</p>
 */
public final class Keybinds {

    public static final String CATEGORY = "key.categories.simpleschematics";

    /** Tap to open the library, hold to reach everything in {@link #chords()}. */
    public static final KeyMapping MENU = key("key.simpleschematics.menu", InputConstants.KEY_M);

    // ---- chords, all of them prefixed with MENU ----------------------------

    public static final KeyMapping PLACEMENTS = key("key.simpleschematics.placements", InputConstants.KEY_P);
    public static final KeyMapping RESOURCE_LIST_SCREEN = key("key.simpleschematics.resource_list_screen", InputConstants.KEY_L);
    public static final KeyMapping SETTINGS = key("key.simpleschematics.settings", InputConstants.KEY_C);
    public static final KeyMapping TOGGLE_RENDERING = key("key.simpleschematics.toggle_rendering", InputConstants.KEY_R);
    public static final KeyMapping TOGGLE_MOD = key("key.simpleschematics.toggle", InputConstants.KEY_T);
    public static final KeyMapping RESOURCE_LIST = key("key.simpleschematics.resource_list", InputConstants.KEY_O);
    public static final KeyMapping HIGHLIGHT = key("key.simpleschematics.highlight", InputConstants.KEY_H);
    public static final KeyMapping TOGGLE_BOX = key("key.simpleschematics.toggle_box", InputConstants.KEY_B);

    // ---- plain keys, no prefix --------------------------------------------

    public static final KeyMapping CONFIRM = key("key.simpleschematics.confirm", InputConstants.KEY_RETURN);
    public static final KeyMapping CLEAR = key("key.simpleschematics.clear", InputConstants.KEY_BACKSLASH);
    public static final KeyMapping ROTATE = key("key.simpleschematics.rotate", InputConstants.KEY_PERIOD);
    public static final KeyMapping MIRROR = key("key.simpleschematics.mirror", InputConstants.KEY_COMMA);
    public static final KeyMapping LAYER_NEXT = key("key.simpleschematics.layer_next", InputConstants.KEY_PAGEUP);
    public static final KeyMapping LAYER_PREVIOUS = key("key.simpleschematics.layer_previous", InputConstants.KEY_PAGEDOWN);

    /** Order matters only in that the first match wins, so keep them distinct. */
    private static final List<KeyMapping> CHORDS = List.of(
            PLACEMENTS, RESOURCE_LIST_SCREEN, SETTINGS,
            TOGGLE_RENDERING, TOGGLE_MOD, RESOURCE_LIST, HIGHLIGHT, TOGGLE_BOX);

    private static final List<KeyMapping> PLAIN = List.of(
            CONFIRM, CLEAR, ROTATE, MIRROR, LAYER_NEXT, LAYER_PREVIOUS);

    private Keybinds() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    /** The keys that only mean something while {@link #MENU} is held. */
    public static List<KeyMapping> chords() {
        return CHORDS;
    }

    /** The keys that work on their own. */
    public static List<KeyMapping> plain() {
        return PLAIN;
    }

    /** Two of these sharing a key would make the second one unreachable. */
    public static boolean isChord(KeyMapping mapping) {
        return CHORDS.contains(mapping);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(MENU);
        for (KeyMapping mapping : CHORDS) {
            event.register(mapping);
        }
        for (KeyMapping mapping : PLAIN) {
            event.register(mapping);
        }
    }
}

package dev.willtda.simpleschematics.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Player;
//? if forge {
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.loading.FMLPaths;
//?} else {
/*import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
*///?}

import java.nio.file.Path;

/**
 * The few things shared code has to ask the loader for, in one place.
 *
 * <p>Everything else loader specific, the events and registrations, lives in
 * one listener class per loader beside this one. Porting to another loader
 * means answering these methods again and writing that one class; the rest of
 * the mod does not change.</p>
 */
public final class Platform {

    private Platform() {
    }

    /** The game folder, which holds the mods, saves and config folders. */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** A key that only acts in game, so it never fights a screen for the same key. */
    public static KeyMapping inGameKey(String name, int keyCode, String category) {
        return new KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, keyCode, category);
    }

    /** How far the player can reach to place or use a block. */
    public static double blockReach(Player player) {
        //? if >=1.21 {
        /*return player.blockInteractionRange();
        *///?} else {
        return player.getBlockReach();
        //?}
    }
}

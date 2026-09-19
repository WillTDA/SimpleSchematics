package dev.willtda.simpleschematics.printing;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;

/** A predicted client block is not a completed placement until vanilla clears its acknowledgement. */
final class PrintAcknowledgement {
    private static final Field HANDLER = ObfuscationReflectionHelper.findField(ClientLevel.class, "f_233599_");
    private static final Field PENDING = ObfuscationReflectionHelper.findField(BlockStatePredictionHandler.class, "f_233851_");

    private PrintAcknowledgement() { }

    static boolean pending(ClientLevel level, BlockPos pos) {
        try {
            return ((Long2ObjectMap<?>) PENDING.get(HANDLER.get(level))).containsKey(pos.asLong());
        } catch (IllegalAccessException exception) {
            // Fail closed: the manager times out without spending more materials.
            return true;
        }
    }
}

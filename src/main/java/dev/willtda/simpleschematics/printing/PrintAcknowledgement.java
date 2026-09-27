package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.mixin.BlockStatePredictionHandlerAccessor;
import dev.willtda.simpleschematics.mixin.ClientLevelAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** A predicted client block is not a completed placement until vanilla clears its acknowledgement. */
final class PrintAcknowledgement {

    private PrintAcknowledgement() { }

    static boolean pending(ClientLevel level, BlockPos pos) {
        return predictions(level).containsKey(pos.asLong());
    }

    /** Whether any block anywhere is still showing a guess the server has not answered. */
    static boolean any(ClientLevel level) {
        return !predictions(level).isEmpty();
    }

    private static Long2ObjectMap<?> predictions(ClientLevel level) {
        return ((BlockStatePredictionHandlerAccessor) ((ClientLevelAccessor) level).simpleschematics$predictions())
                .simpleschematics$pending();
    }
}

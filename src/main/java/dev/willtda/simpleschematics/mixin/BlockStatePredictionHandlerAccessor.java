package dev.willtda.simpleschematics.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The positions still waiting on the server, keyed by {@code BlockPos.asLong()}. */
@Mixin(BlockStatePredictionHandler.class)
public interface BlockStatePredictionHandlerAccessor {

    @Accessor("serverVerifiedStates")
    Long2ObjectOpenHashMap<?> simpleschematics$pending();
}

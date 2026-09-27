package dev.willtda.simpleschematics.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the block changes the client has guessed and the server has not yet answered. */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {

    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler simpleschematics$predictions();
}

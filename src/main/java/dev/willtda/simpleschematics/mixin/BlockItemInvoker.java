package dev.willtda.simpleschematics.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The state an item would place for a click, without placing it. Protected in
 * vanilla and overridden by several items, so it is called rather than copied.
 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {

    @Invoker("getPlacementState")
    BlockState simpleschematics$placementState(BlockPlaceContext context);
}

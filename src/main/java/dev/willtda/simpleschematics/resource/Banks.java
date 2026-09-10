package dev.willtda.simpleschematics.resource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Working out which blocks can hold materials, and which half of one to store. */
public final class Banks {

    private Banks() {
    }

    /**
     * Chests, barrels, hoppers and the rest. The block entity exists on the
     * client even though its contents do not, which is all this needs to know.
     */
    public static boolean isContainer(Level level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof Container;
    }

    /**
     * The half of a double chest that stands for the pair.
     *
     * <p>Both halves share one screen, so banking each of them separately would
     * count the same items twice. Whichever half you click, the lower of the two
     * positions is the one stored, and the pair is one bank.</p>
     */
    public static BlockPos canonical(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            return pos.immutable();
        }
        if (state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE) {
            return pos.immutable();
        }
        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(connected);
        return (other.asLong() < pos.asLong() ? other : pos).immutable();
    }
}

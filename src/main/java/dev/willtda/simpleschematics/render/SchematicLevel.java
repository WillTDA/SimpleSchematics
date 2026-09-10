package dev.willtda.simpleschematics.render;

import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import java.util.BitSet;

/**
 * A read only view that makes a schematic look enough like a level for the
 * vanilla block renderer to bake it.
 *
 * <p>Two things matter here. Faces are culled against neighbouring schematic
 * blocks rather than the real world, which is what stops the interior of a
 * build being drawn. And everything is lit at full brightness, because a
 * hologram that is dark in a cave is useless.</p>
 *
 * <p>Coordinates are local to the schematic, so the bake is independent of
 * where the placement currently sits and never needs redoing when you move it.
 * Only biome tint is looked up against real world coordinates.</p>
 */
public final class SchematicLevel implements BlockAndTintGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Level real;
    private final Schematic schematic;
    private BlockPos tintOrigin;
    private int minLayer;
    private int maxLayer;
    private BitSet hidden;

    public SchematicLevel(Level real, Schematic schematic, BlockPos tintOrigin) {
        this.real = real;
        this.schematic = schematic;
        this.maxLayer = schematic.height() - 1;
        this.tintOrigin = tintOrigin == null ? BlockPos.ZERO : tintOrigin.immutable();
    }

    public void setTintOrigin(BlockPos origin) {
        this.tintOrigin = origin == null ? BlockPos.ZERO : origin.immutable();
    }

    public void setVisibleBlocks(int minLayer, int maxLayer, BitSet hidden) {
        this.minLayer = minLayer;
        this.maxLayer = maxLayer;
        this.hidden = hidden;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (!SectionVisibility.visible(pos.getX(), pos.getY(), pos.getZ(),
                schematic.width(), schematic.height(), schematic.length(), minLayer, maxLayer, hidden)) {
            return AIR;
        }
        return schematic.getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        // block entity renderers are not used for holograms, the baked model is enough
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return real == null ? 1.0F : real.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return real.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return real == null ? 0xFFFFFF : real.getBlockTint(tintOrigin.offset(pos), resolver);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return true;
    }

    @Override
    public int getHeight() {
        return schematic.height();
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }
}

package dev.willtda.simpleschematics.printing;

import dev.willtda.simpleschematics.placement.Placement;
import dev.willtda.simpleschematics.resource.Banks;
import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.BitSet;

/**
 * One placement as it is to be printed, worked out once when printing starts.
 *
 * <p>The palette is turned the way the placement is turned, entry by entry,
 * and only the positions that hold a block are listed, lowest layer first.
 * Every later pass walks that list rather than the whole volume. Walking the
 * volume, air included, a fixed slice per tick, and turning every state on the
 * way, is most of what made a large build slow to start.</p>
 *
 * <p>The transform is copied rather than read from the placement, so the world
 * positions cannot drift if the placement is moved mid print. The manager
 * stops printing when that happens.</p>
 */
final class PrintPlan {

    final Placement placement;
    final Schematic schematic;
    final String signature;
    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    private final BlockState[] wanted;
    private final int[] targets;
    private final BitSet blockEntities = new BitSet();

    private PrintPlan(Placement placement, Schematic schematic, String signature, BlockState[] wanted, int[] targets) {
        this.placement = placement;
        this.schematic = schematic;
        this.signature = signature;
        this.origin = placement.origin();
        this.rotation = placement.rotation();
        this.mirror = placement.mirror();
        this.wanted = wanted;
        this.targets = targets;
        for (BlockPos local : schematic.blockEntities().keySet()) {
            if (schematic.inBounds(local.getX(), local.getY(), local.getZ())) {
                blockEntities.set(schematic.index(local.getX(), local.getY(), local.getZ()));
            }
        }
    }

    static PrintPlan of(Placement placement, Schematic schematic, String signature) {
        BlockState[] palette = schematic.palette();
        BlockState[] wanted = new BlockState[palette.length];
        boolean[] air = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            wanted[i] = palette[i].mirror(placement.mirror()).rotate(placement.rotation());
            air[i] = palette[i].isAir();
        }
        int volume = (int) schematic.volume();
        int[] targets = new int[schematic.blockCount()];
        int count = 0;
        for (int index = 0; index < volume; index++) {
            if (!air[schematic.paletteIndex(index)]) {
                targets[count++] = index;
            }
        }
        return new PrintPlan(placement, schematic, signature, wanted,
                count == targets.length ? targets : Arrays.copyOf(targets, count));
    }

    /** How many positions hold a block. */
    int size() {
        return targets.length;
    }

    /** The schematic index of the n-th block, in the order the schematic stores them. */
    int target(int ordinal) {
        return targets[ordinal];
    }

    int paletteOf(int index) {
        return schematic.paletteIndex(index);
    }

    boolean isAir(int index) {
        return wanted[schematic.paletteIndex(index)].isAir();
    }

    /** The state wanted at a schematic index, already turned the way the placement is. */
    BlockState wanted(int index) {
        return wanted[schematic.paletteIndex(index)];
    }

    boolean hasBlockEntityData(int index) {
        return blockEntities.get(index);
    }

    boolean accepted(int index) {
        return placement.acceptedCount() > 0 && placement.isAccepted(index);
    }

    boolean moved() {
        return !origin.equals(placement.origin()) || rotation != placement.rotation() || mirror != placement.mirror();
    }

    /** A linked chest standing in the build, which printing must never replace. */
    boolean isBank(Level level, BlockPos pos) {
        return !placement.bankPositions().isEmpty() && placement.isBank(Banks.canonical(level, pos));
    }

    int localX(int index) {
        return index % schematic.width();
    }

    int localY(int index) {
        return index / (schematic.width() * schematic.length());
    }

    int localZ(int index) {
        return index / schematic.width() % schematic.length();
    }

    BlockPos.MutableBlockPos world(int index, BlockPos.MutableBlockPos out) {
        return world(localX(index), localY(index), localZ(index), out);
    }

    /** The same mapping as {@link Placement#toWorld}, without allocating a position per block. */
    BlockPos.MutableBlockPos world(int x, int y, int z, BlockPos.MutableBlockPos out) {
        int w = schematic.width();
        int l = schematic.length();
        int mx = mirror == Mirror.FRONT_BACK ? w - 1 - x : x;
        int mz = mirror == Mirror.LEFT_RIGHT ? l - 1 - z : z;
        int rx;
        int rz;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                rx = l - 1 - mz;
                rz = mx;
            }
            case CLOCKWISE_180 -> {
                rx = w - 1 - mx;
                rz = l - 1 - mz;
            }
            case COUNTERCLOCKWISE_90 -> {
                rx = mz;
                rz = w - 1 - mx;
            }
            default -> {
                rx = mx;
                rz = mz;
            }
        }
        return out.set(origin.getX() + rx, origin.getY() + y, origin.getZ() + rz);
    }

    static boolean inWorld(Level level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
}

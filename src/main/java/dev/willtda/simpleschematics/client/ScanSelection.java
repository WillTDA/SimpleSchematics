package dev.willtda.simpleschematics.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

/**
 * Two corners and nothing else. Left click sets the start, right click sets the
 * end, and the box between them is what gets saved.
 */
public final class ScanSelection {

    private BlockPos start;
    private BlockPos end;

    public BlockPos start() {
        return start;
    }

    public BlockPos end() {
        return end;
    }

    public void setStart(BlockPos pos) {
        this.start = pos == null ? null : pos.immutable();
    }

    public void setEnd(BlockPos pos) {
        this.end = pos == null ? null : pos.immutable();
    }

    public void clear() {
        start = null;
        end = null;
    }

    public boolean isComplete() {
        return start != null && end != null;
    }

    public boolean isEmpty() {
        return start == null && end == null;
    }

    public BlockPos min() {
        if (!isComplete()) {
            return null;
        }
        return new BlockPos(Math.min(start.getX(), end.getX()),
                Math.min(start.getY(), end.getY()),
                Math.min(start.getZ(), end.getZ()));
    }

    public BlockPos max() {
        if (!isComplete()) {
            return null;
        }
        return new BlockPos(Math.max(start.getX(), end.getX()),
                Math.max(start.getY(), end.getY()),
                Math.max(start.getZ(), end.getZ()));
    }

    public Vec3i size() {
        if (!isComplete()) {
            return Vec3i.ZERO;
        }
        BlockPos min = min();
        BlockPos max = max();
        return new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
    }

    public long volume() {
        Vec3i size = size();
        return (long) size.getX() * size.getY() * size.getZ();
    }

    /** The full box, in world coordinates, for rendering. */
    public AABB boundingBox() {
        if (!isComplete()) {
            return null;
        }
        BlockPos min = min();
        BlockPos max = max();
        return new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
    }

    public static AABB blockBox(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }
}

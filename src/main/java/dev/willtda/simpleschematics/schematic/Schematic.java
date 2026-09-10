package dev.willtda.simpleschematics.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A saved build.
 *
 * <p>Deliberately a single box of blocks rather than the scattered multi region
 * layout other tools use. Anything imported with several regions is flattened
 * into one volume on the way in, which keeps rendering, placement and the
 * resource list far simpler to reason about.</p>
 *
 * <p>Block states are stored as indices into a palette, ordered
 * {@code y * width * length + z * width + x}.</p>
 */
public final class Schematic {

    /** Human readable details, all of it optional apart from the name. */
    public static final class Meta {
        public String name = "Untitled";
        public String author = "";
        public String description = "";
        public long created = System.currentTimeMillis();
        public long modified = System.currentTimeMillis();
        /** Where this came from, shown in the library. */
        public String source = "Simple Schematics";
        /** Optional 140x140 ARGB thumbnail carried over from an imported file. */
        public int[] previewPixels = null;
        public int previewWidth = 0;
        public int previewHeight = 0;
    }

    private final Meta meta;
    private final Vec3i size;
    private final BlockState[] palette;
    private final int[] states;
    private final Map<BlockPos, CompoundTag> blockEntities;
    private final List<CompoundTag> entities;

    private int cachedBlockCount = -1;

    Schematic(Meta meta, Vec3i size, BlockState[] palette, int[] states,
              Map<BlockPos, CompoundTag> blockEntities, List<CompoundTag> entities) {
        this.meta = meta;
        this.size = size;
        this.palette = palette;
        this.states = states;
        this.blockEntities = blockEntities;
        this.entities = entities;
    }

    public Meta meta() {
        return meta;
    }

    public Vec3i size() {
        return size;
    }

    public int width() {
        return size.getX();
    }

    public int height() {
        return size.getY();
    }

    public int length() {
        return size.getZ();
    }

    public long volume() {
        return (long) width() * height() * length();
    }

    public BlockState[] palette() {
        return palette;
    }

    public Map<BlockPos, CompoundTag> blockEntities() {
        return blockEntities;
    }

    public List<CompoundTag> entities() {
        return entities;
    }

    int[] rawStates() {
        return states;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < width() && y < height() && z < length();
    }

    public int index(int x, int y, int z) {
        return (y * length() + z) * width() + x;
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        return palette[states[index(x, y, z)]];
    }

    public BlockState getBlockState(BlockPos local) {
        return getBlockState(local.getX(), local.getY(), local.getZ());
    }

    /** Non air block count, worked out once and remembered. */
    public int blockCount() {
        if (cachedBlockCount < 0) {
            int count = 0;
            boolean[] airFlags = new boolean[palette.length];
            for (int i = 0; i < palette.length; i++) {
                airFlags[i] = palette[i].isAir();
            }
            for (int state : states) {
                if (!airFlags[state]) {
                    count++;
                }
            }
            cachedBlockCount = count;
        }
        return cachedBlockCount;
    }

    public boolean hasPreviewImage() {
        return meta.previewPixels != null && meta.previewWidth > 0 && meta.previewHeight > 0
                && meta.previewPixels.length >= meta.previewWidth * meta.previewHeight;
    }

    // -----------------------------------------------------------------------

    /** Collects block states into a palette while a schematic is being assembled. */
    public static final class Builder {

        private final Meta meta = new Meta();
        private final int width;
        private final int height;
        private final int length;
        private final int[] states;
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> paletteLookup = new HashMap<>();
        private final Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        private final List<CompoundTag> entities = new ArrayList<>();

        public Builder(int width, int height, int length) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.length = Math.max(1, length);
            this.states = new int[this.width * this.height * this.length];
            // index 0 is always air so the array starts out empty
            paletteId(Blocks.AIR.defaultBlockState());
        }

        public Meta meta() {
            return meta;
        }

        public int paletteId(BlockState state) {
            Integer existing = paletteLookup.get(state);
            if (existing != null) {
                return existing;
            }
            int id = palette.size();
            palette.add(state);
            paletteLookup.put(state, id);
            return id;
        }

        public void set(int x, int y, int z, BlockState state) {
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                return;
            }
            states[(y * length + z) * width + x] = paletteId(state);
        }

        public void setBlockEntity(BlockPos local, CompoundTag tag) {
            if (tag != null) {
                blockEntities.put(local.immutable(), tag);
            }
        }

        public void addEntity(CompoundTag tag) {
            if (tag != null) {
                entities.add(tag);
            }
        }

        public Schematic build() {
            return new Schematic(meta, new Vec3i(width, height, length),
                    palette.toArray(new BlockState[0]), states, blockEntities, entities);
        }
    }
}

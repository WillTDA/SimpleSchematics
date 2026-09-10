package dev.willtda.simpleschematics.render;

import dev.willtda.simpleschematics.schematic.Schematic;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * The set of blocks in a schematic whose edges are worth drawing.
 *
 * <p>Outlining every block would mean walking the whole volume every frame, and
 * most of what that finds is buried inside the build where nobody can see it.
 * The positions that have at least one exposed face are worked out once per
 * schematic and kept, which for a normal build is a small fraction of the
 * volume and makes the per frame job a walk down a flat array.</p>
 */
public final class BlockOutlines {

    private static final Map<String, BlockOutlines> CACHE = new HashMap<>();

    /** Local positions packed as x, y, z triples. */
    private final int[] positions;

    private BlockOutlines(int[] positions) {
        this.positions = positions;
    }

    public int count() {
        return positions.length / 3;
    }

    public int x(int index) {
        return positions[index * 3];
    }

    public int y(int index) {
        return positions[index * 3 + 1];
    }

    public int z(int index) {
        return positions[index * 3 + 2];
    }

    public static BlockOutlines of(String key, Schematic schematic) {
        return CACHE.computeIfAbsent(key, k -> build(schematic));
    }

    public static void invalidate(String key) {
        CACHE.remove(key);
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    private static BlockOutlines build(Schematic schematic) {
        int w = schematic.width();
        int h = schematic.height();
        int l = schematic.length();
        // Grows as needed; most builds are mostly shell, so this rarely doubles far.
        int[] out = new int[Math.min(4096, Math.max(96, schematic.blockCount() * 3))];
        int size = 0;

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (schematic.getBlockState(x, y, z).isAir() || !exposed(schematic, x, y, z)) {
                        continue;
                    }
                    if (size + 3 > out.length) {
                        int[] bigger = new int[out.length * 2];
                        System.arraycopy(out, 0, bigger, 0, size);
                        out = bigger;
                    }
                    out[size++] = x;
                    out[size++] = y;
                    out[size++] = z;
                }
            }
        }

        int[] trimmed = new int[size];
        System.arraycopy(out, 0, trimmed, 0, size);
        return new BlockOutlines(trimmed);
    }

    /** A block with air on any side; anything else is buried and never seen. */
    private static boolean exposed(Schematic schematic, int x, int y, int z) {
        return air(schematic, x - 1, y, z) || air(schematic, x + 1, y, z)
                || air(schematic, x, y - 1, z) || air(schematic, x, y + 1, z)
                || air(schematic, x, y, z - 1) || air(schematic, x, y, z + 1);
    }

    private static boolean air(Schematic schematic, int x, int y, int z) {
        if (!schematic.inBounds(x, y, z)) {
            return true;
        }
        BlockState state = schematic.getBlockState(x, y, z);
        return state.isAir();
    }
}

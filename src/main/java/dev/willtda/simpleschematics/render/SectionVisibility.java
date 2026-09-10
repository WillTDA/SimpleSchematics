package dev.willtda.simpleschematics.render;

import java.util.BitSet;

/** Shared, dependency-free rules for visible blocks and section boundary invalidation. */
public final class SectionVisibility {
    private SectionVisibility() {
    }

    public static boolean visible(int x, int y, int z, int width, int height, int length,
                                  int minLayer, int maxLayer, BitSet hidden) {
        return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < length
                && y >= minLayer && y <= maxLayer
                && (hidden == null || !hidden.get((y * length + z) * width + x));
    }

    public static BitSet withNeighbours(BitSet changed, int width, int height, int length) {
        BitSet result = new BitSet();
        int total = width * height * length;
        for (int index = changed.nextSetBit(0); index >= 0; index = changed.nextSetBit(index + 1)) {
            if (index >= total) break;
            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            result.set(index);
            if (x > 0) result.set(index - 1);
            if (x + 1 < width) result.set(index + 1);
            if (z > 0) result.set(index - width);
            if (z + 1 < length) result.set(index + width);
            if (y > 0) result.set(index - width * length);
            if (y + 1 < height) result.set(index + width * length);
        }
        return result;
    }
}

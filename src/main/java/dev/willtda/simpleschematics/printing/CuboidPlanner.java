package dev.willtda.simpleschematics.printing;

import java.util.function.IntPredicate;

/**
 * Grows a box of identical blocks so one fill command places the lot.
 *
 * <p>Plain arithmetic over schematic indices, ordered the way the schematic
 * stores them ({@code (y * length + z) * width + x}), with no game types in it,
 * so it can be checked on its own and carried to any loader unchanged.</p>
 *
 * <p>The box starts at one block and grows along x, then row by row along z,
 * then layer by layer up y, each step only if every new cell is allowed to
 * join. The caller decides what joining means: the same block, still needing
 * placement, nothing in the way. Growing in the storage order means every cell
 * in the box comes after the start, so a box never reaches back over a cell
 * that has already had its turn.</p>
 */
public final class CuboidPlanner {

    private CuboidPlanner() {
    }

    /**
     * @param joins     whether the cell at a schematic index may join; never asked about the start
     * @param maxVolume the most cells one box may hold, the server's fill limit
     * @return the box as {@code {minX, minY, minZ, maxX, maxY, maxZ}}, inclusive, in schematic space
     */
    public static int[] grow(int width, int height, int length, int start, IntPredicate joins, int maxVolume) {
        int x0 = start % width;
        int z0 = start / width % length;
        int y0 = start / (width * length);
        int limit = Math.max(1, maxVolume);

        int x1 = x0;
        while (x1 + 1 < width && x1 + 2 - x0 <= limit && joins.test(index(width, length, x1 + 1, y0, z0))) {
            x1++;
        }
        int spanX = x1 - x0 + 1;

        int z1 = z0;
        while (z1 + 1 < length && (long) spanX * (z1 + 2 - z0) <= limit
                && rowJoins(width, length, x0, x1, y0, z1 + 1, joins)) {
            z1++;
        }
        int spanZ = z1 - z0 + 1;

        int y1 = y0;
        while (y1 + 1 < height && (long) spanX * spanZ * (y1 + 2 - y0) <= limit
                && layerJoins(width, length, x0, x1, z0, z1, y1 + 1, joins)) {
            y1++;
        }
        return new int[]{x0, y0, z0, x1, y1, z1};
    }

    public static int volume(int[] box) {
        return (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
    }

    public static int index(int width, int length, int x, int y, int z) {
        return (y * length + z) * width + x;
    }

    private static boolean rowJoins(int width, int length, int x0, int x1, int y, int z, IntPredicate joins) {
        for (int x = x0; x <= x1; x++) {
            if (!joins.test(index(width, length, x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean layerJoins(int width, int length, int x0, int x1, int z0, int z1, int y, IntPredicate joins) {
        for (int z = z0; z <= z1; z++) {
            if (!rowJoins(width, length, x0, x1, y, z, joins)) {
                return false;
            }
        }
        return true;
    }
}

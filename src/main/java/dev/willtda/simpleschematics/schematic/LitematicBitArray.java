package dev.willtda.simpleschematics.schematic;

/**
 * Read only view over the packed long array used by {@code .litematic} files.
 *
 * <p>Worth spelling out because it is a common trip hazard: this packing lets a
 * single value straddle the boundary between two longs. Vanilla stopped doing
 * that in 1.16 and pads each long instead, so the vanilla helper cannot be
 * reused here.</p>
 */
final class LitematicBitArray {

    private final long[] data;
    private final int bitsPerEntry;
    private final long maxEntryValue;
    private final long size;

    LitematicBitArray(int bitsPerEntry, long size, long[] data) {
        this.bitsPerEntry = bitsPerEntry;
        this.size = size;
        this.maxEntryValue = (1L << bitsPerEntry) - 1L;
        this.data = data;
    }

    int get(long index) {
        if (index < 0 || index >= size) {
            return 0;
        }
        long startOffset = index * bitsPerEntry;
        int startIndex = (int) (startOffset >> 6);
        int endIndex = (int) (((index + 1L) * bitsPerEntry - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);

        if (startIndex < 0 || endIndex >= data.length) {
            return 0;
        }
        if (startIndex == endIndex) {
            return (int) ((data[startIndex] >>> startBit) & maxEntryValue);
        }
        int endBits = 64 - startBit;
        return (int) (((data[startIndex] >>> startBit) | (data[endIndex] << endBits)) & maxEntryValue);
    }

    /** Litematica never drops below two bits per entry, even for tiny palettes. */
    static int bitsFor(int paletteSize) {
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        return Math.min(bits, 32);
    }

    static long expectedLongCount(long volume, int bitsPerEntry) {
        return (volume * bitsPerEntry + 63L) / 64L;
    }
}

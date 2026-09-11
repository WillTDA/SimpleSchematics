package dev.willtda.simpleschematics.gui;

/**
 * Where the columns of the full resource list fall, worked out from the widest
 * text each one has to hold rather than from fixed offsets.
 *
 * <p>The figures keep their full width and the name takes what is left. Fixed
 * offsets put the breakdown into the count as soon as the numbers grew, and
 * measuring the columns from what they hold is what lets 50 × 64 + 14 sit
 * beside 3,214 without the two touching. Should the figures leave the name no
 * room, the breakdown is the first thing to go, being a convenience: the count
 * beside it says the same thing in fewer characters.</p>
 *
 * <p>Every position is relative to the left edge of the list. Kept free of
 * Minecraft classes so {@code scripts/ResourceListLayoutCheck} can compile it
 * on its own.</p>
 */
public final class ResourceListColumns {

    /** Held between neighbouring columns, so figures never touch. */
    public static final int GAP = 8;
    /** The icon, its padding, and the gap before the name. */
    public static final int NAME_X = 24;
    /** Kept clear of the right edge. */
    public static final int RIGHT_PADDING = 8;
    /** The least the name will accept before the breakdown is given up. */
    public static final int MIN_NAME = 60;

    /**
     * The right edge of each figure column, and the room the name has. A
     * column that is not shown has its right edge where the next one starts,
     * so drawing nothing there is harmless.
     */
    public record Layout(int nameRoom, int haveRight, int breakdownRight, int countRight, boolean breakdownShown) {
    }

    /**
     * @param width     the list's width
     * @param have      the widest have column text
     * @param breakdown the widest breakdown text, zero when there is none
     * @param count     the widest count text
     */
    public static Layout of(int width, int have, int breakdown, int count) {
        int countRight = width - RIGHT_PADDING;
        int breakdownRight = countRight - count - GAP;
        int haveRight = breakdownRight - breakdown - GAP;
        boolean breakdownShown = breakdown > 0 && haveRight - have - GAP - NAME_X >= MIN_NAME;
        if (!breakdownShown) {
            haveRight = breakdownRight;
        }
        int nameRoom = Math.max(0, haveRight - have - GAP - NAME_X);
        return new Layout(nameRoom, haveRight, breakdownRight, countRight, breakdownShown);
    }

    private ResourceListColumns() {
    }
}

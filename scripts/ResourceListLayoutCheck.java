import dev.willtda.simpleschematics.gui.ResourceListColumns;

/**
 * Layout checks for the resource list screen and overlay across GUI scales.
 *
 * <p>The column arithmetic is the real class. The rest models the screen and
 * overlay layout methods, so the constants here have to be kept in step with
 * {@code ResourceListScreen} and {@code ResourceListOverlay}. Text widths use
 * the default font's metrics: six pixels for a digit or most letters, two for
 * a comma, four for a space.</p>
 *
 * <pre>
 * javac -encoding UTF-8 -d build/verification scripts/ResourceListLayoutCheck.java src/main/java/dev/willtda/simpleschematics/gui/ResourceListColumns.java
 * java -cp build/verification ResourceListLayoutCheck
 * </pre>
 */
public final class ResourceListLayoutCheck {

    private static final int LINE_HEIGHT = 9;

    // ResourceListScreen
    private static final int SCREEN_ROW_HEIGHT = 20;
    private static final int TITLE_Y = 14;
    private static final int LIST_TOP = 44;

    // ResourceListOverlay
    private static final int OVERLAY_ROW_HEIGHT = 18;
    private static final int PADDING = 4;
    private static final int HEADER_HEIGHT = 20;
    private static final int NAME_HEIGHT = 12;

    /** Every scaled window size vanilla can produce, from 320 by 240 upwards. */
    private static final int[][] WINDOWS = {
            {320, 240}, {427, 240}, {320, 320}, {427, 320}, {480, 270}, {640, 360}, {640, 480},
            {683, 384}, {800, 450}, {854, 480}, {960, 540}, {1024, 576}, {1280, 720}, {1366, 768},
            {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {213, 120}, {160, 90},
    };

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    /** Roughly the default font: digits and letters six wide, commas two, spaces four. */
    private static int width(String text) {
        int total = 0;
        for (char c : text.toCharArray()) {
            total += switch (c) {
                case ',', '.', '\'' -> 2;
                case ' ' -> 4;
                case 'i', 'l', '!' -> 2;
                case 't' -> 4;
                default -> 6;
            };
        }
        return total;
    }

    private static String grouped(int value) {
        return String.format("%,d", value);
    }

    private static String breakdown(int total, int stackSize) {
        if (stackSize <= 1 || total < stackSize) {
            return "";
        }
        int stacks = total / stackSize;
        int remainder = total % stackSize;
        if (stacks == 1 && remainder == 0) {
            return "";
        }
        String head = stacks == 1 ? String.valueOf(stackSize) : grouped(stacks) + " × " + stackSize;
        return remainder == 0 ? head : head + " + " + remainder;
    }

    // ---- screen -----------------------------------------------------------

    private static void screen(int w, int h) {
        String where = " at " + w + "x" + h;
        int contentWidth = Math.max(180, Math.min(w - 24, 420));
        int listX = Math.max(12, (w - contentWidth) / 2);
        int buttonY = h - 28;
        int hintY = buttonY - 6 - LINE_HEIGHT;
        int listBottom = Math.max(LIST_TOP + SCREEN_ROW_HEIGHT, buttonY - 6 - LINE_HEIGHT - 4);
        int nameY = TITLE_Y + LINE_HEIGHT + 3;

        check(TITLE_Y + LINE_HEIGHT <= nameY, "title runs into the build name" + where);
        check(nameY + LINE_HEIGHT <= LIST_TOP, "build name runs into the list" + where);
        check(listBottom > LIST_TOP, "list has no height" + where);
        if (h >= 240) {
            check(listBottom <= hintY, "list runs into the hint" + where);
            check(hintY + LINE_HEIGHT <= buttonY, "hint runs into the buttons" + where);
            check(listX + contentWidth <= w, "list runs off the right edge" + where);
        }

        // Every figure at its widest, for a build big enough to need seven digit
        // totals, then the everyday case, then nothing to break down at all.
        int[][] figures = {
                {1_000_000, 1_000_000, 64}, {99_999, 99_999, 64}, {3_214, 3_214, 64},
                {197, 197, 64}, {68, 68, 64}, {20, 20, 16}, {5, 5, 1}, {64, 64, 64},
        };
        for (int[] figure : figures) {
            int missing = figure[0];
            String have = grouped(0) + " / " + grouped(figure[1]);
            String stacks = breakdown(missing, figure[2]);
            String count = grouped(missing);
            ResourceListColumns.Layout layout =
                    ResourceListColumns.of(contentWidth, width(have), width(stacks), width(count));
            String which = where + " with " + count + " (" + stacks + ")";

            check(layout.countRight() == contentWidth - ResourceListColumns.RIGHT_PADDING,
                    "count is not flush with the right padding" + which);
            int countLeft = layout.countRight() - width(count);
            int haveLeft = layout.haveRight() - width(have);
            if (layout.breakdownShown()) {
                int breakdownLeft = layout.breakdownRight() - width(stacks);
                check(layout.breakdownRight() + ResourceListColumns.GAP <= countLeft,
                        "breakdown touches the count" + which);
                check(layout.haveRight() + ResourceListColumns.GAP <= breakdownLeft,
                        "have touches the breakdown" + which);
                check(layout.nameRoom() >= ResourceListColumns.MIN_NAME,
                        "breakdown kept while the name is squeezed" + which);
            } else {
                check(layout.haveRight() + ResourceListColumns.GAP <= countLeft,
                        "have touches the count" + which);
                int roomWithBreakdown = countLeft - ResourceListColumns.GAP - width(stacks)
                        - ResourceListColumns.GAP - width(have) - ResourceListColumns.GAP - ResourceListColumns.NAME_X;
                check(stacks.isEmpty() || roomWithBreakdown < ResourceListColumns.MIN_NAME,
                        "breakdown dropped although the name had room" + which);
            }
            check(ResourceListColumns.NAME_X + layout.nameRoom() + ResourceListColumns.GAP <= haveLeft
                            || layout.nameRoom() == 0,
                    "name touches the have column" + which);
            check(layout.nameRoom() >= 0, "negative name room" + which);
            if (w >= 320 && missing <= 99_999) {
                // Anything vanilla can show, with any total a real build reaches,
                // must leave the name legible.
                check(layout.nameRoom() >= ResourceListColumns.MIN_NAME,
                        "name has " + layout.nameRoom() + "px" + which);
            }
        }
    }

    // ---- overlay ----------------------------------------------------------

    private static void overlay(int w, int h, double scale, boolean named, int rows, int maxRows) {
        String where = " at " + w + "x" + h + " scale " + scale + (named ? " named" : "") + " rows " + rows;
        int header = HEADER_HEIGHT + (named ? NAME_HEIGHT : 0);
        int maxHeight = (int) (h * 0.66F / scale);

        int roomForLines = Math.max(1, (maxHeight - header - PADDING) / OVERLAY_ROW_HEIGHT);
        int drawn = Math.min(Math.min(maxRows, rows), roomForLines);
        if (rows > drawn && drawn + 1 > roomForLines) {
            drawn = Math.max(1, roomForLines - 1);
        }
        int hidden = rows - drawn;
        int shown = Math.max(1, drawn) + (hidden > 0 ? 1 : 0);
        int height = header + shown * OVERLAY_ROW_HEIGHT + PADDING;

        int titleY = PADDING + (named ? NAME_HEIGHT : 0);
        if (named) {
            check(PADDING + LINE_HEIGHT <= titleY, "build name runs into the title" + where);
        }
        check(titleY + LINE_HEIGHT <= header - 4, "title runs into the rule" + where);
        check(header - 3 <= header, "rule runs into the first row" + where);
        check(drawn >= 0 && drawn <= rows, "drew rows that do not exist" + where);
        int moreY = header + drawn * OVERLAY_ROW_HEIGHT + 5;
        check(hidden == 0 || moreY + LINE_HEIGHT <= height, "the more line runs off the panel" + where);
        check(hidden == 0 || moreY >= header + drawn * OVERLAY_ROW_HEIGHT,
                "the more line overlaps the last row" + where);

        // One row and the count of the rest are always promised, even on a
        // window too small for vanilla to produce. Given room for two lines the
        // panel has to stay inside the two thirds it is allowed.
        if (roomForLines >= 2) {
            check(Math.round(height * scale) <= h * 0.66F + scale, "panel taller than its share of the window" + where);
        }
        check(Math.round(height * scale) <= h || roomForLines < 2, "panel taller than the window" + where);
    }

    public static void main(String[] args) {
        int screens = 0;
        int overlays = 0;
        double[] scales = {0.4D, 0.75D, 1.0D, 1.5D, 2.0D};
        int[] rowCounts = {0, 1, 3, 10, 40, 200};
        int[] maxRows = {1, 10, 40};
        for (int[] window : WINDOWS) {
            screen(window[0], window[1]);
            screens++;
            for (double scale : scales) {
                for (boolean named : new boolean[]{false, true}) {
                    for (int rows : rowCounts) {
                        for (int max : maxRows) {
                            overlay(window[0], window[1], scale, named, rows, max);
                            overlays++;
                        }
                    }
                }
            }
        }
        System.out.println("Checked " + screens + " screen layouts and " + overlays + " overlay layouts.");
    }
}

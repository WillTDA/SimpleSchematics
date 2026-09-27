package dev.willtda.simpleschematics.gui;

/**
 * Where everything on the print confirmation goes, from the window size and
 * how many lines the notes wrapped to.
 *
 * <p>Kept apart from the screen, with no game types in it, so the arithmetic
 * can be checked across window sizes without starting a client. The notes
 * give way first: when the window is too short for all of them, the lines that
 * do not fit are cut and the last one shown ends in an ellipsis, so the
 * buttons are never pushed off the bottom.</p>
 */
public record PrintConfirmLayout(int textX, int textWidth, int titleY, int notesY, int visibleLines,
                                 boolean truncated, int checkboxY, int buttonsY, int printX, int cancelX,
                                 int buttonWidth) {

    public static final int MARGIN = 10;
    public static final int GAP = 6;
    public static final int LINE = 9;
    public static final int BUTTON_HEIGHT = 20;
    public static final int MAX_TEXT_WIDTH = 300;
    public static final int MAX_BUTTON_WIDTH = 120;

    /**
     * @param lines    wrapped note lines, counting the blank line between notes
     * @param checkbox whether the Don't Ask Again box is shown
     */
    public static PrintConfirmLayout of(int width, int height, int lines, boolean checkbox) {
        int textWidth = Math.max(40, Math.min(MAX_TEXT_WIDTH, width - MARGIN * 2));
        int textX = (width - textWidth) / 2;
        int buttonWidth = Math.min(MAX_BUTTON_WIDTH, (textWidth - GAP) / 2);
        int fixed = LINE + GAP * 2 + GAP * 2 + (checkbox ? BUTTON_HEIGHT + GAP : 0) + BUTTON_HEIGHT;
        int room = Math.max(LINE, height - MARGIN * 2 - fixed);
        int visible = Math.max(1, Math.min(lines, room / LINE));
        int total = fixed + visible * LINE;
        int top = Math.max(MARGIN, (height - total) / 2);
        int titleY = top;
        int notesY = titleY + LINE + GAP * 2;
        int afterNotes = notesY + visible * LINE + GAP * 2;
        int checkboxY = checkbox ? afterNotes : -1;
        int buttonsY = checkbox ? afterNotes + BUTTON_HEIGHT + GAP : afterNotes;
        int printX = width / 2 - GAP / 2 - buttonWidth;
        int cancelX = width / 2 + GAP / 2;
        return new PrintConfirmLayout(textX, textWidth, titleY, notesY, visible, visible < lines,
                checkboxY, buttonsY, printX, cancelX, buttonWidth);
    }
}

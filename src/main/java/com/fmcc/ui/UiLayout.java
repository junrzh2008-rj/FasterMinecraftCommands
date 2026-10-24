package com.fmcc.ui;

/**
 * Geometry for the right-hand panel and its rows. Kept as pure functions so layout can be checked
 * without a running client (rules: the main button is exactly two thirds of a row, the "+" button
 * is right aligned with the rows, buttons are ordered main / configure / delete).
 */
public final class UiLayout {
    private UiLayout() {
    }

    public record Panel(int x, int y, int width, int height) {
        public int contentX() {
            return x + UiTheme.PADDING;
        }

        public int contentWidth() {
            return width - 2 * UiTheme.PADDING;
        }

        public int contentRight() {
            return x + width - UiTheme.PADDING;
        }

        public int listTop() {
            return y + UiTheme.HEADER_HEIGHT;
        }

        public int listBottom() {
            return y + height - UiTheme.FOOTER_HEIGHT;
        }

        public int listHeight() {
            return listBottom() - listTop();
        }
    }

    /** One main-menu row: [main button 2/3][configure][delete]. */
    public record MainRow(int y, int mainX, int mainWidth, int configureX, int configureWidth,
                          int deleteX, int deleteWidth) {
    }

    /** One config-screen row: [text field][delete]. */
    public record FieldRow(int y, int fieldX, int fieldWidth, int deleteX, int deleteWidth) {
    }

    /** Centred modal dialog used by the "new entry" title prompt. */
    public record Dialog(int x, int y, int width, int height) {
        public int contentX() {
            return x + 14;
        }

        public int contentWidth() {
            return width - 28;
        }
    }

    public static Panel panel(int screenWidth, int screenHeight) {
        // Compact footprint: roughly one third of the screen width, with a practical minimum so the
        // three buttons still fit; shrink instead of clipping on tiny windows / extreme GUI scales.
        int available = Math.max(100, screenWidth - 2 * UiTheme.MARGIN);
        int target = Math.round(screenWidth / 3f);
        int preferred = Math.max(UiTheme.PANEL_WIDTH_MIN, Math.min(UiTheme.PANEL_WIDTH, target));
        int width = Math.min(preferred, available);
        int x = Math.max(UiTheme.MARGIN, screenWidth - width - UiTheme.MARGIN);
        // Height is reduced as well, but always leaves room for two rows plus the "+" button.
        int minimumHeight = 168;
        int preferredHeight = Math.max(minimumHeight, Math.round(screenHeight * 0.55f));
        int height = Math.min(screenHeight - 2 * UiTheme.MARGIN, preferredHeight);
        return new Panel(x, UiTheme.MARGIN, width, height);
    }

    public static int rowStride() {
        return UiTheme.ROW_HEIGHT + UiTheme.ROW_GAP;
    }

    public static MainRow mainRow(Panel panel, int index, int scrollOffset) {
        int y = panel.listTop() + index * rowStride() - scrollOffset;
        int contentWidth = panel.contentWidth();
        int mainWidth = Math.round(contentWidth * 2f / 3f);
        int configureWidth = contentWidth - mainWidth - UiTheme.DELETE_WIDTH - 2 * UiTheme.BUTTON_GAP;
        int mainX = panel.contentX();
        int configureX = mainX + mainWidth + UiTheme.BUTTON_GAP;
        int deleteX = configureX + configureWidth + UiTheme.BUTTON_GAP;
        return new MainRow(y, mainX, mainWidth, configureX, configureWidth, deleteX, UiTheme.DELETE_WIDTH);
    }

    public static FieldRow fieldRow(Panel panel, int index, int scrollOffset) {
        int y = panel.listTop() + index * rowStride() - scrollOffset;
        int fieldWidth = panel.contentWidth() - UiTheme.DELETE_WIDTH - UiTheme.BUTTON_GAP;
        int fieldX = panel.contentX();
        int deleteX = fieldX + fieldWidth + UiTheme.BUTTON_GAP;
        return new FieldRow(y, fieldX, fieldWidth, deleteX, UiTheme.DELETE_WIDTH);
    }

    /**
     * The "+" button sits directly below the last row and is right aligned with it.
     *
     * @param count number of rows (0 puts it at the top of the list)
     */
    public static int addButtonY(Panel panel, int count, int scrollOffset) {
        return panel.listTop() + count * rowStride() - scrollOffset;
    }

    public static int addButtonX(Panel panel) {
        return panel.contentRight() - UiTheme.ADD_SIZE;
    }

    public static int contentHeight(int rows) {
        return rows <= 0 ? 0 : rows * UiTheme.ROW_HEIGHT + (rows - 1) * UiTheme.ROW_GAP;
    }

    public static int maxScroll(Panel panel, int rows) {
        int content = contentHeight(rows) + UiTheme.ADD_SIZE + UiTheme.ROW_GAP;
        return Math.max(0, content - panel.listHeight());
    }

    public static Dialog dialog(int screenWidth, int screenHeight) {
        int width = Math.min(300, Math.max(220, Math.round(screenWidth / 2f)));
        int height = Math.min(124, Math.max(112, screenHeight - 2 * UiTheme.MARGIN));
        return new Dialog((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }
}

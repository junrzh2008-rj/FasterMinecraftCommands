package com.fmcc.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure layout maths: the panel hugs the right edge and is about one third of the screen, the main
 * button is exactly two thirds of a row, configure/delete follow to the right, rows share one
 * stride and the "+" button is right aligned below the last row. These are the geometry acceptance
 * criteria for the menu and are checked again in-game by the self-test harness.
 */
class UiLayoutTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static int expectedPanelWidth(int screenWidth) {
        int available = Math.max(100, screenWidth - 2 * UiTheme.MARGIN);
        int target = Math.round(screenWidth / 3f);
        int preferred = Math.max(UiTheme.PANEL_WIDTH_MIN, Math.min(UiTheme.PANEL_WIDTH, target));
        return Math.min(preferred, available);
    }

    private static int expectedPanelHeight(int screenHeight) {
        return Math.min(screenHeight - 2 * UiTheme.MARGIN,
                Math.max(168, Math.round(screenHeight * 0.55f)));
    }

    @Test
    @DisplayName("panel hugs the right edge, is roughly a third of the screen and stays compact")
    void panelGeometry() {
        UiLayout.Panel panel = UiLayout.panel(WIDTH, HEIGHT);
        assertEquals(expectedPanelWidth(WIDTH), panel.width());
        assertTrue(panel.width() <= WIDTH / 3 || panel.width() == UiTheme.PANEL_WIDTH_MIN,
                "panel should be around one third of the screen width");
        assertEquals(WIDTH - panel.width() - UiTheme.MARGIN, panel.x());
        assertEquals(UiTheme.MARGIN, panel.y());
        assertEquals(expectedPanelHeight(HEIGHT), panel.height());
        assertTrue(panel.height() < HEIGHT - 2 * UiTheme.MARGIN, "panel must be shorter than the screen");
        assertEquals(panel.x() + panel.width() - UiTheme.PADDING, panel.contentRight());
        assertEquals(panel.x() + UiTheme.PADDING, panel.contentX());
        assertEquals(panel.width() - 2 * UiTheme.PADDING, panel.contentWidth());
        assertEquals(panel.y() + UiTheme.HEADER_HEIGHT, panel.listTop());
        assertEquals(panel.y() + panel.height() - UiTheme.FOOTER_HEIGHT, panel.listBottom());
        assertEquals(panel.listBottom() - panel.listTop(), panel.listHeight());
    }

    @Test
    @DisplayName("panel shrinks instead of clipping on very narrow screens")
    void panelOnNarrowScreen() {
        UiLayout.Panel tiny = UiLayout.panel(200, 120);
        assertEquals(expectedPanelWidth(200), tiny.width());
        assertEquals(200 - tiny.width() - UiTheme.MARGIN, tiny.x());
        assertTrue(tiny.x() >= UiTheme.MARGIN);
        assertTrue(tiny.x() + tiny.width() <= 200 - UiTheme.MARGIN);
        assertEquals(expectedPanelHeight(120), tiny.height());
    }

    @Test
    @DisplayName("main row: 2/3 main button, then configure, then delete")
    void mainRowGeometry() {
        UiLayout.Panel panel = UiLayout.panel(WIDTH, HEIGHT);
        UiLayout.MainRow row = UiLayout.mainRow(panel, 0, 0);

        int contentWidth = panel.contentWidth();
        assertEquals(Math.round(contentWidth * 2f / 3f), row.mainWidth());
        assertEquals(panel.contentX(), row.mainX());
        assertEquals(row.mainX() + row.mainWidth() + UiTheme.BUTTON_GAP, row.configureX());
        assertEquals(row.configureX() + row.configureWidth() + UiTheme.BUTTON_GAP, row.deleteX());
        assertEquals(UiTheme.DELETE_WIDTH, row.deleteWidth());
        assertEquals(row.deleteX() + row.deleteWidth(), panel.contentRight());
        assertTrue(row.mainX() < row.configureX() && row.configureX() < row.deleteX());
        assertTrue(row.mainX() + row.mainWidth() <= row.configureX());
        assertTrue(row.configureX() + row.configureWidth() <= row.deleteX());
    }

    @Test
    @DisplayName("rows advance by exactly one stride and never overlap")
    void rowStride() {
        UiLayout.Panel panel = UiLayout.panel(WIDTH, HEIGHT);
        UiLayout.MainRow first = UiLayout.mainRow(panel, 0, 0);
        UiLayout.MainRow second = UiLayout.mainRow(panel, 1, 0);
        UiLayout.MainRow third = UiLayout.mainRow(panel, 2, 0);
        assertEquals(UiLayout.rowStride(), second.y() - first.y());
        assertEquals(UiLayout.rowStride(), third.y() - second.y());
        assertTrue(first.y() + UiTheme.ROW_HEIGHT <= second.y());
        assertTrue(second.y() + UiTheme.ROW_HEIGHT <= third.y());
    }

    @Test
    @DisplayName("the '+' button is right aligned and sits directly below the last row")
    void addButtonGeometry() {
        UiLayout.Panel panel = UiLayout.panel(WIDTH, HEIGHT);
        assertEquals(panel.contentRight() - UiTheme.ADD_SIZE, UiLayout.addButtonX(panel));
        assertEquals(UiLayout.addButtonX(panel) + UiTheme.ADD_SIZE, panel.contentRight());

        int emptyY = UiLayout.addButtonY(panel, 0, 0);
        assertEquals(panel.listTop(), emptyY);

        int afterOne = UiLayout.addButtonY(panel, 1, 0);
        assertEquals(panel.listTop() + UiLayout.rowStride(), afterOne);
        UiLayout.MainRow row = UiLayout.mainRow(panel, 0, 0);
        assertEquals(row.y() + UiTheme.ROW_HEIGHT + UiTheme.ROW_GAP, afterOne);
    }

    @Test
    @DisplayName("scrolling shifts rows up by the scroll offset and maxScroll grows with overflow")
    void scrolling() {
        UiLayout.Panel panel = UiLayout.panel(WIDTH, HEIGHT);
        UiLayout.MainRow unscrolled = UiLayout.mainRow(panel, 0, 0);
        UiLayout.MainRow scrolled = UiLayout.mainRow(panel, 0, UiLayout.rowStride());
        assertEquals(unscrolled.y() - UiLayout.rowStride(), scrolled.y());
        assertEquals(0, UiLayout.maxScroll(panel, 1));
        assertTrue(UiLayout.maxScroll(panel, 20) > 0);
    }

    @Test
    @DisplayName("field row: field first, delete at the content right edge")
    void fieldRowGeometry() {
        UiLayout.Panel panel = UiLayout.panel(WIDTH, HEIGHT);
        UiLayout.FieldRow row = UiLayout.fieldRow(panel, 0, 0);
        assertEquals(panel.contentX(), row.fieldX());
        assertEquals(panel.contentWidth() - UiTheme.DELETE_WIDTH - UiTheme.BUTTON_GAP, row.fieldWidth());
        assertEquals(row.fieldX() + row.fieldWidth() + UiTheme.BUTTON_GAP, row.deleteX());
        assertEquals(row.deleteX() + row.deleteWidth(), panel.contentRight());
    }

    @Test
    @DisplayName("dialog is centred, compact and sane on small screens")
    void dialogGeometry() {
        UiLayout.Dialog dialog = UiLayout.dialog(WIDTH, HEIGHT);
        assertEquals(WIDTH / 2, dialog.x() + dialog.width() / 2, 1);
        assertEquals(HEIGHT / 2, dialog.y() + dialog.height() / 2, 1);
        assertEquals(300, dialog.width());
        assertEquals(124, dialog.height());
        assertEquals(dialog.x() + 14, dialog.contentX());
        assertEquals(dialog.width() - 28, dialog.contentWidth());

        UiLayout.Dialog small = UiLayout.dialog(300, 200);
        assertTrue(small.x() >= 0 && small.y() >= 0);
        assertTrue(small.x() + small.width() <= 300);
        assertTrue(small.y() + small.height() <= 200);
        assertTrue(small.width() < dialog.width());
    }

    @Test
    @DisplayName("content height counts rows and gaps only")
    void contentHeight() {
        assertEquals(0, UiLayout.contentHeight(0));
        assertEquals(UiTheme.ROW_HEIGHT, UiLayout.contentHeight(1));
        assertEquals(UiTheme.ROW_HEIGHT * 2 + UiTheme.ROW_GAP, UiLayout.contentHeight(2));
    }
}

package com.fmcc.ui;

/**
 * Central visual constants for the FMCC menu.
 * <p>
 * New look (player feedback): a compact, black 50% translucent panel with white outlines, buttons
 * that share the panel background and are separated only by white wireframes, white text.
 * Everything is in GUI-scaled pixels and ARGB colours so the look is identical at every GUI scale
 * and independent of resource packs.
 */
public final class UiTheme {
    private UiTheme() {
    }

    // ---- layout -------------------------------------------------------------
    public static final int MARGIN = 10;
    /** Preferred maximum panel width; the actual width is about one third of the screen. */
    public static final int PANEL_WIDTH = 340;
    /** Smallest usable panel width, so the three buttons still fit on tiny windows. */
    public static final int PANEL_WIDTH_MIN = 240;
    public static final int PADDING = 10;
    public static final int HEADER_HEIGHT = 40;
    public static final int FOOTER_HEIGHT = 24;
    public static final int ROW_HEIGHT = 26;
    public static final int ROW_GAP = 6;
    public static final int BUTTON_GAP = 4;
    public static final int DELETE_WIDTH = 26;
    public static final int ADD_SIZE = 26;
    public static final int CORNER_RADIUS = 6;

    // ---- colours (ARGB) -----------------------------------------------------
    /** The panel itself carries the darkening; no extra full-screen veil is drawn. */
    public static final int DIM = 0x00000000;
    /** Black at 50% opacity - the panel background. */
    public static final int PANEL_BG = 0x80000000;
    public static final int PANEL_BG_TOP = 0x80000000;
    /** White wireframe around the panel. */
    public static final int PANEL_BORDER = 0xB3FFFFFF;
    public static final int PANEL_SHADOW = 0x40000000;
    public static final int SEPARATOR = 0x33FFFFFF;

    public static final int ACCENT = 0xFFFFFFFF;
    public static final int ACCENT_HOVER = 0xFFFFFFFF;
    public static final int ACCENT_SOFT = 0x33FFFFFF;

    // Buttons are transparent so they literally share the panel background; the white wireframe
    // and a faint white wash on hover are the only differences.
    public static final int BUTTON_BG = 0x00000000;
    public static final int BUTTON_BG_HOVER = 0x1AFFFFFF;
    public static final int BUTTON_BG_DOWN = 0x26FFFFFF;
    public static final int BUTTON_BORDER = 0x80FFFFFF;
    public static final int BUTTON_BORDER_HOVER = 0xFFFFFFFF;

    public static final int CONFIGURE_BG = 0x00000000;
    public static final int CONFIGURE_BG_HOVER = 0x1AFFFFFF;

    public static final int DANGER = 0xFFFFFFFF;
    public static final int DANGER_BG = 0x00000000;
    public static final int DANGER_BG_HOVER = 0x1AFFFFFF;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_MUTED = 0xD9FFFFFF;
    public static final int TEXT_FAINT = 0x99FFFFFF;
    public static final int TEXT_ON_ACCENT = 0xFFFFFFFF;

    public static final int FIELD_BG = 0x00000000;
    public static final int FIELD_BORDER = 0x80FFFFFF;
    public static final int FIELD_BORDER_FOCUS = 0xFFFFFFFF;
    public static final int FIELD_TEXT = 0xFFFFFFFF;

    public static final int SUCCESS = 0xFFFFFFFF;
    public static final int TOOLTIP_BG = 0xCC000000;
    public static final int TOOLTIP_BORDER = 0xCCFFFFFF;
}


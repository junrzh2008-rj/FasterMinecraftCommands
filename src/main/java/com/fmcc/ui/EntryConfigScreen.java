package com.fmcc.ui;

import com.fmcc.FmccClient;
import com.fmcc.config.ConfigStore;
import com.fmcc.config.FmccEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-entry configuration screen: an ordered, scrollable list with one text field per
 * {@link FmccEntry#getTexts()} index, a "-" button on every row and a "+" button below the last
 * row. "Back" (top right) and ESC both return to the main menu.
 * <p>
 * Every keystroke is pushed into the shared {@link ConfigStore} (which persists immediately - the
 * config file is tiny), so leaving the screen never loses text. Structural changes ("+"/"-")
 * rebuild the row widgets so the captured row indices always match the model order.
 */
public class EntryConfigScreen extends Screen {
    // Cached translations: no per-frame Text allocation in the render loop.
    private static final Text TITLE_TEXT = Text.translatable("fmcc.config.title");
    private static final Text BACK_TEXT = Text.translatable("fmcc.config.back");
    private static final Text ADD_TEXT = Text.translatable("fmcc.config.add");
    private static final Text DELETE_TEXT = Text.translatable("fmcc.menu.delete");
    private static final Text EMPTY_TEXT = Text.translatable("fmcc.config.empty");
    private static final Text EMPTY_HINT_TEXT = Text.translatable("fmcc.config.empty_hint");
    private static final Text HINT_TEXT = Text.translatable("fmcc.config.hint");
    private static final Text PLACEHOLDER_TEXT = Text.translatable("fmcc.config.placeholder");
    private static final Text TOOLTIP_ADD = Text.translatable("fmcc.config.tooltip.add");
    private static final Text TOOLTIP_DELETE = Text.translatable("fmcc.config.tooltip.delete");

    /** Upper bound for a single sendable text; keeps huge clipboard pastes reasonable. */
    private static final int MAX_TEXT_LENGTH = 256;

    /** Horizontal padding between the rounded field background and the editable text. */
    private static final int FIELD_INSET_X = 6;
    /**
     * Vertical padding of the editable text inside a {@link UiTheme#ROW_HEIGHT} high field. The
     * vanilla field only left-aligns text at the top of its own bounds when no background is
     * drawn, so the widget rectangle is the text line itself and the rounded background is ours.
     */
    private static final int FIELD_INSET_Y = 8;
    private static final int FIELD_WIDGET_HEIGHT = 10;

    private static final int SCROLLBAR_WIDTH = 3;
    private static final int BACK_BUTTON_HEIGHT = 20;

    private final FmccEntry entry;
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<FmccButton> deleteButtons = new ArrayList<>();

    private UiLayout.Panel panel;
    private FmccButton backButton;
    private FmccButton addButton;
    private int scrollOffset;
    /** Row index that should receive focus after the next rebuild (set by "+"). */
    private int pendingFocusIndex = -1;

    public EntryConfigScreen(FmccEntry entry) {
        super(TITLE_TEXT);
        this.entry = entry == null ? new FmccEntry("", "", List.of()) : entry;
    }

    public FmccEntry entry() {
        return this.entry;
    }

    @Override
    protected void init() {
        this.panel = UiLayout.panel(this.width, this.height);
        this.fields.clear();
        this.deleteButtons.clear();
        List<String> texts = this.entry.getTexts();
        this.scrollOffset = this.pendingFocusIndex >= 0
                ? this.offsetForRow(this.pendingFocusIndex, texts.size())
                : this.clampScroll(this.scrollOffset, texts.size());
        this.addChrome();
        this.addTextRows(texts);
        this.applyLayout();
    }

    // ------------------------------------------------------------------ widgets

    private void addChrome() {
        int backWidth = Math.min(this.panel.contentWidth() - 24,
                Math.max(48, this.textRenderer.getWidth(BACK_TEXT) + 18));
        this.backButton = this.addDrawableChild(new FmccButton(
                this.panel.contentRight() - backWidth,
                this.panel.y() + (UiTheme.HEADER_HEIGHT - BACK_BUTTON_HEIGHT) / 2,
                backWidth, BACK_BUTTON_HEIGHT,
                BACK_TEXT,
                FmccButton.Style.SECONDARY,
                this::backToMenu));
        this.addButton = this.addDrawableChild(new FmccButton(
                UiLayout.addButtonX(this.panel),
                UiLayout.addButtonY(this.panel, this.entry.getTexts().size(), this.scrollOffset),
                UiTheme.ADD_SIZE, UiTheme.ADD_SIZE,
                ADD_TEXT,
                FmccButton.Style.PRIMARY,
                this::onAddText)
                .compact()
                .tooltip(TOOLTIP_ADD));
    }

    private void addTextRows(List<String> texts) {
        for (int index = 0; index < texts.size(); index++) {
            UiLayout.FieldRow row = UiLayout.fieldRow(this.panel, index, this.scrollOffset);
            TextFieldWidget field = new TextFieldWidget(this.textRenderer,
                    row.fieldX() + FIELD_INSET_X,
                    row.y() + FIELD_INSET_Y,
                    row.fieldWidth() - 2 * FIELD_INSET_X,
                    FIELD_WIDGET_HEIGHT,
                    Text.empty());
            field.setDrawsBackground(false);
            field.setMaxLength(MAX_TEXT_LENGTH);
            field.setEditableColor(UiTheme.FIELD_TEXT);
            field.setUneditableColor(UiTheme.TEXT_FAINT);
            field.setPlaceholder(PLACEHOLDER_TEXT);
            field.setText(texts.get(index) == null ? "" : texts.get(index));
            // Listener last: setText() above must never be interpreted as a user edit.
            final int textIndex = index;
            field.setChangedListener(value -> this.onTextChanged(textIndex, value));
            this.fields.add(field);
            this.addDrawableChild(field);

            FmccButton delete = new FmccButton(row.deleteX(), row.y(), row.deleteWidth(), UiTheme.ROW_HEIGHT,
                    DELETE_TEXT, FmccButton.Style.GHOST,
                    () -> this.onDeleteText(textIndex))
                    .compact()
                    .tooltip(TOOLTIP_DELETE);
            this.deleteButtons.add(delete);
            this.addDrawableChild(delete);
        }
    }

    /** Repositions every widget for the current {@link #scrollOffset} and culls clipped rows. */
    private void applyLayout() {
        if (this.panel == null) {
            return;
        }
        int listTop = this.panel.listTop();
        int listBottom = this.panel.listBottom();
        if (this.addButton != null) {
            int addY = UiLayout.addButtonY(this.panel, this.entry.getTexts().size(), this.scrollOffset);
            this.addButton.setX(UiLayout.addButtonX(this.panel));
            this.addButton.setY(addY);
            this.addButton.visible = addY >= listTop && addY + UiTheme.ADD_SIZE <= listBottom;
        }
        for (int index = 0; index < this.fields.size(); index++) {
            UiLayout.FieldRow row = UiLayout.fieldRow(this.panel, index, this.scrollOffset);
            // Overlap-based culling: a partially visible row must still render (the list scissor
            // clips it), otherwise the last scroll step leaves a blank strip.
            boolean visible = row.y() + UiTheme.ROW_HEIGHT > listTop && row.y() < listBottom;
            TextFieldWidget field = this.fields.get(index);
            field.setWidth(row.fieldWidth() - 2 * FIELD_INSET_X);
            field.setX(row.fieldX() + FIELD_INSET_X);
            field.setY(row.y() + FIELD_INSET_Y);
            field.visible = visible;
            if (index < this.deleteButtons.size()) {
                FmccButton delete = this.deleteButtons.get(index);
                delete.setX(row.deleteX());
                delete.setY(row.y());
                delete.visible = visible;
            }
        }
    }

    // ------------------------------------------------------------------- model

    private void onTextChanged(int index, String value) {
        // Keep this off the disk path: typing must not touch the file system. The live model is
        // updated here and flushed on close / structural changes / client shutdown.
        List<String> texts = this.entry.getTexts();
        if (index >= 0 && index < texts.size()) {
            String next = value == null ? "" : value;
            if (!next.equals(texts.get(index))) {
                texts.set(index, next);
                ConfigStore store = FmccClient.store();
                if (store != null) {
                    store.markDirty();
                }
            }
        }
    }

    private void onAddText() {
        ConfigStore store = FmccClient.store();
        int index;
        if (store != null) {
            index = store.addText(this.entry);
        } else {
            List<String> texts = this.entry.getTexts();
            texts.add("");
            index = texts.size() - 1;
        }
        this.pendingFocusIndex = index;
        // Rebuild synchronously: indices in the captured listeners must follow the model order.
        this.clearAndInit();
    }

    private void onDeleteText(int index) {
        ConfigStore store = FmccClient.store();
        if (store != null) {
            store.removeText(this.entry, index);
        } else {
            List<String> texts = this.entry.getTexts();
            if (index >= 0 && index < texts.size()) {
                texts.remove(index);
            }
        }
        this.pendingFocusIndex = -1;
        this.scrollOffset = this.clampScroll(this.scrollOffset, this.entry.getTexts().size());
        this.clearAndInit();
    }

    /** Picks up programmatic field edits (e.g. from an automated self test) once per tick. */
    private void syncFromWidgets() {
        List<String> texts = this.entry.getTexts();
        ConfigStore store = FmccClient.store();
        int limit = Math.min(this.fields.size(), texts.size());
        for (int index = 0; index < limit; index++) {
            String value = this.fields.get(index).getText();
            if (value == null || value.equals(texts.get(index))) {
                continue;
            }
            texts.set(index, value);
            if (store != null) {
                store.markDirty();
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void backToMenu() {
        if (this.client != null) {
            // Re-reads the store on init, so every edit is visible immediately.
            this.client.setScreen(new FmccScreen());
        }
    }

    private void focusPending() {
        if (this.pendingFocusIndex < 0) {
            return;
        }
        int index = this.pendingFocusIndex;
        this.pendingFocusIndex = -1;
        if (index >= 0 && index < this.fields.size()) {
            this.setFocused(this.fields.get(index));
            this.fields.get(index).setCursorToEnd(true);
        }
    }

    /**
     * Drops focus from a widget that a rebuild removed from the screen (otherwise keystrokes would
     * keep going to a widget that is no longer rendered or part of the screen).
     */
    private void dropStaleFocus() {
        Element focused = this.getFocused();
        if (focused != null && !this.children().contains(focused)) {
            this.setFocused((Element) null);
        }
    }

    // ------------------------------------------------------------------ layout

    private int clampScroll(int value, int rows) {
        if (this.panel == null) {
            return 0;
        }
        int max = UiLayout.maxScroll(this.panel, rows);
        return Math.max(0, Math.min(max, value));
    }

    /** Smallest scroll offset (never scrolling further than needed) that shows the whole row. */
    private int offsetForRow(int index, int rows) {
        if (this.panel == null || index < 0) {
            return 0;
        }
        int rowTop = index * UiLayout.rowStride();
        int offset = this.scrollOffset;
        if (rowTop < offset) {
            offset = rowTop;
        } else if (rowTop + UiTheme.ROW_HEIGHT > offset + this.panel.listHeight()) {
            // Jump to the end so the freshly appended row sits right above the "+" button.
            offset = UiLayout.maxScroll(this.panel, rows);
        }
        return this.clampScroll(offset, rows);
    }

    private boolean isRowVisible(int index) {
        if (this.panel == null) {
            return false;
        }
        UiLayout.FieldRow row = UiLayout.fieldRow(this.panel, index, this.scrollOffset);
        return row.y() >= this.panel.listTop() && row.y() + UiTheme.ROW_HEIGHT <= this.panel.listBottom();
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return this.panel != null
                && mouseX >= this.panel.x() && mouseX <= this.panel.x() + this.panel.width()
                && mouseY >= this.panel.listTop() && mouseY <= this.panel.listBottom();
    }

    // ------------------------------------------------------------------ events

    @Override
    public void tick() {
        super.tick();
        this.focusPending();
        this.dropStaleFocus();
        this.syncFromWidgets();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.panel == null) {
            return false;
        }
        if (!this.isInsideList(mouseX, mouseY)) {
            // Only the header controls may react outside the list; clipped rows must never fire.
            if (this.backButton != null && this.backButton.visible
                    && this.backButton.isMouseOver(mouseX, mouseY)) {
                boolean handled = super.mouseClicked(mouseX, mouseY, button);
                this.focusPending();
                this.dropStaleFocus();
                return handled;
            }
            return false;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled && button == 0) {
            handled = this.focusFieldAt(mouseX, mouseY);
        }
        // super.mouseClicked() may have moved the screen focus onto a widget that "+" replaced.
        this.focusPending();
        this.dropStaleFocus();
        return handled;
    }

    /** Focuses the field whose whole rounded background was clicked (not just its text line). */
    private boolean focusFieldAt(double mouseX, double mouseY) {
        for (int index = 0; index < this.fields.size(); index++) {
            if (!this.isRowVisible(index)) {
                continue;
            }
            UiLayout.FieldRow row = UiLayout.fieldRow(this.panel, index, this.scrollOffset);
            if (mouseX >= row.fieldX() && mouseX <= row.fieldX() + row.fieldWidth()
                    && mouseY >= row.y() && mouseY <= row.y() + UiTheme.ROW_HEIGHT) {
                this.setFocused(this.fields.get(index));
                return true;
            }
        }
        return false;
    }

    /**
     * Scrolls by whole rows: every row is then either fully inside the list or fully outside it, so
     * the row widgets never need to be clipped mid-row (they are simple drawables, not scissored).
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.panel == null || verticalAmount == 0.0 || !this.isInsideList(mouseX, mouseY)) {
            return false;
        }
        int rows = this.entry.getTexts().size();
        int max = UiLayout.maxScroll(this.panel, rows);
        if (max <= 0) {
            return false;
        }
        int notch = (int) Math.round(verticalAmount);
        if (notch == 0) {
            return true;
        }
        int stride = UiLayout.rowStride();
        int next = this.scrollOffset - notch * stride;
        if (next <= 0) {
            next = 0;
        } else if (next >= max) {
            next = max;
        } else {
            next = Math.round((float) next / stride) * stride;
        }
        if (next == this.scrollOffset) {
            return true;
        }
        this.scrollOffset = next;
        this.applyLayout();
        return true;
    }

    @Override
    public void close() {
        this.flushPendingEdits();
        this.backToMenu();
    }

    @Override
    public void removed() {
        // Also reached when another screen replaces this one (e.g. disconnect).
        this.flushPendingEdits();
    }

    private void flushPendingEdits() {
        ConfigStore store = FmccClient.store();
        if (store != null && store.isDirty()) {
            store.save();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1.21.8: Screen.renderWithTooltip already invoked renderBackground(); add the veil on top.
        context.fill(0, 0, this.width, this.height, UiTheme.DIM);
        if (this.panel != null) {
            this.renderPanel(context);
            this.renderHeader(context);
            this.renderList(context);
            this.renderFooter(context);
        }
        super.render(context, mouseX, mouseY, delta);
        this.renderTooltips(context, mouseX, mouseY);
    }

    private void renderPanel(DrawContext context) {
        int x = this.panel.x();
        int y = this.panel.y();
        int width = this.panel.width();
        int height = this.panel.height();
        Draw.shadow(context, x, y, width, height, UiTheme.CORNER_RADIUS);
        // Same flat black 50% + white wireframe look as the main menu.
        Draw.roundedBorder(context, x, y, width, height, UiTheme.CORNER_RADIUS,
                UiTheme.PANEL_BORDER, UiTheme.PANEL_BG);
        context.fill(x + 1, this.panel.listTop() - 1, x + width - 1, this.panel.listTop(), UiTheme.SEPARATOR);
        context.fill(x + 1, this.panel.listBottom(), x + width - 1, this.panel.listBottom() + 1, UiTheme.SEPARATOR);
    }

    private void renderHeader(DrawContext context) {
        int x = this.panel.contentX();
        int y = this.panel.y();
        Draw.roundedRect(context, x, y + 12, 3, 16, 1, UiTheme.ACCENT);
        int titleX = x + 9;
        int titleLimit = Math.max(24,
                (this.backButton == null ? this.panel.contentRight() : this.backButton.getX()) - 8 - titleX);
        String title = Draw.ellipsize(this.textRenderer, this.entry.getTitle(), titleLimit);
        Draw.boldString(context, this.textRenderer, title, titleX, y + 13, UiTheme.TEXT);
        Draw.text(context, this.textRenderer, TITLE_TEXT, titleX, y + 26, UiTheme.TEXT_MUTED);
    }

    private void renderList(DrawContext context) {
        List<String> texts = this.entry.getTexts();
        int listLeft = this.panel.contentX() - 2;
        int listTop = this.panel.listTop();
        int listRight = this.panel.x() + this.panel.width() - 2;
        int listBottom = this.panel.listBottom();
        context.enableScissor(listLeft, listTop, listRight, listBottom);
        if (texts.isEmpty()) {
            this.renderEmptyState(context);
        } else {
            int limit = Math.min(texts.size(), this.fields.size());
            for (int index = 0; index < limit; index++) {
                if (!this.isRowVisible(index)) {
                    continue;
                }
                UiLayout.FieldRow row = UiLayout.fieldRow(this.panel, index, this.scrollOffset);
                boolean focused = this.fields.get(index).isFocused();
                int border = focused ? UiTheme.FIELD_BORDER_FOCUS : UiTheme.FIELD_BORDER;
                int fill = focused ? Draw.lerpColor(UiTheme.FIELD_BG, UiTheme.ACCENT, 0.06f) : UiTheme.FIELD_BG;
                Draw.roundedFrame(context, row.fieldX(), row.y(), row.fieldWidth(), UiTheme.ROW_HEIGHT,
                        UiTheme.CORNER_RADIUS, border, fill);
            }
            this.renderScrollbar(context, texts.size());
        }
        context.disableScissor();
    }

    private void renderEmptyState(DrawContext context) {
        int centerX = this.panel.contentX() + this.panel.contentWidth() / 2;
        int centerY = this.panel.listTop() + this.panel.listHeight() / 2;
        int boxHeight = Math.min(46, Math.max(16, this.panel.listHeight() - 4));
        if (boxHeight >= 34) {
            int boxWidth = Math.min(this.panel.contentWidth(), 244);
            int boxX = centerX - boxWidth / 2;
            int boxY = centerY - boxHeight / 2;
            Draw.roundedBorder(context, boxX, boxY, boxWidth, boxHeight, 5, 0x66FFFFFF, 0x40000000);
            Draw.centered(context, this.textRenderer, EMPTY_TEXT, centerX, boxY + 11, UiTheme.TEXT_MUTED);
            Draw.centered(context, this.textRenderer, EMPTY_HINT_TEXT, centerX, boxY + 25, UiTheme.TEXT_FAINT);
        } else {
            Draw.centered(context, this.textRenderer, EMPTY_TEXT, centerX, centerY - 10, UiTheme.TEXT_MUTED);
            Draw.centered(context, this.textRenderer, EMPTY_HINT_TEXT, centerX, centerY + 1, UiTheme.TEXT_FAINT);
        }
    }

    private void renderScrollbar(DrawContext context, int rows) {
        int max = UiLayout.maxScroll(this.panel, rows);
        if (max <= 0) {
            return;
        }
        int trackX = this.panel.x() + this.panel.width() - 7;
        int trackTop = this.panel.listTop() + 2;
        int trackHeight = Math.max(4, this.panel.listHeight() - 4);
        int content = UiLayout.contentHeight(rows) + UiTheme.ADD_SIZE + UiTheme.ROW_GAP;
        int thumbHeight = Math.max(16,
                (int) ((long) trackHeight * this.panel.listHeight() / Math.max(1, content)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int travel = trackHeight - thumbHeight;
        int thumbY = trackTop + (travel <= 0 ? 0
                : (int) ((long) travel * this.scrollOffset / max));
        Draw.roundedRect(context, trackX, trackTop, SCROLLBAR_WIDTH, trackHeight, 1, 0x33FFFFFF);
        Draw.roundedRect(context, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, 1, 0xCCFFFFFF);
    }

    private void renderFooter(DrawContext context) {
        int footerTop = this.panel.y() + this.panel.height() - UiTheme.FOOTER_HEIGHT;
        int textY = footerTop + (UiTheme.FOOTER_HEIGHT - this.textRenderer.fontHeight) / 2;
        String hint = Draw.ellipsize(this.textRenderer, HINT_TEXT.getString(), this.panel.contentWidth());
        Draw.text(context, this.textRenderer, Text.literal(hint),
                this.panel.contentX(), textY, UiTheme.TEXT_MUTED);
    }

    private void renderTooltips(DrawContext context, int mouseX, int mouseY) {
        List<Text> lines = List.of();
        if (this.addButton != null && this.addButton.isHovered()) {
            lines = this.addButton.tooltipLines();
        }
        if (lines.isEmpty()) {
            for (FmccButton delete : this.deleteButtons) {
                if (delete.isHovered()) {
                    lines = delete.tooltipLines();
                    break;
                }
            }
        }
        if (!lines.isEmpty()) {
            Draw.tooltip(context, this.textRenderer, lines, mouseX, mouseY);
        }
    }
}

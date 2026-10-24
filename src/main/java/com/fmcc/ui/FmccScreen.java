package com.fmcc.ui;

import com.fmcc.FmccClient;
import com.fmcc.chat.MessageQueue;
import com.fmcc.config.ConfigStore;
import com.fmcc.config.FmccEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Main FMCC menu: a modern right-hand panel listing every configured entry.
 * <p>
 * One row per entry, left to right: send button (2/3 of the row, label = entry title), "Configure"
 * and "-" (delete). The "+" button sits below the last row, right aligned with the rows. The list
 * scrolls with the mouse wheel when it does not fit, and every widget is a real
 * {@link FmccButton} child so clicks can be driven by coordinates (self-test) as well as by a
 * player. All data is read from {@link FmccClient#store()} and re-read every time the screen is
 * shown, so changes made in the config/add screens are always reflected here.
 * <p>
 * OWNER: ui-main.
 */
public class FmccScreen extends Screen {
    /** How long the in-panel status toast stays visible. */
    private static final long TOAST_DURATION_MS = 2600L;
    private static final long TOAST_FADE_IN_MS = 120L;
    private static final long TOAST_FADE_OUT_MS = 220L;
    /**
     * Ignore a second delete click right after a successful one: the rows reflow under the cursor,
     * so a double click would otherwise delete two entries.
     */
    private static final long DELETE_GUARD_MS = 350L;

    // Cached Text constants: render() must not allocate translations on every frame.
    private static final Text TITLE_TEXT = Text.translatable("fmcc.menu.title");
    private static final Text SUBTITLE_TEXT = Text.translatable("fmcc.menu.subtitle");
    private static final Text ADD_LABEL = Text.translatable("fmcc.menu.add");
    private static final Text CONFIGURE_LABEL = Text.translatable("fmcc.menu.configure");
    /** Fallback shown when the panel is narrow and "Configure" would not fit. */
    private static final Text CONFIGURE_LABEL_SHORT = Text.translatable("fmcc.menu.configure.short");
    private static final Text DELETE_LABEL = Text.translatable("fmcc.menu.delete");
    private static final Text EMPTY_TEXT = Text.translatable("fmcc.menu.empty");
    private static final Text EMPTY_HINT_TEXT = Text.translatable("fmcc.menu.empty_hint");
    private static final Text CLOSE_HINT_TEXT = Text.translatable("fmcc.menu.close_hint");
    private static final Text TOOLTIP_ADD = Text.translatable("fmcc.menu.tooltip.add");
    private static final Text TOOLTIP_CONFIGURE = Text.translatable("fmcc.menu.tooltip.configure");
    private static final Text TOOLTIP_DELETE = Text.translatable("fmcc.menu.tooltip.delete");
    private static final Text NO_TEXTS_TEXT = Text.translatable("fmcc.menu.no_texts");

    private final List<Row> rows = new ArrayList<>();
    private List<FmccEntry> entries = List.of();
    private UiLayout.Panel panel = new UiLayout.Panel(0, 0, UiTheme.PANEL_WIDTH, 240);
    private FmccButton addButton;
    private int scrollOffset;
    private Text countText = Text.empty();
    private int countValue = -1;
    private final Toast toast = new Toast();
    private long lastDeleteMs = Long.MIN_VALUE / 2;

    public FmccScreen() {
        super(TITLE_TEXT);
    }

    // ---- lifecycle ----------------------------------------------------------

    @Override
    protected void init() {
        this.rebuild();
    }

    @Override
    public void onDisplayed() {
        // MinecraftClient.setScreen calls onDisplayed() before init(); reading here keeps the
        // very first frame in sync even if an earlier screen mutated the config.
        this.readEntries();
    }

    /**
     * Re-reads the config store and rebuilds every widget. Safe to call from other screens or the
     * self-test at any time; no-op-ish when nothing changed (small config, tiny files).
     */
    public void refresh() {
        this.rebuild();
    }

    /** Rebuilds children/data from scratch. Also used after deleting an entry. */
    private void rebuild() {
        this.clearChildren();
        this.setFocused((Element) null);
        this.rows.clear();
        this.readEntries();
        this.createWidgets();
        this.layout();
    }

    private void readEntries() {
        ConfigStore store = FmccClient.store();
        this.entries = store == null ? List.of() : store.entries();
    }

    private void createWidgets() {
        for (FmccEntry entry : this.entries) {
            FmccButton main = new FmccButton(0, 0, 1, UiTheme.ROW_HEIGHT,
                    Text.literal(entry.getTitle()), FmccButton.Style.PRIMARY, () -> this.sendEntry(entry));
            FmccButton configure = new FmccButton(0, 0, 1, UiTheme.ROW_HEIGHT,
                    CONFIGURE_LABEL, FmccButton.Style.SECONDARY, () -> this.openConfig(entry));
            configure.tooltip(TOOLTIP_CONFIGURE);
            FmccButton delete = new FmccButton(0, 0, UiTheme.DELETE_WIDTH, UiTheme.ROW_HEIGHT,
                    DELETE_LABEL, FmccButton.Style.DANGER, () -> this.deleteEntry(entry)).compact();
            delete.tooltip(TOOLTIP_DELETE);

            this.rows.add(new Row(entry, main, configure, delete));
            this.addDrawableChild(main);
            this.addDrawableChild(configure);
            this.addDrawableChild(delete);
        }

        this.addButton = new FmccButton(0, 0, UiTheme.ADD_SIZE, UiTheme.ADD_SIZE,
                ADD_LABEL, FmccButton.Style.ACCENT, this::openAdd).compact();
        this.addButton.tooltip(TOOLTIP_ADD);
        this.addDrawableChild(this.addButton);
    }

    /**
     * Recomputes the panel and pushes {@link UiLayout} geometry into the widgets. Called from
     * {@link #init()} and whenever the scroll offset changes.
     */
    private void layout() {
        this.panel = UiLayout.panel(this.width, this.height);
        int maxScroll = this.maxScroll();
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));

        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            UiLayout.MainRow geometry = UiLayout.mainRow(this.panel, i, this.scrollOffset);
            this.position(row.main(), geometry.mainX(), geometry.y(), geometry.mainWidth(), UiTheme.ROW_HEIGHT);
            this.position(row.configure(), geometry.configureX(), geometry.y(), geometry.configureWidth(),
                    UiTheme.ROW_HEIGHT);
            this.position(row.delete(), geometry.deleteX(), geometry.y(), geometry.deleteWidth(), UiTheme.ROW_HEIGHT);
            // Compact panel: fall back to a short label instead of showing "Conf…".
            Text configureLabel = this.textRenderer.getWidth(CONFIGURE_LABEL) + 6 <= geometry.configureWidth()
                    ? CONFIGURE_LABEL : CONFIGURE_LABEL_SHORT;
            if (!configureLabel.equals(row.configure().getMessage())) {
                row.configure().setMessage(configureLabel);
            }
            if (row.entry().sendableTexts().isEmpty()) {
                row.main().tooltip(NO_TEXTS_TEXT);
            } else {
                row.main().tooltip();
            }
        }

        if (this.addButton != null) {
            int addY = UiLayout.addButtonY(this.panel, this.rows.size(), this.scrollOffset);
            this.position(this.addButton, UiLayout.addButtonX(this.panel), addY, UiTheme.ADD_SIZE, UiTheme.ADD_SIZE);
        }

        if (this.countValue != this.entries.size()) {
            this.countValue = this.entries.size();
            this.countText = this.countValue == 1
                    ? Text.translatable("fmcc.menu.count.one")
                    : Text.translatable("fmcc.menu.count", this.countValue);
        }
    }

    private void position(FmccButton button, int x, int y, int width, int height) {
        button.setX(x);
        button.setY(y);
        button.setWidth(width);
        button.setHeight(height);
        // Rows scrolled fully out of the viewport stay clickable otherwise (isMouseOver ignores the
        // scissor), so hide them; partially visible rows are clipped by the scissor while rendering.
        button.visible = y + height > this.panel.listTop() && y < this.panel.listBottom();
    }

    // ---- rendering ----------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Screen.renderWithTooltip already drew the vanilla in-world backdrop, so only a translucent
        // veil is added here (same backdrop darkness as EntryConfigScreen / AddEntryScreen).
        context.fill(0, 0, this.width, this.height, UiTheme.DIM);

        this.drawPanel(context);
        this.drawHeader(context);

        context.enableScissor(this.panel.contentX(), this.panel.listTop(),
                this.panel.contentRight(), this.panel.listBottom());
        if (this.entries.isEmpty()) {
            this.drawEmptyState(context);
        }
        // Rows and the "+" button are normal addDrawableChild widgets; rendering them while the
        // scissor is active clips the scrolled edges exactly like a scroll view.
        super.render(context, mouseX, mouseY, delta);
        context.disableScissor();

        this.drawScrollbar(context);
        this.drawFooter(context);
        this.drawToast(context);
        this.drawTooltips(context, mouseX, mouseY);
    }

    private void drawPanel(DrawContext context) {
        UiLayout.Panel p = this.panel;
        Draw.shadow(context, p.x(), p.y(), p.width(), p.height(), UiTheme.CORNER_RADIUS);
        // Single flat black 50% fill plus a white wireframe: no extra bands, so the opacity the
        // player asked for is exactly what is drawn.
        Draw.roundedBorder(context, p.x(), p.y(), p.width(), p.height(),
                UiTheme.CORNER_RADIUS, UiTheme.PANEL_BORDER, UiTheme.PANEL_BG);
        context.fill(p.x() + 1, p.listTop() - 1, p.x() + p.width() - 1, p.listTop(), UiTheme.SEPARATOR);
        context.fill(p.x() + 1, p.listBottom(), p.x() + p.width() - 1, p.listBottom() + 1, UiTheme.SEPARATOR);
    }

    private void drawHeader(DrawContext context) {
        int x = this.panel.contentX();
        int y = this.panel.y();
        Draw.roundedRect(context, x, y + 12, 3, 16, 1, UiTheme.ACCENT);
        Draw.boldText(context, this.textRenderer, TITLE_TEXT, x + 9, y + 13, UiTheme.TEXT);
        Draw.text(context, this.textRenderer, SUBTITLE_TEXT, x + 9, y + 26, UiTheme.TEXT_MUTED);
    }

    private void drawEmptyState(DrawContext context) {
        int centerX = this.panel.x() + this.panel.width() / 2;
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

    private void drawScrollbar(DrawContext context) {
        int maxScroll = this.maxScroll();
        if (maxScroll <= 0) {
            return;
        }
        int trackX = this.panel.x() + this.panel.width() - 7;
        int trackTop = this.panel.listTop() + 2;
        int trackHeight = Math.max(4, this.panel.listHeight() - 4);
        int contentHeight = trackHeight + maxScroll;
        int thumbHeight = Math.max(16, Math.round((float) trackHeight * trackHeight / contentHeight));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int travel = trackHeight - thumbHeight;
        int thumbY = trackTop + (travel <= 0 ? 0 : Math.round((float) travel * this.scrollOffset / maxScroll));

        Draw.roundedRect(context, trackX, trackTop, 3, trackHeight, 1, 0x33FFFFFF);
        Draw.roundedRect(context, trackX, thumbY, 3, thumbHeight, 1, 0xCCFFFFFF);
    }

    private void drawFooter(DrawContext context) {
        int footerTop = this.panel.y() + this.panel.height() - UiTheme.FOOTER_HEIGHT;
        int textY = footerTop + (UiTheme.FOOTER_HEIGHT - this.textRenderer.fontHeight) / 2;
        Draw.text(context, this.textRenderer, this.countText, this.panel.contentX(), textY, UiTheme.TEXT_MUTED);
        int countWidth = this.textRenderer.getWidth(this.countText);
        int available = Math.max(0, this.panel.contentWidth() - countWidth - 6);
        String hint = Draw.ellipsize(this.textRenderer, CLOSE_HINT_TEXT.getString(), available);
        int hintWidth = this.textRenderer.getWidth(hint);
        Draw.text(context, this.textRenderer, Text.literal(hint), this.panel.contentRight() - hintWidth, textY,
                UiTheme.TEXT_FAINT);
    }

    private void drawToast(DrawContext context) {
        if (!this.toast.visible) {
            return;
        }
        long age = Util.getMeasuringTimeMs() - this.toast.shownAt;
        if (age < 0L || age > TOAST_DURATION_MS) {
            this.toast.visible = false;
            return;
        }
        float fadeIn = Math.min(1f, age / (float) TOAST_FADE_IN_MS);
        float fadeOut = Math.min(1f, (TOAST_DURATION_MS - age) / (float) TOAST_FADE_OUT_MS);
        float opacity = Math.min(fadeIn, fadeOut);
        if (opacity <= 0.02f) {
            return;
        }
        int alpha = (int) (opacity * 255f);
        int textWidth = this.textRenderer.getWidth(this.toast.message);
        int width = textWidth + 22;
        int height = 18;
        int x = this.panel.x() + (this.panel.width() - width) / 2;
        int y = this.panel.listBottom() - height - 6;

        Draw.roundedBorder(context, x, y, width, height, 5,
                withAlpha(UiTheme.TOOLTIP_BORDER, alpha), withAlpha(UiTheme.TOOLTIP_BG, alpha));
        Draw.roundedRect(context, x + 6, y + 5, 2, height - 10, 1, withAlpha(this.toast.accent, alpha));
        Draw.text(context, this.textRenderer, this.toast.message, x + 12,
                y + (height - this.textRenderer.fontHeight) / 2, withAlpha(UiTheme.TEXT, alpha));
    }

    private void drawTooltips(DrawContext context, int mouseX, int mouseY) {
        for (Element child : this.children()) {
            if (child instanceof FmccButton button && button.visible && button.isHovered()
                    && !button.tooltipLines().isEmpty()) {
                Draw.tooltip(context, this.textRenderer, button.tooltipLines(), mouseX, mouseY);
                return;
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ---- input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Row widgets can extend past the list viewport while scrolling; the scissor hides that
        // part, so a click there must not reach the hidden part of the button either.
        if (!this.isInsideList(mouseX, mouseY)) {
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Player request: pressing the hotkey again closes the menu. ESC keeps working as the vanilla
     * safety net (handled by {@code super.keyPressed}).
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        net.minecraft.client.option.KeyBinding key = FmccClient.openKey();
        if (key != null && !key.isUnbound() && key.matchesKey(keyCode, scanCode)) {
            FmccClient.closeMenuFromHotkey(this.client);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        // ParentElement.mouseClicked() re-focuses the clicked widget *after* onClick(), so a button
        // removed by its own click can stay focused; drop focus from widgets no longer present.
        Element focused = this.getFocused();
        if (focused != null && !this.children().contains(focused)) {
            this.setFocused((Element) null);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0 || !this.isInsideList(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int maxScroll = this.maxScroll();
        if (maxScroll <= 0) {
            return true;
        }
        int step = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * UiLayout.rowStride()));
        int next = this.scrollOffset + (verticalAmount > 0 ? -step : step);
        next = Math.max(0, Math.min(maxScroll, next));
        if (next != this.scrollOffset) {
            this.scrollOffset = next;
            this.layout();
        }
        return true;
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= this.panel.contentX() && mouseX < this.panel.contentRight()
                && mouseY >= this.panel.listTop() && mouseY < this.panel.listBottom();
    }

    // ---- actions ------------------------------------------------------------

    private void sendEntry(FmccEntry entry) {
        FmccEntry current = this.currentEntry(entry);
        if (current == null) {
            return;
        }
        List<String> texts = current.sendableTexts();
        if (texts.isEmpty()) {
            this.toast(Text.translatable("fmcc.status.empty"), UiTheme.DANGER);
            return;
        }
        MessageQueue queue = FmccClient.queue();
        int queued = queue == null ? 0 : queue.enqueue(texts);
        if (queued <= 0) {
            this.toast(Text.translatable("fmcc.status.not_in_world"), UiTheme.DANGER);
            return;
        }
        boolean busy = queue != null && queue.isBusy();
        this.toast(Text.translatable(busy ? "fmcc.menu.queued" : "fmcc.menu.sent", queued), UiTheme.SUCCESS);
    }

    private void openConfig(FmccEntry entry) {
        FmccEntry current = this.currentEntry(entry);
        if (current != null) {
            this.openScreen(new EntryConfigScreen(current));
        }
    }

    private void openAdd() {
        this.openScreen(new AddEntryScreen(this));
    }

    private void deleteEntry(FmccEntry entry) {
        ConfigStore store = FmccClient.store();
        FmccEntry current = this.currentEntry(entry);
        if (store == null || current == null) {
            return;
        }
        long now = Util.getMeasuringTimeMs();
        if (now - this.lastDeleteMs < DELETE_GUARD_MS) {
            // Double click protection: the list already moved after the first delete.
            return;
        }
        if (store.removeEntry(current)) {
            this.lastDeleteMs = now;
            // The store already persisted the change; rebuild so the list and the footer count
            // reflect it immediately (also re-clamps the scroll offset).
            this.clearAndInit();
            this.toast(Text.translatable("fmcc.status.deleted"), UiTheme.TEXT_MUTED);
        }
    }

    private void openScreen(Screen screen) {
        MinecraftClient client = this.client != null ? this.client : MinecraftClient.getInstance();
        client.setScreen(screen);
    }

    /** Looks the entry up by id so an action always operates on the live config object. */
    private FmccEntry currentEntry(FmccEntry entry) {
        if (entry == null) {
            return null;
        }
        ConfigStore store = FmccClient.store();
        if (store != null && entry.getId() != null) {
            return store.byId(entry.getId()).orElse(entry);
        }
        return entry;
    }

    private void toast(Text message, int accent) {
        this.toast.show(message, accent);
    }

    // ---- public helpers (self-test / other screens) -------------------------

    /** Number of rows currently scrolled out of view (0 when everything fits). */
    public int scrollOffset() {
        return this.scrollOffset;
    }

    /** Largest legal {@link #scrollOffset()}; 0 when the list fits. */
    public int maxScroll() {
        return UiLayout.maxScroll(this.panel, this.entries.size());
    }

    /** Number of entries currently shown. */
    public int entryCount() {
        return this.entries.size();
    }

    /** Current panel geometry, so the self-test can compute expected widget coordinates. */
    public UiLayout.Panel panel() {
        return this.panel;
    }

    @Override
    public boolean shouldPause() {
        // The menu must not freeze the world (sends/exact commands happen in the background).
        return false;
    }

    // ---- value types --------------------------------------------------------

    private record Row(FmccEntry entry, FmccButton main, FmccButton configure, FmccButton delete) {
    }

    /** Small status pill inside the panel; replaces action-bar/chat feedback for this menu. */
    private static final class Toast {
        private Text message = Text.empty();
        private int accent = UiTheme.SUCCESS;
        private long shownAt;
        private boolean visible;

        void show(Text message, int accent) {
            this.message = message == null ? Text.empty() : message;
            this.accent = accent;
            this.shownAt = Util.getMeasuringTimeMs();
            this.visible = true;
        }
    }
}

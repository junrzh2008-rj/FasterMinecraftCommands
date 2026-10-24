package com.fmcc.ui;

import com.fmcc.FmccClient;
import com.fmcc.config.ConfigStore;
import com.fmcc.config.FmccEntry;
import com.fmcc.util.TitleRules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Modal dialog that asks for the new entry title (1..{@link TitleRules#MAX_CODE_POINTS} characters).
 * <p>
 * Cancelling (button or ESC) returns to the parent screen and never touches the store, so no entry
 * is created. Confirming stores the entry and opens a fresh main menu that already contains it.
 */
public class AddEntryScreen extends Screen {
    // Cached translations: no per-frame Text allocation in the render loop.
    private static final Text TITLE_TEXT = Text.translatable("fmcc.add.title");
    private static final Text HINT_TEXT = Text.translatable("fmcc.add.hint");
    private static final Text CONFIRM_TEXT = Text.translatable("fmcc.add.confirm");
    private static final Text CANCEL_TEXT = Text.translatable("fmcc.add.cancel");
    private static final Text INVALID_TEXT = Text.translatable("fmcc.add.invalid");
    /** "n/10" is bounded, so the whole label set is cached once. */
    private static final Text[] COUNTER_TEXTS = new Text[TitleRules.MAX_CODE_POINTS + 1];

    static {
        for (int count = 0; count <= TitleRules.MAX_CODE_POINTS; count++) {
            COUNTER_TEXTS[count] = Text.translatable("fmcc.add.counter", count);
        }
    }

    private static final int FIELD_HEIGHT = 20;
    private static final int COUNTER_WIDTH = 46;
    private static final int BUTTON_HEIGHT = 22;
    private static final int CONTENT_PADDING = 14;
    /** Darkening applied to the "Create" button while the title is not valid yet. */
    private static final int DISABLED_OVERLAY = 0x88000000;

    private final FmccScreen parent;
    private UiLayout.Dialog dialog;
    private TextFieldWidget titleField;
    private FmccButton createButton;
    private FmccButton cancelButton;
    /** Set when the player tried to confirm an unusable title (or the store refused it). */
    private boolean invalid;

    private int fieldX;
    private int fieldY;
    private int fieldWidth;

    public AddEntryScreen(FmccScreen parent) {
        super(TITLE_TEXT);
        this.parent = parent;
    }

    public FmccScreen parentScreen() {
        return this.parent;
    }

    @Override
    protected void init() {
        // Keep a typed title across window resizes / GUI-scale changes (init() runs again).
        String previousTitle = this.titleField == null ? "" : this.titleField.getText();
        this.dialog = UiLayout.dialog(this.width, this.height);
        UiLayout.Dialog d = this.dialog;
        int contentX = d.contentX();
        int contentWidth = d.contentWidth();
        this.fieldX = contentX;
        this.fieldY = d.y() + 44;
        this.fieldWidth = Math.max(60, contentWidth - COUNTER_WIDTH - 8);

        this.titleField = new TextFieldWidget(this.textRenderer,
                this.fieldX + 7, this.fieldY + 6,
                this.fieldWidth - 14, FIELD_HEIGHT - 6,
                Text.empty());
        this.titleField.setDrawsBackground(false);
        this.titleField.setMaxLength(TitleRules.EDIT_BOX_MAX_CHARS);
        this.titleField.setEditableColor(UiTheme.FIELD_TEXT);
        this.titleField.setUneditableColor(UiTheme.TEXT_FAINT);
        this.titleField.setText(previousTitle == null ? "" : previousTitle);
        this.titleField.setChangedListener(this::onTitleChanged);
        this.addDrawableChild(this.titleField);

        int buttonY = d.y() + d.height() - CONTENT_PADDING - BUTTON_HEIGHT;
        int gap = 8;
        int cancelWidth = (contentWidth - gap) / 2;
        this.cancelButton = this.addDrawableChild(new FmccButton(contentX, buttonY,
                cancelWidth, BUTTON_HEIGHT,
                CANCEL_TEXT, FmccButton.Style.SECONDARY, this::cancel));
        this.createButton = this.addDrawableChild(new FmccButton(contentX + cancelWidth + gap, buttonY,
                contentWidth - cancelWidth - gap, BUTTON_HEIGHT,
                CONFIRM_TEXT, FmccButton.Style.PRIMARY, this::confirm));
        this.updateCreateState();
        this.invalid = false;
        // Focus here as well as in setInitialFocus(): a resize re-runs init() without the latter.
        this.setFocused(this.titleField);
        this.titleField.setCursorToEnd(true);
    }

    @Override
    protected void setInitialFocus() {
        if (this.titleField != null) {
            this.setFocused(this.titleField);
            this.titleField.setCursorToEnd(true);
        }
    }

    // ------------------------------------------------------------------- model

    private void onTitleChanged(String raw) {
        String clamped = clampTitle(raw);
        if (!clamped.equals(raw) && this.titleField != null) {
            // Re-entrancy safe: TextFieldWidget.setText() does not invoke the changed listener.
            this.titleField.setText(clamped);
            this.titleField.setCursorToEnd(true);
        }
        this.invalid = false;
        this.updateCreateState(clamped);
    }

    /** Clamps the typed value to {@link TitleRules#MAX_CODE_POINTS} code points, keeping spaces. */
    private static String clampTitle(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        raw.codePoints().filter(codePoint -> !Character.isISOControl(codePoint)).forEach(builder::appendCodePoint);
        String cleaned = builder.toString();
        if (TitleRules.count(cleaned) <= TitleRules.MAX_CODE_POINTS) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, TitleRules.MAX_CODE_POINTS));
    }

    private void updateCreateState() {
        this.updateCreateState(this.titleField == null ? "" : this.titleField.getText());
    }

    private void updateCreateState(String value) {
        if (this.createButton != null) {
            this.createButton.active = TitleRules.isValid(value);
        }
    }

    private int titleCount() {
        return this.titleField == null ? 0 : TitleRules.count(this.titleField.getText());
    }

    // ----------------------------------------------------------------- actions

    private void confirm() {
        String title = TitleRules.sanitize(this.titleField == null ? "" : this.titleField.getText());
        if (!TitleRules.isValid(title)) {
            this.invalid = true;
            return;
        }
        ConfigStore store = FmccClient.store();
        if (store == null) {
            FmccClient.LOGGER.warn("FMCC config store is not initialised; refusing to create an entry");
            this.invalid = true;
            return;
        }
        Optional<FmccEntry> created = store.addEntry(title);
        if (created.isEmpty()) {
            this.invalid = true;
            return;
        }
        this.invalid = false;
        if (this.client != null) {
            this.client.setScreen(new FmccScreen());
        }
    }

    private void cancel() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(this.parent != null ? this.parent : new FmccScreen());
    }

    // ------------------------------------------------------------------ events

    @Override
    public void tick() {
        super.tick();
        this.updateCreateState();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.createButton != null && this.createButton.active) {
                this.confirm();
            } else {
                this.invalid = true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Keep the enabled state in sync even when the value was set programmatically.
        this.updateCreateState();
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled && button == 0 && this.titleField != null
                && mouseX >= this.fieldX && mouseX <= this.fieldX + this.fieldWidth
                && mouseY >= this.fieldY && mouseY <= this.fieldY + FIELD_HEIGHT) {
            this.setFocused(this.titleField);
            handled = true;
        }
        return handled;
    }

    @Override
    public void close() {
        this.cancel();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Screen.renderWithTooltip already invoked renderBackground(); a light veil keeps the modal
        // readable while the menu itself is a 50% black panel.
        context.fill(0, 0, this.width, this.height, 0x40000000);
        this.updateCreateState();
        if (this.dialog != null) {
            this.renderDialog(context);
        }
        super.render(context, mouseX, mouseY, delta);
        this.renderDisabledOverlay(context);
    }

    private void renderDialog(DrawContext context) {
        UiLayout.Dialog d = this.dialog;
        Draw.shadow(context, d.x(), d.y(), d.width(), d.height(), UiTheme.CORNER_RADIUS);
        Draw.roundedBorder(context, d.x(), d.y(), d.width(), d.height(), UiTheme.CORNER_RADIUS,
                UiTheme.PANEL_BORDER, UiTheme.PANEL_BG);

        int contentX = d.contentX();
        int contentWidth = d.contentWidth();
        Draw.roundedRect(context, contentX, d.y() + 12, 3, 16, 1, UiTheme.ACCENT);
        Draw.boldText(context, this.textRenderer, TITLE_TEXT, contentX + 9, d.y() + 13, UiTheme.TEXT);
        Draw.text(context, this.textRenderer, HINT_TEXT, contentX, d.y() + 31, UiTheme.TEXT_MUTED);

        boolean focused = this.titleField != null && this.titleField.isFocused();
        int border = focused ? UiTheme.FIELD_BORDER_FOCUS : UiTheme.FIELD_BORDER;
        int fill = focused ? Draw.lerpColor(UiTheme.FIELD_BG, UiTheme.ACCENT, 0.06f) : UiTheme.FIELD_BG;
        Draw.roundedFrame(context, this.fieldX, this.fieldY, this.fieldWidth, FIELD_HEIGHT,
                UiTheme.CORNER_RADIUS, border, fill);

        int count = this.titleCount();
        Text counter = COUNTER_TEXTS[Math.max(0, Math.min(TitleRules.MAX_CODE_POINTS, count))];
        int counterColor = count >= TitleRules.MAX_CODE_POINTS ? UiTheme.ACCENT : UiTheme.TEXT_FAINT;
        Draw.text(context, this.textRenderer, counter,
                contentX + contentWidth - this.textRenderer.getWidth(counter),
                this.fieldY + 6,
                counterColor);

        if (this.invalid) {
            Draw.text(context, this.textRenderer, INVALID_TEXT, contentX, d.y() + 71, UiTheme.DANGER);
        }
    }

    /** Visually mutes the "Create" button while the title is not valid; clicks are refused anyway. */
    private void renderDisabledOverlay(DrawContext context) {
        FmccButton button = this.createButton;
        if (button == null || button.active) {
            return;
        }
        Draw.roundedRect(context, button.getX(), button.getY(), button.getWidth(), button.getHeight(),
                UiTheme.CORNER_RADIUS, DISABLED_OVERLAY);
        // Keep the label readable: redraw it above the darkening layer.
        String label = button.getMessage() == null ? "" : button.getMessage().getString();
        if (!label.isEmpty()) {
            int textY = button.getY() + (button.getHeight() - 8) / 2;
            int textX = button.getX() + (button.getWidth() - this.textRenderer.getWidth(label)) / 2;
            context.drawTextWithShadow(this.textRenderer, label, textX, textY, UiTheme.TEXT_MUTED);
        }
    }
}

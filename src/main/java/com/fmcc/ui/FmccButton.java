package com.fmcc.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Borderless "modern" button used across the FMCC UI. Renders a rounded rectangle whose colour
 * eases toward the hover colour, keeps the vanilla click handling and hover bookkeeping from
 * {@link ClickableWidget}, and exposes custom tooltip lines.
 */
public class FmccButton extends ClickableWidget {
    public enum Style {
        /** Accent filled - used for the main "send" button. */
        PRIMARY,
        /** Dark neutral - used for "Configure". */
        SECONDARY,
        /** Red tinted - used for destructive actions. */
        DANGER,
        /** Transparent until hovered - used for "+"/"-" icon buttons. */
        GHOST,
        /** Accent outline. */
        ACCENT
    }

    private final Runnable action;
    private final Style style;
    private float hoverProgress;
    private List<Text> tooltipLines = List.of();
    private boolean compactLabel;

    public FmccButton(int x, int y, int width, int height, Text label, Style style, Runnable action) {
        super(x, y, width, height, label);
        this.style = style;
        this.action = action;
    }

    public FmccButton tooltip(Text... lines) {
        this.tooltipLines = lines == null || lines.length == 0 ? List.of() : List.of(lines);
        return this;
    }

    /** Icon buttons render their label at the centre without extra padding. */
    public FmccButton compact() {
        this.compactLabel = true;
        return this;
    }

    public List<Text> tooltipLines() {
        return tooltipLines;
    }

    public Style style() {
        return style;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.action.run();
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        float target = this.isHovered() && this.active ? 1f : 0f;
        float step = Math.min(1f, Math.max(0.15f, delta * 0.45f));
        this.hoverProgress += (target - this.hoverProgress) * step;
        if (Math.abs(target - this.hoverProgress) < 0.01f) {
            this.hoverProgress = target;
        }
        float hover = this.hoverProgress;

        int background;
        int border;
        int textColor;
        // Monochrome look: every button shares the panel background and is delimited by a white
        // wireframe; only the border brightness (and a slightly darker fill) reacts to hovering.
        switch (this.style) {
            case PRIMARY -> {
                background = Draw.lerpColor(UiTheme.BUTTON_BG, UiTheme.BUTTON_BG_HOVER, hover);
                border = Draw.lerpColor(UiTheme.BUTTON_BORDER_HOVER, 0xFFFFFFFF, hover);
                textColor = UiTheme.TEXT;
            }
            case ACCENT -> {
                background = Draw.lerpColor(UiTheme.BUTTON_BG, UiTheme.BUTTON_BG_HOVER, hover);
                border = Draw.lerpColor(UiTheme.BUTTON_BORDER_HOVER, 0xFFFFFFFF, hover);
                textColor = UiTheme.TEXT;
            }
            case DANGER -> {
                background = Draw.lerpColor(UiTheme.BUTTON_BG, UiTheme.BUTTON_BG_HOVER, hover);
                border = Draw.lerpColor(UiTheme.BUTTON_BORDER, UiTheme.BUTTON_BORDER_HOVER, hover);
                textColor = Draw.lerpColor(UiTheme.TEXT_MUTED, UiTheme.TEXT, hover);
            }
            case GHOST -> {
                background = hover <= 0.01f ? 0x00000000 : Draw.lerpColor(0x00000000, UiTheme.BUTTON_BG_HOVER, hover);
                border = drawWireframeWithAlpha(hover);
                textColor = Draw.lerpColor(UiTheme.TEXT_MUTED, UiTheme.TEXT, hover);
            }
            default -> {
                background = Draw.lerpColor(UiTheme.BUTTON_BG, UiTheme.BUTTON_BG_HOVER, hover);
                border = Draw.lerpColor(UiTheme.BUTTON_BORDER, UiTheme.BUTTON_BORDER_HOVER, hover);
                textColor = Draw.lerpColor(UiTheme.TEXT_MUTED, UiTheme.TEXT, hover);
            }
        }
        Draw.roundedFrame(context, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
                UiTheme.CORNER_RADIUS, border, background);

        if (this.getMessage() != null && !this.getMessage().getString().isEmpty()) {
            var font = MinecraftClient.getInstance().textRenderer;
            String label = Draw.ellipsize(font, this.getMessage().getString(),
                    this.getWidth() - (this.compactLabel ? 2 : 8));
            int textY = this.getY() + (this.getHeight() - 8) / 2;
            int textX = this.getX() + (this.getWidth() - font.getWidth(label)) / 2;
            context.drawTextWithShadow(font, label, textX, textY, textColor);
        }
    }

    private static int drawWireframeWithAlpha(float hover) {
        if (hover <= 0.01f) {
            return 0x00000000;
        }
        int alpha = (int) (0x66 * Math.min(1f, hover * 1.6f));
        return (alpha << 24) | 0x00FFFFFF;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}

package com.fmcc.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Small drawing toolkit used by every FMCC screen: rounded rectangles, borders, soft shadows,
 * text helpers and a styled tooltip. Built on vanilla {@link DrawContext} fills so it works with
 * Sodium/Iris and any GUI scale.
 */
public final class Draw {
    private Draw() {
    }

    /** Filled rounded rectangle. */
    public static void roundedRect(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || ((color >>> 24) & 0xFF) == 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        int middleTop = y + r;
        int middleBottom = y + h - r;
        ctx.fill(x, middleTop, x + w, middleBottom, color);
        for (int dy = 0; dy < r; dy++) {
            int inset = cornerInset(dy, h, r);
            ctx.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
            ctx.fill(x + inset, y + h - dy - 1, x + w - inset, y + h - dy, color);
        }
    }

    /** Filled rounded rectangle plus a one pixel outline on top. */
    public static void roundedBorder(DrawContext ctx, int x, int y, int w, int h, int radius,
                                     int borderColor, int fillColor) {
        roundedRect(ctx, x, y, w, h, radius, fillColor);
        roundedOutline(ctx, x, y, w, h, radius, borderColor);
    }

    /**
     * One pixel rounded outline that does not fill the interior (the outer rounded rectangle minus
     * the inner one). Required for transparent buttons: a transparent fill painted over a solid
     * border would otherwise leave the whole shape filled with the border colour.
     */
    public static void roundedOutline(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || ((color >>> 24) & 0xFF) == 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) {
            ctx.fill(x, y, x + w, y + 1, color);
            ctx.fill(x, y + h - 1, x + w, y + h, color);
            ctx.fill(x, y + 1, x + 1, y + h - 1, color);
            ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        int innerRadius = Math.max(0, r - 1);
        int innerHeight = h - 2;
        for (int row = 0; row < h; row++) {
            int outerInset = cornerInset(row, h, r);
            int outerLeft = x + outerInset;
            int outerRight = x + w - outerInset;
            boolean hasInner = row >= 1 && row <= h - 2;
            if (!hasInner) {
                ctx.fill(outerLeft, y + row, outerRight, y + row + 1, color);
                continue;
            }
            int innerInset = cornerInset(row - 1, innerHeight, innerRadius);
            int innerLeft = x + 1 + innerInset;
            int innerRight = x + w - 1 - innerInset;
            if (innerLeft > outerLeft) {
                ctx.fill(outerLeft, y + row, innerLeft, y + row + 1, color);
            }
            if (innerRight < outerRight) {
                ctx.fill(innerRight, y + row, outerRight, y + row + 1, color);
            }
        }
    }

    /** Border + fill when the fill is visible, outline only when it is fully transparent. */
    public static void roundedFrame(DrawContext ctx, int x, int y, int w, int h, int radius,
                                    int borderColor, int fillColor) {
        roundedRect(ctx, x, y, w, h, radius, fillColor);
        roundedOutline(ctx, x, y, w, h, radius, borderColor);
    }

    /** Horizontal inset of the 1px outline for a given row of a rounded rectangle. */
    private static int cornerInset(int row, int height, int radius) {
        int r = Math.min(radius, height / 2);
        if (r <= 0 || (row >= r && row < height - r)) {
            return 0;
        }
        int distanceFromEdge = row < r ? row : height - 1 - row;
        double d = r - distanceFromEdge - 0.5;
        return r - (int) Math.round(Math.sqrt(Math.max(0.0, (double) r * r - d * d)));
    }

    /** Soft drop shadow made of a few translucent layers. */
    public static void shadow(DrawContext ctx, int x, int y, int w, int h, int radius) {
        roundedRect(ctx, x - 2, y + 2, w + 4, h + 3, radius + 2, 0x38000000);
        roundedRect(ctx, x - 1, y + 1, w + 2, h + 2, radius + 1, 0x50000000);
    }

    /**
     * Blends two ARGB colours.
     *
     * @param amount 0 keeps {@code from}, 1 returns {@code to}
     */
    public static int lerpColor(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = (int) (((from >>> 24) & 0xFF) + ((((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t));
        int r = (int) (((from >>> 16) & 0xFF) + ((((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t));
        int g = (int) (((from >>> 8) & 0xFF) + ((((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t));
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Emphasised text. Drawn once with the normal shadow: drawing the same glyph twice used to
     * create the "double image / ghosting" the player noticed in the header.
     */
    public static void boldText(DrawContext ctx, TextRenderer font, Text text, int x, int y, int color) {
        ctx.drawTextWithShadow(font, text, x, y, color);
    }

    public static void boldString(DrawContext ctx, TextRenderer font, String text, int x, int y, int color) {
        ctx.drawTextWithShadow(font, text, x, y, color);
    }

    public static void text(DrawContext ctx, TextRenderer font, Text text, int x, int y, int color) {
        ctx.drawTextWithShadow(font, text, x, y, color);
    }

    public static void textNoShadow(DrawContext ctx, TextRenderer font, Text text, int x, int y, int color) {
        ctx.drawText(font, text, x, y, color, false);
    }

    /** Centres text horizontally around {@code centerX}. */
    public static void centered(DrawContext ctx, TextRenderer font, Text text, int centerX, int y, int color) {
        int x = centerX - font.getWidth(text) / 2;
        ctx.drawTextWithShadow(font, text, x, y, color);
    }

    /** Trims a string to {@code maxWidth} pixels and appends an ellipsis when shortened. */
    public static String ellipsize(TextRenderer font, String text, int maxWidth) {
        if (font.getWidth(text) <= maxWidth) {
            return text;
        }
        String trimmed = font.trimToWidth(text, Math.max(0, maxWidth - font.getWidth("…")));
        return trimmed + "…";
    }

    /** Styled tooltip drawn above everything else, clamped to the screen. */
    public static void tooltip(DrawContext ctx, TextRenderer font, List<Text> lines, int mouseX, int mouseY) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int width = 0;
        for (Text line : lines) {
            width = Math.max(width, font.getWidth(line));
        }
        int height = lines.size() * (font.fontHeight + 2) - 2;
        int padX = 6;
        int padY = 5;
        int x = mouseX + 10;
        int y = mouseY - height - 6;
        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();
        if (x + width + padX * 2 > screenW) {
            x = screenW - width - padX * 2 - 2;
        }
        if (y < 2) {
            y = mouseY + 12;
        }
        if (y + height + padY * 2 > screenH) {
            y = screenH - height - padY * 2 - 2;
        }
        x = Math.max(2, x);
        roundedBorder(ctx, x, y, width + padX * 2, height + padY * 2, 4, UiTheme.TOOLTIP_BORDER, UiTheme.TOOLTIP_BG);
        for (int i = 0; i < lines.size(); i++) {
            text(ctx, font, lines.get(i), x + padX, y + padY + i * (font.fontHeight + 2), UiTheme.TEXT);
        }
    }

    /** Draws a subtle top-light vertical gradient inside the given rectangle. */
    public static void verticalGradient(DrawContext ctx, int x, int y, int w, int h, int topColor, int bottomColor) {
        ctx.fillGradient(x, y, x + w, y + h, topColor, bottomColor);
    }
}

package com.example.accountswitcher.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Subtle stone panels around Minecraft's own buttons and text fields. */
final class KeychainUi {
    static final int TEXT = 0xFFF0F0F0;
    static final int MUTED = 0xFFB4B4B4;
    static final int GREEN = 0xFF9DDD73;
    static final int GOLD = 0xFFE9CA79;
    static final int RED = 0xFFFF9A91;

    private KeychainUi() {}

    static void panel(GuiGraphics g, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        g.fill(x, y, x + width, y + height, 0xEE242424);
        g.fill(x, y, x + width, y + 1, 0xFF777777);
        g.fill(x, y, x + 1, y + height, 0xFF777777);
        g.fill(x, y + height - 1, x + width, y + height, 0xFF111111);
        g.fill(x + width - 1, y, x + width, y + height, 0xFF111111);
    }

    static void line(GuiGraphics g, Font font, String text, int cx, int y, int width, int color) {
        g.drawCenteredString(font, font.plainSubstrByWidth(text, Math.max(1, width)), cx, y, color);
    }
}

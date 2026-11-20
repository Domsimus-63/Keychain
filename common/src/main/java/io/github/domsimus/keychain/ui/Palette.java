package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.compat.Gfx;
import net.minecraft.resources.Identifier;

/** Colors, sprites and small shared drawing helpers. Colors are ARGB. */
public final class Palette {
    public static final int TEXT = 0xFFFFFFFF;
    // Vanilla's gray, and a dimmer one that still reads on a bright, blurred world behind the menu.
    public static final int MUTED = 0xFFAAAAAA;
    public static final int FAINT = 0xFF858585;
    public static final int GOOD = 0xFF80D46E;
    public static final int WARN = 0xFFF0C552;
    public static final int BAD = 0xFFFF7B6B;
    public static final int LINK = 0xFF8CC8FF;

    public static final int PANEL = 0xB0101010;
    public static final int PANEL_EDGE = 0xFF4E4E4E;
    public static final int ROW_HOVER = 0x30FFFFFF;
    public static final int ROW_SELECTED = 0x50FFFFFF;
    public static final int ROW_SELECTED_EDGE = 0xFFB0B0B0;

    /** Where every Keychain screen draws its title and the line under it, so nothing jumps between screens. */
    public static final int TITLE_Y = 10;
    public static final int SUBTITLE_Y = 23;
    /** Space between the bottom of the footer buttons and the bottom of the screen. */
    public static final int BOTTOM_MARGIN = 8;

    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HOVERED = Identifier.withDefaultNamespace("widget/button_highlighted");

    public static final Identifier ICON = id("icon/keychain");
    public static final Identifier STAR = id("star");
    public static final Identifier STAR_EMPTY = id("star_empty");

    private Palette() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("keychain", path);
    }

    /** Translucent dark panel with a 1px border, like vanilla's world-selection frames. */
    public static void panel(Gfx gfx, int x, int y, int width, int height) {
        gfx.fill(x, y, x + width, y + height, PANEL);
        gfx.outline(x, y, width, height, PANEL_EDGE);
    }

    /** The four-square Microsoft mark, {@code size} pixels wide. */
    public static void microsoftLogo(Gfx gfx, int x, int y, int size) {
        int half = Math.max(1, (size - 1) / 2);
        int gap = size - 2 * half;
        gfx.fill(x, y, x + half, y + half, 0xFFF25022);
        gfx.fill(x + half + gap, y, x + size, y + half, 0xFF7FBA00);
        gfx.fill(x, y + half + gap, x + half, y + size, 0xFF00A4EF);
        gfx.fill(x + half + gap, y + half + gap, x + size, y + size, 0xFFFFB900);
    }

    /** A small button drawn by hand inside a list row, with the same look as vanilla buttons. */
    public static void button(Gfx gfx, net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component label, int x, int y, int width, int height, boolean hovered) {
        gfx.sprite(hovered ? BUTTON_HOVERED : BUTTON, x, y, width, height);
        gfx.centered(font, label, x + width / 2, y + (height - 8) / 2, TEXT);
    }

    /** A small yellow "!" badge, 10 x 10 pixels, marking an account that needs attention. */
    public static void warningBadge(Gfx gfx, int x, int y) {
        gfx.fill(x + 1, y, x + 9, y + 10, WARN);
        gfx.fill(x, y + 1, x + 10, y + 9, WARN);
        gfx.fill(x + 4, y + 2, x + 6, y + 6, 0xFF2B2108);
        gfx.fill(x + 4, y + 7, x + 6, y + 9, 0xFF2B2108);
    }

    /** A small filled circle-ish dot used as a status light. */
    public static void dot(Gfx gfx, int x, int y, int color) {
        gfx.fill(x + 1, y, x + 4, y + 5, color);
        gfx.fill(x, y + 1, x + 5, y + 4, color);
    }
}

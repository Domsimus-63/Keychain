package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.compat.Gfx;
import net.minecraft.resources.Identifier;

/** Colors, sprites and small shared drawing helpers. Colors are ARGB. */
public final class Palette {
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFFA8A8A8;
    public static final int FAINT = 0xFF707070;
    public static final int GOOD = 0xFF80D46E;
    public static final int WARN = 0xFFF0C552;
    public static final int BAD = 0xFFFF7B6B;
    public static final int LINK = 0xFF8CC8FF;

    public static final int PANEL = 0xB0101010;
    public static final int PANEL_EDGE = 0xFF3C3C3C;
    public static final int ROW_HOVER = 0x30FFFFFF;
    public static final int ROW_SELECTED = 0x50FFFFFF;
    public static final int ROW_SELECTED_EDGE = 0xFFB0B0B0;

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

    /** A small filled circle-ish dot used as a status light. */
    public static void dot(Gfx gfx, int x, int y, int color) {
        gfx.fill(x + 1, y, x + 4, y + 5, color);
        gfx.fill(x, y + 1, x + 5, y + 4, color);
    }
}

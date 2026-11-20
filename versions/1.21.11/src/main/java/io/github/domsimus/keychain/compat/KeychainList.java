package io.github.domsimus.keychain.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;

/** A selection list whose rows draw themselves through {@link Gfx}; the vanilla selection box is left out. */
public abstract class KeychainList<E extends KeychainList.Row<E>> extends ObjectSelectionList<E> {
    protected KeychainList(Minecraft minecraft, int width, int height, int top, int rowHeight) {
        super(minecraft, width, height, top, rowHeight);
    }

    /** Inner padding between the frame and the rows, and between the rows and the scrollbar. */
    private static final int PADDING = 6;
    private static final int SCROLLBAR = 6;

    /** The scrollbar sits inside the frame, against its right edge. */
    @Override
    protected int scrollBarX() {
        return getX() + getWidth() - SCROLLBAR - 1;
    }

    /** Rows start a fixed distance from the left edge of the frame... */
    @Override
    public int getRowLeft() {
        return getX() + PADDING;
    }

    /** ...and stop the same distance before the scrollbar. */
    @Override
    public int getRowWidth() {
        return Math.max(40, getWidth() - 2 * PADDING - SCROLLBAR - 1);
    }

    @Override
    public int getRowRight() {
        return getRowLeft() + getRowWidth();
    }

    /** Every Keychain list sits in the same framed panel as the rest of Keychain's screens. */
    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        io.github.domsimus.keychain.ui.Palette.panel(new Gfx(graphics), getX(), getY(), getWidth(), getHeight());
    }

    /** The frame replaces vanilla's separator lines above and below the list. */
    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
    }

    @Override
    protected void renderSelection(GuiGraphics graphics, E entry, int color) {
        // Rows draw their own selected state.
    }

    public abstract static class Row<E extends Row<E>> extends ObjectSelectionList.Entry<E> {
        protected abstract void draw(Gfx gfx, int mouseX, int mouseY, boolean hovered, float delta);

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            draw(new Gfx(graphics), mouseX, mouseY, hovered, delta);
        }
    }
}

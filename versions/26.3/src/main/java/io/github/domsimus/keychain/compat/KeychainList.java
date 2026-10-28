package io.github.domsimus.keychain.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;

/** A selection list whose rows draw themselves through {@link Gfx}; the vanilla selection box is left out. */
public abstract class KeychainList<E extends KeychainList.Row<E>> extends ObjectSelectionList<E> {
    protected KeychainList(Minecraft minecraft, int width, int height, int top, int rowHeight) {
        super(minecraft, width, height, top, rowHeight);
    }

    @Override
    protected void extractSelection(GuiGraphicsExtractor graphics, E entry, int color) {
        // Rows draw their own selected state.
    }

    public abstract static class Row<E extends Row<E>> extends ObjectSelectionList.Entry<E> {
        protected abstract void draw(Gfx gfx, int mouseX, int mouseY, boolean hovered, float delta);

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            draw(new Gfx(graphics), mouseX, mouseY, hovered, delta);
        }
    }
}

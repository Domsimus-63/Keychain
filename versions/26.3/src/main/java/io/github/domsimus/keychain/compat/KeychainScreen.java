package io.github.domsimus.keychain.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base for Keychain screens: routes this version's render hooks to version-independent ones. */
public abstract class KeychainScreen extends Screen {
    protected KeychainScreen(Component title) {
        super(title);
    }

    /** Drawn on top of the screen's widgets. */
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {}

    /** Drawn over the menu background, below the widgets. */
    protected void drawBackdrop(Gfx gfx, int mouseX, int mouseY, float delta) {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawForeground(new Gfx(graphics), mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        drawBackdrop(new Gfx(graphics), mouseX, mouseY, delta);
    }
}

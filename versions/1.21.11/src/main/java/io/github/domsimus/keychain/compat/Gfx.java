package io.github.domsimus.keychain.compat;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The drawing calls Keychain uses, over whichever GUI graphics class this Minecraft version has.
 * Every versions/&lt;mc&gt; folder has a class with this exact name and these exact methods.
 */
public final class Gfx {
    public static final int LINE_HEIGHT = 10;
    private final GuiGraphics graphics;

    public Gfx(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    public void fill(int x0, int y0, int x1, int y1, int argb) {
        graphics.fill(x0, y0, x1, y1, argb);
    }

    public void outline(int x, int y, int width, int height, int argb) {
        graphics.renderOutline(x, y, width, height, argb);
    }

    public void text(Font font, Component text, int x, int y, int argb) {
        graphics.drawString(font, text, x, y, argb);
    }

    public void text(Font font, String text, int x, int y, int argb) {
        graphics.drawString(font, text, x, y, argb);
    }

    public void centered(Font font, Component text, int centerX, int y, int argb) {
        graphics.drawCenteredString(font, text, centerX, y, argb);
    }

    /** Text drawn {@code scale} times larger, centered on {@code centerX}. */
    public void scaledCentered(Font font, Component text, int centerX, int y, float scale, int argb) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, y);
        graphics.pose().scale(scale, scale);
        graphics.drawString(font, text, -font.width(text) / 2, 0, argb);
        graphics.pose().popMatrix();
    }

    /** Word-wraps {@code text} to {@code width}, centered on {@code centerX}; returns the height used. */
    public int wrapped(Font font, Component text, int centerX, int y, int width, int argb, int maxLines) {
        List<FormattedCharSequence> lines = font.split(text, width);
        int count = Math.min(lines.size(), maxLines);
        for (int i = 0; i < count; i++) {
            FormattedCharSequence line = lines.get(i);
            graphics.drawString(font, line, centerX - font.width(line) / 2, y + i * LINE_HEIGHT, argb);
        }
        return count * LINE_HEIGHT;
    }

    public void face(PlayerSkin skin, int x, int y, int size) {
        PlayerFaceRenderer.draw(graphics, skin, x, y, size);
    }

    public void sprite(Identifier sprite, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    public void scissor(int x0, int y0, int x1, int y1) {
        graphics.enableScissor(x0, y0, x1, y1);
    }

    public void endScissor() {
        graphics.disableScissor();
    }

    public void tooltip(Font font, Component text, int mouseX, int mouseY) {
        graphics.setTooltipForNextFrame(font, text, mouseX, mouseY);
    }
}

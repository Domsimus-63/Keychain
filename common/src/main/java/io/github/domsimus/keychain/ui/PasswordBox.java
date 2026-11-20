package io.github.domsimus.keychain.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** A text box that shows dots instead of what is typed, and never reads it out to the narrator. */
final class PasswordBox extends EditBox {
    PasswordBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
        setMaxLength(128);
        addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
    }

    /** The typed password as characters, so callers can wipe their copy after use. */
    char[] password() {
        return getValue().toCharArray();
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}

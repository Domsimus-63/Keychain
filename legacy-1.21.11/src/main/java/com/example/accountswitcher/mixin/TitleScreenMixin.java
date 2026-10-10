/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.components.AbstractWidget
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.Tooltip
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.gui.screens.TitleScreen
 *  net.minecraft.network.chat.Component
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.example.accountswitcher.mixin;

import com.example.accountswitcher.gui.AccountSwitcherScreen;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={TitleScreen.class})
public abstract class TitleScreenMixin
extends Screen {
    private static final int ICON_SIZE = 20;
    private static final int ICON_GAP = 4;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method={"init()V"}, at={@At(value="TAIL")})
    private void keychain$addButton(CallbackInfo ci) {
        int y;
        int x;
        Screen self = (Screen)(Object)this;
        ArrayList<Button> iconRow = new ArrayList<Button>();
        Button singleplayerAnchor = null;
        String singleplayerLabel = Component.translatable((String)"menu.singleplayer").getString();
        for (GuiEventListener child : this.children()) {
            if (!(child instanceof Button)) continue;
            Button button = (Button)child;
            if (button.getWidth() == 20 && button.getHeight() == 20) {
                iconRow.add(button);
            }
            if (!button.getMessage().getString().equals(singleplayerLabel)) continue;
            singleplayerAnchor = button;
        }
        if (iconRow.size() >= 3) {
            iconRow.sort(Comparator.comparingInt(AbstractWidget::getX));
            Button rightmost = (Button)iconRow.get(iconRow.size() - 1);
            x = rightmost.getX() + rightmost.getWidth() + 4;
            y = rightmost.getY();
        } else if (singleplayerAnchor != null) {
            x = singleplayerAnchor.getX() - 20 - 4;
            y = singleplayerAnchor.getY();
        } else {
            x = 10;
            y = 10;
        }
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"K"), btn -> Minecraft.getInstance().setScreenAndShow((Screen)new AccountSwitcherScreen((Screen)self))).bounds(x, y, 20, 20).tooltip(Tooltip.create((Component)Component.translatable((String)"accountswitcher.title"))).build());
    }
}


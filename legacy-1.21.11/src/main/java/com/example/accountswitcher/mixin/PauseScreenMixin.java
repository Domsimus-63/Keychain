/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.PauseScreen
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.network.chat.Component
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.example.accountswitcher.mixin;

import com.example.accountswitcher.gui.AccountSwitcherScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={PauseScreen.class})
public abstract class PauseScreenMixin
extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method={"init()V"}, at={@At(value="TAIL")})
    private void keychain$addButton(CallbackInfo ci) {
        Screen self = (Screen)(Object)this;
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Keychain"), btn -> Minecraft.getInstance().setScreenAndShow((Screen)new AccountSwitcherScreen((Screen)self))).bounds(10, 10, 80, 20).build());
    }
}


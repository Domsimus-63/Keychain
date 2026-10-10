/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.network.chat.Component
 */
package com.example.accountswitcher.gui;

import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.gui.AccountSwitcherScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ConfirmRemoveScreen
extends Screen {
    private final AccountSwitcherScreen parent;
    private final Account account;

    public ConfirmRemoveScreen(AccountSwitcherScreen parent, Account account) {
        super((Component)Component.literal((String)"Remove account"));
        this.parent = parent;
        this.account = account;
    }

    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Remove"), b -> {
            this.parent.removeAndRefresh(this.account);
            this.minecraft.setScreen((Screen)this.parent);
        }).bounds(cx - 100, cy + 28, 95, 20).build());
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Cancel"), b -> this.minecraft.setScreen((Screen)this.parent)).bounds(cx + 5, cy + 28, 95, 20).build());
    }

    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        int cx = this.width / 2;
        int cy = this.height / 2;
        int boxW = Math.min(340, this.width - 20);
        int boxH = 114;
        int x0 = cx - boxW / 2;
        int y0 = cy - boxH / 2 - 16;
        int x1 = cx + boxW / 2;
        int y1 = y0 + boxH;
        g.drawCenteredString(this.font, "Remove saved account?", cx, y0 + 14, KeychainUi.GOLD);
        g.drawCenteredString(this.font, this.font.plainSubstrByWidth(
            "\"" + this.account.username + "\" will be removed from Keychain.", boxW - 20),
            cx, y0 + 38, -1);
        String detail = this.account.type == Account.Type.MICROSOFT ? "You'll need to sign in again to use it." : "You can add it back any time.";
        g.drawCenteredString(this.font, this.font.plainSubstrByWidth(detail, boxW - 20),
            cx, y0 + 56, -5592406);
        g.drawCenteredString(this.font, "Your current session will stay signed in.", cx, y0 + 71, KeychainUi.MUTED);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        super.renderBackground(g, mx, my, dt);
        int boxW = Math.min(340, this.width - 20);
        KeychainUi.panel(g, this.width / 2 - boxW / 2, this.height / 2 - 73, boxW, 132);
    }

    public void onClose() {
        this.minecraft.setScreen((Screen)this.parent);
    }
}

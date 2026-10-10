/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.EditBox
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.network.chat.Component
 */
package com.example.accountswitcher.gui;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.gui.AccountSwitcherScreen;
import com.example.accountswitcher.session.SessionUtil;
import java.time.Instant;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

public final class OfflineAccountScreen
extends Screen {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private final AccountSwitcherScreen parent;
    private EditBox nameField;
    private Button addButton;
    private int formWidth;
    private int formX;
    private int formY;

    public OfflineAccountScreen(AccountSwitcherScreen parent) {
        super((Component)Component.literal((String)"Add Offline Account"));
        this.parent = parent;
    }

    protected void init() {
        String previous = this.nameField == null ? "" : this.nameField.getValue();
        this.formWidth = Math.min(320, this.width - 32);
        this.formX = (this.width - this.formWidth) / 2;
        this.formY = Math.max(54, this.height / 2 - 42);
        this.nameField = new EditBox(this.font, this.formX + 12, this.formY + 42, this.formWidth - 24, 20, Component.literal("Username"));
        this.nameField.setMaxLength(16);
        this.nameField.setHint(Component.literal("Minecraft username"));
        this.nameField.setValue(previous);
        this.nameField.setResponder(s -> this.updateButton());
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);
        int buttonWidth = (this.formWidth - 28) / 2;
        this.addButton = this.addRenderableWidget(Button.builder(Component.literal("Add and use"), b -> this.add()).bounds(this.formX + 12, this.formY + 94, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose()).bounds(this.formX + 16 + buttonWidth, this.formY + 94, buttonWidth, 20).build());
        this.updateButton();
    }

    private void updateButton() {
        if (this.addButton != null) this.addButton.active = this.nameField != null && USERNAME.matcher(this.nameField.getValue()).matches();
    }

    private void add() {
        if (!this.addButton.active) return;
        String name = this.nameField.getValue().trim();
        Account a = new Account();
        a.type = Account.Type.OFFLINE;
        a.username = name;
        a.uuid = SessionUtil.offlineUuid(name);
        a.accessToken = "0";
        a.lastUsedAt = Instant.now();
        if (!AccountSwitcherMod.storage().add(a)) {
            this.parent.showToast("Could not save offline account.");
            this.minecraft.setScreen(this.parent);
            return;
        }
        SessionUtil.apply(a);
        this.parent.showToast("Now using " + name + (this.minecraft.getConnection() == null ? "" : ". Reconnect to use it on the server."));
        this.minecraft.setScreen((Screen)this.parent);
    }

    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        KeychainUi.line(g, this.font, "Add Offline Account", this.width / 2, 22, this.width - 24, KeychainUi.TEXT);
        KeychainUi.line(g, this.font, "For singleplayer and offline servers", this.width / 2, this.formY + 12, this.formWidth - 24, KeychainUi.MUTED);
        g.drawString(this.font, "Username", this.formX + 12, this.formY + 28, KeychainUi.TEXT);
        String value = this.nameField.getValue();
        String hint = value.isEmpty() ? "3-16 letters, numbers, or underscores" : USERNAME.matcher(value).matches() ? "Ready. Press Enter to add and use." : "Use 3-16 letters, numbers, or underscores.";
        KeychainUi.line(g, this.font, hint, this.width / 2, this.formY + 73, this.formWidth - 24,
            value.isEmpty() ? KeychainUi.MUTED : this.addButton.active ? KeychainUi.GREEN : KeychainUi.RED);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        super.renderBackground(g, mx, my, dt);
        KeychainUi.panel(g, this.formX, this.formY, this.formWidth, 126);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
            && this.nameField.isFocused() && this.addButton.active) {
            this.add();
            return true;
        }
        return super.keyPressed(event);
    }

    public void onClose() {
        this.minecraft.setScreen((Screen)this.parent);
    }
}

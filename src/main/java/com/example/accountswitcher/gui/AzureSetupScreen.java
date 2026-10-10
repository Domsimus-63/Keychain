/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.gui.GuiGraphicsExtractor
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.EditBox
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.network.chat.Component
 *  net.minecraft.util.Util
 */
package com.example.accountswitcher.gui;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Config;
import com.example.accountswitcher.auth.BrowserLauncher;
import com.example.accountswitcher.gui.AccountSwitcherScreen;
import com.example.accountswitcher.gui.AddMicrosoftScreen;
import java.net.URI;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class AzureSetupScreen
extends Screen {
    private static final String AZURE_PORTAL = "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/CreateApplicationBlade";
    private final Screen parent;
    private final boolean continueToLoginOnSave;
    private EditBox idField;
    private Button saveButton;
    private String linkStatus;

    public AzureSetupScreen(Screen parent, boolean continueToLoginOnSave) {
        super((Component)Component.literal((String)"Set up Microsoft sign-in"));
        this.parent = parent;
        this.continueToLoginOnSave = continueToLoginOnSave;
    }

    protected void init() {
        int formWidth = Math.min(360, this.width - 24);
        int formX = (this.width - formWidth) / 2;
        int actionWidth = (formWidth - 10) / 2;
        int fieldY = this.height - 96;
        this.idField = new EditBox(this.font, formX, fieldY, formWidth, 20, (Component)Component.literal((String)"Azure client id"));
        this.idField.setMaxLength(36);
        this.idField.setHint((Component)Component.literal((String)"Paste your Application (client) ID here"));
        String existing = Config.getAzureClientId();
        if (!existing.isEmpty()) {
            this.idField.setValue(existing);
        }
        this.idField.setResponder(s -> this.updateButton());
        this.addRenderableWidget(this.idField);
        this.setInitialFocus(this.idField);
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Open Azure portal"), b -> this.openUrl(AZURE_PORTAL)).bounds(formX, this.height - 64, actionWidth, 20).build());
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Paste from clipboard"), b -> {
            if (this.minecraft != null) {
                this.idField.setValue(this.minecraft.keyboardHandler.getClipboard());
            }
        }).bounds(formX + actionWidth + 10, this.height - 64, actionWidth, 20).build());
        this.saveButton = (Button)this.addRenderableWidget(Button.builder((Component)Component.literal((String)(this.continueToLoginOnSave ? "Save & sign in" : "Save")), b -> this.save()).bounds(formX, this.height - 36, actionWidth, 20).build());
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Cancel"), b -> this.minecraft.setScreenAndShow(this.parent)).bounds(formX + actionWidth + 10, this.height - 36, actionWidth, 20).build());
        this.updateButton();
    }

    private void openUrl(String url) {
        try {
            BrowserLauncher.open(URI.create(url));
            this.linkStatus = "Opening Azure portal...";
        }
        catch (Exception error) {
            AccountSwitcherMod.LOGGER.warn("Could not open Azure portal", error);
            if (this.minecraft != null) this.minecraft.keyboardHandler.setClipboard(url);
            this.linkStatus = "Browser unavailable. Portal link copied to clipboard.";
        }
    }

    private void updateButton() {
        if (this.saveButton != null && this.idField != null) {
            this.saveButton.active = Config.isValidClientIdFormat(this.idField.getValue());
        }
    }

    private void save() {
        Screen screen;
        Config.setAzureClientId(this.idField.getValue());
        if (this.continueToLoginOnSave && (screen = this.parent) instanceof AccountSwitcherScreen) {
            AccountSwitcherScreen p = (AccountSwitcherScreen)screen;
            this.minecraft.setScreenAndShow((Screen)new AddMicrosoftScreen(p));
        } else {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        g.centeredText(this.font, "Set up Microsoft sign-in", this.width / 2, 16, -1);
        g.centeredText(this.font, "Use your own Azure app ID if the default fails.", this.width / 2, 30, -6642768);
        if (this.linkStatus != null) {
            g.centeredText(this.font, this.linkStatus, this.width / 2, 44, -3157030);
        }
        int x = Math.max(8, this.width / 2 - 200);
        int y = 56;
        int color = -3157030;
        g.text(this.font, "1. Open Azure portal and sign in.", x, y, color);
        g.text(this.font, "2. New registration: personal accounts only.", x, y + 14, color);
        g.text(this.font, "3. Authentication: allow public client flows.", x, y + 28, color);
        g.text(this.font, "4. Copy Application (client) ID from Overview.", x, y + 42, color);
        g.text(this.font, "5. Paste the ID below.", x, y + 56, color);
        g.text(this.font, "Application (client) ID:", (this.width - Math.min(360, this.width - 24)) / 2,
            this.height - 110, -1);
    }

    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}

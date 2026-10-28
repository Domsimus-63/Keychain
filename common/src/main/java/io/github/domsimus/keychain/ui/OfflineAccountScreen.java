package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.session.SessionManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Adds an offline account: just a name, for singleplayer and offline-mode servers. */
public final class OfflineAccountScreen extends KeychainScreen {
    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private EditBox name;
    private Button addButton, addAndUseButton;
    private String value = "";
    private int panelX, panelY, panelWidth;

    public OfflineAccountScreen(Screen parent) {
        super(Component.translatable("keychain.offline.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(40, height / 2 - 70);
        name = addRenderableWidget(new EditBox(font, panelX + 44, panelY + 36, panelWidth - 56, 20, Component.translatable("keychain.offline.name")));
        name.setMaxLength(16);
        name.setHint(Component.translatable("keychain.offline.name.hint"));
        name.setValue(value);
        name.setResponder(text -> {
            value = text;
            updateButtons();
        });
        setInitialFocus(name);

        int inner = panelWidth - 24;
        int third = (inner - 8) / 3;
        int y = panelY + 98;
        addAndUseButton = addRenderableWidget(Button.builder(Component.translatable("keychain.offline.add_and_use"), b -> add(true))
            .bounds(panelX + 12, y, third, 20).build());
        addButton = addRenderableWidget(Button.builder(Component.translatable("keychain.offline.add"), b -> add(false))
            .bounds(panelX + 16 + third, y, third, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(panelX + 20 + 2 * third, y, inner - 2 * third - 8, 20).build());
        updateButtons();
    }

    private String trimmed() {
        return value.strip();
    }

    private boolean duplicate() {
        return keychain.vault().find(Account.offlineUuid(trimmed())).isPresent();
    }

    private void updateButtons() {
        boolean valid = Account.isValidOfflineName(trimmed()) && !duplicate();
        if (addButton != null) addButton.active = valid;
        if (addAndUseButton != null) addAndUseButton.active = valid;
    }

    private void add(boolean use) {
        if (addButton == null || !addButton.active) return;
        Account account = Account.offline(trimmed());
        if (!keychain.vault().upsert(account)) {
            SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
            return;
        }
        Compat.setScreen(parent);
        if (use) keychain.sessions().switchTo(account);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && name.isFocused()) {
            add(true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Compat.setScreen(parent);
    }

    @Override
    protected void drawBackdrop(Gfx gfx, int mouseX, int mouseY, float delta) {
        Palette.panel(gfx, panelX, panelY, panelWidth, 130);
    }

    @Override
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {
        gfx.centered(font, title, width / 2, 15, Palette.TEXT);
        gfx.centered(font, Component.translatable("keychain.offline.subtitle"), width / 2, 29, Palette.MUTED);
        gfx.text(font, Component.translatable("keychain.offline.name"), panelX + 12, panelY + 14, Palette.TEXT);
        String text = trimmed();
        gfx.face(Account.isValidOfflineName(text) ? DefaultPlayerSkin.get(Account.offlineUuid(text)) : DefaultPlayerSkin.getDefaultSkin(),
            panelX + 12, panelY + 34, 24);
        Component hint;
        int color;
        if (text.isEmpty()) {
            hint = Component.translatable("keychain.offline.rules");
            color = Palette.MUTED;
        } else if (!Account.isValidOfflineName(text)) {
            hint = Component.translatable("keychain.offline.invalid");
            color = Palette.BAD;
        } else if (duplicate()) {
            hint = Component.translatable("keychain.offline.duplicate");
            color = Palette.WARN;
        } else {
            hint = Component.translatable("keychain.offline.ready");
            color = Palette.GOOD;
        }
        gfx.wrapped(font, hint, width / 2, panelY + 66, panelWidth - 24, color, 2);
    }
}

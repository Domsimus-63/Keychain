package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.Settings;
import io.github.domsimus.keychain.session.SessionManager;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Keychain preferences. Changes save immediately. */
public final class SettingsScreen extends KeychainScreen {
    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private EditBox clientId;
    private String clientIdValue;
    private int columnX, columnWidth, clientIdY;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("keychain.settings.title"));
        this.parent = parent;
        this.clientIdValue = keychain.settings().customClientId;
    }

    @Override
    protected void init() {
        Settings settings = keychain.settings();
        columnWidth = Math.min(310, width - 24);
        columnX = (width - columnWidth) / 2;
        int half = (columnWidth - 4) / 2;
        int y = 34;

        toggle(columnX, y, half, "keychain.settings.title_button", () -> settings.titleScreenButton, value -> settings.titleScreenButton = value);
        toggle(columnX + half + 4, y, columnWidth - half - 4, "keychain.settings.pause_button", () -> settings.pauseMenuButton, value -> settings.pauseMenuButton = value);
        y += 24;
        toggle(columnX, y, half, "keychain.settings.multiplayer_button", () -> settings.multiplayerButton, value -> settings.multiplayerButton = value);
        toggle(columnX + half + 4, y, columnWidth - half - 4, "keychain.settings.restore", () -> settings.restoreLastAccount, value -> settings.restoreLastAccount = value);
        y += 24;
        addRenderableWidget(Button.builder(sharedLabel(), button -> {
            if (keychain.setSharedVault(!settings.sharedVault)) {
                button.setMessage(sharedLabel());
            } else {
                SessionManager.toast(Component.translatable("keychain.settings.shared.failed"), null);
            }
        }).bounds(columnX, y, columnWidth, 20).tooltip(Tooltip.create(Component.translatable("keychain.settings.shared.tooltip"))).build());
        y += 34;
        clientIdY = y;
        clientId = addRenderableWidget(new EditBox(font, columnX, y + 12, columnWidth - 64, 20, Component.translatable("keychain.settings.client_id")));
        clientId.setMaxLength(36);
        clientId.setHint(Component.translatable("keychain.settings.client_id.hint"));
        clientId.setValue(clientIdValue);
        clientId.setResponder(value -> {
            clientIdValue = value;
            if (value.isBlank() || Settings.isValidClientId(value)) {
                settings.customClientId = value.strip();
                settings.save();
            }
        });
        addRenderableWidget(Button.builder(Component.translatable("keychain.settings.client_id.reset"), b -> clientId.setValue(""))
            .bounds(columnX + columnWidth - 60, y + 12, 60, 20).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void toggle(int x, int y, int width, String key, Supplier<Boolean> get, Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(onOff(key, get.get()), button -> {
            set.accept(!get.get());
            Settings settings = keychain.settings();
            if (!settings.titleScreenButton && !settings.pauseMenuButton && !settings.multiplayerButton && keychain.openKeyUnbound()) {
                // Hiding every button (with no key bound) would leave no way back into Keychain.
                set.accept(true);
                SessionManager.toast(Component.translatable("keychain.settings.need_button"), Component.translatable("keychain.settings.need_button.body"));
                return;
            }
            settings.save();
            button.setMessage(onOff(key, get.get()));
        }).bounds(x, y, width, 20).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build());
    }

    private static Component onOff(String key, boolean value) {
        return CommonComponents.optionNameValue(Component.translatable(key), value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    private Component sharedLabel() {
        return onOff("keychain.settings.shared", keychain.settings().sharedVault);
    }

    /** The vault folder, relative to the game folder when it is inside it. */
    private String storageLocation() {
        java.nio.file.Path folder = keychain.vault().file().toAbsolutePath().normalize().getParent();
        java.nio.file.Path game = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        return folder.startsWith(game) ? game.relativize(folder).toString().replace(java.io.File.separatorChar, '/') : folder.toString();
    }

    @Override
    public void onClose() {
        if (!clientIdValue.isBlank() && !Settings.isValidClientId(clientIdValue)) {
            SessionManager.toast(Component.translatable("keychain.settings.client_id.not_saved"), null);
        }
        Compat.setScreen(parent);
    }

    @Override
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {
        gfx.centered(font, title, width / 2, 15, Palette.TEXT);
        gfx.text(font, Component.translatable("keychain.settings.client_id"), columnX, clientIdY, Palette.TEXT);
        String value = clientIdValue.strip();
        Component status;
        int color;
        if (value.isEmpty()) {
            status = Component.translatable("keychain.settings.client_id.default");
            color = Palette.FAINT;
        } else if (Settings.isValidClientId(value)) {
            status = Component.translatable("keychain.settings.client_id.custom");
            color = Palette.GOOD;
        } else {
            status = Component.translatable("keychain.settings.client_id.invalid");
            color = Palette.BAD;
        }
        gfx.wrapped(font, status, width / 2, clientIdY + 37, columnWidth, color, 2);
        gfx.wrapped(font, Component.translatable("keychain.settings.storage", storageLocation()), width / 2, height - 42, width - 24, Palette.FAINT, 1);
    }
}

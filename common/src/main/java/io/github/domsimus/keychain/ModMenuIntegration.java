package io.github.domsimus.keychain;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.domsimus.keychain.ui.SettingsScreen;

/** Mod Menu's "Configure" button opens Keychain's settings. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}

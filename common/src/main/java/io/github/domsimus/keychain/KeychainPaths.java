package io.github.domsimus.keychain;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Where Keychain keeps its files. Per-instance data lives in {@code config/keychain}; the vault key
 * and the optional shared vault live in a per-user folder outside every instance.
 */
public record KeychainPaths(Path instanceDir, Path userDir) {
    public static KeychainPaths detect() {
        return new KeychainPaths(FabricLoader.getInstance().getConfigDir().resolve("keychain"), userDirectory());
    }

    public Path settings() {
        return instanceDir.resolve("settings.json");
    }

    public Path instanceVault() {
        return instanceDir.resolve("accounts.vault");
    }

    public Path sharedVault() {
        return userDir.resolve("accounts.vault");
    }

    public Path vaultKey() {
        return userDir.resolve("vault.key");
    }

    public Path legacyVault() {
        return instanceDir.getParent().resolve(io.github.domsimus.keychain.core.vault.LegacyVault.FILE_NAME);
    }

    public Path legacyConfig() {
        return instanceDir.getParent().resolve(io.github.domsimus.keychain.core.vault.LegacyVault.CONFIG_NAME);
    }

    private static Path userDirectory() {
        String override = System.getProperty("keychain.home");
        if (override != null && !override.isBlank()) return Path.of(override);
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.startsWith("windows")) {
            String appData = System.getenv("APPDATA");
            return appData != null && !appData.isBlank() ? Path.of(appData, "Keychain") : Path.of(home, "AppData", "Roaming", "Keychain");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "Keychain");
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "keychain") : Path.of(home, ".local", "share", "keychain");
    }
}

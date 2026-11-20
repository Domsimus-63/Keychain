package io.github.domsimus.keychain;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.core.SafeFiles;
import io.github.domsimus.keychain.core.Settings;
import io.github.domsimus.keychain.core.auth.MicrosoftAuth;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.core.vault.BackupCodec;
import io.github.domsimus.keychain.core.vault.VaultData;
import io.github.domsimus.keychain.core.vault.VaultKey;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.ui.AccountsScreen;
import io.github.domsimus.keychain.ui.KeychainLanguage;
import io.github.domsimus.keychain.ui.MenuButtons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint and owner of Keychain's long-lived services. */
public final class Keychain implements ClientModInitializer {
    public static final String MOD_ID = "keychain";
    public static final Logger LOGGER = LoggerFactory.getLogger("Keychain");

    private static Keychain instance;

    private final KeychainPaths paths = KeychainPaths.detect();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Runnable forward = () -> listeners.forEach(Runnable::run);
    private Settings settings;
    private AccountVault vault;
    private MicrosoftAuth auth;
    private SessionManager sessions;
    private SecretKey key;
    private KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        instance = this;
        settings = Settings.load(paths.settings());
        if (!Files.exists(paths.settings())) settings.save();
        KeychainLanguage.select(settings.language);
        vault = openVault(settings.sharedVault ? paths.sharedVault() : paths.instanceVault());

        AtomicInteger threads = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "Keychain-Auth-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        auth = new MicrosoftAuth(() -> settings.clientId(), executor);
        sessions = new SessionManager(this);

        MenuButtons.register();
        // Unbound by default; players pick a key in Controls if they want one.
        openKey = Compat.registerKey(new KeyMapping("key.keychain.open", InputConstants.UNKNOWN.getValue(),
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keychain"))));
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            // A resource reload (or a change of the game's language) replaces the game's language
            // table; put Keychain's own language choice back on top of the new one.
            KeychainLanguage.apply();
            if (vault.takeRestoredNotice()) {
                SessionManager.toast(net.minecraft.network.chat.Component.translatable("keychain.vault.restored"),
                    net.minecraft.network.chat.Component.translatable("keychain.vault.restored.body"));
            }
            sessions.tick();
            while (openKey.consumeClick()) {
                if (Compat.currentScreen() == null) Compat.setScreen(new AccountsScreen(null));
            }
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> sessions.onGameStarted());
        LOGGER.info("Keychain {} ready: {} saved accounts", version(), vault.accounts().size());
    }

    public static Keychain get() {
        return instance;
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    public KeychainPaths paths() {
        return paths;
    }

    public Settings settings() {
        return settings;
    }

    public AccountVault vault() {
        return vault;
    }

    public MicrosoftAuth auth() {
        return auth;
    }

    public SessionManager sessions() {
        return sessions;
    }

    /** True when the "Open Keychain" key has no binding, so the menu buttons are the only way in. */
    public boolean openKeyUnbound() {
        return openKey == null || openKey.isUnbound();
    }

    /** Runs whenever the account list changes, including when switching to another vault. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Switches between this instance's vault and the vault shared by all instances. Accounts from
     * the current vault are copied into the target so nothing disappears from the list.
     */
    public boolean setSharedVault(boolean shared) {
        if (shared == settings.sharedVault) return true;
        AccountVault target = openVault(shared ? paths.sharedVault() : paths.instanceVault());
        VaultData current = vault.data();
        if (target.state() != AccountVault.State.READY || !target.update(data -> data.mergeMissing(current))) {
            target.removeListener(forward);
            return false;
        }
        vault.removeListener(forward);
        vault = target;
        settings.sharedVault = shared;
        settings.save();
        forward.run();
        return true;
    }

    /**
     * Recovers from a vault that cannot be opened: the vault is moved aside, and if the key itself is
     * unreadable (new computer or Windows user) the key is moved aside too and a new one is made.
     * Nothing is deleted. Returns the backup of the vault, or {@code null} if the vault opened after
     * all (the problem was temporary) and nothing had to be moved.
     */
    public synchronized Path resetVault() throws java.io.IOException {
        vault.load();
        if (vault.state() == AccountVault.State.READY) return null;
        try {
            key();
        } catch (java.io.IOException unreadableKey) {
            Path keyFile = paths.vaultKey();
            if (Files.exists(keyFile)) {
                Files.move(keyFile, keyFile.resolveSibling(keyFile.getFileName() + ".unreadable-" + System.currentTimeMillis()));
            }
            LOGGER.warn("Keychain replaced an unreadable vault key", unreadableKey);
        }
        return vault.resetUnreadable();
    }

    private AccountVault openVault(Path file) {
        AccountVault opened = new AccountVault(file, this::key);
        opened.load();
        opened.addListener(forward);
        return opened;
    }

    private synchronized SecretKey key() throws java.io.IOException {
        if (key == null) key = VaultKey.loadOrCreate(paths.vaultKey());
        return key;
    }

    /**
     * Writes every saved account to a new password-protected backup in the backups folder and
     * returns its path. Slow on purpose (the password is stretched), so call it off the render thread.
     */
    public Path exportBackup(char[] password) throws java.io.IOException {
        String encoded = BackupCodec.encode(vault.data(), password);
        Path folder = paths.backups();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(java.time.LocalDateTime.now());
        Path file = folder.resolve("keychain-backup_" + stamp + BackupCodec.EXTENSION);
        SafeFiles.writeAtomically(file, encoded);
        return file;
    }

    /**
     * Restores the accounts in a backup: accounts that are missing are added, and saved accounts
     * that are signed out get the backup's sign-in if it is newer. Nothing is removed.
     */
    public VaultData.ImportResult importBackup(Path file, char[] password) throws java.io.IOException {
        VaultData backup = BackupCodec.decode(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8), password);
        VaultData.ImportResult[] result = new VaultData.ImportResult[1];
        boolean saved = vault.update(data -> {
            result[0] = data.importFrom(backup, java.time.Instant.now());
            return result[0].data();
        });
        if (!saved) throw new java.io.IOException("The restored accounts could not be saved");
        return result[0];
    }

    /** Convenience for UI code that needs the game instance. */
    public static Minecraft minecraft() {
        return Minecraft.getInstance();
    }
}

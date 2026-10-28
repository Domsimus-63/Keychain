package io.github.domsimus.keychain;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.core.Settings;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import io.github.domsimus.keychain.core.auth.MicrosoftAuth;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.core.vault.LegacyVault;
import io.github.domsimus.keychain.core.vault.VaultData;
import io.github.domsimus.keychain.core.vault.VaultKey;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.ui.AccountsScreen;
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
    private String migrationProblem;
    private KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        instance = this;
        boolean firstRun = !Files.exists(paths.settings());
        settings = Settings.load(paths.settings());
        if (firstRun) {
            String oldClientId = LegacyVault.readClientId(paths.legacyConfig());
            if (oldClientId != null && Settings.isValidClientId(oldClientId)) settings.customClientId = oldClientId;
            settings.save();
        }
        vault = openVault(settings.sharedVault ? paths.sharedVault() : paths.instanceVault());
        migrateLegacyVault();

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

    /** Why old Keychain accounts could not be imported, or {@code null}. */
    public String migrationProblem() {
        return migrationProblem;
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
     * Nothing is deleted. Returns the backup of the vault.
     */
    public synchronized Path resetVault() throws java.io.IOException {
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

    /** Imports {@code config/account-switcher.json} from Keychain 3.x and older, once. */
    private void migrateLegacyVault() {
        Path legacy = paths.legacyVault();
        if (!Files.exists(legacy)) return;
        try {
            // Old refresh tokens belong to whichever Azure app the old version used.
            String oldClientId = LegacyVault.readClientId(paths.legacyConfig());
            List<Account> accounts = LegacyVault.read(legacy).stream().map(account -> {
                MicrosoftSession s = account.session();
                if (s == null) return account;
                return account.withSession(new MicrosoftSession(s.accessToken(), s.refreshToken(), s.xuid(), s.expiresAt(),
                    oldClientId != null ? oldClientId : Settings.DEFAULT_CLIENT_ID, null));
            }).toList();
            VaultData imported = new VaultData(accounts, null);
            if (!vault.update(data -> data.mergeMissing(imported))) {
                migrationProblem = "The new vault could not be saved";
                return;
            }
            LOGGER.info("Keychain imported {} accounts from {}", accounts.size(), legacy.getFileName());
            try {
                Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + ".migrated"));
            } catch (java.io.IOException failure) {
                // Already imported; importing again next launch adds nothing because duplicates are skipped.
                LOGGER.warn("Keychain could not rename {} after importing it", legacy, failure);
            }
        } catch (Exception failure) {
            migrationProblem = failure.getMessage();
            LOGGER.warn("Keychain could not import {}; the file was left untouched", legacy, failure);
        }
    }

    /** Convenience for UI code that needs the game instance. */
    public static Minecraft minecraft() {
        return Minecraft.getInstance();
    }
}

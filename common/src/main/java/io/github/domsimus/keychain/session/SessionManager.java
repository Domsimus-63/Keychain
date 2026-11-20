package io.github.domsimus.keychain.session;

import com.mojang.realmsclient.RealmsMainScreen;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import io.github.domsimus.keychain.core.auth.AuthException;
import io.github.domsimus.keychain.core.auth.Cancellation;
import io.github.domsimus.keychain.core.auth.MicrosoftAuth;
import io.github.domsimus.keychain.ui.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * Switches the game to a saved account. Microsoft accounts are checked (and refreshed if needed)
 * first, so the game never ends up with a token servers reject. While the player is in a world the
 * switch waits until they leave, because the open connection is bound to the old identity.
 */
public final class SessionManager {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);
    /** Refresh tokens Microsoft has not renewed for this long are renewed in the background. */
    private static final Duration KEEP_ALIVE_AFTER = Duration.ofDays(7);

    public enum Outcome {
        /** The account is now active. */
        SWITCHED,
        /** It becomes active as soon as the player leaves the current world or server. */
        QUEUED,
        ALREADY_ACTIVE,
        /** The Microsoft session is gone; the user must sign in again. */
        NEEDS_SIGN_IN,
        FAILED,
        /** Superseded by a newer switch or cancelled. */
        CANCELLED
    }

    private final Keychain keychain;
    /** Read by the background keep-alive too, hence volatile. */
    private volatile UUID preparing;
    private Cancellation preparation;
    private int generation;
    private Account queued;
    private boolean launcherQueued;
    private boolean keptAlive;
    /**
     * The session the game was started with, remembered right before Keychain first replaces it, so
     * the player can return to an account that is not saved in Keychain without restarting.
     */
    private User launcher;

    public SessionManager(Keychain keychain) {
        this.keychain = keychain;
    }

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    /** The game is signed in as this account (same UUID and name). */
    public boolean isActive(Account account) {
        User user = minecraft().getUser();
        return account.id().equals(user.getProfileId()) && account.name().equals(user.getName());
    }

    /** Active and using exactly this account's token, so nothing needs re-applying. */
    private boolean isActiveSession(Account account) {
        return isActive(account) && account.accessToken().equals(minecraft().getUser().getAccessToken());
    }

    private static boolean sameSession(User first, User second) {
        return first.getProfileId().equals(second.getProfileId()) && first.getName().equals(second.getName())
            && first.getAccessToken().equals(second.getAccessToken());
    }

    /** The account currently being checked or refreshed before a switch, if any. */
    public Optional<UUID> preparing() {
        return Optional.ofNullable(preparing);
    }

    /** The saved account waiting for the player to leave their world, if any. */
    public Optional<Account> queued() {
        return Optional.ofNullable(queued);
    }

    public boolean isQueued(Account account) {
        return queued != null && queued.id().equals(account.id());
    }

    /** Name of whichever account (saved, or the launcher's) is waiting for the player to leave. */
    public Optional<String> queuedName() {
        if (queued != null) return Optional.of(queued.name());
        return launcherQueued && launcher != null ? Optional.of(launcher.getName()) : Optional.empty();
    }

    public void cancelQueued() {
        queuedName().ifPresent(name -> toast(Component.translatable("keychain.toast.queue_cancelled", name), null));
        queued = null;
        launcherQueued = false;
    }

    /**
     * The account the game was started with, when the player has switched away from it and it is
     * not saved in Keychain (a saved account is switched to from the list instead).
     */
    public Optional<User> launcher() {
        if (launcher == null || sameSession(launcher, minecraft().getUser())) return Optional.empty();
        if (keychain.vault().find(launcher.getProfileId()).isPresent()) return Optional.empty();
        return Optional.of(launcher);
    }

    /**
     * True while a world or server connection exists or is being set up. A connection is bound to
     * the identity it was opened with, so the session must not change underneath it.
     */
    public static boolean inWorld() {
        Minecraft minecraft = minecraft();
        if (minecraft.level != null || minecraft.getConnection() != null || minecraft.getSingleplayerServer() != null) return true;
        // Logging in, and a server sending the player back to its configuration phase: no world is
        // loaded during either, but the connection is very much alive.
        Screen screen = Compat.currentScreen();
        return screen instanceof ConnectScreen || screen instanceof ServerReconfigScreen;
    }

    /**
     * A menu the game only ever shows without a connection. A queued switch waits for one of these
     * rather than for "no world loaded": proxy networks unload the world while moving the player
     * between servers, and switching then would change the identity of a live connection.
     */
    private static boolean atMenu() {
        Screen screen = Compat.currentScreen();
        return screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen || screen instanceof SelectWorldScreen
            || screen instanceof RealmsMainScreen || screen instanceof DisconnectedScreen || screen instanceof KeychainScreen;
    }

    /** Only the latest request takes effect: a new one cancels a switch that is still preparing. */
    private int supersede() {
        if (preparation != null) preparation.cancel();
        preparing = null;
        preparation = null;
        return ++generation;
    }

    /**
     * Starts switching to {@code account}. The future always completes on the game thread, and only
     * the latest request takes effect: starting a new switch cancels one that is still preparing.
     */
    public CompletableFuture<Outcome> switchTo(Account account) {
        int request = supersede();
        Account.Status status = account.status(Instant.now());
        if (status == Account.Status.NEEDS_SIGN_IN) return CompletableFuture.completedFuture(Outcome.NEEDS_SIGN_IN);
        if (isActiveSession(account) && status == Account.Status.READY) {
            queued = null;
            launcherQueued = false;
            return CompletableFuture.completedFuture(Outcome.ALREADY_ACTIVE);
        }
        if (!account.isMicrosoft()) return CompletableFuture.completedFuture(activate(account));
        return prepareThenActivate(account, request);
    }

    /**
     * Returns to the account the game was started with, using the launcher's session exactly as the
     * game did at startup. Like any switch, it waits while the player is in a world.
     */
    public Outcome switchToLauncher() {
        User target = launcher;
        if (target == null) return Outcome.CANCELLED;
        supersede();
        if (sameSession(target, minecraft().getUser())) {
            queued = null;
            launcherQueued = false;
            return Outcome.ALREADY_ACTIVE;
        }
        if (inWorld()) {
            queued = null;
            launcherQueued = true;
            toast(Component.translatable("keychain.toast.queued.title", target.getName()), Component.translatable("keychain.toast.queued.body"));
            return Outcome.QUEUED;
        }
        if (!apply(target, !minecraft().isOfflineDeveloperMode())) return Outcome.FAILED;
        // "Remember account" should start as the launcher's account again, too.
        keychain.vault().update(vault -> vault.withLastActive(null));
        SkinCache.forget(target.getProfileId());
        return Outcome.SWITCHED;
    }

    private CompletableFuture<Outcome> prepareThenActivate(Account account, int request) {
        Cancellation cancellation = new Cancellation();
        preparation = cancellation;
        preparing = account.id();
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        keychain.auth().prepare(account, cancellation).whenComplete((prepared, failure) -> minecraft().execute(() -> {
            boolean latest = request == generation;
            if (latest) {
                preparing = null;
                preparation = null;
            }
            boolean stillSaved = keychain.vault().find(account.id()).isPresent();
            if (failure == null && stillSaved && !prepared.equals(account) && !keychain.vault().upsert(prepared)) {
                // Renewed tokens are worth keeping even if this switch was overtaken by another one.
                Keychain.LOGGER.warn("Keychain could not save the renewed session of {}", prepared.name());
            }
            if (!latest || !stillSaved) {
                // Overtaken by a newer switch, or removed while it was being checked: do not bring it
                // back, and do not report a problem with an account the player no longer has.
                result.complete(Outcome.CANCELLED);
                return;
            }
            if (failure == null) {
                // Use the checked account itself: it carries the tokens that were just confirmed, even
                // if saving them failed.
                result.complete(activate(prepared));
                return;
            }
            AuthException error = MicrosoftAuth.unwrap(failure);
            if (error.reason() == AuthException.Reason.CANCELLED) {
                result.complete(Outcome.CANCELLED);
            } else if (error.reason() == AuthException.Reason.SESSION_REVOKED) {
                // The token was checked and refused, and it could not be renewed: nothing in it is usable.
                forgetRefreshToken(account, true);
                result.complete(Outcome.NEEDS_SIGN_IN);
            } else {
                Keychain.LOGGER.warn("Keychain could not refresh {}: {}", account.name(), error.getMessage());
                toast(Component.translatable("keychain.toast.refresh_failed", account.name()), Messages.reason(error.reason()));
                result.complete(Outcome.FAILED);
            }
        }));
        return result;
    }

    /**
     * With {@code tokenRejected} the access token is marked unusable too; otherwise it keeps working
     * until it expires (a background renewal failing says nothing about the current token).
     * Marks the account as needing a new sign-in, unless another game instance already replaced the
     * refresh token we tried (shared vaults), in which case the newer token is kept.
     */
    private void forgetRefreshToken(Account used, boolean tokenRejected) {
        String rejected = used.session() == null ? null : used.session().refreshToken();
        keychain.vault().update(vault -> vault.replace(used.id(), saved -> {
            MicrosoftSession session = saved.session();
            if (session == null || !Objects.equals(session.refreshToken(), rejected)) return saved;
            return saved.withSession(tokenRejected ? session.revoked() : session.withoutRefreshToken());
        }));
    }

    private Outcome activate(Account account) {
        if (inWorld()) {
            queued = account;
            launcherQueued = false;
            toast(Component.translatable("keychain.toast.queued.title", account.name()), Component.translatable("keychain.toast.queued.body"));
            return Outcome.QUEUED;
        }
        User user = new User(account.name(), account.id(), account.accessToken(),
            Optional.ofNullable(account.session() == null ? null : account.session().xuid()), minecraft().getUser().getClientId());
        if (!apply(user, account.isMicrosoft())) return Outcome.FAILED;
        keychain.vault().markUsed(account.id(), Instant.now());
        SkinCache.forget(account.id());
        Keychain.LOGGER.info("Keychain switched to {} ({})", account.name(), account.kind());
        return Outcome.SWITCHED;
    }

    /** Makes {@code user} the game's session. Returns {@code false}, with the old session intact, on failure. */
    private boolean apply(User user, boolean online) {
        Minecraft minecraft = minecraft();
        if (launcher == null) launcher = minecraft.getUser();
        try {
            Compat.applySession(minecraft, user, online);
        } catch (RuntimeException failure) {
            Keychain.LOGGER.error("Keychain could not switch to {}", user.getName(), failure);
            toast(Component.translatable("keychain.toast.switch_failed", user.getName()), Component.translatable("keychain.toast.switch_failed.body"));
            return false;
        }
        queued = null;
        launcherQueued = false;
        // An open title screen still shows the previous account's Realms invitations and news.
        if (Compat.currentScreen() instanceof TitleScreen) Compat.setScreen(new TitleScreen());
        toast(Component.translatable("keychain.toast.switched", user.getName()), null);
        return true;
    }

    /** Applies a queued switch once the player is back at a menu. */
    public void tick() {
        if ((queued == null && !launcherQueued) || inWorld() || !atMenu()) return;
        if (launcherQueued) {
            launcherQueued = false;
            switchToLauncher();
            return;
        }
        Account account = keychain.vault().find(queued.id()).orElse(null);
        queued = null;
        if (account != null) switchTo(account).thenAccept(outcome -> {
            if (outcome == Outcome.NEEDS_SIGN_IN) {
                toast(Component.translatable("keychain.toast.restore_failed", account.name()), Messages.reason(AuthException.Reason.SESSION_REVOKED));
            }
        });
    }

    /** At startup: optionally return to the last account, then renew sessions that are going stale. */
    public void onGameStarted() {
        CompletableFuture<?> restore = keychain.settings().restoreLastAccount ? restoreLastAccount() : CompletableFuture.completedFuture(null);
        // Renew only after the restore finished, so both never refresh the same account at once.
        restore.whenComplete((ignored, failure) -> minecraft().execute(this::keepSessionsAlive));
    }

    private CompletableFuture<?> restoreLastAccount() {
        UUID last = keychain.vault().data().lastActive();
        Account account = last == null ? null : keychain.vault().find(last).filter(saved -> !isActive(saved)).orElse(null);
        if (account == null) return CompletableFuture.completedFuture(null);
        return switchTo(account).thenAccept(outcome -> {
            if (outcome == Outcome.NEEDS_SIGN_IN) {
                toast(Component.translatable("keychain.toast.restore_failed", account.name()), Messages.reason(AuthException.Reason.SESSION_REVOKED));
            }
        });
    }

    /**
     * Microsoft forgets refresh tokens that go unused for about 90 days. Renewing old ones once a
     * week, one at a time in the background, keeps rarely used accounts signed in indefinitely.
     * Each account is looked up again right before its turn, so edits made meanwhile are respected.
     */
    private void keepSessionsAlive() {
        if (keptAlive) return;
        keptAlive = true;
        List<UUID> candidates = keychain.vault().accounts().stream().filter(SessionManager::needsKeepAlive).map(Account::id).toList();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (UUID id : candidates) {
            chain = chain.thenCompose(ignored -> {
                Account current = keychain.vault().find(id).filter(SessionManager::needsKeepAlive).orElse(null);
                if (current == null || id.equals(preparing)) return CompletableFuture.completedFuture(null);
                return keychain.auth().refresh(current, new Cancellation()).handle((refreshed, failure) -> {
                    minecraft().execute(() -> keepAliveResult(current, refreshed, failure));
                    return null;
                });
            });
        }
    }

    private static boolean needsKeepAlive(Account account) {
        MicrosoftSession session = account.session();
        return session != null && session.canRefresh() && session.isOlderThan(KEEP_ALIVE_AFTER, Instant.now());
    }

    private void keepAliveResult(Account used, Account refreshed, Throwable failure) {
        if (failure == null) {
            String usedToken = used.session().refreshToken();
            keychain.vault().update(vault -> vault.find(used.id())
                .filter(saved -> saved.session() != null && Objects.equals(saved.session().refreshToken(), usedToken))
                .map(saved -> vault.upsert(refreshed))
                .orElse(vault));
            return;
        }
        AuthException error = MicrosoftAuth.unwrap(failure);
        if (error.reason() == AuthException.Reason.SESSION_REVOKED) forgetRefreshToken(used, false);
        else Keychain.LOGGER.debug("Keychain could not renew {} in the background: {}", used.name(), error.getMessage());
    }

    public static void toast(Component title, Component body) {
        SystemToast.addOrUpdate(Compat.toasts(), TOAST, title, body);
    }
}

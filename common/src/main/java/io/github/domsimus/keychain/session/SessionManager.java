package io.github.domsimus.keychain.session;

import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
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
    private UUID preparing;
    private Cancellation preparation;
    private int generation;
    private Account queued;
    private boolean keptAlive;

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

    /** The account currently being checked or refreshed before a switch, if any. */
    public Optional<UUID> preparing() {
        return Optional.ofNullable(preparing);
    }

    /** The account waiting for the player to leave their world, if any. */
    public Optional<Account> queued() {
        return Optional.ofNullable(queued);
    }

    public boolean isQueued(Account account) {
        return queued != null && queued.id().equals(account.id());
    }

    public void cancelQueued() {
        if (queued != null) toast(Component.translatable("keychain.toast.queue_cancelled", queued.name()), null);
        queued = null;
    }

    public static boolean inWorld() {
        Minecraft minecraft = minecraft();
        return minecraft.level != null || minecraft.getConnection() != null;
    }

    /**
     * Starts switching to {@code account}. The future always completes on the game thread, and only
     * the latest request takes effect: starting a new switch cancels one that is still preparing.
     */
    public CompletableFuture<Outcome> switchTo(Account account) {
        int request = ++generation;
        if (preparation != null) preparation.cancel();
        preparing = null;
        preparation = null;
        Account.Status status = account.status(Instant.now());
        if (status == Account.Status.NEEDS_SIGN_IN) return CompletableFuture.completedFuture(Outcome.NEEDS_SIGN_IN);
        if (isActiveSession(account) && status == Account.Status.READY) {
            queued = null;
            return CompletableFuture.completedFuture(Outcome.ALREADY_ACTIVE);
        }
        if (!account.isMicrosoft()) return CompletableFuture.completedFuture(activate(account));
        return prepareThenActivate(account, request);
    }

    private CompletableFuture<Outcome> prepareThenActivate(Account account, int request) {
        Cancellation cancellation = new Cancellation();
        preparation = cancellation;
        preparing = account.id();
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        keychain.auth().prepare(account, cancellation).whenComplete((prepared, failure) -> minecraft().execute(() -> {
            if (request != generation) {
                result.complete(Outcome.CANCELLED);
                return;
            }
            preparing = null;
            preparation = null;
            if (failure == null && keychain.vault().find(account.id()).isEmpty()) {
                // Removed while it was being checked: do not bring it back.
                result.complete(Outcome.CANCELLED);
                return;
            }
            if (failure == null) {
                if (!prepared.equals(account)) keychain.vault().upsert(prepared);
                result.complete(activate(keychain.vault().find(prepared.id()).orElse(prepared)));
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
            toast(Component.translatable("keychain.toast.queued.title", account.name()), Component.translatable("keychain.toast.queued.body"));
            return Outcome.QUEUED;
        }
        try {
            User current = minecraft().getUser();
            User user = new User(account.name(), account.id(), account.accessToken(),
                Optional.ofNullable(account.session() == null ? null : account.session().xuid()), current.getClientId());
            Compat.applySession(minecraft(), user, account.isMicrosoft());
        } catch (RuntimeException failure) {
            Keychain.LOGGER.error("Keychain could not switch to {}", account.name(), failure);
            toast(Component.translatable("keychain.toast.switch_failed", account.name()), Component.translatable("keychain.toast.switch_failed.body"));
            return Outcome.FAILED;
        }
        queued = null;
        keychain.vault().markUsed(account.id(), Instant.now());
        SkinCache.forget(account.id());
        toast(Component.translatable("keychain.toast.switched", account.name()), null);
        Keychain.LOGGER.info("Keychain switched to {} ({})", account.name(), account.kind());
        return Outcome.SWITCHED;
    }

    /** Applies a queued switch once the player has left their world. */
    public void tick() {
        if (queued != null && !inWorld()) {
            Account account = keychain.vault().find(queued.id()).orElse(null);
            queued = null;
            if (account != null) switchTo(account).thenAccept(outcome -> {
                if (outcome == Outcome.NEEDS_SIGN_IN) {
                    toast(Component.translatable("keychain.toast.restore_failed", account.name()), Messages.reason(AuthException.Reason.SESSION_REVOKED));
                }
            });
        }
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

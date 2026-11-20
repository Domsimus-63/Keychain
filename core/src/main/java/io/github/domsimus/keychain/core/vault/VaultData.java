package io.github.domsimus.keychain.core.vault;

import io.github.domsimus.keychain.core.account.Account;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything stored in the vault, in the user's saved order.
 *
 * @param lastActive the account most recently switched to, used to restore it on the next launch
 */
public record VaultData(List<Account> accounts, UUID lastActive) {
    public static final VaultData EMPTY = new VaultData(List.of(), null);

    public VaultData {
        accounts = List.copyOf(accounts);
    }

    public Optional<Account> find(UUID id) {
        return accounts.stream().filter(account -> account.id().equals(id)).findFirst();
    }

    /**
     * Adds an account, or replaces the saved one with the same UUID in place. Favorites and the
     * last-used time survive re-signing in, so refreshing tokens never reshuffles the list.
     */
    public VaultData upsert(Account account) {
        List<Account> next = new ArrayList<>(accounts);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(account.id())) {
                next.set(i, account.keepingPreferencesOf(next.get(i)));
                return new VaultData(next, lastActive);
            }
        }
        next.add(account);
        return new VaultData(next, lastActive);
    }

    public VaultData remove(UUID id) {
        List<Account> next = new ArrayList<>(accounts);
        next.removeIf(account -> account.id().equals(id));
        return new VaultData(next, id.equals(lastActive) ? null : lastActive);
    }

    public VaultData replace(UUID id, java.util.function.UnaryOperator<Account> change) {
        List<Account> next = new ArrayList<>(accounts);
        next.replaceAll(account -> account.id().equals(id) ? change.apply(account) : account);
        return new VaultData(next, lastActive);
    }

    /** Moves an account up (negative) or down (positive) in the saved order, clamped to the ends. */
    public VaultData move(UUID id, int offset) {
        List<Account> next = new ArrayList<>(accounts);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(id)) {
                int target = Math.max(0, Math.min(next.size() - 1, i + offset));
                next.add(target, next.remove(i));
                break;
            }
        }
        return new VaultData(next, lastActive);
    }

    /**
     * Moves {@code id} directly before or after {@code neighbor} in the saved order. The list shows
     * favorites first, so moving relative to the visible neighbor is what makes a move visible.
     */
    public VaultData placeNextTo(UUID id, UUID neighbor, boolean after) {
        if (id.equals(neighbor)) return this;
        List<Account> next = new ArrayList<>(accounts);
        Account moving = null;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(id)) {
                moving = next.remove(i);
                break;
            }
        }
        if (moving == null) return this;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(neighbor)) {
                next.add(after ? i + 1 : i, moving);
                return new VaultData(next, lastActive);
            }
        }
        return this;
    }

    /** Puts a removed account back at its old position (undo). Does nothing if it already exists. */
    public VaultData restore(Account account, int index) {
        if (find(account.id()).isPresent()) return this;
        List<Account> next = new ArrayList<>(accounts);
        next.add(Math.max(0, Math.min(index, next.size())), account);
        return new VaultData(next, lastActive);
    }

    /** Does nothing if the account is no longer saved (removed in another game instance). */
    public VaultData markUsed(UUID id, Instant when) {
        if (find(id).isEmpty()) return this;
        VaultData updated = replace(id, account -> account.withLastUsed(when));
        return new VaultData(updated.accounts, id);
    }

    /** Sets which account is restored on the next launch; {@code null} means the launcher's own. */
    public VaultData withLastActive(UUID id) {
        return new VaultData(accounts, id);
    }

    /** What restoring a backup changed. */
    public record ImportResult(VaultData data, int added, int renewed, int unchanged) {}

    /**
     * Restores accounts from a backup without losing anything saved here: accounts that are
     * missing are added at the end, and a saved Microsoft account that has to be signed in again
     * takes over the backup's sign-in if that one still works. Everything else stays as it is.
     */
    public ImportResult importFrom(VaultData backup, Instant now) {
        VaultData merged = this;
        int added = 0, renewed = 0, unchanged = 0;
        for (Account incoming : backup.accounts) {
            Account saved = merged.find(incoming.id()).orElse(null);
            if (saved == null) {
                merged = merged.upsert(incoming);
                added++;
            } else if (saved.isMicrosoft() && incoming.isMicrosoft() && incoming.session() != null
                && saved.status(now) == Account.Status.NEEDS_SIGN_IN && incoming.status(now) != Account.Status.NEEDS_SIGN_IN) {
                merged = merged.replace(saved.id(), account -> account.withSession(incoming.session()));
                renewed++;
            } else {
                unchanged++;
            }
        }
        return new ImportResult(merged, added, renewed, unchanged);
    }

    /** Adds every account from {@code other} that this vault does not have yet. */
    public VaultData mergeMissing(VaultData other) {
        VaultData merged = this;
        for (Account account : other.accounts) {
            if (merged.find(account.id()).isEmpty()) merged = merged.upsert(account);
        }
        return merged;
    }
}

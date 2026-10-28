package io.github.domsimus.keychain.core.vault;

import io.github.domsimus.keychain.core.account.Account;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The encrypted account store.
 *
 * <p>Every change is all-or-nothing: the new state is written to a temporary file and atomically
 * moved into place, and the in-memory state only changes once that succeeded. A vault that cannot be
 * read is never overwritten. Before each change the file is re-read if another game instance has
 * modified it, so two instances sharing one vault do not undo each other's edits.
 */
public final class AccountVault {
    private static final Logger LOGGER = LoggerFactory.getLogger("Keychain");

    public enum State { READY, UNREADABLE }

    private final Path file;
    private final KeySource keys;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile VaultData data = VaultData.EMPTY;
    private volatile State state = State.READY;
    private volatile String problem;
    private FileTime loadedAt;

    /** Supplies the encryption key lazily, so a damaged key file only affects the vault, not startup. */
    @FunctionalInterface
    public interface KeySource {
        SecretKey get() throws IOException;
    }

    public AccountVault(Path file, KeySource keys) {
        this.file = file;
        this.keys = keys;
    }

    public Path file() {
        return file;
    }

    public List<Account> accounts() {
        return data.accounts();
    }

    public VaultData data() {
        return data;
    }

    public Optional<Account> find(UUID id) {
        return data.find(id);
    }

    public State state() {
        return state;
    }

    /** Human-readable reason the vault is unreadable, for the UI. */
    public String problem() {
        return problem;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public synchronized void load() {
        try {
            if (!Files.exists(file)) {
                set(VaultData.EMPTY, State.READY, null);
                loadedAt = null;
                return;
            }
            FileTime modified = Files.getLastModifiedTime(file);
            VaultData read = VaultCodec.decode(Files.readString(file, StandardCharsets.UTF_8), keys.get());
            loadedAt = modified;
            set(read, State.READY, null);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Keychain could not read {}; it will not be overwritten", file, failure);
            set(VaultData.EMPTY, State.UNREADABLE, failure.getMessage());
        }
    }

    /**
     * Applies {@code change} and saves. Returns {@code false}, leaving everything as it was, if the
     * vault is unreadable or the file could not be written.
     */
    public synchronized boolean update(UnaryOperator<VaultData> change) {
        reloadIfChangedElsewhere();
        if (state != State.READY) return false;
        VaultData next = change.apply(data);
        if (next.equals(data)) return true;
        try {
            write(next);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Keychain could not save {}", file, failure);
            return false;
        }
        set(next, State.READY, null);
        return true;
    }

    public boolean upsert(Account account) {
        return update(vault -> vault.upsert(account));
    }

    public boolean remove(UUID id) {
        return update(vault -> vault.remove(id));
    }

    public boolean setFavorite(UUID id, boolean favorite) {
        return update(vault -> vault.replace(id, account -> account.withFavorite(favorite)));
    }

    public boolean move(UUID id, int offset) {
        return update(vault -> vault.move(id, offset));
    }

    public boolean placeNextTo(UUID id, UUID neighbor, boolean after) {
        return update(vault -> vault.placeNextTo(id, neighbor, after));
    }

    public boolean markUsed(UUID id, Instant when) {
        return update(vault -> vault.markUsed(id, when));
    }

    /**
     * Moves an unreadable vault aside (it is kept, never deleted) and starts empty, so the user can
     * keep using Keychain after a reinstall or a lost key.
     */
    public synchronized Path resetUnreadable() throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".unreadable-"
            + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()));
        if (Files.exists(file)) Files.move(file, backup);
        loadedAt = null;
        set(VaultData.EMPTY, State.READY, null);
        return backup;
    }

    /** Writes {@code initial} to a vault file that does not exist yet (first run or migration). */
    public synchronized boolean initialize(VaultData initial) {
        if (Files.exists(file)) return false;
        try {
            write(initial);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Keychain could not create {}", file, failure);
            return false;
        }
        set(initial, State.READY, null);
        return true;
    }

    private void reloadIfChangedElsewhere() {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (loadedAt == null || !modified.equals(loadedAt)) load();
        } catch (NoSuchFileException deleted) {
            // Removed while running: the next save recreates it from memory.
        } catch (IOException failure) {
            LOGGER.warn("Keychain could not check {} for outside changes", file, failure);
        }
    }

    private void write(VaultData next) throws IOException {
        String encoded = VaultCodec.encode(next, keys.get());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        loadedAt = Files.getLastModifiedTime(file);
    }

    private void set(VaultData next, State nextState, String nextProblem) {
        data = next;
        state = nextState;
        problem = nextProblem;
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("Keychain vault listener failed", failure);
            }
        }
    }
}

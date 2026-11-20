package io.github.domsimus.keychain.core.vault;

import io.github.domsimus.keychain.core.SafeFiles;
import io.github.domsimus.keychain.core.account.Account;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * read is never overwritten. Each change re-reads the file first and holds a lock file while it
 * works, so two game instances sharing one vault never undo each other's edits.
 *
 * <p>Every save is decrypted again before it replaces the file, is forced to disk, and keeps the
 * previous state as {@code accounts.vault.bak}. If the vault file is ever damaged, it is rebuilt
 * from that copy automatically.
 */
public final class AccountVault {
    private static final Logger LOGGER = LoggerFactory.getLogger("Keychain");
    /** How long to wait for another game instance to finish its own change before going ahead. */
    private static final int LOCK_ATTEMPTS = 40;
    private static final long LOCK_RETRY_MILLIS = 50;

    public enum State { READY, UNREADABLE }

    private final Path file;
    private final KeySource keys;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile VaultData data = VaultData.EMPTY;
    private volatile State state = State.READY;
    private volatile String problem;
    /** Exactly what the file contained when {@link #data} was read from or written to it. */
    private String loadedContent;
    private volatile boolean restoredFromBackup;

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
        try (Lock ignored = lock()) {
            read();
        }
    }

    /**
     * Applies {@code change} and saves. Returns {@code false}, leaving everything as it was, if the
     * vault is unreadable or the file could not be written.
     */
    public synchronized boolean update(UnaryOperator<VaultData> change) {
        try (Lock ignored = lock()) {
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
     * keep using Keychain after a reinstall or a lost key. The file is read once more first: if it
     * opens after all (the problem was temporary), nothing is moved and {@code null} is returned.
     */
    public synchronized Path resetUnreadable() throws IOException {
        try (Lock ignored = lock()) {
            read();
            if (state == State.READY) return null;
            Path backup = file.resolveSibling(file.getFileName() + ".unreadable-" + timestamp());
            if (Files.exists(file)) Files.move(file, backup);
            loadedContent = null;
            set(VaultData.EMPTY, State.READY, null);
            return backup;
        }
    }

    /** Writes {@code initial} to a vault file that does not exist yet (first run or migration). */
    public synchronized boolean initialize(VaultData initial) {
        try (Lock ignored = lock()) {
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
    }

    private void read() {
        SecretKey key = null;
        try {
            if (!Files.exists(file)) {
                loadedContent = null;
                set(VaultData.EMPTY, State.READY, null);
                return;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            key = keys.get();
            VaultData read = VaultCodec.decode(content, key);
            loadedContent = content;
            set(read, State.READY, null);
        } catch (IOException | RuntimeException failure) {
            // A damaged file (cut off by a crash, a bad disk, a sync tool) can be replaced by the
            // last good copy. A missing key or a file from a newer Keychain cannot: the copy would
            // fail the same way, or roll back newer data.
            if (key != null && !(failure instanceof VaultCodec.NewerVersionException) && restoreBackup(key)) return;
            LOGGER.error("Keychain could not read {}; it will not be overwritten", file, failure);
            loadedContent = null;
            String reason = failure.getMessage();
            set(VaultData.EMPTY, State.UNREADABLE, reason == null || reason.isBlank() ? failure.getClass().getSimpleName() : reason);
        }
    }

    /** The previous good state of the vault, kept by every save. */
    public Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /**
     * True once after the vault was rebuilt from its automatic backup because the file itself was
     * damaged, so the UI can say that the most recent changes may be missing.
     */
    public boolean takeRestoredNotice() {
        boolean notice = restoredFromBackup;
        restoredFromBackup = false;
        return notice;
    }

    private boolean restoreBackup(SecretKey key) {
        Path backup = backupFile();
        try {
            if (!Files.exists(backup)) return false;
            String content = Files.readString(backup, StandardCharsets.UTF_8);
            VaultData restored = VaultCodec.decode(content, key);
            // Keep a copy of the damaged file for inspection, then replace it in one step: if anything
            // fails on the way, the vault file is still where it was.
            Files.copy(file, file.resolveSibling(file.getFileName() + ".damaged-" + timestamp()));
            SafeFiles.writeAtomically(file, content);
            loadedContent = content;
            restoredFromBackup = true;
            LOGGER.warn("Keychain restored {} from its automatic backup because the file was damaged", file);
            set(restored, State.READY, null);
            return true;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Keychain could not restore {} from {}", file, backup, failure);
            return false;
        }
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
    }

    /**
     * Compares the file's content, not its modification time: two saves inside one timestamp tick
     * (file systems with one- or two-second resolution) must still be noticed.
     */
    private void reloadIfChangedElsewhere() {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (state != State.READY || !content.equals(loadedContent)) read();
        } catch (NoSuchFileException deleted) {
            // Removed while running: the next save recreates it from memory, unless it never opened.
            if (state != State.READY) read();
        } catch (IOException failure) {
            LOGGER.warn("Keychain could not check {} for outside changes", file, failure);
        }
    }

    private void write(VaultData next) throws IOException {
        SecretKey key = keys.get();
        String encoded = VaultCodec.encode(next, key);
        // Never save something that cannot be read back: decrypt the new file before it replaces the old one.
        if (!VaultCodec.decode(encoded, key).equals(next)) throw new IOException("The vault did not read back correctly; nothing was saved");
        // Keep the state being replaced as the automatic backup. Only a state that was read or
        // written successfully qualifies, so a damaged file never overwrites a good backup.
        if (loadedContent != null) {
            try {
                SafeFiles.writeAtomically(backupFile(), loadedContent);
            } catch (IOException failure) {
                LOGGER.warn("Keychain could not update the backup copy of {}", file, failure);
            }
        }
        SafeFiles.writeAtomically(file, encoded);
        loadedContent = encoded;
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

    /**
     * Takes the lock file next to the vault so that another game instance cannot read, change and
     * save at the same moment. Best effort: where locking is unavailable (or another instance holds
     * the lock unusually long) the change goes ahead anyway, protected by the re-read before it.
     */
    private Lock lock() {
        FileChannel channel = null;
        try {
            Files.createDirectories(file.getParent());
            channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
                FileLock held = channel.tryLock();
                if (held != null) return new Lock(channel);
                Thread.sleep(LOCK_RETRY_MILLIS);
            }
            LOGGER.warn("Keychain could not lock {} in time; continuing without the lock", file);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | OverlappingFileLockException | UnsupportedOperationException unavailable) {
            LOGGER.debug("Keychain cannot lock {}; continuing without the lock", file, unavailable);
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Nothing was locked.
            }
        }
        return new Lock(null);
    }

    /** Closing the channel releases the lock. */
    private record Lock(FileChannel channel) implements AutoCloseable {
        @Override
        public void close() {
            if (channel == null) return;
            try {
                channel.close();
            } catch (IOException failure) {
                LOGGER.debug("Keychain could not release a vault lock", failure);
            }
        }
    }
}

package io.github.domsimus.keychain.core.vault;

import io.github.domsimus.keychain.core.SafeFiles;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The random AES key that encrypts the vault.
 *
 * <p>The key lives in the per-user Keychain folder, outside every Minecraft instance, so zipping and
 * sharing an instance or modpack never shares working tokens. On Windows the key itself is sealed
 * with DPAPI (tied to the Windows user account) through the JNA library that Minecraft already
 * ships; elsewhere the file is readable only by its owner.
 */
public final class VaultKey {
    private static final String DPAPI = "dpapi:";
    private static final String PLAIN = "plain:";
    private static final int KEY_BYTES = 32;

    private VaultKey() {}

    /**
     * Loads the key at {@code file}, creating it on first use. Safe when several games start at once:
     * the key is written to a temporary file first and only then published under its real name, so
     * another game never sees a half-written key, and every game ends up with the key that won.
     */
    public static SecretKey loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) return read(file);
        byte[] raw = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(raw);
        String encoded = Dpapi.available()
            ? DPAPI + Base64.getEncoder().encodeToString(Dpapi.protect(raw))
            : PLAIN + Base64.getEncoder().encodeToString(raw);
        Files.createDirectories(file.getParent());
        SafeFiles.restrictToOwner(file.getParent());
        Arrays.fill(raw, (byte) 0);
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            SafeFiles.restrictToOwner(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII)));
                channel.force(true);
            }
            publish(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
        // Always return what is on disk: if another game published first, its key is the one in use.
        return read(file);
    }

    /** Makes the finished key file appear as {@code file} in one step, unless one already exists. */
    private static void publish(Path temporary, Path file) throws IOException {
        try {
            Files.createLink(file, temporary);
            return;
        } catch (FileAlreadyExistsException anotherGameWon) {
            return;
        } catch (UnsupportedOperationException | IOException noHardLinks) {
            // File systems without hard links (FAT, some network shares): fall through.
        }
        try {
            Files.copy(temporary, file);
            SafeFiles.restrictToOwner(file);
        } catch (FileAlreadyExistsException anotherGameWon) {
            // Keep the existing key.
        }
    }

    private static SecretKey read(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.US_ASCII).strip();
        // An empty file is one that another game (or an older Keychain) is still writing: wait briefly.
        for (int attempt = 0; content.isEmpty() && attempt < 20; attempt++) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            content = Files.readString(file, StandardCharsets.US_ASCII).strip();
        }
        byte[] raw;
        try {
            if (content.startsWith(DPAPI)) {
                if (!Dpapi.available()) throw new IOException("The vault key is sealed with Windows DPAPI, which is unavailable");
                raw = Dpapi.unprotect(Base64.getDecoder().decode(content.substring(DPAPI.length())));
            } else if (content.startsWith(PLAIN)) {
                raw = Base64.getDecoder().decode(content.substring(PLAIN.length()));
            } else {
                throw new IOException("Unknown vault key format");
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("The vault key file is damaged", malformed);
        }
        if (raw.length != KEY_BYTES) throw new IOException("The vault key has the wrong length");
        SecretKey key = new SecretKeySpec(raw, "AES");
        // The key object keeps its own copy; do not leave another one lying around in memory.
        Arrays.fill(raw, (byte) 0);
        return key;
    }

    /** Windows DPAPI through JNA, looked up reflectively so the core has no hard JNA dependency. */
    static final class Dpapi {
        private static final Method PROTECT;
        private static final Method UNPROTECT;

        static {
            Method protect = null, unprotect = null;
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                try {
                    Class<?> util = Class.forName("com.sun.jna.platform.win32.Crypt32Util");
                    protect = util.getMethod("cryptProtectData", byte[].class);
                    unprotect = util.getMethod("cryptUnprotectData", byte[].class);
                } catch (ReflectiveOperationException | LinkageError unavailable) {
                    protect = unprotect = null;
                }
            }
            PROTECT = protect;
            UNPROTECT = unprotect;
        }

        static boolean available() {
            return PROTECT != null;
        }

        static byte[] protect(byte[] data) throws IOException {
            return call(PROTECT, data);
        }

        static byte[] unprotect(byte[] data) throws IOException {
            return call(UNPROTECT, data);
        }

        private static byte[] call(Method method, byte[] data) throws IOException {
            try {
                return (byte[]) method.invoke(null, (Object) data);
            } catch (InvocationTargetException failure) {
                throw new IOException("Windows DPAPI failed", failure.getCause());
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IOException("Windows DPAPI is unavailable", failure);
            }
        }
    }
}

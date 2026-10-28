package io.github.domsimus.keychain.core.vault;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
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

    /** Loads the key at {@code file}, creating it on first use. Safe when several games start at once. */
    public static SecretKey loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) return read(file);
        byte[] raw = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(raw);
        String encoded = Dpapi.available()
            ? DPAPI + Base64.getEncoder().encodeToString(Dpapi.protect(raw))
            : PLAIN + Base64.getEncoder().encodeToString(raw);
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, encoded, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException anotherGameWon) {
            return read(file);
        }
        restrictToOwner(file);
        return new SecretKeySpec(raw, "AES");
    }

    private static SecretKey read(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.US_ASCII).strip();
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
        return new SecretKeySpec(raw, "AES");
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the key is protected by DPAPI and the per-user AppData ACL instead.
        }
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

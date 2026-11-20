package io.github.domsimus.keychain.core.vault;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-protected backups of the vault, for moving accounts to another computer or keeping a
 * copy. Unlike the vault, a backup does not depend on this computer's key: the AES-256-GCM key is
 * derived from the password with PBKDF2-HMAC-SHA256 and a random salt, so the file opens anywhere
 * with the right password, and guessing the password is slow.
 */
public final class BackupCodec {
    public static final String EXTENSION = ".keychain-backup";
    public static final int MIN_PASSWORD_LENGTH = 8;
    static final String FORMAT = "keychain-backup";
    static final int VERSION = 1;
    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256; about half a second on a typical PC. */
    static final int ITERATIONS = 600_000;
    /** Bounds for the count read from a file, so a crafted file can neither weaken nor hang an import. */
    private static final int MIN_ITERATIONS = 100_000, MAX_ITERATIONS = 10_000_000;
    private static final int SALT_BYTES = 16, IV_BYTES = 12, TAG_BITS = 128, KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why a backup could not be opened, for a precise message in the UI. */
    public enum Problem { NOT_A_BACKUP, NEWER_VERSION, WRONG_PASSWORD, DAMAGED }

    public static final class BackupException extends IOException {
        private final Problem problem;

        BackupException(Problem problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    private BackupCodec() {}

    public static String encode(VaultData data, char[] password) throws IOException {
        if (password.length < MIN_PASSWORD_LENGTH) throw new IllegalArgumentException("Password too short");
        byte[] salt = random(SALT_BYTES);
        byte[] iv = random(IV_BYTES);
        // The backup holds accounts only; which account was active belongs to each installation.
        byte[] plain = VaultCodec.toJson(new VaultData(data.accounts(), null)).toString().getBytes(StandardCharsets.UTF_8);
        byte[] cipherText;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, ITERATIONS), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(ITERATIONS, salt));
            cipherText = cipher.doFinal(plain);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Could not encrypt the backup", failure);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("format", FORMAT);
        envelope.addProperty("version", VERSION);
        envelope.addProperty("created", Instant.now().toString());
        envelope.addProperty("kdf", "PBKDF2WithHmacSHA256");
        envelope.addProperty("iterations", ITERATIONS);
        envelope.addProperty("salt", Base64.getEncoder().encodeToString(salt));
        envelope.addProperty("iv", Base64.getEncoder().encodeToString(iv));
        envelope.addProperty("data", Base64.getEncoder().encodeToString(cipherText));
        return envelope.toString();
    }

    public static VaultData decode(String content, char[] password) throws BackupException {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new BackupException(Problem.NOT_A_BACKUP, "Not a Keychain backup", malformed);
        }
        if (!FORMAT.equals(string(envelope, "format"))) throw new BackupException(Problem.NOT_A_BACKUP, "Not a Keychain backup", null);
        byte[] plain;
        try {
            int version = envelope.get("version").getAsInt();
            if (version > VERSION) throw new BackupException(Problem.NEWER_VERSION, "Made by a newer Keychain", null);
            if (version != VERSION || !"PBKDF2WithHmacSHA256".equals(string(envelope, "kdf"))) {
                throw new BackupException(Problem.DAMAGED, "Unsupported backup format", null);
            }
            int iterations = envelope.get("iterations").getAsInt();
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw new BackupException(Problem.DAMAGED, "Implausible iteration count", null);
            byte[] salt = Base64.getDecoder().decode(string(envelope, "salt"));
            byte[] iv = Base64.getDecoder().decode(string(envelope, "iv"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, iterations), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(iterations, salt));
            plain = cipher.doFinal(Base64.getDecoder().decode(string(envelope, "data")));
        } catch (AEADBadTagException wrongPassword) {
            // GCM cannot tell a wrong password from a changed file; the password is far more likely.
            throw new BackupException(Problem.WRONG_PASSWORD, "Wrong password", wrongPassword);
        } catch (BackupException known) {
            throw known;
        } catch (GeneralSecurityException | RuntimeException damaged) {
            throw new BackupException(Problem.DAMAGED, "The backup is damaged", damaged);
        }
        try {
            VaultData data = VaultCodec.fromJson(JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject());
            return new VaultData(data.accounts(), null);
        } catch (RuntimeException malformed) {
            throw new BackupException(Problem.DAMAGED, "The backup is damaged", malformed);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            SecretKey key = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return key;
        } finally {
            spec.clearPassword();
        }
    }

    /** Ties the encrypted data to its own parameters, so they cannot be swapped in the file. */
    private static byte[] aad(int iterations, byte[] salt) {
        return (FORMAT + "/" + VERSION + "/" + iterations + "/" + Base64.getEncoder().encodeToString(salt)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return value;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}

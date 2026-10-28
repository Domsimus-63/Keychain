package io.github.domsimus.keychain.core.vault;

import static org.junit.jupiter.api.Assertions.*;

import io.github.domsimus.keychain.core.account.Account;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyVaultTest {
    private static final String OLD_VAULT = """
        [
          {"type":"OFFLINE","username":"Older","uuid":"6f3b1f0e-1d5c-3d8e-9b7a-2b2f6c1a0e01","accessToken":"0","favorite":false},
          {"type":"MICROSOFT","username":"Newer","uuid":"0f8fad5b-d9cb-469f-a165-70867728950e","accessToken":"mc-token",
           "refreshToken":"ms-refresh","xuid":"123","expiresAt":"2026-10-10T10:00:00Z","lastUsedAt":"2026-10-09T10:00:00Z","favorite":true}
        ]
        """;

    @TempDir Path dir;

    @Test
    void readsEncryptedVaultFromKeychain3() throws Exception {
        Path file = dir.resolve(LegacyVault.FILE_NAME);
        Files.writeString(file, encryptLikeKeychain3(OLD_VAULT));
        assertMigrated(LegacyVault.read(file));
    }

    @Test
    void readsPlainTextVaultFromKeychain1() throws Exception {
        Path file = dir.resolve(LegacyVault.FILE_NAME);
        Files.writeString(file, OLD_VAULT);
        assertMigrated(LegacyVault.read(file));
    }

    @Test
    void refusesADamagedVaultInsteadOfDroppingAccounts() throws Exception {
        Path file = dir.resolve(LegacyVault.FILE_NAME);
        Files.writeString(file, "[{\"type\":\"OFFLINE\",\"username\":\"NoUuid\"}]");
        assertThrows(IOException.class, () -> LegacyVault.read(file));
    }

    @Test
    void readsTheOldCustomClientId() throws Exception {
        Path file = dir.resolve(LegacyVault.CONFIG_NAME);
        Files.writeString(file, "azure_client_id=11111111-2222-3333-4444-555555555555\n");
        assertEquals("11111111-2222-3333-4444-555555555555", LegacyVault.readClientId(file));
        Files.writeString(file, "azure_client_id=\n");
        assertNull(LegacyVault.readClientId(file));
    }

    private static void assertMigrated(List<Account> accounts) {
        assertEquals(List.of("Newer", "Older"), accounts.stream().map(Account::name).toList(), "old display order is newest first");
        Account microsoft = accounts.getFirst();
        assertEquals(Account.Kind.MICROSOFT, microsoft.kind());
        assertTrue(microsoft.favorite());
        assertEquals("ms-refresh", microsoft.session().refreshToken());
        assertEquals(Instant.parse("2026-10-09T10:00:00Z"), microsoft.lastUsed());
        assertEquals(Account.Kind.OFFLINE, accounts.get(1).kind());
    }

    /** Reproduces Keychain 3.0's machine-derived encryption. */
    private static String encryptLikeKeychain3(String json) throws Exception {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isEmpty()) host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) host = "default-host";
        ByteArrayOutputStream material = new ByteArrayOutputStream();
        material.write("keychain-mod-aes-key-v1".getBytes(StandardCharsets.UTF_8));
        material.write(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
        material.write(0);
        material.write(host.getBytes(StandardCharsets.UTF_8));
        byte[] key = MessageDigest.getInstance("SHA-256").digest(material.toByteArray());
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[12 + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, 12);
        System.arraycopy(encrypted, 0, combined, 12, encrypted.length);
        return "$keychain1$" + Base64.getEncoder().encodeToString(combined);
    }
}

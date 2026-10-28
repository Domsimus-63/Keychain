package io.github.domsimus.keychain.core.vault;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads the vault written by Keychain 1.x to 3.x ({@code config/account-switcher.json}) so existing
 * users keep their accounts. Read-only: the old format is never written again.
 */
public final class LegacyVault {
    public static final String FILE_NAME = "account-switcher.json";
    public static final String CONFIG_NAME = "account-switcher.properties";
    private static final String PREFIX = "$keychain1$";
    private static final byte[] PEPPER = "keychain-mod-aes-key-v1".getBytes(StandardCharsets.UTF_8);

    private LegacyVault() {}

    /**
     * Returns the accounts in the order the old version displayed them (newest first), skipping
     * nothing: a single unreadable entry fails the migration so no account is silently lost.
     */
    public static List<Account> read(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (content.isEmpty()) return List.of();
        String json = content;
        if (content.startsWith(PREFIX)) {
            json = decrypt(content.substring(PREFIX.length()));
            if (json == null) throw new IOException("The old Keychain vault was encrypted on a different computer or user account");
        }
        List<Account> accounts = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) accounts.add(account(element.getAsJsonObject()));
        } catch (RuntimeException malformed) {
            throw new IOException("The old Keychain vault is damaged", malformed);
        }
        Collections.reverse(accounts);
        return accounts;
    }

    /** The custom Azure client ID from the old properties file, or {@code null}. */
    public static String readClientId(Path file) {
        if (!Files.exists(file)) return null;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException unreadable) {
            return null;
        }
        String id = properties.getProperty("azure_client_id", "").strip();
        return id.isEmpty() ? null : id;
    }

    private static Account account(JsonObject json) {
        UUID id = UUID.fromString(json.get("uuid").getAsString());
        String name = json.get("username").getAsString();
        boolean favorite = json.has("favorite") && json.get("favorite").getAsBoolean();
        Instant lastUsed = instant(json, "lastUsedAt");
        if (!"MICROSOFT".equals(json.get("type").getAsString())) {
            return new Account(id, name, Account.Kind.OFFLINE, null, favorite, lastUsed);
        }
        Instant expires = instant(json, "expiresAt");
        MicrosoftSession session = new MicrosoftSession(text(json, "accessToken", ""), text(json, "refreshToken", null),
            text(json, "xuid", null), expires == null ? Instant.EPOCH : expires);
        return new Account(id, name, Account.Kind.MICROSOFT, session, favorite, lastUsed);
    }

    private static String text(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static Instant instant(JsonObject json, String key) {
        String value = text(json, key, null);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    /** The old key was derived from the user and computer names; two host-name sources were used over time. */
    private static String decrypt(String base64) {
        String environmentHost = System.getenv("COMPUTERNAME");
        if (environmentHost == null || environmentHost.isEmpty()) environmentHost = System.getenv("HOSTNAME");
        if (environmentHost == null || environmentHost.isEmpty()) environmentHost = "default-host";
        String result = decrypt(base64, environmentHost);
        if (result != null) return result;
        try {
            return decrypt(base64, InetAddress.getLocalHost().getHostName());
        } catch (IOException unknownHost) {
            return decrypt(base64, "unknown-host");
        }
    }

    private static String decrypt(String base64, String host) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64);
            if (combined.length < 28) return null;
            ByteArrayOutputStream material = new ByteArrayOutputStream();
            material.write(PEPPER);
            material.write(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
            material.write(0);
            material.write(host.getBytes(StandardCharsets.UTF_8));
            byte[] key = MessageDigest.getInstance("SHA-256").digest(material.toByteArray());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception wrongKey) {
            return null;
        }
    }
}

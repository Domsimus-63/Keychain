package io.github.domsimus.keychain.core.vault;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Reads and writes the vault file: a small JSON envelope around AES-256-GCM encrypted account data.
 * The envelope is versioned so future formats can be detected instead of misread.
 */
public final class VaultCodec {
    static final String FORMAT = "keychain-vault";
    static final int VERSION = 2;
    private static final byte[] AAD = (FORMAT + "/" + VERSION).getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCodec() {}

    public static String encode(VaultData data, SecretKey key) throws IOException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] cipherText;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            cipherText = cipher.doFinal(toJson(data).toString().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IOException("Could not encrypt the vault", failure);
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("format", FORMAT);
        envelope.addProperty("version", VERSION);
        envelope.addProperty("iv", Base64.getEncoder().encodeToString(iv));
        envelope.addProperty("data", Base64.getEncoder().encodeToString(cipherText));
        return envelope.toString();
    }

    public static VaultData decode(String content, SecretKey key) throws IOException {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("The vault file is not valid JSON", malformed);
        }
        if (!FORMAT.equals(string(envelope, "format"))) throw new IOException("Not a Keychain vault");
        int version = envelope.has("version") ? envelope.get("version").getAsInt() : -1;
        if (version != VERSION) throw new IOException("Unsupported vault version " + version + " (made by a newer Keychain?)");
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(string(envelope, "iv"))));
            cipher.updateAAD(AAD);
            plain = cipher.doFinal(Base64.getDecoder().decode(string(envelope, "data")));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException("The vault could not be decrypted with this computer's key", failure);
        }
        try {
            return fromJson(JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException malformed) {
            throw new IOException("The decrypted vault is damaged", malformed);
        }
    }

    static JsonObject toJson(VaultData data) {
        JsonArray accounts = new JsonArray();
        for (Account account : data.accounts()) accounts.add(toJson(account));
        JsonObject root = new JsonObject();
        root.add("accounts", accounts);
        if (data.lastActive() != null) root.addProperty("lastActive", data.lastActive().toString());
        return root;
    }

    /** Strict: one malformed account fails the whole read so a save can never silently drop it. */
    static VaultData fromJson(JsonObject root) {
        List<Account> accounts = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("accounts")) accounts.add(accountFromJson(element.getAsJsonObject()));
        String lastActive = string(root, "lastActive");
        return new VaultData(accounts, lastActive == null ? null : UUID.fromString(lastActive));
    }

    private static JsonObject toJson(Account account) {
        JsonObject json = new JsonObject();
        json.addProperty("id", account.id().toString());
        json.addProperty("name", account.name());
        json.addProperty("kind", account.kind().name().toLowerCase(Locale.ROOT));
        json.addProperty("favorite", account.favorite());
        if (account.lastUsed() != null) json.addProperty("lastUsed", account.lastUsed().toString());
        MicrosoftSession session = account.session();
        if (session != null) {
            JsonObject tokens = new JsonObject();
            tokens.addProperty("accessToken", session.accessToken());
            if (session.refreshToken() != null) tokens.addProperty("refreshToken", session.refreshToken());
            if (session.xuid() != null) tokens.addProperty("xuid", session.xuid());
            tokens.addProperty("expiresAt", session.expiresAt().toString());
            if (session.clientId() != null) tokens.addProperty("clientId", session.clientId());
            if (session.obtainedAt() != null) tokens.addProperty("obtainedAt", session.obtainedAt().toString());
            json.add("session", tokens);
        }
        return json;
    }

    private static Account accountFromJson(JsonObject json) {
        Account.Kind kind = Account.Kind.valueOf(string(json, "kind").toUpperCase(Locale.ROOT));
        MicrosoftSession session = null;
        if (json.has("session")) {
            JsonObject tokens = json.getAsJsonObject("session");
            String obtained = string(tokens, "obtainedAt");
            session = new MicrosoftSession(string(tokens, "accessToken"), string(tokens, "refreshToken"), string(tokens, "xuid"),
                Instant.parse(string(tokens, "expiresAt")), string(tokens, "clientId"), obtained == null ? null : Instant.parse(obtained));
        }
        String lastUsed = string(json, "lastUsed");
        return new Account(UUID.fromString(string(json, "id")), string(json, "name"), kind, session,
            json.has("favorite") && json.get("favorite").getAsBoolean(), lastUsed == null ? null : Instant.parse(lastUsed));
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}

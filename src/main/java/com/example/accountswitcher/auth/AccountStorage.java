/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  com.google.gson.TypeAdapter
 *  com.google.gson.reflect.TypeToken
 *  com.google.gson.stream.JsonReader
 *  com.google.gson.stream.JsonToken
 *  com.google.gson.stream.JsonWriter
 *  net.fabricmc.loader.api.FabricLoader
 */
package com.example.accountswitcher.auth;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.auth.TokenCrypto;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileAttribute;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class AccountStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(Instant.class, (Object)new TypeAdapter<Instant>(){

        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            try {
                return Instant.parse(in.nextString());
            }
            catch (DateTimeParseException var3) {
                return null;
            }
        }
    }).create();
    private static final Type LIST_TYPE = new TypeToken<List<Account>>(){}.getType();
    private static final String ENC_PREFIX = "$keychain1$";
    private final Path file;
    private final List<Account> accounts = new ArrayList<Account>();
    private boolean unreadable;

    public AccountStorage() {
        this(FabricLoader.getInstance().getConfigDir().resolve("account-switcher.json"));
    }

    AccountStorage(Path file) {
        this.file = file;
    }

    public synchronized List<Account> getAccounts() {
        return List.copyOf(this.accounts);
    }

    public synchronized void load() {
        this.accounts.clear();
        this.unreadable = false;
        if (Files.exists(this.file, new LinkOption[0])) {
            String content;
            try {
                content = Files.readString(this.file).trim();
            }
            catch (IOException var5) {
                AccountSwitcherMod.LOGGER.error("Failed to read accounts file", (Throwable)var5);
                this.unreadable = true;
                return;
            }
            if (!content.isEmpty()) {
                String json;
                boolean needResave = false;
                if (content.startsWith(ENC_PREFIX)) {
                    json = TokenCrypto.decrypt(content.substring(ENC_PREFIX.length()));
                    if (json == null) {
                        AccountSwitcherMod.LOGGER.warn("Saved accounts could not be decrypted. The file will not be overwritten.");
                        this.unreadable = true;
                        return;
                    }
                } else {
                    json = content;
                    needResave = true;
                    AccountSwitcherMod.LOGGER.info("Migrating plaintext accounts file to encrypted format.");
                }
                try {
                    List<Account> loaded = GSON.fromJson(json, LIST_TYPE);
                    if (loaded == null) throw new IOException("The vault is not an account list");
                    for (Account account : loaded) {
                        if (account == null || account.type == null || account.username == null
                            || account.username.isBlank() || account.uuid == null)
                            throw new IOException("The vault contains an invalid account");
                        this.accounts.add(account);
                    }
                }
                catch (Exception var6) {
                    AccountSwitcherMod.LOGGER.error("Saved accounts JSON was malformed; preserving the vault", (Throwable)var6);
                    this.accounts.clear();
                    this.unreadable = true;
                    return;
                }
                if (needResave) {
                    this.save();
                }
            }
        }
    }

    public synchronized boolean save() {
        if (this.unreadable) {
            AccountSwitcherMod.LOGGER.warn("Refusing to overwrite an unreadable accounts file");
            return false;
        }
        Path temporary = null;
        try {
            String json = GSON.toJson(this.accounts);
            String body = ENC_PREFIX + TokenCrypto.encrypt(json);
            Files.createDirectories(this.file.getParent(), new FileAttribute[0]);
            temporary = Files.createTempFile(this.file.getParent(), "account-switcher-", ".tmp");
            Files.writeString(temporary, body, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, this.file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
        catch (Exception error) {
            AccountSwitcherMod.LOGGER.error("Failed to save accounts", error);
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public synchronized boolean isUnreadable() {
        return this.unreadable;
    }

    public synchronized boolean add(Account account) {
        if (account == null || account.uuid == null || account.type == null
            || account.username == null || account.username.isBlank()) return false;
        List<Account> previous = List.copyOf(this.accounts);
        int existing = -1;
        for (int i = 0; i < this.accounts.size(); i++) {
            Account saved = this.accounts.get(i);
            if (saved.uuid != null && saved.uuid.equals(account.uuid)) {
                if (existing < 0) {
                    existing = i;
                    account.favorite = saved.favorite;
                    this.accounts.set(i, account);
                } else {
                    this.accounts.remove(i--);
                }
            }
        }
        if (existing < 0) this.accounts.add(account);
        if (this.save()) return true;
        this.accounts.clear();
        this.accounts.addAll(previous);
        return false;
    }

    public synchronized boolean remove(Account account) {
        List<Account> previous = List.copyOf(this.accounts);
        this.accounts.removeIf(saved -> java.util.Objects.equals(saved.uuid, account.uuid));
        if (this.save()) return true;
        this.accounts.clear();
        this.accounts.addAll(previous);
        return false;
    }

    public synchronized boolean toggleFavorite(Account account) {
        for (Account saved : this.accounts) {
            if (java.util.Objects.equals(saved.uuid, account.uuid)) {
                boolean previous = saved.favorite;
                saved.favorite = !previous;
                if (this.save()) {
                    account.favorite = saved.favorite;
                    return true;
                }
                saved.favorite = previous;
                return false;
            }
        }
        return false;
    }
}

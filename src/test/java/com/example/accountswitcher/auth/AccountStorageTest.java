package com.example.accountswitcher.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStorageTest {
    @TempDir Path directory;

    @Test
    void savesAndReloadsEncryptedAccounts() throws Exception {
        Path path = directory.resolve("account-switcher.json");
        AccountStorage storage = new AccountStorage(path);
        Account account = offlineAccount();

        assertTrue(storage.add(account));
        assertTrue(Files.readString(path).startsWith("$keychain1$"));
        assertFalse(Files.readString(path).contains(account.username));

        AccountStorage reloaded = new AccountStorage(path);
        reloaded.load();
        assertEquals(1, reloaded.getAccounts().size());
        assertEquals(account.uuid, reloaded.getAccounts().getFirst().uuid);
    }

    @Test
    void neverOverwritesAnUnreadableVault() throws Exception {
        Path path = directory.resolve("account-switcher.json");
        String damaged = "$keychain1$unreadable";
        Files.writeString(path, damaged);
        AccountStorage storage = new AccountStorage(path);

        storage.load();
        assertTrue(storage.isUnreadable());
        assertFalse(storage.add(offlineAccount()));
        assertEquals(damaged, Files.readString(path));
    }

    @Test
    void switchingAnAccountKeepsItsPositionAfterReload() throws Exception {
        Path path = directory.resolve("account-switcher.json");
        AccountStorage storage = new AccountStorage(path);
        Account first = offlineAccount("First");
        Account middle = offlineAccount("Middle");
        Account last = offlineAccount("Last");
        assertTrue(storage.add(first));
        assertTrue(storage.add(middle));
        assertTrue(storage.add(last));

        Account switched = offlineAccount("Middle");
        switched.lastUsedAt = java.time.Instant.now();
        assertTrue(storage.add(switched));
        assertEquals(java.util.List.of(first.uuid, middle.uuid, last.uuid),
                storage.getAccounts().stream().map(account -> account.uuid).toList());

        AccountStorage reloaded = new AccountStorage(path);
        reloaded.load();
        assertEquals(java.util.List.of(first.uuid, middle.uuid, last.uuid),
                reloaded.getAccounts().stream().map(account -> account.uuid).toList());
    }

    private static Account offlineAccount() {
        return offlineAccount("TestPlayer");
    }

    @Test
    void favoritesSurviveReloadAndTokenReplacement() {
        Path path = directory.resolve("account-switcher.json");
        AccountStorage storage = new AccountStorage(path);
        Account original = offlineAccount();
        assertTrue(storage.add(original));
        assertTrue(storage.toggleFavorite(original));
        Account replacement = offlineAccount();
        assertTrue(storage.add(replacement));
        assertTrue(replacement.favorite);
        AccountStorage reloaded = new AccountStorage(path);
        reloaded.load();
        assertTrue(reloaded.getAccounts().getFirst().favorite);
        assertTrue(reloaded.toggleFavorite(reloaded.getAccounts().getFirst()));
        assertFalse(reloaded.getAccounts().getFirst().favorite);
    }

    @Test
    void favoriteFailureRollsBackMemory() throws Exception {
        Path path = directory.resolve("account-switcher.json");
        AccountStorage storage = new AccountStorage(path);
        Account account = offlineAccount();
        assertTrue(storage.add(account));
        Files.delete(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("occupied"), "force save failure");
        assertFalse(storage.toggleFavorite(account));
        assertFalse(account.favorite);
    }

    @Test
    void malformedEntriesDoNotPartiallyMigrateTheVault() throws Exception {
        Path path = directory.resolve("account-switcher.json");
        String original = "[{\"username\":\"Saved\",\"uuid\":\"" + UUID.randomUUID() + "\",\"type\":\"OFFLINE\"},null]";
        Files.writeString(path, original);
        AccountStorage storage = new AccountStorage(path);
        storage.load();
        assertTrue(storage.isUnreadable());
        assertTrue(storage.getAccounts().isEmpty());
        assertFalse(storage.add(offlineAccount()));
        assertEquals(original, Files.readString(path));
    }

    @Test
    void retainsMicrosoftAccountsThatNeedReauthentication() throws Exception {
        Path path = directory.resolve("account-switcher.json");
        Files.writeString(path, "[{\"username\":\"Expired\",\"uuid\":\"" + UUID.randomUUID() + "\",\"type\":\"MICROSOFT\"}]");
        AccountStorage storage = new AccountStorage(path);
        storage.load();
        assertFalse(storage.isUnreadable());
        assertEquals(1, storage.getAccounts().size());
        assertTrue(storage.getAccounts().getFirst().isExpired());
        assertTrue(Files.readString(path).startsWith("$keychain1$"));
    }

    @Test
    void removesByUuidAfterUsernameChanges() {
        AccountStorage storage = new AccountStorage(directory.resolve("account-switcher.json"));
        Account original = offlineAccount();
        assertTrue(storage.add(original));
        Account renamed = offlineAccount();
        renamed.username = "Renamed";
        assertTrue(storage.remove(renamed));
        assertTrue(storage.getAccounts().isEmpty());
    }

    private static Account offlineAccount(String username) {
        Account account = new Account();
        account.type = Account.Type.OFFLINE;
        account.username = username;
        account.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
        account.accessToken = "0";
        return account;
    }
}

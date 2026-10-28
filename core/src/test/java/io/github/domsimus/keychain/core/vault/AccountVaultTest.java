package io.github.domsimus.keychain.core.vault;

import static org.junit.jupiter.api.Assertions.*;

import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountVaultTest {
    @TempDir Path dir;

    private AccountVault vault() {
        Path key = dir.resolve("key/vault.key");
        AccountVault vault = new AccountVault(dir.resolve("accounts.vault"), () -> VaultKey.loadOrCreate(key));
        vault.load();
        return vault;
    }

    private static Account microsoft(String name) {
        return Account.microsoft(UUID.randomUUID(), name,
            new MicrosoftSession("access-" + name, "refresh-" + name, "xuid", Instant.now().plusSeconds(3600)));
    }

    @Test
    void roundTripsEncrypted() throws Exception {
        AccountVault vault = vault();
        Account alex = microsoft("Alex");
        assertTrue(vault.upsert(alex));
        assertTrue(vault.upsert(Account.offline("Steve")));

        String raw = Files.readString(vault.file());
        assertFalse(raw.contains("Alex"), "names must not be stored in plain text");
        assertFalse(raw.contains("refresh-Alex"), "tokens must not be stored in plain text");

        AccountVault reloaded = vault();
        assertEquals(vault.accounts(), reloaded.accounts());
        assertEquals("refresh-Alex", reloaded.find(alex.id()).orElseThrow().session().refreshToken());
    }

    @Test
    void reSigningInKeepsPositionFavoriteAndLastUse() {
        AccountVault vault = vault();
        Account first = microsoft("First");
        Account second = microsoft("Second");
        vault.upsert(first);
        vault.upsert(second);
        Instant used = Instant.parse("2026-01-01T00:00:00Z");
        vault.setFavorite(first.id(), true);
        vault.markUsed(first.id(), used);

        Account refreshed = Account.microsoft(first.id(), "First", new MicrosoftSession("new", "new-refresh", null, Instant.now().plusSeconds(60)));
        assertTrue(vault.upsert(refreshed));

        Account saved = vault.accounts().getFirst();
        assertEquals(first.id(), saved.id());
        assertTrue(saved.favorite());
        assertEquals(used, saved.lastUsed());
        assertEquals("new", saved.session().accessToken());
        assertEquals(first.id(), vault.data().lastActive());
    }

    @Test
    void keepsTheAppIdAndAgeOfTokens() {
        AccountVault vault = vault();
        Instant obtained = Instant.parse("2026-09-01T00:00:00Z");
        Account account = Account.microsoft(UUID.randomUUID(), "Custom",
            new MicrosoftSession("a", "r", null, Instant.now().plusSeconds(60), "11111111-2222-3333-4444-555555555555", obtained));
        vault.upsert(account);
        MicrosoftSession saved = vault().find(account.id()).orElseThrow().session();
        assertEquals("11111111-2222-3333-4444-555555555555", saved.clientId());
        assertEquals(obtained, saved.obtainedAt());
        assertTrue(saved.isOlderThan(java.time.Duration.ofDays(7), Instant.parse("2026-10-01T00:00:00Z")));
        assertFalse(saved.isOlderThan(java.time.Duration.ofDays(7), Instant.parse("2026-09-03T00:00:00Z")));
    }

    @Test
    void neverOverwritesAnUnreadableVault() throws Exception {
        Path file = dir.resolve("accounts.vault");
        Files.writeString(file, "{\"format\":\"keychain-vault\",\"version\":2,\"iv\":\"AAAA\",\"data\":\"AAAA\"}");
        String before = Files.readString(file);
        AccountVault vault = vault();

        assertEquals(AccountVault.State.UNREADABLE, vault.state());
        assertNotNull(vault.problem());
        assertFalse(vault.upsert(Account.offline("Steve")));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void resetKeepsTheUnreadableFileAsBackup() throws Exception {
        Path file = dir.resolve("accounts.vault");
        Files.writeString(file, "garbage");
        AccountVault vault = vault();
        Path backup = vault.resetUnreadable();

        assertTrue(Files.exists(backup));
        assertEquals("garbage", Files.readString(backup));
        assertTrue(vault.upsert(Account.offline("Steve")));
        assertEquals(1, vault().accounts().size());
    }

    @Test
    void rejectsVaultsFromANewerVersion() throws Exception {
        Files.writeString(dir.resolve("accounts.vault"), "{\"format\":\"keychain-vault\",\"version\":99}");
        AccountVault vault = vault();
        assertEquals(AccountVault.State.UNREADABLE, vault.state());
        assertTrue(vault.problem().contains("99"));
    }

    @Test
    void picksUpChangesFromAnotherGameInstance() throws Exception {
        AccountVault first = vault();
        AccountVault second = vault();
        first.upsert(Account.offline("FromFirst"));
        // Make sure the modification time differs even on coarse file systems.
        Files.setLastModifiedTime(first.file(), FileTime.from(Instant.now().plusSeconds(5)));
        assertTrue(second.upsert(Account.offline("FromSecond")));

        assertEquals(List.of("FromFirst", "FromSecond"), vault().accounts().stream().map(Account::name).toList());
    }

    @Test
    void removingTheActiveAccountClearsIt() {
        AccountVault vault = vault();
        Account steve = Account.offline("Steve");
        vault.upsert(steve);
        vault.markUsed(steve.id(), Instant.now());
        assertTrue(vault.remove(steve.id()));
        assertNull(vault.data().lastActive());
        assertTrue(vault.accounts().isEmpty());
    }

    @Test
    void notifiesListenersAfterChanges() {
        AccountVault vault = vault();
        int[] calls = {0};
        vault.addListener(() -> calls[0]++);
        vault.upsert(Account.offline("Steve"));
        assertEquals(1, calls[0]);
    }
}

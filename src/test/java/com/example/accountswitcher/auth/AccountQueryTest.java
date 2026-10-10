package com.example.accountswitcher.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountQueryTest {
    @Test
    void searchAndFiltersComposeWithoutChangingStorageOrder() {
        Account first = account("Builder", Account.Type.OFFLINE);
        Account second = account("BuildOnline", Account.Type.MICROSOFT);
        Account third = account("Miner", Account.Type.OFFLINE);
        List<Account> original = List.of(first, second, third);
        assertEquals(List.of(second), AccountQuery.select(original, "  BUILD ", AccountQuery.Filter.ONLINE, AccountQuery.Sort.SAVED));
        assertEquals(List.of(first), AccountQuery.select(original, "build", AccountQuery.Filter.OFFLINE, AccountQuery.Sort.NAME));
        assertEquals(List.of(first, second, third), original);
        assertTrue(AccountQuery.select(original, "missing", AccountQuery.Filter.ALL, AccountQuery.Sort.SAVED).isEmpty());
    }

    @Test
    void favoritesStayFirstAndSavedOrderStaysStable() {
        Account first = account("First", Account.Type.OFFLINE);
        Account second = account("Second", Account.Type.OFFLINE);
        Account favorite = account("Favorite", Account.Type.OFFLINE);
        favorite.favorite = true;
        List<Account> accounts = List.of(favorite, first, second);
        assertEquals(List.of(favorite, second, first), AccountQuery.select(accounts, "", AccountQuery.Filter.ALL, AccountQuery.Sort.SAVED));
        assertEquals(List.of(favorite), AccountQuery.select(accounts, "", AccountQuery.Filter.FAVORITES, AccountQuery.Sort.SAVED));
    }

    @Test
    void recentSortPutsUnusedAccountsLast() {
        Account unused = account("Unused", Account.Type.OFFLINE);
        Account old = account("Old", Account.Type.OFFLINE);
        Account recent = account("Recent", Account.Type.OFFLINE);
        old.lastUsedAt = Instant.parse("2026-01-01T00:00:00Z");
        recent.lastUsedAt = Instant.parse("2026-09-01T00:00:00Z");
        assertEquals(List.of(recent, old, unused), AccountQuery.select(List.of(old, recent, unused), "", AccountQuery.Filter.ALL, AccountQuery.Sort.RECENT));
    }

    @Test
    void namesSortWithoutCaseSensitivity() {
        Account z = account("zebra", Account.Type.OFFLINE);
        Account a = account("Alpha", Account.Type.OFFLINE);
        assertEquals(List.of(a, z), AccountQuery.select(List.of(a, z), "", AccountQuery.Filter.ALL, AccountQuery.Sort.NAME));
    }

    @Test
    void microsoftTokensWithoutExpiryAreNotTrusted() {
        Account account = account("Online", Account.Type.MICROSOFT);
        account.accessToken = "test-token";
        assertTrue(account.isExpired());
        account.expiresAt = Instant.now().plusSeconds(3600);
        assertFalse(account.isExpired());
        account.expiresAt = Instant.now().plusSeconds(30);
        assertTrue(account.isExpired());
        assertFalse(account("Offline", Account.Type.OFFLINE).isExpired());
    }

    private static Account account(String name, Account.Type type) {
        Account account = new Account();
        account.username = name;
        account.uuid = UUID.randomUUID();
        account.type = type;
        return account;
    }
}

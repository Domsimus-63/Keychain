package io.github.domsimus.keychain.core.account;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    void offlineUuidMatchesVanilla() {
        // The UUID a vanilla offline-mode server assigns to "Notch".
        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), Account.offlineUuid("Notch"));
    }

    @Test
    void validatesOfflineNames() {
        assertTrue(Account.isValidOfflineName("Steve_123"));
        assertFalse(Account.isValidOfflineName("ab"));
        assertFalse(Account.isValidOfflineName("has space"));
        assertFalse(Account.isValidOfflineName("seventeen_chars_x"));
        assertFalse(Account.isValidOfflineName(null));
    }

    @Test
    void statusFollowsTokens() {
        UUID id = UUID.randomUUID();
        Account fresh = Account.microsoft(id, "A", new MicrosoftSession("t", "r", null, NOW.plusSeconds(3600)));
        Account expired = Account.microsoft(id, "A", new MicrosoftSession("t", "r", null, NOW.minusSeconds(1)));
        Account nearlyExpired = Account.microsoft(id, "A", new MicrosoftSession("t", "r", null, NOW.plusSeconds(30)));
        Account revoked = Account.microsoft(id, "A", new MicrosoftSession("t", null, null, NOW.minusSeconds(1)));

        assertEquals(Account.Status.READY, fresh.status(NOW));
        assertEquals(Account.Status.NEEDS_REFRESH, expired.status(NOW));
        assertEquals(Account.Status.NEEDS_REFRESH, nearlyExpired.status(NOW));
        assertEquals(Account.Status.NEEDS_SIGN_IN, revoked.status(NOW));
        assertEquals(Account.Status.READY, Account.offline("Steve").status(NOW));
        assertEquals(Account.Status.NEEDS_SIGN_IN, fresh.withSession(fresh.session().revoked()).status(NOW));
        assertEquals(Account.Status.READY, fresh.withSession(fresh.session().withoutRefreshToken()).status(NOW));
    }

    @Test
    void offlineAccountsNeverCarryTokens() {
        Account offline = new Account(UUID.randomUUID(), "Steve", Account.Kind.OFFLINE,
            new MicrosoftSession("secret", "r", null, NOW), false, null);
        assertNull(offline.session());
        assertEquals("0", offline.accessToken());
    }

    @Test
    void sessionToStringHidesTokens() {
        assertFalse(new MicrosoftSession("secret-access", "secret-refresh", null, NOW).toString().contains("secret"));
    }

    @Test
    void queryKeepsFavoritesFirstAndFiltersCaseInsensitively() {
        Account alpha = Account.offline("Alpha").withLastUsed(NOW.minusSeconds(100));
        Account beta = Account.microsoft(UUID.randomUUID(), "beta", new MicrosoftSession("t", "r", null, NOW)).withLastUsed(NOW);
        Account gamma = Account.offline("Gamma").withFavorite(true);
        List<Account> saved = List.of(alpha, beta, gamma);

        assertEquals(List.of(gamma, alpha, beta), AccountQuery.DEFAULT.apply(saved));
        assertEquals(List.of(gamma, beta, alpha), AccountQuery.DEFAULT.withSort(AccountQuery.Sort.RECENT).apply(saved));
        assertEquals(List.of(gamma, alpha, beta), AccountQuery.DEFAULT.withSort(AccountQuery.Sort.NAME).apply(saved));
        assertEquals(List.of(beta), AccountQuery.DEFAULT.withSearch("  BET ").apply(saved));
        assertEquals(List.of(beta), AccountQuery.DEFAULT.withFilter(AccountQuery.Filter.MICROSOFT).apply(saved));
        assertEquals(List.of(gamma), AccountQuery.DEFAULT.withFilter(AccountQuery.Filter.FAVORITES).apply(saved));
        assertEquals(List.of(beta), AccountQuery.DEFAULT.withSearch(beta.id().toString().substring(0, 13)).apply(saved), "UUID search");
        assertTrue(AccountQuery.DEFAULT.allowsReordering());
        assertFalse(AccountQuery.DEFAULT.withSearch("x").allowsReordering());
    }
}

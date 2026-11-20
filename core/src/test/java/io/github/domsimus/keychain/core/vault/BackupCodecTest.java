package io.github.domsimus.keychain.core.vault;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackupCodecTest {
    private static final char[] PASSWORD = "correct horse".toCharArray();

    private static VaultData sample() {
        Account alex = Account.microsoft(UUID.randomUUID(), "Alex",
            new MicrosoftSession("access", "refresh-Alex", "xuid", Instant.now().plusSeconds(3600), "client", Instant.now()));
        return new VaultData(List.of(alex, Account.offline("Steve").withFavorite(true)), alex.id());
    }

    @Test
    void roundTripsWithThePassword() throws Exception {
        VaultData data = sample();
        String encoded = BackupCodec.encode(data, PASSWORD);

        assertFalse(encoded.contains("Alex"), "names must not be readable");
        assertFalse(encoded.contains("refresh-Alex"), "tokens must not be readable");
        VaultData decoded = BackupCodec.decode(encoded, PASSWORD);
        assertEquals(data.accounts(), decoded.accounts());
        assertNull(decoded.lastActive(), "the active account belongs to each installation");
    }

    @Test
    void sameDataGivesDifferentFiles() throws Exception {
        VaultData data = sample();
        assertNotEquals(BackupCodec.encode(data, PASSWORD), BackupCodec.encode(data, PASSWORD), "salt and IV are random");
    }

    @Test
    void wrongPasswordIsReportedAsSuch() throws Exception {
        String encoded = BackupCodec.encode(sample(), PASSWORD);
        BackupCodec.BackupException failure = assertThrows(BackupCodec.BackupException.class,
            () -> BackupCodec.decode(encoded, "wrong password".toCharArray()));
        assertEquals(BackupCodec.Problem.WRONG_PASSWORD, failure.problem());
    }

    @Test
    void changedParametersAreDetected() throws Exception {
        JsonObject envelope = JsonParser.parseString(BackupCodec.encode(sample(), PASSWORD)).getAsJsonObject();
        // Lowering the work factor in the file must not produce a working backup.
        envelope.addProperty("iterations", 200_000);
        assertThrows(BackupCodec.BackupException.class, () -> BackupCodec.decode(envelope.toString(), PASSWORD));
    }

    @Test
    void absurdWorkFactorsAreRejectedWithoutTrying() throws Exception {
        JsonObject envelope = JsonParser.parseString(BackupCodec.encode(sample(), PASSWORD)).getAsJsonObject();
        envelope.addProperty("iterations", 2_000_000_000);
        BackupCodec.BackupException failure = assertThrows(BackupCodec.BackupException.class,
            () -> BackupCodec.decode(envelope.toString(), PASSWORD));
        assertEquals(BackupCodec.Problem.DAMAGED, failure.problem());
    }

    @Test
    void recognizesOtherFiles() {
        assertEquals(BackupCodec.Problem.NOT_A_BACKUP,
            assertThrows(BackupCodec.BackupException.class, () -> BackupCodec.decode("hello", PASSWORD)).problem());
        assertEquals(BackupCodec.Problem.NOT_A_BACKUP,
            assertThrows(BackupCodec.BackupException.class, () -> BackupCodec.decode("{\"format\":\"keychain-vault\"}", PASSWORD)).problem());
        assertEquals(BackupCodec.Problem.NEWER_VERSION,
            assertThrows(BackupCodec.BackupException.class, () -> BackupCodec.decode("{\"format\":\"keychain-backup\",\"version\":9}", PASSWORD)).problem());
    }

    @Test
    void refusesShortPasswords() {
        assertThrows(IllegalArgumentException.class, () -> BackupCodec.encode(sample(), "short".toCharArray()));
    }

    @Test
    void importAddsMissingAndRenewsSignedOutAccounts() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        MicrosoftSession working = new MicrosoftSession("access", "refresh", "xuid", now.plusSeconds(3600), "client", now);
        Account signedOut = Account.microsoft(id, "Alex", working.revoked()).withFavorite(true);
        Account steve = Account.offline("Steve");
        VaultData here = new VaultData(List.of(signedOut, steve), null);
        VaultData backup = new VaultData(List.of(Account.microsoft(id, "Alex", working), steve, Account.offline("Notch")), null);

        VaultData.ImportResult result = here.importFrom(backup, now);

        assertEquals(1, result.added());
        assertEquals(1, result.renewed());
        assertEquals(1, result.unchanged());
        Account alex = result.data().find(id).orElseThrow();
        assertEquals(Account.Status.READY, alex.status(now));
        assertTrue(alex.favorite(), "local choices survive a restore");
        assertEquals(List.of("Alex", "Steve", "Notch"), result.data().accounts().stream().map(Account::name).toList());
    }

    @Test
    void importNeverReplacesAWorkingSignIn() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        Account mine = Account.microsoft(id, "Alex", new MicrosoftSession("new", "new-refresh", null, now.plusSeconds(3600)));
        Account old = Account.microsoft(id, "Alex", new MicrosoftSession("old", "old-refresh", null, now.plusSeconds(60)));

        VaultData.ImportResult result = new VaultData(List.of(mine), null).importFrom(new VaultData(List.of(old), null), now);

        assertEquals(0, result.added() + result.renewed());
        assertEquals("new-refresh", result.data().find(id).orElseThrow().session().refreshToken());
    }
}

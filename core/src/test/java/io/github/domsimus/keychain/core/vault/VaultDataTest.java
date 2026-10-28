package io.github.domsimus.keychain.core.vault;

import static org.junit.jupiter.api.Assertions.*;

import io.github.domsimus.keychain.core.account.Account;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultDataTest {
    @TempDir Path dir;

    private static List<String> names(VaultData data) {
        return data.accounts().stream().map(Account::name).toList();
    }

    @Test
    void moveIsClampedToTheEnds() {
        VaultData data = VaultData.EMPTY.upsert(Account.offline("Aaa")).upsert(Account.offline("Bbb")).upsert(Account.offline("Ccc"));
        assertEquals(List.of("Bbb", "Aaa", "Ccc"), names(data.move(Account.offlineUuid("Bbb"), -1)));
        assertEquals(List.of("Bbb", "Ccc", "Aaa"), names(data.move(Account.offlineUuid("Aaa"), 10)));
        assertEquals(List.of("Aaa", "Bbb", "Ccc"), names(data.move(Account.offlineUuid("Aaa"), -5)));
    }

    @Test
    void placesNextToTheVisibleNeighbor() {
        VaultData data = VaultData.EMPTY.upsert(Account.offline("Aaa")).upsert(Account.offline("Bbb")).upsert(Account.offline("Ccc"));
        assertEquals(List.of("Bbb", "Ccc", "Aaa"), names(data.placeNextTo(Account.offlineUuid("Aaa"), Account.offlineUuid("Ccc"), true)));
        assertEquals(List.of("Ccc", "Aaa", "Bbb"), names(data.placeNextTo(Account.offlineUuid("Ccc"), Account.offlineUuid("Aaa"), false)));
        assertSame(data, data.placeNextTo(Account.offlineUuid("Aaa"), Account.offlineUuid("Aaa"), true));
    }

    @Test
    void undoRestoresTheOldPosition() {
        VaultData data = VaultData.EMPTY.upsert(Account.offline("Aaa")).upsert(Account.offline("Bbb")).upsert(Account.offline("Ccc"));
        Account middle = data.accounts().get(1);
        VaultData removed = data.remove(middle.id());
        assertEquals(names(data), names(removed.restore(middle, 1)));
        assertEquals(List.of("Aaa", "Ccc", "Bbb"), names(removed.restore(middle, 99)));
        assertSame(data, data.restore(middle, 0));
    }

    @Test
    void mergeOnlyAddsMissingAccounts() {
        VaultData local = VaultData.EMPTY.upsert(Account.offline("Aaa").withFavorite(true));
        VaultData shared = VaultData.EMPTY.upsert(Account.offline("Aaa")).upsert(Account.offline("Bbb"));
        VaultData merged = local.mergeMissing(shared);
        assertEquals(List.of("Aaa", "Bbb"), names(merged));
        assertTrue(merged.accounts().getFirst().favorite());
    }

    @Test
    void keyIsCreatedOnceEvenWhenSeveralGamesStartTogether() throws Exception {
        Path file = dir.resolve("vault.key");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<SecretKey> load = () -> VaultKey.loadOrCreate(file);
            List<Future<SecretKey>> keys = pool.invokeAll(List.of(load, load, load, load));
            byte[] first = keys.getFirst().get().getEncoded();
            for (Future<SecretKey> key : keys) assertArrayEquals(first, key.get().getEncoded());
        } finally {
            pool.shutdownNow();
        }
        assertArrayEquals(VaultKey.loadOrCreate(file).getEncoded(), VaultKey.loadOrCreate(file).getEncoded());
        assertTrue(Files.readString(file).startsWith(VaultKey.Dpapi.available() ? "dpapi:" : "plain:"));
    }
}

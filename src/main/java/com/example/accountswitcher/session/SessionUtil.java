/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.User
 */
package com.example.accountswitcher.session;

import com.example.accountswitcher.auth.Account;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

public final class SessionUtil {
    private SessionUtil() {
    }

    public static void apply(Account account) {
        User user;
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = account.uuid != null ? account.uuid : SessionUtil.offlineUuid(account.username);
        mc.user = user = new User(account.username, uuid, account.accessToken, Optional.ofNullable(account.xuid), Optional.empty());
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}


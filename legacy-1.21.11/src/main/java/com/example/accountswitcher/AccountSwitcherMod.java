/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.fabricmc.api.ClientModInitializer
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.example.accountswitcher;

import com.example.accountswitcher.auth.AccountStorage;
import com.example.accountswitcher.auth.Config;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AccountSwitcherMod
implements ClientModInitializer {
    public static final String MOD_ID = "accountswitcher";
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"accountswitcher");
    private static AccountStorage storage;

    public void onInitializeClient() {
        Config.load();
        storage = new AccountStorage();
        storage.load();
        LOGGER.info("Keychain loaded ({} accounts, azure-id={})", (Object)storage.getAccounts().size(), (Object)(Config.hasUserOverride() ? "custom" : "default"));
    }

    public static AccountStorage storage() {
        return storage;
    }
}


/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.fabricmc.loader.api.FabricLoader
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.example.accountswitcher.auth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Properties;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Config {
    private static final Logger LOG = LoggerFactory.getLogger((String)"accountswitcher.config");
    private static final String DEFAULT_CLIENT_ID = "e16699bb-2aa8-46da-b5e3-45cbcce29091";
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("account-switcher.properties");
    private static final Pattern UUID_FMT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Properties PROPS = new Properties();

    private Config() {
    }

    public static synchronized void load() {
        PROPS.clear();
        if (!Files.exists(FILE, new LinkOption[0])) {
            Config.save();
        } else {
            try (BufferedReader in = Files.newBufferedReader(FILE);){
                PROPS.load(in);
            }
            catch (IOException var5) {
                LOG.warn("Could not read {}", (Object)FILE, (Object)var5);
            }
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent(), new FileAttribute[0]);
            try (BufferedWriter out = Files.newBufferedWriter(FILE, new OpenOption[0]);){
                PROPS.store(out, "Account Switcher config. Leave azure_client_id blank to use the bundled default. Set it explicitly only if Microsoft revokes the default.");
            }
        }
        catch (IOException var5) {
            LOG.warn("Could not write {}", (Object)FILE, (Object)var5);
        }
    }

    public static synchronized String getAzureClientId() {
        String configured = PROPS.getProperty("azure_client_id", "").trim();
        return configured.isEmpty() ? DEFAULT_CLIENT_ID : configured;
    }

    public static synchronized boolean hasUserOverride() {
        return !PROPS.getProperty("azure_client_id", "").trim().isEmpty();
    }

    public static synchronized void setAzureClientId(String id) {
        PROPS.setProperty("azure_client_id", id == null ? "" : id.trim());
        Config.save();
    }

    public static boolean isValidClientIdFormat(String id) {
        return id != null && UUID_FMT.matcher(id.trim()).matches();
    }
}


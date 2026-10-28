package io.github.domsimus.keychain.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** User preferences, stored as plain JSON in {@code config/keychain/settings.json}. */
public final class Settings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Keychain");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Keychain's own Azure app registration (personal Microsoft accounts, public client). */
    public static final String DEFAULT_CLIENT_ID = "e16699bb-2aa8-46da-b5e3-45cbcce29091";

    // Field names are the JSON keys; keep them stable.
    public boolean titleScreenButton = true;
    public boolean pauseMenuButton = true;
    public boolean multiplayerButton = true;
    public boolean restoreLastAccount = false;
    public boolean sharedVault = false;
    public String customClientId = "";

    private transient Path file;

    public static Settings load(Path file) {
        Settings settings = null;
        if (Files.exists(file)) {
            try {
                settings = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
            } catch (IOException | JsonParseException failure) {
                LOGGER.warn("Keychain could not read {}; using defaults", file, failure);
            }
        }
        if (settings == null) settings = new Settings();
        if (settings.customClientId == null) settings.customClientId = "";
        settings.file = file;
        return settings;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOGGER.warn("Keychain could not save {}", file, failure);
        }
    }

    public String clientId() {
        String custom = customClientId.strip();
        return custom.isEmpty() ? DEFAULT_CLIENT_ID : custom;
    }

    public boolean usesCustomClientId() {
        return !customClientId.isBlank();
    }

    public static boolean isValidClientId(String id) {
        return id != null && CLIENT_ID.matcher(id.strip()).matches();
    }
}

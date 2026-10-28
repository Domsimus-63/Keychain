package io.github.domsimus.keychain.session;

import com.google.common.collect.HashMultimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.core.account.Account;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Skins for the account list. Microsoft accounts load their signed texture from Mojang in the
 * background (Minecraft's skin manager then downloads and caches the image); until then, and for
 * offline accounts, the vanilla default skin for the UUID is shown.
 */
public final class SkinCache {
    private static final long RETRY_FAILED_MS = 60_000;
    private static final long REFRESH_MS = 30 * 60_000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    public enum State { DEFAULT, LOADING, LOADED, UNAVAILABLE }

    private record Entry(CompletableFuture<Optional<PlayerSkin>> skin, long startedAt) {}

    private SkinCache() {}

    public static PlayerSkin get(Account account) {
        PlayerSkin fallback = DefaultPlayerSkin.get(account.id());
        if (!account.isMicrosoft()) return fallback;
        return entry(account.id()).skin().getNow(Optional.empty()).orElse(fallback);
    }

    /** The skin for any profile UUID, such as the launcher's account when it is not saved in Keychain. */
    public static PlayerSkin forProfile(UUID id) {
        return entry(id).skin().getNow(Optional.empty()).orElse(DefaultPlayerSkin.get(id));
    }

    public static State state(Account account) {
        if (!account.isMicrosoft()) return State.DEFAULT;
        CompletableFuture<Optional<PlayerSkin>> skin = entry(account.id()).skin();
        if (!skin.isDone()) return State.LOADING;
        return skin.getNow(Optional.empty()).isPresent() ? State.LOADED : State.UNAVAILABLE;
    }

    /** Drops the cached skin so the next lookup fetches it again (after signing in or switching). */
    public static void forget(UUID id) {
        ENTRIES.remove(id);
    }

    private static Entry entry(UUID id) {
        long now = System.currentTimeMillis();
        return ENTRIES.compute(id, (key, previous) -> {
            if (previous == null) return new Entry(load(key), now);
            boolean failed = previous.skin().isDone() && previous.skin().getNow(Optional.empty()).isEmpty();
            return now - previous.startedAt() >= (failed ? RETRY_FAILED_MS : REFRESH_MS) ? new Entry(load(key), now) : previous;
        });
    }

    private static CompletableFuture<Optional<PlayerSkin>> load(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        return CompletableFuture.supplyAsync(() -> {
                try {
                    return signedProfile(id);
                } catch (IOException | InterruptedException failure) {
                    throw new RuntimeException(failure);
                }
            }, Util.nonCriticalIoPool())
            .thenCompose(profile -> minecraft.getSkinManager().get(profile))
            .exceptionally(failure -> {
                Keychain.LOGGER.debug("Keychain could not load the skin for {}", id, failure);
                return Optional.empty();
            });
    }

    private static GameProfile signedProfile(UUID id) throws IOException, InterruptedException {
        String undashed = id.toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + undashed + "?unsigned=false"))
            .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Mojang profile request returned HTTP " + response.statusCode());
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!undashed.equalsIgnoreCase(json.get("id").getAsString())) throw new IOException("Mojang returned a different profile");
            for (JsonElement element : json.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString()) || !property.has("signature")) continue;
                HashMultimap<String, Property> properties = HashMultimap.create();
                properties.put("textures", new Property("textures", property.get("value").getAsString(), property.get("signature").getAsString()));
                return new GameProfile(id, json.get("name").getAsString(), new PropertyMap(properties));
            }
        } catch (RuntimeException malformed) {
            throw new IOException("Mojang returned an invalid profile", malformed);
        }
        throw new IOException("Mojang returned no signed skin");
    }
}

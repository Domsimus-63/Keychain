package com.example.accountswitcher.gui;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.google.common.collect.HashMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
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

/** Resolves signed Mojang skin profiles and lets Minecraft download the texture. */
final class SkinPreviewCache {
    private static final long RETRY_AFTER_MS = 60_000;
    private static final long REFRESH_AFTER_MS = 30 * 60_000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<UUID, Request> SKINS = new ConcurrentHashMap<>();

    private SkinPreviewCache() {}

    static PlayerSkin get(Account account) {
        if (account.uuid == null) return DefaultPlayerSkin.getDefaultSkin();
        PlayerSkin fallback = DefaultPlayerSkin.get(account.uuid);
        if (account.type != Account.Type.MICROSOFT) return fallback;
        return request(account.uuid).future().getNow(Optional.empty()).orElse(fallback);
    }

    static String status(Account account) {
        if (account == null) return "Select an account";
        if (account.type != Account.Type.MICROSOFT) return "Offline skin";
        if (account.uuid == null) return "Skin unavailable";
        Request request = request(account.uuid);
        if (!request.future().isDone()) return "Loading skin...";
        return request.future().getNow(Optional.empty()).isPresent()
            ? "Drag to rotate" : "Skin unavailable";
    }

    private static Request request(UUID uuid) {
        long now = System.currentTimeMillis();
        return SKINS.compute(uuid, (id, previous) -> {
            if (previous == null) return new Request(load(id), now);
            long maxAge = previous.future().isDone()
                && previous.future().getNow(Optional.empty()).isEmpty()
                    ? RETRY_AFTER_MS : REFRESH_AFTER_MS;
            return now - previous.startedAt() >= maxAge
                ? new Request(load(id), now) : previous;
        });
    }

    private static CompletableFuture<Optional<PlayerSkin>> load(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        return CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchSignedProfile(uuid);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }, Util.nonCriticalIoPool())
            .thenCompose(profile -> minecraft.getSkinManager().get(profile))
            .exceptionally(error -> {
                AccountSwitcherMod.LOGGER.warn("Could not load skin for {}", uuid, error);
                return Optional.empty();
            });
    }

    private static GameProfile fetchSignedProfile(UUID uuid) throws IOException, InterruptedException {
        String id = uuid.toString().replace("-", "");
        URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
            + id + "?unsigned=false");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Mojang profile request returned HTTP " + response.statusCode());
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!id.equalsIgnoreCase(json.get("id").getAsString())) {
                throw new IOException("Mojang returned a different profile ID");
            }
            String name = json.get("name").getAsString();
            JsonArray properties = json.getAsJsonArray("properties");
            if (properties == null) throw new IOException("Mojang returned no skin properties");
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString())) continue;
                String value = property.get("value").getAsString();
                String signature = property.get("signature").getAsString();
                if (value.isBlank() || signature.isBlank()) break;
                var values = HashMultimap.<String, Property>create();
                values.put("textures", new Property("textures", value, signature));
                return new GameProfile(uuid, name, new PropertyMap(values));
            }
            throw new IOException("Mojang returned no signed skin texture");
        } catch (RuntimeException malformed) {
            throw new IOException("Mojang returned an invalid skin profile", malformed);
        }
    }

    private record Request(CompletableFuture<Optional<PlayerSkin>> future, long startedAt) {}
}

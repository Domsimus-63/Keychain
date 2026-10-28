package io.github.domsimus.keychain.core.account;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Tokens for a Microsoft account.
 *
 * @param accessToken Minecraft services token used to join servers
 * @param refreshToken Microsoft OAuth refresh token, or {@code null} once it has been rejected
 * @param xuid Xbox user hash, passed to Minecraft for telemetry and Realms
 * @param expiresAt when {@code accessToken} stops working
 * @param clientId the Azure app the refresh token belongs to, or {@code null} for Keychain's default.
 *     Refresh tokens only work with the app that issued them, so this must survive settings changes.
 * @param obtainedAt when these tokens were issued, or {@code null} if unknown (imported accounts)
 */
public record MicrosoftSession(String accessToken, String refreshToken, String xuid, Instant expiresAt, String clientId, Instant obtainedAt) {
    public MicrosoftSession {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public MicrosoftSession(String accessToken, String refreshToken, String xuid, Instant expiresAt) {
        this(accessToken, refreshToken, xuid, expiresAt, null, null);
    }

    public boolean hasValidAccessToken(Instant at) {
        return !accessToken.isBlank() && at.isBefore(expiresAt);
    }

    public boolean canRefresh() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /** True when the refresh token has not been renewed for {@code age}: Microsoft drops unused ones after ~90 days. */
    public boolean isOlderThan(Duration age, Instant now) {
        return obtainedAt == null || obtainedAt.plus(age).isBefore(now);
    }

    /** A copy that can no longer refresh, kept so the account stays listed until signed in again. */
    public MicrosoftSession withoutRefreshToken() {
        return new MicrosoftSession(accessToken, null, xuid, expiresAt, clientId, obtainedAt);
    }

    /** A copy for tokens Minecraft services rejected outright: nothing left to use or refresh. */
    public MicrosoftSession revoked() {
        return new MicrosoftSession(accessToken, null, xuid, Instant.EPOCH, clientId, obtainedAt);
    }

    @Override
    public String toString() {
        return "MicrosoftSession[expiresAt=" + expiresAt + ", refreshable=" + canRefresh() + "]";
    }
}

package io.github.domsimus.keychain.core.account;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One saved account. Immutable: every change produces a new instance, so a snapshot handed to the
 * UI can never be modified underneath it by a background refresh.
 *
 * @param session Microsoft tokens; always {@code null} for offline accounts
 * @param lastUsed when this account was last switched to, or {@code null} if never
 */
public record Account(UUID id, String name, Kind kind, MicrosoftSession session, boolean favorite, Instant lastUsed) {
    private static final Pattern OFFLINE_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Refresh slightly before Minecraft's token really expires so a join never races the expiry. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);

    public enum Kind { MICROSOFT, OFFLINE }

    /** What the account needs before it can be used. */
    public enum Status {
        /** Usable right now. */
        READY,
        /** Minecraft token expired; it will be refreshed automatically when used. */
        NEEDS_REFRESH,
        /** No usable refresh token: the user has to sign in with Microsoft again. */
        NEEDS_SIGN_IN
    }

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (name.isBlank()) throw new IllegalArgumentException("Account name is blank");
        if (kind == Kind.OFFLINE) session = null;
    }

    public static Account offline(String name) {
        return new Account(offlineUuid(name), name, Kind.OFFLINE, null, false, null);
    }

    public static Account microsoft(UUID id, String name, MicrosoftSession session) {
        return new Account(id, name, Kind.MICROSOFT, Objects.requireNonNull(session, "session"), false, null);
    }

    /** Same UUID the vanilla server derives for an offline-mode player of this name. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isValidOfflineName(String name) {
        return name != null && OFFLINE_NAME.matcher(name).matches();
    }

    public boolean isMicrosoft() {
        return kind == Kind.MICROSOFT;
    }

    public Status status(Instant now) {
        if (!isMicrosoft()) return Status.READY;
        if (session == null) return Status.NEEDS_SIGN_IN;
        if (session.hasValidAccessToken(now.plus(EXPIRY_MARGIN))) return Status.READY;
        return session.canRefresh() ? Status.NEEDS_REFRESH : Status.NEEDS_SIGN_IN;
    }

    /** The access token Minecraft should use. Offline sessions use the vanilla placeholder. */
    public String accessToken() {
        return session == null ? "0" : session.accessToken();
    }

    public Account withFavorite(boolean value) {
        return new Account(id, name, kind, session, value, lastUsed);
    }

    public Account withLastUsed(Instant value) {
        return new Account(id, name, kind, session, favorite, value);
    }

    public Account withSession(MicrosoftSession value) {
        return new Account(id, name, kind, value, favorite, lastUsed);
    }

    /** Copies the user's own choices (favorite, last use) onto a freshly signed-in copy. */
    public Account keepingPreferencesOf(Account previous) {
        Instant used = lastUsed != null ? lastUsed : previous.lastUsed;
        return new Account(id, name, kind, session, previous.favorite, used);
    }
}

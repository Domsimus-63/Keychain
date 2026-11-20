package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import io.github.domsimus.keychain.core.auth.AuthException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Translated wording shared by several screens. */
public final class Messages {
    /** Microsoft drops a refresh token that has not been used for about 90 days. */
    private static final Duration LIKELY_EXPIRED = Duration.ofDays(90);

    private Messages() {}

    /** What went wrong and what to do about it, for a sign-in failure. */
    public static Component reason(AuthException.Reason reason) {
        return Component.translatable("keychain.error." + reason.name().toLowerCase(Locale.ROOT));
    }

    public static Component kind(Account account) {
        return Component.translatable(account.isMicrosoft() ? "keychain.kind.microsoft" : "keychain.kind.offline");
    }

    public static Component status(Account.Status status) {
        return Component.translatable("keychain.status." + status.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Why the account needs attention, or {@code null} if it does not: it is signed out, or its
     * sign-in was last renewed so long ago that Microsoft has most likely dropped it.
     */
    public static Component warning(Account account, Instant now) {
        Account.Status status = account.status(now);
        if (status == Account.Status.NEEDS_SIGN_IN) return Component.translatable("keychain.warning.signed_out");
        MicrosoftSession session = account.session();
        if (session != null && session.obtainedAt() != null && session.isOlderThan(LIKELY_EXPIRED, now)) {
            return Component.translatable("keychain.warning.stale");
        }
        return null;
    }

    public static int statusColor(Account.Status status) {
        return switch (status) {
            case READY -> Palette.GOOD;
            case NEEDS_REFRESH -> Palette.MUTED;
            case NEEDS_SIGN_IN -> Palette.WARN;
        };
    }

    /** "13 h left", "25 min left" until {@code when}. */
    public static Component remaining(Instant when) {
        long minutes = Math.max(0, Duration.between(Instant.now(), when).toMinutes());
        return minutes >= 60 ? Component.translatable("keychain.time.hours_left", minutes / 60)
            : Component.translatable("keychain.time.minutes_left", minutes);
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago", or "never". */
    public static Component lastUsed(Instant when) {
        if (when == null) return Component.translatable("keychain.time.never");
        long seconds = Math.max(0, Duration.between(when, Instant.now()).getSeconds());
        if (seconds < 60) return Component.translatable("keychain.time.now");
        if (seconds < 3600) return Component.translatable("keychain.time.minutes", seconds / 60);
        if (seconds < 86_400) return Component.translatable("keychain.time.hours", seconds / 3600);
        long days = seconds / 86_400;
        if (days < 60) return Component.translatable("keychain.time.days", days);
        return Component.translatable("keychain.time.months", days / 30);
    }
}

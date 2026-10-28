package io.github.domsimus.keychain.core.auth;

/**
 * A sign-in failure. {@link #reason()} is stable and maps to a translated, actionable message in
 * the UI; the exception message carries technical detail for logs and "copy details".
 */
public final class AuthException extends Exception {
    public enum Reason {
        CANCELLED,
        TIMED_OUT,
        NETWORK,
        /** The Azure app ID is unknown or not set up for personal accounts. */
        CLIENT_ID_REJECTED,
        /** The Azure app does not allow the localhost redirect or public client flows. */
        CLIENT_MISCONFIGURED,
        /** Minecraft services refuse this Azure app: custom apps must be approved by Mojang first. */
        APP_NOT_APPROVED,
        /** The user closed or declined the Microsoft consent page. */
        DECLINED,
        /** The saved refresh token was revoked or expired: sign in again. */
        SESSION_REVOKED,
        NO_XBOX_PROFILE,
        XBOX_REGION_BLOCKED,
        XBOX_AGE_VERIFICATION,
        XBOX_CHILD_ACCOUNT,
        XBOX_BANNED,
        NO_MINECRAFT,
        RATE_LIMITED,
        SERVICE_ERROR
    }

    private final Reason reason;

    public AuthException(Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public AuthException(Reason reason, String detail, Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** True when trying again later (rather than changing something) is the right fix. */
    public boolean isTransient() {
        return reason == Reason.NETWORK || reason == Reason.TIMED_OUT || reason == Reason.RATE_LIMITED || reason == Reason.SERVICE_ERROR;
    }
}

/*
 * Decompiled with CFR 0.152.
 */
package com.example.accountswitcher.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Account {
    public Type type = Type.OFFLINE;
    public String username = "";
    public UUID uuid;
    public String accessToken = "";
    public String refreshToken;
    public String xuid;
    public Instant expiresAt;
    public Instant lastUsedAt;
    public boolean favorite;

    public boolean isExpired() {
        return this.type == Type.MICROSOFT && (this.accessToken == null
            || this.accessToken.isBlank() || this.expiresAt == null
            || !Instant.now().isBefore(this.expiresAt.minusSeconds(60L)));
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public boolean equals(Object o) {
        if (!(o instanceof Account)) return false;
        Account a = (Account)o;
        if (!Objects.equals(this.uuid, a.uuid)) return false;
        if (!Objects.equals(this.username, a.username)) return false;
        return true;
    }

    public int hashCode() {
        return Objects.hash(this.uuid, this.username);
    }

    public static enum Type {
        MICROSOFT,
        OFFLINE;

    }
}


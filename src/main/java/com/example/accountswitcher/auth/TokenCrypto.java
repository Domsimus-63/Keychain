/*
 * Decompiled with CFR 0.152.
 */
package com.example.accountswitcher.auth;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class TokenCrypto {
    private static final byte[] PEPPER = "keychain-mod-aes-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private TokenCrypto() {
    }

    private static String hostId() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isEmpty()) {
            h = System.getenv("HOSTNAME");
        }
        if (h == null || h.isEmpty()) {
            h = "default-host";
        }
        return h;
    }

    private static String legacyHostId() {
        String hostName = "unknown-host";
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        }
        catch (Exception exception) {
            // empty catch block
        }
        return hostName;
    }

    private static SecretKey deriveKey(String hostName) throws Exception {
        String userName = System.getProperty("user.name", "");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(PEPPER);
        baos.write(userName.getBytes(StandardCharsets.UTF_8));
        baos.write(0);
        baos.write(hostName.getBytes(StandardCharsets.UTF_8));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(baos.toByteArray());
        return new SecretKeySpec(hash, "AES");
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            SecretKey key = TokenCrypto.deriveKey(TokenCrypto.hostId());
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(1, (Key)key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[12 + ct.length];
            System.arraycopy(iv, 0, combined, 0, 12);
            System.arraycopy(ct, 0, combined, 12, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        }
        catch (Exception var6) {
            throw new RuntimeException("Encryption failed", var6);
        }
    }

    public static String decrypt(String b64) {
        if (b64 == null) {
            return null;
        }
        String r = TokenCrypto.tryDecrypt(b64, TokenCrypto.hostId());
        return r != null ? r : TokenCrypto.tryDecrypt(b64, TokenCrypto.legacyHostId());
    }

    private static String tryDecrypt(String b64, String hostName) {
        try {
            byte[] combined = Base64.getDecoder().decode(b64);
            if (combined.length < 28) {
                return null;
            }
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);
            byte[] ct = new byte[combined.length - 12];
            System.arraycopy(combined, 12, ct, 0, ct.length);
            SecretKey key = TokenCrypto.deriveKey(hostName);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(2, (Key)key, new GCMParameterSpec(128, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        }
        catch (Exception var8) {
            return null;
        }
    }
}


/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  com.google.gson.JsonSyntaxException
 *  net.minecraft.util.Util
 */
package com.example.accountswitcher.auth;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.auth.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.util.Util;

public final class MicrosoftAuth {
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String AUTHZ_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final SecureRandom RNG = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private MicrosoftAuth() {
    }

    private static String clientId() {
        return Config.getAzureClientId();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static Account loginWithBrowser(Consumer<String> urlConsumer, Consumer<String> progress, BooleanSupplier cancelled) throws Exception {
        Account var18;
        progress.accept("Starting local sign-in server\u2026");
        String verifier = MicrosoftAuth.randomUrlSafe(64);
        String challenge = MicrosoftAuth.pkceChallenge(verifier);
        String state = MicrosoftAuth.randomUrlSafe(24);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        String redirectUri = "http://localhost:" + port + "/callback";
        CompletableFuture codeFuture = new CompletableFuture();
        server.createContext("/callback", exchange -> MicrosoftAuth.handleCallback(exchange, state, codeFuture));
        server.createContext("/", exchange -> MicrosoftAuth.sendHtml(exchange, 404, "Not the right page. Return to Minecraft.", false));
        server.setExecutor((Executor)Util.nonCriticalIoPool());
        server.start();
        try {
            String authUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=" + MicrosoftAuth.url(MicrosoftAuth.clientId()) + "&response_type=code&redirect_uri=" + MicrosoftAuth.url(redirectUri) + "&scope=" + MicrosoftAuth.url(SCOPE) + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=" + state + "&prompt=select_account";
            urlConsumer.accept(authUrl);
            progress.accept("Opening Microsoft sign-in in your browser\u2026");
            boolean browserRequested = true;
            try {
                BrowserLauncher.open(URI.create(authUrl));
            }
            catch (Exception error) {
                browserRequested = false;
                AccountSwitcherMod.LOGGER.warn("Could not open the sign-in browser", error);
            }
            progress.accept(browserRequested ? "Waiting for you to sign in\u2026"
                : "Browser did not open. Use Open Browser or Copy Link.");
            String code = (String)MicrosoftAuth.awaitWithCancel(codeFuture, Duration.ofMinutes(5L), cancelled);
            progress.accept("Exchanging sign-in for token\u2026");
            String body = "client_id=" + MicrosoftAuth.url(MicrosoftAuth.clientId()) + "&grant_type=authorization_code&code=" + MicrosoftAuth.url(code) + "&redirect_uri=" + MicrosoftAuth.url(redirectUri) + "&code_verifier=" + verifier;
            HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject tokens = MicrosoftAuth.parseJsonOrThrow(resp, "token exchange");
            if (!tokens.has("access_token")) {
                throw new IOException(MicrosoftAuth.prettyAzureError("exchanging sign-in code", tokens));
            }
            String msAccess = tokens.get("access_token").getAsString();
            String msRefresh = tokens.has("refresh_token") ? tokens.get("refresh_token").getAsString() : null;
            var18 = MicrosoftAuth.finishWithMicrosoftToken(msAccess, msRefresh, progress);
        }
        finally {
            server.stop(0);
        }
        return var18;
    }

    public static Account refresh(Account account, Consumer<String> progress) throws Exception {
        if (account.refreshToken == null) {
            throw new IllegalStateException("No refresh token; sign in again.");
        }
        progress.accept("Refreshing Microsoft token\u2026");
        String body = "client_id=" + MicrosoftAuth.url(MicrosoftAuth.clientId()) + "&grant_type=refresh_token&refresh_token=" + MicrosoftAuth.url(account.refreshToken) + "&scope=" + MicrosoftAuth.url(SCOPE);
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = MicrosoftAuth.parseJsonOrThrow(resp, "token refresh");
        if (!json.has("access_token")) {
            throw new IOException(MicrosoftAuth.prettyAzureError("refreshing token", json));
        }
        String newRefresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : account.refreshToken;
        return MicrosoftAuth.finishWithMicrosoftToken(json.get("access_token").getAsString(), newRefresh, progress);
    }

    private static Account finishWithMicrosoftToken(String msAccess, String msRefresh, Consumer<String> progress) throws Exception {
        JsonObject xbl;
        progress.accept("Authenticating with Xbox Live\u2026");
        try {
            xbl = MicrosoftAuth.postJsonOrThrow("https://user.auth.xboxlive.com/user/authenticate", "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + msAccess + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}", "Xbox Live");
        }
        catch (IOException var14) {
            throw new IOException("Xbox Live sign-in failed: " + var14.getMessage(), var14);
        }
        if (xbl.has("Token") && xbl.has("DisplayClaims")) {
            String xblToken = xbl.get("Token").getAsString();
            String userHash = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
            progress.accept("Getting XSTS token\u2026");
            JsonObject xsts = MicrosoftAuth.postJsonOrThrow("https://xsts.auth.xboxlive.com/xsts/authorize", "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xblToken + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}", "XSTS");
            if (xsts.has("XErr")) {
                long err = xsts.get("XErr").getAsLong();
                throw new IOException("Xbox sign-in error " + err + " \u2014 " + MicrosoftAuth.xstsHelp(err));
            }
            if (!xsts.has("Token")) {
                throw new IOException("XSTS returned no token. Response: " + MicrosoftAuth.truncate(xsts.toString(), 300));
            }
            String xstsToken = xsts.get("Token").getAsString();
            progress.accept("Authenticating with Minecraft services\u2026");
            JsonObject mc = MicrosoftAuth.postJsonOrThrow("https://api.minecraftservices.com/authentication/login_with_xbox", "{\"identityToken\":\"XBL3.0 x=" + userHash + ";" + xstsToken + "\"}", "Minecraft Services");
            if (!mc.has("access_token")) {
                throw new IOException("Minecraft Services returned no token. Response: " + MicrosoftAuth.truncate(mc.toString(), 300));
            }
            String mcToken = mc.get("access_token").getAsString();
            progress.accept("Fetching profile\u2026");
            HttpRequest profileReq = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile")).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + mcToken).GET().build();
            HttpResponse<String> profileResp = HTTP.send(profileReq, HttpResponse.BodyHandlers.ofString());
            JsonObject profile = MicrosoftAuth.parseJsonOrThrow(profileResp, "profile");
            if (!profile.has("id")) {
                String err = profile.has("errorMessage") ? profile.get("errorMessage").getAsString() : "This Microsoft account doesn't own Minecraft.";
                throw new IOException(err);
            }
            Account account = new Account();
            account.type = Account.Type.MICROSOFT;
            account.username = profile.get("name").getAsString();
            account.uuid = MicrosoftAuth.parseDashlessUuid(profile.get("id").getAsString());
            account.accessToken = mcToken;
            account.refreshToken = msRefresh;
            account.xuid = userHash;
            long lifetime = mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86400L;
            account.expiresAt = Instant.now().plusSeconds(Math.max(60L, lifetime));
            return account;
        }
        throw new IOException("Xbox Live returned no token. Response: " + MicrosoftAuth.truncate(xbl.toString(), 300));
    }

    private static void handleCallback(HttpExchange exchange, String expectedState, CompletableFuture<String> codeFuture) {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                MicrosoftAuth.sendHtml(exchange, 405, "Use the sign-in link from Minecraft.", false);
                return;
            }
            Map<String, String> q = MicrosoftAuth.parseQuery(exchange.getRequestURI().getRawQuery());
            if (!expectedState.equals(q.get("state"))) {
                MicrosoftAuth.sendHtml(exchange, 400, "State mismatch — please try again from Minecraft.", false);
                return;
            }
            if (q.containsKey("error")) {
                String desc = q.getOrDefault("error_description", q.get("error"));
                MicrosoftAuth.sendHtml(exchange, 400, "Microsoft reported: " + desc, false);
                codeFuture.completeExceptionally(new IOException("Microsoft error: " + desc));
                return;
            }
            String code = q.get("code");
            if (code == null || code.isBlank()) {
                MicrosoftAuth.sendHtml(exchange, 400, "No authorization code in callback.", false);
                return;
            }
            codeFuture.complete(code);
            MicrosoftAuth.sendHtml(exchange, 200, "Sign-in complete. You can close this tab and return to Minecraft.", true);
        }
        catch (Throwable var6) {
            try {
                MicrosoftAuth.sendHtml(exchange, 400, "Invalid sign-in callback.", false);
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String message, boolean ok) throws IOException {
        String accent = ok ? "#5BACFF" : "#E7654A";
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Keychain</title><style>html,body{margin:0;height:100%;}body{background:#0e1218;color:#e6e9ef;font:16px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;}.card{background:#161a24;border-top:2px solid " + accent + ";border-bottom:2px solid " + accent + ";padding:32px 40px;border-radius:6px;max-width:560px;text-align:center;}h1{margin:0 0 12px;color:" + accent + ";font-size:22px;font-weight:600;}p{margin:0;color:#b9c0cb;line-height:1.5;}</style></head><body><div class=\"card\"><h1>" + (ok ? "Sign-in complete" : "Sign-in failed") + "</h1><p>" + MicrosoftAuth.escape(message) + "</p></div></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody();){
            out.write(bytes);
        }
    }

    private static JsonObject postJsonOrThrow(String url, String body, String stepName) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return MicrosoftAuth.parseJsonOrThrow(HTTP.send(req, HttpResponse.BodyHandlers.ofString()), stepName);
    }

    private static JsonObject parseJsonOrThrow(HttpResponse<String> resp, String stepName) throws IOException {
        String b = resp.body() == null ? "" : resp.body();
        try {
            return JsonParser.parseString((String)b).getAsJsonObject();
        }
        catch (JsonSyntaxException var4) {
            AccountSwitcherMod.LOGGER.error("Non-JSON response from {} (HTTP {})", (Object)stepName, (Object)resp.statusCode());
            throw new IOException(stepName + " returned a non-JSON response (HTTP " + resp.statusCode() + ").");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String pkceChallenge(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static <T> T awaitWithCancel(CompletableFuture<T> f, Duration timeout, BooleanSupplier cancelled) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!cancelled.getAsBoolean()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0L) {
                throw new IOException("Sign-in timed out.");
            }
            try {
                return f.get(Math.min(500L, left), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException timeoutException) {
            }
        }
        f.cancel(true);
        throw new IOException("Sign-in cancelled.");
    }

    private static Map<String, String> parseQuery(String raw) {
        HashMap<String, String> out = new HashMap<String, String>();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf(61);
                if (eq < 0) continue;
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            return out;
        }
        return out;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static UUID parseDashlessUuid(String id) {
        return UUID.fromString(id.replaceFirst("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]+)", "$1-$2-$3-$4-$5"));
    }

    private static String prettyAzureError(String when, JsonObject json) {
        String desc;
        String code = json.has("error") ? json.get("error").getAsString() : "unknown";
        String string = desc = json.has("error_description") ? json.get("error_description").getAsString() : json.toString();
        if (code.equals("unauthorized_client") || desc.contains("AADSTS700016")) {
            return "Azure says the client ID is not registered. Open Account Switcher \u2192 Add Online \u2192 Use custom Azure ID to set a custom one.";
        }
        if (desc.contains("AADSTS9002313")) {
            return "Azure says the client ID is malformed. Re-paste it.";
        }
        if (!desc.contains("AADSTS50011") && !desc.contains("redirect_uri")) {
            int dot = desc.indexOf(46);
            if (dot > 0 && dot < 200) {
                desc = desc.substring(0, dot);
            }
            return "Microsoft error while " + when + ": " + MicrosoftAuth.truncate(desc, 240);
        }
        return "Azure rejected the redirect URL. The default client may have been revoked. Try Use custom Azure ID with your own.";
    }

    private static String xstsHelp(long code) {
        if (code == 2148916233L) {
            return "no Xbox account on this Microsoft account; sign up at xbox.com first";
        }
        if (code == 2148916235L) {
            return "Xbox Live not available in this country/region";
        }
        if (code == 2148916236L || code == 2148916237L) {
            return "adult verification required at xbox.com";
        }
        return code == 2148916238L ? "child account; needs to be added to a Family group on xbox.com" : "see Microsoft documentation";
    }
}

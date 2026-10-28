package io.github.domsimus.keychain.core.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Microsoft account sign-in for Minecraft, the same way the official launcher does it: Microsoft
 * OAuth in the browser (with PKCE and a local redirect page), then Xbox Live, XSTS, Minecraft services and the Minecraft profile.
 *
 * <p>All methods return immediately; the work runs on the given executor and completes the future
 * with an {@link Account} or an {@link AuthException} (wrapped in a {@link CompletionException}).
 */
public final class MicrosoftAuth {
    static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String SCOPE = "XboxLive.signin offline_access";
    private static final String XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final Duration BROWSER_TIMEOUT = Duration.ofMinutes(10);

    /** Progress shown to the user, in order. */
    public enum Step { WAITING_FOR_USER, MICROSOFT, XBOX_LIVE, MINECRAFT, PROFILE }

    public interface Listener {
        void step(Step step);

        /** Browser flow: the page to open. Called once, before {@link Step#WAITING_FOR_USER}. */
        default void browserUrl(String url) {}
    }

    private final Supplier<String> clientId;
    private final Executor executor;
    private final Http http;

    public MicrosoftAuth(Supplier<String> clientId, Executor executor) {
        this(clientId, executor, Http.create());
    }

    MicrosoftAuth(Supplier<String> clientId, Executor executor, Http http) {
        this.clientId = clientId;
        this.executor = executor;
        this.http = http;
    }

    public CompletableFuture<Account> signInWithBrowser(Listener listener, Cancellation cancellation) {
        return run(() -> {
            String client = clientId.get();
            try (LoopbackServer server = LoopbackServer.start()) {
                cancellation.onCancel(server::close);
                Pkce pkce = Pkce.create();
                String url = AUTHORIZE_URL + "?client_id=" + Http.encode(client)
                    + "&response_type=code&response_mode=query"
                    + "&redirect_uri=" + Http.encode(server.redirectUri())
                    + "&scope=" + Http.encode(SCOPE)
                    + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256"
                    + "&state=" + server.state() + "&prompt=select_account";
                listener.browserUrl(url);
                listener.step(Step.WAITING_FOR_USER);
                String code = server.awaitCode(BROWSER_TIMEOUT, cancellation);
                listener.step(Step.MICROSOFT);
                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", client);
                form.put("grant_type", "authorization_code");
                form.put("code", code);
                form.put("redirect_uri", server.redirectUri());
                form.put("code_verifier", pkce.verifier());
                form.put("scope", SCOPE);
                return finish(microsoftToken(form, cancellation), client, listener, cancellation);
            }
        });
    }

    /** Exchanges the saved refresh token for a fresh Minecraft token. Keeps the user's preferences. */
    public CompletableFuture<Account> refresh(Account account, Cancellation cancellation) {
        return run(() -> {
            MicrosoftSession session = account.session();
            if (session == null || !session.canRefresh()) {
                throw new AuthException(AuthException.Reason.SESSION_REVOKED, "No refresh token saved for " + account.name());
            }
            Map<String, String> form = new LinkedHashMap<>();
            // A refresh token only works with the app that issued it, even if the setting changed since.
            String client = session.clientId() != null ? session.clientId() : clientId.get();
            form.put("client_id", client);
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", session.refreshToken());
            form.put("scope", SCOPE);
            MsToken token = microsoftToken(form, cancellation);
            if (token.refreshToken() == null) token = new MsToken(token.accessToken(), session.refreshToken());
            return finish(token, client, step -> {}, cancellation).keepingPreferencesOf(account);
        });
    }

    /**
     * Makes sure {@code account} can join servers right now: checks a still-valid token against
     * Minecraft services (which also picks up a changed username) and refreshes it if Microsoft no
     * longer accepts it or it expired. If the check itself cannot reach the services, the saved
     * token is used as is, so an outage never blocks switching.
     */
    public CompletableFuture<Account> prepare(Account account, Cancellation cancellation) {
        if (account.status(Instant.now()) != Account.Status.READY) return refresh(account, cancellation);
        return run(() -> {
            Http.Response profile;
            try {
                profile = http.get(PROFILE_URL, account.accessToken(), cancellation, "Minecraft profile");
            } catch (AuthException failure) {
                if (failure.reason() == AuthException.Reason.CANCELLED) throw failure;
                return account;
            }
            if (profile.status() == 401 || profile.status() == 403) return null;
            String name = profile.text("name");
            if (profile.ok() && name != null && !name.equals(account.name())) {
                return new Account(account.id(), name, account.kind(), account.session(), account.favorite(), account.lastUsed());
            }
            return account;
        }).thenCompose(checked -> checked != null ? CompletableFuture.completedFuture(checked) : refresh(account, cancellation));
    }

    private record MsToken(String accessToken, String refreshToken) {}

    private MsToken microsoftToken(Map<String, String> form, Cancellation cancellation) throws AuthException {
        Http.Response response = http.postForm(TOKEN_URL, form, cancellation, "Microsoft sign-in");
        if (!response.ok() || response.text("access_token") == null) throw azureError(response, "refresh_token".equals(form.get("grant_type")));
        return new MsToken(response.text("access_token"), response.text("refresh_token"));
    }

    private Account finish(MsToken microsoft, String client, Listener listener, Cancellation cancellation) throws AuthException {
        listener.step(Step.XBOX_LIVE);
        JsonObject xblProperties = new JsonObject();
        xblProperties.addProperty("AuthMethod", "RPS");
        xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProperties.addProperty("RpsTicket", "d=" + microsoft.accessToken());
        JsonObject xblRequest = new JsonObject();
        xblRequest.add("Properties", xblProperties);
        xblRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblRequest.addProperty("TokenType", "JWT");
        Http.Response xbl = http.postJson(XBOX_AUTH_URL, xblRequest, cancellation, "Xbox Live");
        if (!xbl.ok() || xbl.text("Token") == null) {
            throw new AuthException(AuthException.Reason.SERVICE_ERROR, "Xbox Live sign-in failed (HTTP " + xbl.status() + ")");
        }
        String userHash = userHash(xbl.body());

        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xbl.text("Token"));
        xstsProperties.add("UserTokens", userTokens);
        JsonObject xstsRequest = new JsonObject();
        xstsRequest.add("Properties", xstsProperties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        Http.Response xsts = http.postJson(XSTS_URL, xstsRequest, cancellation, "Xbox Live");
        if (xsts.body().has("XErr")) throw xstsError(xsts.body().get("XErr").getAsLong());
        if (!xsts.ok() || xsts.text("Token") == null) {
            throw new AuthException(AuthException.Reason.SERVICE_ERROR, "Xbox security token request failed (HTTP " + xsts.status() + ")");
        }

        listener.step(Step.MINECRAFT);
        JsonObject login = new JsonObject();
        login.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xsts.text("Token"));
        Http.Response minecraft = http.postJson(MINECRAFT_LOGIN_URL, login, cancellation, "Minecraft services");
        if (minecraft.status() == 403) {
            throw new AuthException(AuthException.Reason.APP_NOT_APPROVED, "Minecraft services rejected this Azure app (HTTP 403): "
                + truncate(String.valueOf(minecraft.text("errorMessage"))));
        }
        if (!minecraft.ok() || minecraft.text("access_token") == null) {
            throw new AuthException(AuthException.Reason.SERVICE_ERROR, "Minecraft services sign-in failed (HTTP " + minecraft.status() + ")");
        }
        String minecraftToken = minecraft.text("access_token");
        long lifetime = minecraft.body().has("expires_in") ? minecraft.body().get("expires_in").getAsLong() : 86_400;

        listener.step(Step.PROFILE);
        Http.Response profile = http.get(PROFILE_URL, minecraftToken, cancellation, "Minecraft profile");
        if (profile.status() == 404 || (profile.ok() && profile.text("id") == null)) {
            throw new AuthException(AuthException.Reason.NO_MINECRAFT, "This Microsoft account does not own Minecraft: Java Edition or has no profile yet");
        }
        if (!profile.ok()) throw new AuthException(AuthException.Reason.SERVICE_ERROR, "Minecraft profile request failed (HTTP " + profile.status() + ")");

        Instant now = Instant.now();
        MicrosoftSession session = new MicrosoftSession(minecraftToken, microsoft.refreshToken(), userHash,
            now.plusSeconds(Math.max(60, lifetime)), client, now);
        return Account.microsoft(parseUndashedUuid(profile.text("id")), profile.text("name"), session);
    }

    private static String userHash(JsonObject xbl) throws AuthException {
        try {
            return xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        } catch (RuntimeException missing) {
            throw new AuthException(AuthException.Reason.SERVICE_ERROR, "Xbox Live returned no user hash", missing);
        }
    }

    static AuthException azureError(Http.Response response, boolean refreshing) {
        String error = String.valueOf(response.text("error"));
        String description = String.valueOf(response.text("error_description"));
        String detail = "Microsoft: " + error + " - " + truncate(description);
        if (refreshing && "invalid_grant".equals(error)) return new AuthException(AuthException.Reason.SESSION_REVOKED, detail);
        if ("authorization_declined".equals(error) || "access_denied".equals(error)) return new AuthException(AuthException.Reason.DECLINED, detail);
        if ("unauthorized_client".equals(error) || "invalid_client".equals(error) || description.contains("AADSTS700016")
            || description.contains("AADSTS9002313") || description.contains("AADSTS90002")) {
            return new AuthException(AuthException.Reason.CLIENT_ID_REJECTED, detail);
        }
        if (description.contains("AADSTS50011") || description.contains("AADSTS7000218") || description.contains("AADSTS700025")) {
            return new AuthException(AuthException.Reason.CLIENT_MISCONFIGURED, detail);
        }
        if ("invalid_grant".equals(error)) return new AuthException(AuthException.Reason.DECLINED, detail);
        return new AuthException(AuthException.Reason.SERVICE_ERROR, detail + " (HTTP " + response.status() + ")");
    }

    static AuthException xstsError(long code) {
        AuthException.Reason reason;
        if (code == 2148916227L) reason = AuthException.Reason.XBOX_BANNED;
        else if (code == 2148916233L) reason = AuthException.Reason.NO_XBOX_PROFILE;
        else if (code == 2148916235L) reason = AuthException.Reason.XBOX_REGION_BLOCKED;
        else if (code == 2148916236L || code == 2148916237L) reason = AuthException.Reason.XBOX_AGE_VERIFICATION;
        else if (code == 2148916238L) reason = AuthException.Reason.XBOX_CHILD_ACCOUNT;
        else reason = AuthException.Reason.SERVICE_ERROR;
        return new AuthException(reason, "Xbox Live error " + code);
    }

    static UUID parseUndashedUuid(String id) {
        if (id == null || id.length() != 32) throw new IllegalArgumentException("Bad profile id " + id);
        return new UUID(Long.parseUnsignedLong(id.substring(0, 16), 16), Long.parseUnsignedLong(id.substring(16), 16));
    }

    private static String truncate(String text) {
        int sentence = text.indexOf(". ");
        String first = sentence > 0 && sentence < 220 ? text.substring(0, sentence + 1) : text;
        return first.length() <= 240 ? first : first.substring(0, 240) + "...";
    }

    @FunctionalInterface
    private interface Work {
        Account run() throws AuthException;
    }

    private CompletableFuture<Account> run(Work work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (AuthException failure) {
                throw new CompletionException(failure);
            } catch (RuntimeException unexpected) {
                throw new CompletionException(new AuthException(AuthException.Reason.SERVICE_ERROR, "Unexpected sign-in error: " + unexpected, unexpected));
            }
        }, executor);
    }

    /** Unwraps the {@link AuthException} from a failed future, whatever wrapping it went through. */
    public static AuthException unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof AuthException auth) return auth;
            if (cause instanceof java.util.concurrent.CancellationException) return new AuthException(AuthException.Reason.CANCELLED, "Sign-in cancelled");
            cause = cause.getCause();
        }
        return new AuthException(AuthException.Reason.SERVICE_ERROR, String.valueOf(failure), failure);
    }
}

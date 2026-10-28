package io.github.domsimus.keychain.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrosoftAuthTest {
    private static Http.Response error(int status, String error, String description) {
        JsonObject body = new JsonObject();
        body.addProperty("error", error);
        body.addProperty("error_description", description);
        return new Http.Response(status, body);
    }

    @Test
    void mapsAzureErrorsToActionableReasons() {
        assertEquals(AuthException.Reason.SESSION_REVOKED, MicrosoftAuth.azureError(error(400, "invalid_grant", "AADSTS70000"), true).reason());
        assertEquals(AuthException.Reason.CLIENT_ID_REJECTED,
            MicrosoftAuth.azureError(error(400, "unauthorized_client", "AADSTS700016: Application not found"), false).reason());
        assertEquals(AuthException.Reason.CLIENT_MISCONFIGURED,
            MicrosoftAuth.azureError(error(400, "invalid_request", "AADSTS50011: The redirect URI does not match"), false).reason());
        assertEquals(AuthException.Reason.DECLINED, MicrosoftAuth.azureError(error(400, "authorization_declined", "x"), false).reason());
        assertEquals(AuthException.Reason.SERVICE_ERROR, MicrosoftAuth.azureError(error(500, "server_error", "x"), false).reason());
    }

    @Test
    void mapsXboxErrors() {
        assertEquals(AuthException.Reason.NO_XBOX_PROFILE, MicrosoftAuth.xstsError(2148916233L).reason());
        assertEquals(AuthException.Reason.XBOX_CHILD_ACCOUNT, MicrosoftAuth.xstsError(2148916238L).reason());
        assertEquals(AuthException.Reason.XBOX_AGE_VERIFICATION, MicrosoftAuth.xstsError(2148916236L).reason());
        assertEquals(AuthException.Reason.SERVICE_ERROR, MicrosoftAuth.xstsError(1L).reason());
    }

    @Test
    void parsesProfileIds() {
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), MicrosoftAuth.parseUndashedUuid("069a79f444e94726a5befca90e38aaf5"));
        assertThrows(IllegalArgumentException.class, () -> MicrosoftAuth.parseUndashedUuid("short"));
    }

    @Test
    void refreshWithoutTokenFailsFastWithoutNetwork() {
        Account account = Account.microsoft(UUID.randomUUID(), "A", new MicrosoftSession("t", null, null, Instant.EPOCH));
        MicrosoftAuth auth = new MicrosoftAuth(() -> "client", Runnable::run);
        CompletableFuture<Account> result = auth.refresh(account, new Cancellation());
        assertEquals(AuthException.Reason.SESSION_REVOKED, MicrosoftAuth.unwrap(assertThrows(Exception.class, result::join)).reason());
    }

    @Test
    void loopbackAcceptsOnlyTheExpectedState() throws Exception {
        try (LoopbackServer server = LoopbackServer.start()) {
            assertTrue(server.redirectUri().startsWith("http://localhost:"));
            HttpClient client = HttpClient.newHttpClient();
            // 127.0.0.1 rather than localhost: the server binds to the loopback address.
            String base = server.redirectUri().replace("localhost", "127.0.0.1");
            HttpResponse<String> wrong = client.send(HttpRequest.newBuilder(URI.create(base + "?code=x&state=wrong")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, wrong.statusCode());

            CompletableFuture<String> code = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.awaitCode(java.time.Duration.ofSeconds(10), new Cancellation());
                } catch (AuthException failure) {
                    throw new RuntimeException(failure);
                }
            });
            HttpResponse<String> right = client.send(HttpRequest.newBuilder(URI.create(base + "?code=the-code&state=" + server.state())).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, right.statusCode());
            assertTrue(right.body().contains("signed in"));
            assertEquals("the-code", code.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingStopsWaitingForTheBrowser() throws Exception {
        try (LoopbackServer server = LoopbackServer.start()) {
            Cancellation cancellation = new Cancellation();
            CompletableFuture<AuthException.Reason> result = CompletableFuture.supplyAsync(() -> {
                try {
                    server.awaitCode(java.time.Duration.ofMinutes(5), cancellation);
                    return null;
                } catch (AuthException failure) {
                    return failure.reason();
                }
            });
            Thread.sleep(100);
            cancellation.cancel();
            assertEquals(AuthException.Reason.CANCELLED, result.get(5, TimeUnit.SECONDS));
        }
    }
}

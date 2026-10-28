package io.github.domsimus.keychain.core.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Small JSON-over-HTTP helper. Every call honours {@link Cancellation} and maps failures to {@link AuthException}. */
final class Http {
    static final String USER_AGENT = "Keychain/4 (+https://modrinth.com/mod/keychain)";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    Http(HttpClient client) {
        this.client = client;
    }

    static Http create() {
        return new Http(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    record Response(int status, JsonObject body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String text(String key) {
            return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
        }
    }

    Response postForm(String url, Map<String, String> form, Cancellation cancellation, String step) throws AuthException {
        StringJoiner encoded = new StringJoiner("&");
        form.forEach((key, value) -> encoded.add(encode(key) + "=" + encode(value)));
        return send(request(url).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded.toString())).build(), cancellation, step);
    }

    Response postJson(String url, JsonObject json, Cancellation cancellation, String step) throws AuthException {
        return send(request(url).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString())).build(), cancellation, step);
    }

    Response get(String url, String bearer, Cancellation cancellation, String step) throws AuthException {
        return send(request(url).header("Authorization", "Bearer " + bearer).GET().build(), cancellation, step);
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
            .header("Accept", "application/json").header("User-Agent", USER_AGENT);
    }

    private Response send(HttpRequest request, Cancellation cancellation, String step) throws AuthException {
        cancellation.throwIfCancelled();
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        cancellation.onCancel(() -> pending.cancel(true));
        HttpResponse<String> response;
        try {
            response = pending.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthException(AuthException.Reason.CANCELLED, step + " interrupted");
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new AuthException(AuthException.Reason.CANCELLED, "Sign-in cancelled");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpTimeoutException) throw new AuthException(AuthException.Reason.TIMED_OUT, step + " timed out", cause);
            throw new AuthException(AuthException.Reason.NETWORK, step + " failed: " + cause, cause);
        }
        cancellation.throwIfCancelled();
        if (response.statusCode() == 429) throw new AuthException(AuthException.Reason.RATE_LIMITED, step + " is rate limited (HTTP 429)");
        String body = response.body() == null ? "" : response.body().strip();
        JsonObject json;
        try {
            json = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new AuthException(AuthException.Reason.SERVICE_ERROR, step + " returned HTTP " + response.statusCode() + " without JSON");
        }
        return new Response(response.statusCode(), json);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

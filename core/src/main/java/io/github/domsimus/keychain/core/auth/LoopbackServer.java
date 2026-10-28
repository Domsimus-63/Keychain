package io.github.domsimus.keychain.core.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The local page Microsoft redirects the browser to after sign-in. Binds to the loopback interface
 * on a random port and only accepts a callback carrying the expected {@code state}.
 */
final class LoopbackServer implements AutoCloseable {
    private final HttpServer server;
    /** Same port on ::1, because some systems resolve localhost to IPv6 first. Null if unavailable. */
    private HttpServer ipv6;
    private final String state = Pkce.randomUrlSafe(24);
    private final CompletableFuture<String> code = new CompletableFuture<>();

    private LoopbackServer(HttpServer server) {
        this.server = server;
    }

    static LoopbackServer start() throws AuthException {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new AuthException(AuthException.Reason.NETWORK, "Could not start the local sign-in page", failure);
        }
        LoopbackServer loopback = new LoopbackServer(server);
        server.createContext("/", loopback::handle);
        server.setExecutor(null);
        server.start();
        try {
            HttpServer six = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), server.getAddress().getPort()), 0);
            six.createContext("/", loopback::handle);
            six.start();
            loopback.ipv6 = six;
        } catch (IOException noIpv6) {
            // IPv4 only: browsers fall back to 127.0.0.1 for localhost.
        }
        return loopback;
    }

    /** Microsoft allows any port for {@code http://localhost} redirects of desktop apps. */
    String redirectUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/callback";
    }

    String state() {
        return state;
    }

    String awaitCode(Duration timeout, Cancellation cancellation) throws AuthException {
        cancellation.onCancel(() -> code.cancel(true));
        try {
            return code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            throw new AuthException(AuthException.Reason.TIMED_OUT, "No sign-in within " + timeout.toMinutes() + " minutes");
        } catch (java.util.concurrent.CancellationException | InterruptedException stopped) {
            if (stopped instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AuthException(AuthException.Reason.CANCELLED, "Sign-in cancelled");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof AuthException auth) throw auth;
            throw new AuthException(AuthException.Reason.SERVICE_ERROR, String.valueOf(failure.getCause()), failure.getCause());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"/callback".equals(exchange.getRequestURI().getPath()) || !"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, false, "Nothing here", "Return to Minecraft to continue.");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if (!state.equals(query.get("state"))) {
                respond(exchange, 400, false, "This link has expired", "Start signing in again from Minecraft.");
                return;
            }
            String error = query.get("error");
            if (error != null) {
                String description = query.getOrDefault("error_description", error);
                AuthException.Reason reason = "access_denied".equals(error) ? AuthException.Reason.DECLINED : AuthException.Reason.SERVICE_ERROR;
                code.completeExceptionally(new AuthException(reason, "Microsoft: " + error + " - " + description));
                respond(exchange, 400, false, "Sign-in was not completed", "Return to Minecraft to try again.");
                return;
            }
            String value = query.get("code");
            if (value == null || value.isBlank()) {
                respond(exchange, 400, false, "Something went wrong", "Microsoft did not send a sign-in code. Try again from Minecraft.");
                return;
            }
            try {
                respond(exchange, 200, true, "You're signed in", "You can close this tab and return to Minecraft.");
            } finally {
                code.complete(value);
            }
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                values.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, boolean success, String title, String message) throws IOException {
        String accent = success ? "#5fbf4a" : "#e0604f";
        String html = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>Keychain</title>
            <style>
            :root{color-scheme:dark}
            body{margin:0;min-height:100vh;display:grid;place-items:center;background:#16181d;color:#e8e8e8;
                 font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
            main{max-width:440px;margin:24px;padding:32px 36px;background:#20232a;border:1px solid #2e323b;
                 border-top:4px solid %s;border-radius:10px;text-align:center}
            .mark{font-size:13px;letter-spacing:.12em;text-transform:uppercase;color:#9aa0aa;margin-bottom:14px}
            h1{margin:0 0 8px;font-size:22px;font-weight:650}
            p{margin:0;color:#b8bdc6}
            </style></head><body><main><div class="mark">Keychain</div><h1>%s</h1><p>%s</p></main></body></html>
            """.formatted(accent, escape(title), escape(message));
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void close() {
        code.cancel(true);
        server.stop(0);
        if (ipv6 != null) ipv6.stop(0);
    }
}

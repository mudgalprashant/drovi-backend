package com.pm.drovi_backend;

import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.config.DroviProperties;
import com.pm.drovi_backend.integration.fetch.DocumentFetcher;
import com.pm.drovi_backend.integration.fetch.UrlGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mechanics of reading a link: redirects, ceilings, and what is not sent.
 *
 * <p>Runs against a real HTTP server on loopback — which the production guard refuses, correctly.
 * So the guard here is a permissive stand-in that allows loopback and <strong>refuses one marker
 * host</strong>. That split is deliberate: {@link UrlGuardTest} proves the real guard refuses
 * private space, and this file proves the fetcher <em>consults</em> the guard on every hop. Both
 * properties are needed and neither test can carry both.
 *
 * <p>There is no configuration flag that relaxes the real guard. A switch that turns off
 * request-forgery protection is one that gets turned on during an incident and left on.
 */
class DocumentFetcherTest {

    /** Any host but this one is allowed, so a redirect to it stands in for "somewhere private". */
    private static final String FORBIDDEN_HOST = "169.254.169.254";

    private HttpServer server;
    private DocumentFetcher fetcher;

    private final AtomicReference<Map<String, String>> lastHeaders = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    private boolean enabled = true;
    private int maxBytes = 1_000_000;
    private int maxRedirects = 3;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        requests.set(0);

        UrlGuard permissive = new UrlGuard(new DroviProperties("https://drovi.example")) {
            @Override
            public URI require(String candidate) {
                URI uri = URI.create(candidate);
                if (FORBIDDEN_HOST.equals(uri.getHost())) {
                    throw new RefusedException("That link resolves to a private address.");
                }
                return uri;
            }

            @Override
            public URI requireRedirect(URI from, String location) {
                return require(from.resolve(location).toString());
            }
        };

        AppConfigService config = new AppConfigService(null) {
            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return key.equals("fetch.enabled") ? enabled : fallback;
            }

            @Override
            public int getInt(String key, int fallback) {
                return switch (key) {
                    case "fetch.max.bytes" -> maxBytes;
                    case "fetch.max.redirects" -> maxRedirects;
                    case "fetch.timeout.seconds" -> 5;
                    default -> fallback;
                };
            }
        };
        fetcher = new DocumentFetcher(permissive, config);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // --- reading --------------------------------------------------------------

    @Test
    void fetch_returnsTheDocumentAndWhereItCameFrom() {
        serve("/openapi.json", 200, "{\"openapi\":\"3.0.3\"}");

        DocumentFetcher.Document document = fetcher.fetch(url("/openapi.json"));

        assertThat(document.body()).isEqualTo("{\"openapi\":\"3.0.3\"}");
        assertThat(document.source().getPath()).isEqualTo("/openapi.json");
        assertThat(document.truncated()).isFalse();
    }

    /** Nothing of ours goes out. A fetch is an anonymous GET or it is a credential leak. */
    @Test
    void fetch_sendsNoCredentialsOrCookies() {
        serve("/openapi.json", 200, "{}");

        fetcher.fetch(url("/openapi.json"));

        assertThat(lastHeaders.get()).doesNotContainKeys("authorization", "cookie");
        assertThat(lastHeaders.get().get("user-agent")).contains("Drovi");
    }

    // --- redirects ------------------------------------------------------------

    @Test
    void fetch_followsARedirectAndReportsTheFinalUrl() {
        serve("/spec", 302, "", "/real/spec.json");
        serve("/real/spec.json", 200, "{\"openapi\":\"3.0.3\"}");

        DocumentFetcher.Document document = fetcher.fetch(url("/spec"));

        assertThat(document.body()).contains("openapi");
        assertThat(document.source().getPath()).isEqualTo("/real/spec.json");
    }

    /**
     * The property this file exists for. A public host answering 302 into private space is the
     * usual route in, and the client must never follow it on our behalf.
     */
    @Test
    void aRedirectTheGuardRefuses_stopsTheFetch() {
        serve("/spec", 302, "", "https://" + FORBIDDEN_HOST + "/latest/meta-data/");

        assertThatThrownBy(() -> fetcher.fetch(url("/spec")))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("private");
    }

    @Test
    void aRedirectLoop_endsRatherThanSpinning() {
        maxRedirects = 2;
        serve("/a", 302, "", "/a");

        assertThatThrownBy(() -> fetcher.fetch(url("/a")))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("redirects too many times");
        assertThat(requests).hasValue(3);
    }

    @Test
    void aRedirectWithNoLocation_isRefused() {
        serve("/spec", 302, "");

        assertThatThrownBy(() -> fetcher.fetch(url("/spec")))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    // --- ceilings -------------------------------------------------------------

    /** A URL that streams forever is a one-request denial of service without this. */
    @Test
    void anEnormousDocument_isCutOffAtTheCeilingAndSaysSo() {
        maxBytes = 100;
        serve("/big", 200, "x".repeat(50_000));

        DocumentFetcher.Document document = fetcher.fetch(url("/big"));

        assertThat(document.body()).hasSize(100);
        assertThat(document.truncated()).isTrue();
    }

    /** Content-Length is a claim by the host, not a fact, so the ceiling is enforced on the read. */
    @Test
    void aLyingContentLength_doesNotRaiseTheCeiling() {
        maxBytes = 100;
        handle("/liar", exchange -> {
            byte[] body = "y".repeat(50_000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });

        assertThat(fetcher.fetch(url("/liar")).body()).hasSize(100);
    }

    // --- refusals -------------------------------------------------------------

    @Test
    void aNotFound_isReportedAsARefusalRatherThanAnEmptyDocument() {
        serve("/missing", 404, "nope");

        assertThatThrownBy(() -> fetcher.fetch(url("/missing")))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("404");
    }

    /** Off during an incident, without a deploy — the same reasoning as the AI kill switch. */
    @Test
    void whenFetchingIsSwitchedOff_nothingIsRequested() {
        enabled = false;
        serve("/openapi.json", 200, "{}");

        assertThatThrownBy(() -> fetcher.fetch(url("/openapi.json")))
                .isInstanceOf(UrlGuard.RefusedException.class);
        assertThat(requests).hasValue(0);
    }

    /** A missing config row must not read as "on". */
    @Test
    void theKillSwitch_defaultsToOff() {
        DocumentFetcher unconfigured = new DocumentFetcher(
                new UrlGuard(new DroviProperties("https://drovi.example")),
                new AppConfigService(null) {
                    @Override
                    public boolean getBoolean(String key, boolean fallback) {
                        return fallback;
                    }
                });

        assertThatThrownBy(() -> unconfigured.fetch("https://8.8.8.8/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("switched off");
    }

    // --- fixtures -------------------------------------------------------------

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void serve(String path, int status, String body) {
        serve(path, status, body, null);
    }

    private void serve(String path, int status, String body, String location) {
        handle(path, exchange -> {
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    private void handle(String path, ThrowingHandler handler) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            lastHeaders.set(lowercased(exchange));
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
    }

    private static Map<String, String> lowercased(HttpExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach(
                (name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
        return headers;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

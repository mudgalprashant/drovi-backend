package com.pm.drovi_backend.integration.fetch;

import com.pm.drovi_backend.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads a document from a URL the user gave us, under every limit that makes that safe.
 *
 * <p>Each of these bounds an attack rather than a mistake, so none is a tuning knob to be raised
 * because a document was truncated:
 *
 * <ul>
 *   <li>{@link UrlGuard} decides the URL is fetchable at all — and again at every redirect,
 *       because a public host redirecting into private space is the usual route in.
 *   <li>Redirects are followed <strong>by hand</strong> and capped. {@code HttpClient} following
 *       them for us would follow them past the guard.
 *   <li>The body is read to a byte ceiling and abandoned there. A URL that streams forever is a
 *       one-request denial of service otherwise.
 *   <li>A timeout, because a host that accepts a connection and says nothing costs a thread.
 *   <li>Nothing of ours is sent. No credentials, no cookies, no identifying header beyond a
 *       user-agent that says who is calling.
 * </ul>
 *
 * <p>The whole thing is behind {@code fetch.enabled}, so it can be turned off during an incident
 * without a deploy — the same reasoning as the AI kill switch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentFetcher {

    private static final int TIMEOUT_DEFAULT_SECONDS = 10;
    private static final int MAX_BYTES_DEFAULT = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS_DEFAULT = 3;
    private static final boolean ENABLED_BY_DEFAULT = false;

    private static final String USER_AGENT = "Drovi/1.0 (+sandbox generator; fetches specs only)";

    /** What came back, and where from — the final URL after redirects, not the one asked for. */
    public record Document(URI source, String contentType, String body, boolean truncated) {
    }

    private final UrlGuard guard;
    private final AppConfigService config;

    /**
     * @throws UrlGuard.RefusedException when the URL is not one we are willing to open, or the
     *         host misbehaves. The message is safe to show: it describes the caller's link
     */
    public Document fetch(String url) {
        if (!config.getBoolean("fetch.enabled", ENABLED_BY_DEFAULT)) {
            // Default false. A capability that reaches out from inside the network boundary
            // should be off until somebody turns it on, not on until somebody notices.
            throw new UrlGuard.RefusedException("Reading links is switched off right now.");
        }

        int maxBytes = config.getInt("fetch.max.bytes", MAX_BYTES_DEFAULT);
        int maxRedirects = config.getInt("fetch.max.redirects", MAX_REDIRECTS_DEFAULT);
        Duration timeout = Duration.ofSeconds(config.getInt("fetch.timeout.seconds", TIMEOUT_DEFAULT_SECONDS));

        URI target = guard.require(url);
        try (HttpClient client = HttpClient.newBuilder()
                // NEVER, and the redirects are followed below instead. Letting the client do it
                // would take the very hop the guard exists to inspect.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build()) {

            for (int hop = 0; hop <= maxRedirects; hop++) {
                HttpResponse<InputStream> response = client.send(
                        HttpRequest.newBuilder(target)
                                .GET()
                                .timeout(timeout)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "application/json, application/yaml, text/plain, */*")
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("location")
                            .orElseThrow(() -> new UrlGuard.RefusedException("That link redirects nowhere."));
                    response.body().close();
                    target = guard.requireRedirect(target, location);
                    continue;
                }
                if (status != 200) {
                    throw new UrlGuard.RefusedException("That link answered with HTTP " + status + ".");
                }

                String contentType = response.headers().firstValue("content-type").orElse("");
                Body body = read(response.body(), maxBytes);
                log.info("fetch.ok url={} bytes={} truncated={}", target, body.text().length(), body.truncated());
                return new Document(target, contentType, body.text(), body.truncated());
            }
            throw new UrlGuard.RefusedException("That link redirects too many times.");

        } catch (IOException e) {
            // The upstream message is not returned: it can carry internal hostnames from a
            // resolver or proxy, which is the thing the guard is protecting in the first place.
            log.info("fetch.failed url={} detail={}", url, e.getMessage());
            throw new UrlGuard.RefusedException("That link could not be read.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UrlGuard.RefusedException("That link could not be read.");
        }
    }

    private record Body(String text, boolean truncated) {
    }

    /**
     * Reads to the ceiling and stops, rather than trusting {@code Content-Length}. A hostile host
     * declares a small length and sends gigabytes; a careless one declares nothing at all.
     */
    private static Body read(InputStream stream, int maxBytes) throws IOException {
        try (stream) {
            byte[] buffer = stream.readNBytes(maxBytes + 1);
            boolean truncated = buffer.length > maxBytes;
            return new Body(new String(buffer, 0, Math.min(buffer.length, maxBytes), StandardCharsets.UTF_8),
                    truncated);
        }
    }
}

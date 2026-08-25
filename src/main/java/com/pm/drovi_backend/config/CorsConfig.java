package com.pm.drovi_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Which browsers may call which surface.
 *
 * <p>Nothing here mattered while the only clients were {@code curl} and a server. The console is
 * a browser application on a different origin, and without this every one of its requests fails
 * a preflight — the response never reaches the page, and the error a developer sees says nothing
 * about the API.
 *
 * <h2>The two surfaces get opposite policies, deliberately</h2>
 *
 * <p><strong>{@code /api/v1/**} — a named list of origins.</strong> These routes carry a
 * Firebase ID token and act on somebody's account. A permissive policy would let any page on the
 * internet make authenticated calls with a token it had got hold of, so the origins are
 * configured, and the default is localhost rather than a wildcard.
 *
 * <p><strong>{@code /s/**} — any origin.</strong> A sandbox exists to be called from wherever
 * the developer is testing, which is frequently a single-page app on a origin nobody can predict
 * — and telling them to configure CORS on their own mock would defeat "paste this over your
 * production URL and change nothing else". It holds invented data, and it is protected by its
 * own project key rather than by the browser.
 *
 * <p>That second choice does mean a sandbox with {@code auth_mode = NONE} can be read by any
 * page whose author knows the project id. That was already true of any HTTP client; CORS only
 * ever governed browsers, and it is not the control keeping a sandbox private.
 */
@Configuration
@Slf4j
public class CorsConfig {

    /**
     * Where the console runs. A comma-separated list, because staging and production are
     * different origins and a preview deployment is a third.
     *
     * <p>Exact origins, never a pattern. {@code allowedOriginPatterns} with a wildcard is how a
     * list like this quietly becomes "anywhere ending in a domain somebody else can register".
     */
    @Value("${drovi.console-origins:http://localhost:3000}")
    private String consoleOrigins;

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        List<String> origins = Arrays.stream(consoleOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        log.info("cors.console origins={}", origins);

        CorsConfiguration console = new CorsConfiguration();
        console.setAllowedOrigins(origins);
        console.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        console.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        // So a console can show the id from a failure without the user copying it out of a body.
        console.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        // Deliberately false. The console authenticates with a bearer token, not a cookie, and
        // allowing credentials would forbid a wildcard here later without anyone noticing why.
        console.setAllowCredentials(false);
        console.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/v1/**", console);

        CorsConfiguration sandbox = new CorsConfiguration();
        // A user's app runs wherever they are building it. Making them register an origin to
        // call their own mock would break the one promise: swap the base URL, change nothing.
        sandbox.addAllowedOriginPattern("*");
        sandbox.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        sandbox.addAllowedHeader("*");
        sandbox.setAllowCredentials(false);
        sandbox.setMaxAge(3600L);
        source.registerCorsConfiguration("/s/**", sandbox);

        return source;
    }
}

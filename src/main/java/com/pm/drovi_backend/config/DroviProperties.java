package com.pm.drovi_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicBaseUrl origin only, no path. It is what a project's base URL is built from,
 *                      and therefore what a user copies into their own application — so a
 *                      wrong value here is wrong in every user's codebase.
 */
@ConfigurationProperties(prefix = "drovi")
public record DroviProperties(String publicBaseUrl) {

    public String baseUrlFor(String projectKey) {
        String origin = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "http://localhost:8080"
                : publicBaseUrl.replaceAll("/+$", "");
        return origin + "/s/" + projectKey;
    }
}

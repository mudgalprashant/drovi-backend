package com.pm.drovi_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param projectId the Firebase project. Absent means token verification is not
 *                  configured, and every console route fails closed — see SecurityConfig.
 */
@ConfigurationProperties(prefix = "drovi.firebase")
public record FirebaseProperties(String projectId) {

    /** Google's public keys for Firebase ID tokens. Rotated by Google; fetched and cached. */
    public static final String JWK_SET_URI =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    public boolean isConfigured() {
        return projectId != null && !projectId.isBlank();
    }

    /** Firebase mints tokens with this issuer, and only this one is trusted. */
    public String issuer() {
        return "https://securetoken.google.com/" + projectId;
    }
}

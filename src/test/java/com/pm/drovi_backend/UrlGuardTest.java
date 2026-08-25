package com.pm.drovi_backend;

import com.pm.drovi_backend.config.DroviProperties;
import com.pm.drovi_backend.integration.fetch.UrlGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control that makes fetching a user-supplied URL survivable.
 *
 * <p>A server that retrieves a link a stranger chose is a request-forgery engine pointed at its
 * own network. Every case here is a real target: a cloud metadata endpoint handing out instance
 * credentials, an internal service that trusts anything that reaches it, and this application.
 *
 * <p>No Spring and <strong>no network</strong>. Every host below is an IP literal or resolves
 * from the hosts file, so {@code InetAddress} answers without a lookup — a security test that
 * needs DNS is one that gets disabled the first time CI runs offline.
 */
class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard(new DroviProperties("https://drovi-backend.onrender.com"));

    // --- the shapes that are allowed ------------------------------------------

    @Test
    void aPublicHttpsUrl_isAllowed() {
        assertThatCode(() -> guard.require("https://8.8.8.8/openapi.json")).doesNotThrowAnyException();
    }

    @Test
    void require_returnsTheParsedUrl() {
        assertThat(guard.require("https://8.8.8.8/v3/api-docs").getPath()).isEqualTo("/v3/api-docs");
    }

    // --- scheme ---------------------------------------------------------------

    /** Plain HTTP lets anything in the path rewrite the document we build a sandbox from. */
    @Test
    void plainHttp_isRefused() {
        assertThatThrownBy(() -> guard.require("http://8.8.8.8/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("https");
    }

    @Test
    void file_isRefused() {
        assertThatThrownBy(() -> guard.require("file:///etc/passwd"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    @Test
    void gopherAndFriends_areRefused() {
        assertThatThrownBy(() -> guard.require("gopher://8.8.8.8/_something"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    @Test
    void aSchemelessString_isRefused() {
        assertThatThrownBy(() -> guard.require("example.com/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    @Test
    void nothing_isRefused() {
        assertThatThrownBy(() -> guard.require("   ")).isInstanceOf(UrlGuard.RefusedException.class);
    }

    // --- the addresses that matter --------------------------------------------

    /**
     * The single most valuable thing a request forgery can reach: on most clouds this hands back
     * the instance's own credentials to anyone who asks.
     */
    @Test
    void theCloudMetadataEndpoint_isRefused() {
        assertThatThrownBy(() -> guard.require("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("private");
    }

    @Test
    void loopback_isRefused() {
        assertThatThrownBy(() -> guard.require("https://127.0.0.1/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    /** A name, not an address — which is exactly why the check resolves rather than string-matches. */
    @Test
    void theNameLocalhost_isRefused() {
        assertThatThrownBy(() -> guard.require("https://localhost/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    /** 127.0.0.1 has a great many spellings; resolving is what makes them all one case. */
    @Test
    void loopbackSpelledOddly_isStillRefused() {
        assertThatThrownBy(() -> guard.require("https://127.1/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    @Test
    void privateRanges_areRefused() {
        for (String host : new String[]{"10.0.0.1", "192.168.1.1", "172.16.0.1", "0.0.0.0"}) {
            assertThatThrownBy(() -> guard.require("https://" + host + "/openapi.json"))
                    .as(host)
                    .isInstanceOf(UrlGuard.RefusedException.class);
        }
    }

    /** Routable-looking and not the public internet: carrier-grade NAT. */
    @Test
    void carrierGradeNat_isRefused() {
        assertThatThrownBy(() -> guard.require("https://100.64.0.1/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    @Test
    void ipv6LoopbackAndUniqueLocal_areRefused() {
        assertThatThrownBy(() -> guard.require("https://[::1]/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
        assertThatThrownBy(() -> guard.require("https://[fc00::1]/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
        assertThatThrownBy(() -> guard.require("https://[fe80::1]/openapi.json"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    /** Fetching ourselves reaches our own routes from inside the network boundary. */
    @Test
    void ourOwnHost_isRefused() {
        assertThatThrownBy(() -> guard.require("https://drovi-backend.onrender.com/actuator/health"))
                .isInstanceOf(UrlGuard.RefusedException.class)
                .hasMessageContaining("this service");
    }

    @Test
    void ourOwnHostInAnyCasing_isRefused() {
        assertThatThrownBy(() -> guard.require("https://DROVI-Backend.onrender.com/s/x"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    // --- redirects ------------------------------------------------------------

    /** The usual way in: a public host that answers 302 and points somewhere it should not. */
    @Test
    void aRedirectIntoPrivateSpace_isRefused() {
        assertThatThrownBy(() -> guard.requireRedirect(
                guard.require("https://8.8.8.8/spec"), "https://169.254.169.254/latest/"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    /** A relative Location is resolved before it is judged, or the check inspects the wrong URL. */
    @Test
    void aRelativeRedirect_isResolvedAgainstTheCurrentUrlAndChecked() {
        assertThat(guard.requireRedirect(guard.require("https://8.8.8.8/a/b"), "../c/spec.json").getPath())
                .isEqualTo("/c/spec.json");
    }

    @Test
    void aRedirectDowngradingToHttp_isRefused() {
        assertThatThrownBy(() -> guard.requireRedirect(
                guard.require("https://8.8.8.8/spec"), "http://8.8.8.8/spec"))
                .isInstanceOf(UrlGuard.RefusedException.class);
    }

    /** Refusals are the caller's to read: someone who pasted an internal URL needs to know. */
    @Test
    void aRefusal_isABadRequestAndSaysWhy() {
        assertThatThrownBy(() -> guard.require("https://10.0.0.1/openapi.json"))
                .isInstanceOf(com.pm.drovi_backend.common.DroviException.class)
                .extracting(e -> ((com.pm.drovi_backend.common.DroviException) e).getErrorCode())
                .isEqualTo(com.pm.drovi_backend.common.ErrorCode.VALIDATION_FAILED);
    }
}

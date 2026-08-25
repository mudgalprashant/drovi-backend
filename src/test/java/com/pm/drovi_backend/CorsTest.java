package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whether a browser is allowed to talk to us.
 *
 * <p>None of this mattered while the only clients were {@code curl} and a server. The console is
 * a browser application on a different origin, and without CORS every one of its requests fails
 * at the preflight — the response never reaches the page, and the error a developer sees says
 * nothing about the API at all.
 *
 * <p>The two surfaces get opposite policies on purpose, and both halves are worth a test: the
 * console API is an authenticated surface acting on someone's account, and a sandbox exists to be
 * called from wherever its owner is building.
 */
@SpringBootTest(properties = "drovi.console-origins=https://console.drovi.test,http://localhost:3000")
@AutoConfigureMockMvc
class CorsTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    // --- the console API ------------------------------------------------------

    /**
     * A preflight carries no Authorization header. If CORS is not wired ahead of the authority
     * check, this is a 401 or a 403 and the browser reports a network error.
     */
    @Test
    void aPreflightFromTheConsole_isAllowedWithoutACredential() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "https://console.drovi.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://console.drovi.test"));
    }

    @Test
    void theConsoleOrigin_mayUseTheMethodsAndHeadersItNeeds() throws Exception {
        mvc.perform(options("/api/v1/projects/" + UUID.randomUUID())
                        .header("Origin", "https://console.drovi.test")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("PATCH")));
    }

    /** Local development is a first-class origin, or nobody can run the console at all. */
    @Test
    void localhost_isAllowedByDefault() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
    }

    /**
     * The reason this list is exact rather than a pattern. These routes carry a Firebase token
     * and act on somebody's account; any page being able to make authenticated calls with a
     * token it had got hold of is a different product.
     */
    @Test
    void anUnknownOrigin_isRefusedOnTheConsoleApi() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "https://not-us.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    /** A near-miss on the domain is still a miss — this is what a pattern would have allowed. */
    @Test
    void anOriginThatMerelyLooksLikeOurs_isRefused() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "https://console.drovi.test.evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    /** So a console can show the id from a failure without asking the user to copy it out. */
    @Test
    void theCorrelationIdHeader_isReadableByTheConsole() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "https://console.drovi.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsString("X-Correlation-Id")));
    }

    // --- the sandbox surface --------------------------------------------------

    /**
     * A user's app runs wherever they are building it. Making them register an origin to call
     * their own mock would break the one promise the product makes: swap the base URL and change
     * nothing else.
     */
    @Test
    void anySite_mayCallASandboxFromABrowser() throws Exception {
        mvc.perform(options("/s/" + UUID.randomUUID() + "/v1/cards")
                        .header("Origin", "https://someones-app.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://someones-app.example"));
    }

    @Test
    void aSandboxAcceptsTheWriteMethodsAProductWould() throws Exception {
        mvc.perform(options("/s/" + UUID.randomUUID() + "/v1/cards")
                        .header("Origin", "https://someones-app.example")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk());
    }

    /** CORS is a browser rule, not the thing keeping a sandbox private. Its key does that. */
    @Test
    void aSandboxStillAnswersARequestWithNoOriginAtAll() throws Exception {
        mvc.perform(get("/s/" + UUID.randomUUID() + "/v1/cards"))
                .andExpect(status().isNotFound());
    }
}

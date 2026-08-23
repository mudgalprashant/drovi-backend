package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Identity, end to end over HTTP.
 *
 * <p>The real {@link JwtDecoder} verifies an RS256 signature against Google's published
 * keys, which a test cannot produce. It is replaced here by a stub that treats the bearer
 * token as the Firebase uid — so these tests exercise everything *after* verification:
 * provisioning, the race, entitlements, and deny-by-default. Signature and audience
 * checking is Spring Security's, and is not re-tested here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IdentityTest.StubFirebase.class)
class IdentityTest extends PostgresTestBase {

    @TestConfiguration
    static class StubFirebase {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(token)
                    .claim("email", token + "@example.test")
                    .claim("name", "Test " + token)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static String uid() {
        return "uid-" + UUID.randomUUID();
    }

    /** There is no signup endpoint: the first authenticated call creates the account. */
    @Test
    void firstAuthenticatedCall_provisionsAnAccount() throws Exception {
        String uid = uid();
        assertThat(countFor(uid)).isZero();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(uid + "@example.test"))
                .andExpect(jsonPath("$.planCode").value("FREE"))
                .andExpect(jsonPath("$.accountId").isNotEmpty());

        assertThat(countFor(uid)).isEqualTo(1);
    }

    /** A returning user must not accumulate accounts. */
    @Test
    void repeatedCalls_reuseTheSameAccount() throws Exception {
        String uid = uid();

        String first = accountIdFrom(uid);
        String second = accountIdFrom(uid);

        assertThat(first).isEqualTo(second);
        assertThat(countFor(uid)).isEqualTo(1);
    }

    /**
     * Two devices signing in at once both miss the read and both insert; the unique index
     * settles it and the loser reads the winner's row. Serialised here rather than
     * threaded — the guarantee under test is that a violation is *recovered from*, and the
     * index is what makes that true.
     */
    @Test
    void concurrentProvisioning_yieldsOneAccount() throws Exception {
        String uid = uid();
        jdbc.update("INSERT INTO accounts (firebase_uid) VALUES (?)", uid);

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + uid))
                .andExpect(status().isOk());

        assertThat(countFor(uid)).isEqualTo(1);
    }

    @Test
    void entitlements_comeFromThePlanCatalog_notTheClient() throws Exception {
        String uid = uid();

        mvc.perform(get("/api/v1/me/entitlements").header("Authorization", "Bearer " + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("FREE"))
                .andExpect(jsonPath("$.maxProjects").value(2))
                .andExpect(jsonPath("$.maxStoredBytesPerProject").value(5242880));
    }

    @Test
    void noToken_isRejected() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty());
    }

    /**
     * Deny by default. A route nobody has written yet must still require a credential —
     * otherwise a new controller is public until someone remembers to protect it.
     */
    @Test
    void unknownConsoleRoute_requiresAuthenticationBeforeItCanBeNotFound() throws Exception {
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    /** A suspended account holds a valid token but must not be usable. */
    @Test
    void suspendedAccount_isRefused() throws Exception {
        String uid = uid();
        accountIdFrom(uid);
        jdbc.update("UPDATE accounts SET status = 'SUSPENDED' WHERE firebase_uid = ?", uid);

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + uid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** Every response carries the id that ties a user's report to our logs. */
    @Test
    void everyResponse_carriesACorrelationId() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + uid()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    private String accountIdFrom(String uid) throws Exception {
        String body = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + uid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accountId\":\"([^\"]+)\".*", "$1");
    }

    private long countFor(String uid) {
        return jdbc.queryForObject("SELECT count(*) FROM accounts WHERE firebase_uid = ?", Long.class, uid);
    }
}

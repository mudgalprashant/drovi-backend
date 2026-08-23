package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that setting the project id is all it takes to switch identity on.
 *
 * <p>The configuration path is easy to get subtly wrong and impossible to notice: the
 * property is never written in {@code application.yaml}, so it arrives purely by relaxed
 * binding from {@code DROVI_FIREBASE_PROJECT_ID}. If that binding or the
 * {@code @ConditionalOnProperty} broke, the server would keep starting happily and simply
 * refuse every login — which reads like "Firebase is broken", not "a property is unbound".
 *
 * <p>No network is involved: {@code NimbusJwtDecoder} fetches Google's keys lazily, on the
 * first token it is asked to verify.
 */
@SpringBootTest(properties = "drovi.firebase.project-id=demo-drovi-project")
@AutoConfigureMockMvc
class FirebaseConfiguredTest extends PostgresTestBase {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void settingTheProjectId_createsTheTokenDecoder() {
        assertThat(context.getBeanNamesForType(JwtDecoder.class))
                .as("a configured project must produce a decoder; without one the console fails closed")
                .isNotEmpty();
    }

    /**
     * The difference that matters operationally: <b>503</b> means "this server cannot check
     * credentials at all", <b>401</b> means "check yours". Confusing the two sends someone
     * debugging their login when the real problem is an unset environment variable.
     */
    @Test
    void configured_rejectsWithUnauthenticated_notAuthNotConfigured() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    /** A token that is not a real Firebase JWT is rejected, not waved through. */
    @Test
    void configured_rejectsAGarbageToken() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }
}

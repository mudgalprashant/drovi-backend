package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * With no Firebase project configured — the state of every environment today — the console
 * must **fail closed**.
 *
 * <p>This is the test that would catch the worst possible regression in this area: a
 * refactor that makes an unconfigured server start up with its console routes reachable
 * without a credential. No stub decoder is imported here, which is the whole point.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthNotConfiguredTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void consoleRoutes_failClosed_whenNoProjectIsConfigured() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AUTH_NOT_CONFIGURED"));
    }

    /** Even presenting a token must not get through: there is nothing to verify it with. */
    @Test
    void aTokenDoesNotHelp_whenNoProjectIsConfigured() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer anything"))
                .andExpect(status().isServiceUnavailable());
    }

    /**
     * And the sandbox surface keeps working regardless. Adding console authentication must
     * never touch {@code /s/**} — every user's application holds a project API key and no
     * Firebase token, so subjecting it to console auth would break them all at once.
     */
    @Test
    void sandboxSurface_isUnaffectedByConsoleAuthentication() throws Exception {
        mvc.perform(get("/s/no-such-project/v1/anything"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SANDBOX_NOT_FOUND"));
    }
}

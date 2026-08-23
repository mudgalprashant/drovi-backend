package com.pm.drovi_backend;

import com.pm.drovi_backend.config.DatabaseUrlPreflight;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the message, not just the detection.
 *
 * <p>This check exists because the real failure — {@code 'url' must start with "jdbc"} from
 * inside Flyway's initialisation — reads like a Flyway bug rather than a copied string. A
 * regression that keeps the detection but loses the explanation would put the hour back.
 *
 * <p>A plain unit test: no Spring, because the whole point is running before Spring does.
 */
class DatabaseUrlPreflightTest {

    @Test
    void aLibpqUrl_isRejectedWithInstructionsForFixingIt() {
        var message = DatabaseUrlPreflight.check(
                "postgresql://postgres.abc:secret@aws-0-ap-south-1.pooler.supabase.com:5432/postgres");

        assertThat(message).isPresent();
        assertThat(message.get())
                .as("the message must say what to do, not merely what is wrong")
                .contains("jdbc:postgresql://")
                .contains("DROVI_DB_USERNAME");
    }

    @Test
    void theMessage_warnsAboutTheOtherSupabaseTrap() {
        var message = DatabaseUrlPreflight.check("postgresql://host:6543/postgres");

        // Someone fixing the scheme is one paste away from also fixing the port; telling
        // them now saves a second failed deploy.
        assertThat(message).get().asString().contains("6543");
    }

    @Test
    void aProperJdbcUrl_passes() {
        assertThat(DatabaseUrlPreflight.check(
                "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require"))
                .isEmpty();
    }

    /**
     * Works, but the credentials override the separate username/password variables — so a
     * rotation appears to do nothing. Worth a message even though it would boot.
     */
    @Test
    void aJdbcUrlWithEmbeddedCredentials_isFlagged() {
        var message = DatabaseUrlPreflight.check(
                "jdbc:postgresql://postgres.abc:secret@host.pooler.supabase.com:5432/postgres");

        assertThat(message).get().asString().contains("override");
    }

    /** Absent is normal: tests and local runs supply their own datasource. */
    @Test
    void anAbsentOrBlankValue_isNotAnError() {
        assertThat(DatabaseUrlPreflight.check(null)).isEmpty();
        assertThat(DatabaseUrlPreflight.check("   ")).isEmpty();
    }

    /** A host called "user@example" must not be mistaken for embedded credentials. */
    @Test
    void aJdbcUrlWithoutCredentials_isNotFlagged() {
        assertThat(DatabaseUrlPreflight.check("jdbc:postgresql://localhost:5432/drovi")).isEmpty();
    }
}

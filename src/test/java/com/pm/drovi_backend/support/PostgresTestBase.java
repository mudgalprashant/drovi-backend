package com.pm.drovi_backend.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Base for tests that need a database.
 *
 * <p>Starts a <em>real</em> PostgreSQL — the binary arrives as a Gradle dependency and
 * runs in a temp directory, so this needs no Docker, no Homebrew and nothing installed
 * on the machine. That matters more than the convenience: the schema uses partial
 * indexes, {@code jsonb}, {@code gin_trgm_ops} and expression-based unique constraints,
 * none of which an in-memory substitute implements. A migration verified against H2 has
 * not been verified.
 *
 * <p>One instance is shared by every test class in the JVM. It is never shut down
 * explicitly: the process exiting is what stops it, and paying ~2s of startup per class
 * would buy nothing, since Flyway makes the schema identical either way.
 */
public abstract class PostgresTestBase {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    protected static EmbeddedPostgres postgres() {
        return POSTGRES;
    }
}

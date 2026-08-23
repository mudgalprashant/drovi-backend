package com.pm.drovi_backend.config;

/**
 * Checks {@code DROVI_DB_URL} before Spring starts, so the most likely deployment mistake
 * fails with a sentence instead of a hundred-line stack trace.
 *
 * <p>Supabase hands you a <b>libpq</b> URL — {@code postgresql://user:pass@host:5432/postgres}.
 * Spring needs a <b>JDBC</b> one. Paste the first and Hikari throws
 * {@code 'url' must start with "jdbc"} from six frames inside Flyway's initialisation,
 * which reads like a Flyway bug rather than a copied string.
 *
 * <p>Deliberately runs in {@code main}, not as a bean or an {@code EnvironmentPostProcessor}:
 * the failure happens while the datasource is being built, which is earlier than most
 * application beans exist. The honest place for a check that must precede that is before
 * {@code SpringApplication.run}.
 */
public final class DatabaseUrlPreflight {

    private static final String VAR = "DROVI_DB_URL";

    private DatabaseUrlPreflight() {
    }

    /**
     * @return an actionable message, or empty when the value is absent (local runs and
     *         tests supply their own) or already well-formed
     */
    public static java.util.Optional<String> check(String url) {
        if (url == null || url.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("jdbc:")) {
            return credentialsEmbedded(trimmed)
                    ? java.util.Optional.of(embeddedCredentialsMessage(trimmed))
                    : java.util.Optional.empty();
        }
        return java.util.Optional.of("""
                %s must be a JDBC URL, but it starts with '%s'.

                Supabase shows a libpq URL. Spring needs the JDBC form:
                  wrong: postgresql://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres
                  right: jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require

                Prepend 'jdbc:' and remove the '<user>:<password>@' part — the username and
                password are supplied separately as DROVI_DB_USERNAME and DROVI_DB_PASSWORD.

                Also check the port is 5432 (session pooler). 6543 is transaction mode: it
                has no prepared statements, which Hibernate and Flyway both require.
                """.formatted(VAR, prefixOf(trimmed)));
    }

    /**
     * Credentials inside the URL are not a hard failure — they work — but they end up in
     * logs, crash dumps and shell history, and they silently override the separate
     * username/password variables, which makes a rotation appear not to take effect.
     */
    private static boolean credentialsEmbedded(String url) {
        int authority = url.indexOf("//");
        if (authority < 0) {
            return false;
        }
        int at = url.indexOf('@', authority);
        return at > 0 && url.lastIndexOf(':', at) > authority;
    }

    private static String embeddedCredentialsMessage(String url) {
        return """
                %s contains a username and password inside the URL.

                Remove the '<user>:<password>@' part. Credentials in a URL leak into logs and
                crash dumps, and they override DROVI_DB_USERNAME / DROVI_DB_PASSWORD — so
                rotating those two appears to do nothing.
                """.formatted(VAR);
    }

    private static String prefixOf(String url) {
        int scheme = url.indexOf("://");
        return scheme > 0 ? url.substring(0, scheme + 3) : url.substring(0, Math.min(12, url.length()));
    }

    /** Prints and exits non-zero, so a container restart loop shows the message every time. */
    public static void verifyOrExit(String url) {
        check(url).ifPresent(message -> {
            System.err.println();
            System.err.println("=== Drovi cannot start: database configuration ===");
            System.err.println(message);
            System.exit(1);
        });
    }
}

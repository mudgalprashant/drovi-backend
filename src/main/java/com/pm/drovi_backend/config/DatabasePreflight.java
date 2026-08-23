package com.pm.drovi_backend.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks the database configuration before Spring starts, so the two most likely deployment
 * mistakes fail with a sentence instead of a hundred-line stack trace.
 *
 * <p>Both were found the hard way, on consecutive deploys:
 *
 * <ol>
 *   <li>a <b>libpq</b> URL pasted where a <b>JDBC</b> one belongs — Supabase shows the
 *       former, Spring needs the latter</li>
 *   <li>a URL set without its username, which silently fell back to the local-development
 *       default and produced an error naming the <em>wrong user</em></li>
 * </ol>
 *
 * <p>Deliberately runs in {@code main}, not as a bean: the failures happen while the
 * datasource is being built, which is earlier than most application beans exist.
 */
public final class DatabasePreflight {

    private static final String URL_VAR = "DROVI_DB_URL";
    private static final String USER_VAR = "DROVI_DB_USERNAME";
    private static final String PASSWORD_VAR = "DROVI_DB_PASSWORD";

    private DatabasePreflight() {
    }

    /**
     * @return an actionable message, or empty when the value is absent (local runs and tests
     *         supply their own) or already well-formed
     */
    public static Optional<String> check(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("jdbc:")) {
            return credentialsEmbedded(trimmed) ? Optional.of(embeddedCredentialsMessage()) : Optional.empty();
        }
        return Optional.of(("""
                %s must be a JDBC URL, but it starts with '%s'.

                Supabase shows a libpq URL. Spring needs the JDBC form:
                  wrong: postgresql://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres
                  right: jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require

                Prepend 'jdbc:' and remove the '<user>:<password>@' part -- the username and
                password are supplied separately as %s and %s.

                Also check the port is 5432 (session pooler). 6543 is transaction mode: it
                has no prepared statements, which Hibernate and Flyway both require.
                """).formatted(URL_VAR, prefixOf(trimmed), USER_VAR, PASSWORD_VAR));
    }

    /**
     * Configuring a database means configuring <em>all three</em> variables.
     *
     * <p>{@code application.yaml} defaults the username to {@code postgres} and the password
     * to empty. That is right for a local Postgres and actively harmful anywhere else: a
     * missing {@code DROVI_DB_USERNAME} does not fail, it connects as the wrong user. The
     * resulting {@code password authentication failed for user "postgres"} then sends you
     * looking at the password, which was never the problem.
     *
     * <p>Our own security baseline says a required secret must fail rather than default.
     * This restores that for the one place the defaults exist.
     */
    public static Optional<String> checkCompleteness(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        if (username == null || username.isBlank()) {
            missing.add(USER_VAR);
        }
        if (password == null || password.isBlank()) {
            missing.add(PASSWORD_VAR);
        }
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(("""
                %s is set, but %s %s not.

                Configuring a database means configuring all three. Without them the
                application falls back to its local-development defaults -- username
                'postgres' and an empty password -- and the failure reads:

                  FATAL: password authentication failed for user "postgres"

                which names the WRONG USER and sends you looking at the password.

                On Supabase's session pooler the username carries the project ref:
                  %s=postgres.<project-ref>
                """).formatted(URL_VAR, String.join(" and ", missing),
                missing.size() == 1 ? "is" : "are", USER_VAR));
    }

    /**
     * Credentials inside the URL are not a hard failure -- they work -- but they end up in
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

    private static String embeddedCredentialsMessage() {
        return ("""
                %s contains a username and password inside the URL.

                Remove the '<user>:<password>@' part. Credentials in a URL leak into logs and
                crash dumps, and they override %s / %s -- so rotating those two appears to do
                nothing.
                """).formatted(URL_VAR, USER_VAR, PASSWORD_VAR);
    }

    private static String prefixOf(String url) {
        int scheme = url.indexOf("://");
        return scheme > 0 ? url.substring(0, scheme + 3) : url.substring(0, Math.min(12, url.length()));
    }

    /** Prints and exits non-zero, so a container restart loop shows the message every time. */
    public static void verifyOrExit(String url, String username, String password) {
        check(url)
                .or(() -> checkCompleteness(url, username, password))
                .ifPresent(message -> {
                    System.err.println();
                    System.err.println("=== Drovi cannot start: database configuration ===");
                    System.err.println(message);
                    System.exit(1);
                });
    }
}

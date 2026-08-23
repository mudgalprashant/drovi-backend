package com.pm.drovi_backend;

import com.pm.drovi_backend.support.PostgresTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the migrations apply to a schema that is <em>not</em> empty.
 *
 * <p>Every other test starts from a blank database, so none of them could catch the two
 * deploy failures this guards:
 *
 * <ol>
 *   <li>Supabase's {@code public} schema already contains objects, and Flyway refuses to
 *       migrate into one without a history table — {@code baseline-on-migrate} fixes that</li>
 *   <li>{@code baseline-version} defaults to <b>1</b>, and Flyway applies only migrations
 *       <em>above</em> the baseline. Left at the default, {@code V1__baseline} is silently
 *       skipped: the app starts, Flyway reports success, and the first symptom is Hibernate
 *       complaining about a missing table — which points nowhere near this setting.</li>
 * </ol>
 *
 * <p>No Spring context: this drives Flyway directly against a purpose-made database, which
 * is the only way to reproduce "schema already has something in it".
 */
class FlywayBaselineTest extends PostgresTestBase {

    private static final String PROBE_DB = "baseline_probe";

    @Test
    void allMigrationsApply_evenWhenTheSchemaAlreadyHasObjectsInIt() throws Exception {
        DataSource target = databaseWithAStrayTable();

        Flyway flyway = Flyway.configure()
                .dataSource(target)
                .locations("classpath:db/migration")
                // Exactly what application.yaml sets. If these drift, this test is a lie.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load();

        flyway.migrate();

        List<String> applied = appliedVersions(target);
        assertThat(applied)
                .as("V1 is the one baseline-version=1 would silently skip")
                .contains("1", "2", "3");

        // And the schema really exists, not just a history row claiming it does.
        assertThat(tableExists(target, "sandbox_project")).isTrue();
        assertThat(tableExists(target, "sandbox_record")).isTrue();
        assertThat(tableExists(target, "ai_provider_config")).isTrue();
    }

    /** The stray table is what makes the schema non-empty, exactly as Supabase's does. */
    private DataSource databaseWithAStrayTable() throws Exception {
        try (Connection admin = postgres().getPostgresDatabase().getConnection();
             Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + PROBE_DB);
            s.execute("CREATE DATABASE " + PROBE_DB);
        }
        DataSource target = postgres().getDatabase("postgres", PROBE_DB);
        try (Connection c = target.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE a_pre_existing_object (id int)");
        }
        return target;
    }

    private static List<String> appliedVersions(DataSource ds) throws Exception {
        List<String> versions = new ArrayList<>();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private static boolean tableExists(DataSource ds, String table) throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT to_regclass('public." + table + "') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}

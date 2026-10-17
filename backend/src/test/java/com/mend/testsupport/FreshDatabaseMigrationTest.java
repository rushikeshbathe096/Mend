package com.mend.testsupport;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI migrations check (scripts/ci/migrations.sh): a brand-new empty database, Flyway migrate V1..latest, then validate.
 * Independent of the run's shared test database (which Spring contexts may already have migrated). The database uses the
 * mend_test_ prefix, so a leftover from an aborted run is swept by IsolatedTestDatabase after 24 h.
 */
class FreshDatabaseMigrationTest {

    private static final Pattern VERSION = Pattern.compile("^V([0-9._]+)__.+\\.sql$");

    @Test
    @DisplayName("A fresh database migrates V1..latest and validates")
    void freshDatabaseMigratesToLatestAndValidates() throws Exception {
        String serverUrl = IsolatedTestDatabase.Session.setting("mend.test.db.server-url", "MEND_TEST_DB_SERVER_URL",
                "jdbc:postgresql://localhost:5433/postgres");
        String user = IsolatedTestDatabase.Session.setting("mend.test.db.user", "MEND_TEST_DB_USER", "mend_user");
        String password = IsolatedTestDatabase.Session.setting("mend.test.db.password", "MEND_TEST_DB_PASSWORD", "mend_password");
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        String database = IsolatedTestDatabase.PREFIX + Instant.now().getEpochSecond() + "_" + HexFormat.of().formatHex(random);

        try (Connection admin = DriverManager.getConnection(serverUrl, user, password); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE \"" + database + "\"");
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(IsolatedTestDatabase.withDatabase(serverUrl, database), user, password)
                        .locations("classpath:db/migration")
                        .load();

                assertNull(flyway.info().current(), "the database must be empty before the run");
                MigrateResult result = flyway.migrate();
                flyway.validate();

                MigrationInfo current = flyway.info().current();
                assertNotNull(current, "nothing was applied");
                assertEquals(0, flyway.info().pending().length, "no migration left pending");
                assertEquals(flyway.info().applied().length, result.migrationsExecuted, "every applied migration ran in this run");
                assertEquals(latestMigrationFileVersion(), current.getVersion(), "migrated to the highest V<version> file");
                System.out.println("[migrations] fresh database " + database + ": applied " + result.migrationsExecuted
                        + " migrations, current version " + current.getVersion() + ", validate OK");
            } finally {
                sql.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
            }
        }
    }

    private static MigrationVersion latestMigrationFileVersion() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return files.map(p -> VERSION.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> MigrationVersion.fromVersion(m.group(1).replace('_', '.')))
                    .max(MigrationVersion::compareTo)
                    .orElseThrow();
        }
    }
}

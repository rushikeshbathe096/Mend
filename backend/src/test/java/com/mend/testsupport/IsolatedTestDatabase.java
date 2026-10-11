package com.mend.testsupport;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-M3 hygiene: every test run gets its own empty PostgreSQL database, created before the first test class, migrated by
 * Flyway from V1 when the first Spring context starts, and dropped when the run ends (the JUnit root store closes).
 * <p>
 * Registered for every test class through {@code META-INF/services/org.junit.jupiter.api.extension.Extension} with
 * {@code junit.jupiter.extensions.autodetection.enabled=true}. It publishes the URL as the system property
 * {@value #URL_PROPERTY}; {@code application-test.properties} has no fallback, so a test can never reach the dev database.
 * <p>
 * Server coordinates (system property, then environment variable, then default):
 * {@code mend.test.db.server-url} / {@code MEND_TEST_DB_SERVER_URL} (maintenance DB, default
 * {@code jdbc:postgresql://localhost:5433/postgres}), {@code mend.test.db.user} / {@code MEND_TEST_DB_USER},
 * {@code mend.test.db.password} / {@code MEND_TEST_DB_PASSWORD} (defaults: the docker-compose credentials).
 */
public final class IsolatedTestDatabase implements BeforeAllCallback {

    public static final String URL_PROPERTY = "mend.test.datasource.url";
    static final String PREFIX = "mend_test_";
    static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final Pattern NAME = Pattern.compile("^" + PREFIX + "(\\d{10})_[0-9a-f]{8}$");
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(IsolatedTestDatabase.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(NAMESPACE).computeIfAbsent(Session.class, key -> Session.open(), Session.class);
    }

    /** True only for a name this class creates whose timestamp is older than {@link #STALE_AFTER}; never for other databases. */
    static boolean isStale(String databaseName, Instant now) {
        Matcher name = NAME.matcher(databaseName);
        return name.matches() && Long.parseLong(name.group(1)) < now.minus(STALE_AFTER).getEpochSecond();
    }

    /**
     * One per test run (JVM fork): created on first use, dropped when JUnit closes the root store. The admin connection is
     * held for the whole run: at the end the cached Spring contexts still hold their pools, and a new connection could be
     * refused ("too many clients").
     */
    static final class Session implements AutoCloseable {
        private final Connection admin;
        private final String database;

        private Session(Connection admin, String database) {
            this.admin = admin;
            this.database = database;
        }

        static Session open() {
            String serverUrl = setting("mend.test.db.server-url", "MEND_TEST_DB_SERVER_URL", "jdbc:postgresql://localhost:5433/postgres");
            String user = setting("mend.test.db.user", "MEND_TEST_DB_USER", "mend_user");
            String password = setting("mend.test.db.password", "MEND_TEST_DB_PASSWORD", "mend_password");
            byte[] random = new byte[4];
            new SecureRandom().nextBytes(random);
            String database = PREFIX + Instant.now().getEpochSecond() + "_" + HexFormat.of().formatHex(random);
            Connection admin;
            try {
                admin = DriverManager.getConnection(serverUrl, user, password);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot connect to the test PostgreSQL server " + serverUrl
                        + " (is docker-compose postgres running?)", e);
            }
            try (Statement sql = admin.createStatement()) {
                for (String stale : staleDatabases(sql)) {
                    sql.execute("DROP DATABASE IF EXISTS \"" + stale + "\" WITH (FORCE)");
                    System.out.println("[IsolatedTestDatabase] dropped stale test database " + stale);
                }
                sql.execute("CREATE DATABASE \"" + database + "\"");
            } catch (SQLException e) {
                closeQuietly(admin);
                throw new IllegalStateException("cannot create the isolated test database on " + serverUrl, e);
            }
            String url = serverUrl.substring(0, serverUrl.lastIndexOf('/') + 1) + database;
            System.setProperty(URL_PROPERTY, url);
            System.out.println("[IsolatedTestDatabase] created " + database);
            return new Session(admin, database);
        }

        /** Only names this class creates, older than {@link #STALE_AFTER} (a crashed run never dropped them). */
        private static List<String> staleDatabases(Statement sql) throws SQLException {
            List<String> stale = new ArrayList<>();
            Instant now = Instant.now();
            try (ResultSet rows = sql.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE 'mend\\_test\\_%'")) {
                while (rows.next()) {
                    if (isStale(rows.getString(1), now)) {
                        stale.add(rows.getString(1));
                    }
                }
            }
            return stale;
        }

        /** FORCE: Spring's cached contexts still hold pooled connections when JUnit finishes (they close at JVM exit). */
        @Override
        public void close() throws SQLException {
            try (admin; Statement sql = admin.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
            }
            System.clearProperty(URL_PROPERTY);
            System.out.println("[IsolatedTestDatabase] dropped " + database);
        }

        private static void closeQuietly(Connection connection) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already failing; the original error is what matters
            }
        }

        private static String setting(String property, String env, String fallback) {
            String value = System.getProperty(property);
            if (value == null || value.isBlank()) {
                value = System.getenv(env);
            }
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}

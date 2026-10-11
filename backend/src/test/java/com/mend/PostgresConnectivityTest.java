package com.mend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies PostgreSQL connectivity through the application's own DataSource.
 * Pre-M3 hygiene: it used to open {@code localhost:5432/mend} directly and swallow every failure (it could not fail);
 * it now asserts a real query and that the connection is this run's isolated database, never the dev database.
 */
@ActiveProfiles("test")
@SpringBootTest
class PostgresConnectivityTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void postgresConnectivityTest() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            ResultSet one = stmt.executeQuery("SELECT 1");
            assertThat(one.next()).isTrue();
            assertThat(one.getInt(1)).isEqualTo(1);
            ResultSet database = stmt.executeQuery("SELECT current_database()");
            assertThat(database.next()).isTrue();
            assertThat(database.getString(1)).matches("mend_test_\\d{10}_[0-9a-f]{8}");
        }
    }
}

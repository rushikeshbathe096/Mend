package com.mend.integration;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CustomerReply;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.CustomerReplyRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.reply.ReplyParseOutcome;
import com.mend.reply.ReplyParserClient;
import com.mend.reply.ReplyParsingJob;
import com.mend.testsupport.TestRows;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M3 follow-up: every database session runs in UTC. TIMESTAMP (without time zone) columns with DEFAULT CURRENT_TIMESTAMP
 * take the session zone, and pgjdbc sets the session zone from the JVM default at connect. This test forces both
 * possible sources of a local zone, the JVM default and the database default, to Asia/Kolkata (UTC+5:30), opens fresh
 * pooled connections, and checks that DB-defaulted timestamps agree with Java's UTC Instants.
 */
class SessionTimeZoneIntegrationTest extends AbstractIntegrationTest {

    private static final TimeZone KOLKATA = TimeZone.getTimeZone("Asia/Kolkata");

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantRepository merchants;
    @Autowired private CampaignRepository campaigns;
    @Autowired private CustomerReplyRepository replies;
    @Autowired private ReplyParsingJob job;
    @MockitoBean private ReplyParserClient parser;

    private TimeZone jvmZone;
    private UUID merchantId;
    private Campaign campaign;

    @BeforeEach
    void localZoneEverywhere() {
        jvmZone = TimeZone.getDefault();
        TimeZone.setDefault(KOLKATA);
        jdbc.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET timezone TO ''Asia/Kolkata''', current_database()); END $$");
        freshConnections();

        jdbc.update("UPDATE customer_replies SET next_attempt_at = next_attempt_at + interval '30 days' WHERE status = 'PENDING'");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Time zone merchant"));
        Campaign c = new Campaign(UUID.randomUUID(), merchantId);
        c.setPaymentId("pay_tz" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        c.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaigns.saveAndFlush(c);
    }

    @AfterEach
    void restore() {
        TestRows.deleteMerchant(jdbc, merchantId);
        jdbc.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I RESET timezone', current_database()); END $$");
        TimeZone.setDefault(jvmZone);
        freshConnections();
    }

    private void freshConnections() {
        ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
    }

    private UUID insertReplyWithDbDefaults() {
        UUID id = UUID.randomUUID();
        // no next_attempt_at / created_at / updated_at: the DB defaults (CURRENT_TIMESTAMP) fill them in
        jdbc.update("INSERT INTO customer_replies (id, merchant_id, campaign_id, text, status) VALUES (?, ?, ?, 'hello', 'PENDING')",
                id, merchantId, campaign.getId());
        return id;
    }

    @Test
    @DisplayName("Sessions run in UTC even when the JVM and the database default to Asia/Kolkata")
    void sessionZoneIsUtc() {
        assertEquals("UTC", jdbc.queryForObject("SELECT current_setting('TimeZone')", String.class));
    }

    @Test
    @DisplayName("(a) A reply created with the DB default next_attempt_at is due immediately, not 5.5 h later")
    void dbDefaultedReplyIsDueImmediately() {
        when(parser.parse(anyString())).thenReturn(new ReplyParseOutcome.Failed("AI_SERVICE_UNREACHABLE"));
        UUID id = insertReplyWithDbDefaults();

        assertEquals(1, job.runOnce(), "the reply job sees the row as due now");
        assertEquals(1, replies.findById(id).orElseThrow().getAttempts());
    }

    @Test
    @DisplayName("(b) DB now() in a TIMESTAMP column and Java Instant.now() agree within seconds")
    void dbDefaultTimestampMatchesJavaNow() {
        UUID id = insertReplyWithDbDefaults();
        CustomerReply reply = replies.findById(id).orElseThrow();

        Duration createdSkew = Duration.between(reply.getCreatedAt(), Instant.now()).abs();
        Duration nextSkew = Duration.between(reply.getNextAttemptAt(), Instant.now()).abs();
        assertTrue(createdSkew.compareTo(Duration.ofSeconds(10)) < 0, "created_at skew " + createdSkew);
        assertTrue(nextSkew.compareTo(Duration.ofSeconds(10)) < 0, "next_attempt_at skew " + nextSkew);
    }
}

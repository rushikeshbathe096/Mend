package com.mend.integration;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.reply.ReplyExample;
import com.mend.reply.ReplyExampleStore;
import com.mend.reply.ReplyParseOutcome;
import com.mend.reply.ReplyParserClient;
import com.mend.reply.ReplyParsingJob;
import com.mend.service.CustomerReplyService;
import com.mend.testsupport.TestRows;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M4 U4.7 (ADR-004 §8): the gated retrieval experiment. PostgreSQL full-text store of SYNTHETIC / consented examples only;
 * the flag is off by default, and off the job's model call is exactly the M3 one.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReplyRetrievalIntegrationTest {

    @Autowired private ReplyExampleStore store;
    @Autowired private ReplyParsingJob job;
    @Autowired private CustomerReplyService replyService;
    @Autowired private MerchantRepository merchants;
    @Autowired private CampaignRepository campaigns;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ReplyParserClient parser;

    /** The configured dataset version (mend.reply.retrieval.dataset-version); re-imports are idempotent. */
    private static final String DATASET = "replies-v1-dev";
    private UUID merchantId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("UPDATE customer_replies SET next_attempt_at = next_attempt_at + interval '30 days' WHERE status = 'PENDING'");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Retrieval merchant"));
        store.importExamples(DATASET, "SYNTHETIC", devExamples());
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    /** The SYNTHETIC dev split of replies-v1 (never the held-out test split). */
    static List<ReplyExample> devExamples() throws Exception {
        List<ReplyExample> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("../docs/m3/eval/replies-v1-dev.jsonl"))) {
            JsonNode n = JsonMapper.builder().build().readTree(line);
            assertEquals("SYNTHETIC", n.path("label_source").stringValue());
            out.add(new ReplyExample(n.path("text").stringValue(), n.path("intent").stringValue(),
                    n.path("promised_date").isNull() ? null : n.path("promised_date").stringValue()));
        }
        return out;
    }

    @Test
    @DisplayName("Full-text retrieval ranks a matching example first; unrelated text retrieves nothing; k is capped at 5")
    void retrieval() {
        List<ReplyExample> stop = store.similar("please stop messaging me now", 3);
        assertFalse(stop.isEmpty());
        assertEquals("OPT_OUT", stop.get(0).intent());
        assertTrue(stop.size() <= 3);
        assertEquals(List.of(), store.similar("xyzzy qwertyuiop", 3));
        assertEquals(List.of(), store.similar("   ", 3));
        assertTrue(store.similar("I paid the payment already yesterday please stop me", 50).size() <= ReplyExampleStore.MAX_K);
    }

    @Test
    @DisplayName("Special characters and URLs are lexemes, never query syntax; other dataset versions are not searched; ranking is deterministic")
    void queryConstructionIsSafe() {
        for (String hostile : List.of("it's paid' OR '1'='1", "a\\b & c | !d", "stop:* &&", "see https://x.com/a?b=1&c=2", "(", "'")) {
            assertDoesNotThrow(() -> store.similar(hostile, 3), hostile);
        }
        store.importExamples("replies-v1-dev-other", "SYNTHETIC", List.of(new ReplyExample("stop stop stop messaging", "OPT_OUT", null)));
        assertTrue(store.similar("stop messaging", 5).stream().noneMatch(e -> e.text().equals("stop stop stop messaging")),
                "only the configured dataset version is searched");
        assertEquals(store.similar("please stop messaging me now", 5), store.similar("please stop messaging me now", 5));
    }

    @Test
    @DisplayName("Only SYNTHETIC or CONSENTED examples are accepted; the store is append-only; re-import is idempotent")
    void storeRules() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.importExamples(DATASET, "CUSTOMER", List.of(new ReplyExample("x", "OTHER", null))));
        // U4.7 review: CONSENTED needs consent capture first; evaluation datasets never become examples
        assertThrows(IllegalArgumentException.class, () -> store.importExamples(DATASET, "CONSENTED", List.of(new ReplyExample("x", "OTHER", null))));
        assertThrows(IllegalArgumentException.class, () -> store.importExamples("replies-v1-test", "SYNTHETIC", List.of(new ReplyExample("x", "OTHER", null))));
        assertThrows(IllegalArgumentException.class, () -> store.importExamples("replies-v2", "SYNTHETIC", List.of(new ReplyExample("x", "OTHER", null))));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO reply_label_examples (id, dataset_version, source, text, intent) VALUES (?, 'x', 'CUSTOMER_REPLY', 'y', 'OTHER')", UUID.randomUUID()));
        assertEquals(0, store.importExamples(DATASET, "SYNTHETIC", devExamples()));
        DataAccessException e = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE reply_label_examples SET intent = 'OTHER' WHERE dataset_version = ?", DATASET));
        assertTrue(e.getMostSpecificCause().getMessage().contains("APPEND_ONLY"));
    }

    @Test
    @DisplayName("Flag off (default): the job calls parse(text) exactly as in M3, never with examples")
    void flagOffKeepsTheM3Call() {
        when(parser.parse(anyString())).thenReturn(new ReplyParseOutcome.ModelUnavailable());
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_retr" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaigns.saveAndFlush(campaign);
        replyService.submit(merchantId, campaign.getId(), "please stop messaging me", TestUsers.memberOf(merchantId));
        job.runOnce();
        verify(parser).parse("please stop messaging me");
        verify(parser, never()).parse(anyString(), anyList());
    }
}

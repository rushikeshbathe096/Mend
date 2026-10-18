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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** M4 U4.7: with the experiment flag on, the job sends the retrieved examples (the ai-service then uses the few-shot prompt). */
@SpringBootTest(properties = {"mend.reply.retrieval.enabled=true", "mend.reply.retrieval.k=2"})
@ActiveProfiles("test")
class ReplyRetrievalEnabledIntegrationTest {

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private ReplyExampleStore store;
    @Autowired private ReplyParsingJob job;
    @Autowired private CustomerReplyService replyService;
    @Autowired private MerchantRepository merchants;
    @Autowired private CampaignRepository campaigns;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ReplyParserClient parser;

    private UUID merchantId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("UPDATE customer_replies SET next_attempt_at = next_attempt_at + interval '30 days' WHERE status = 'PENDING'");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Retrieval-on merchant"));
        store.importExamples("replies-v1-dev", "SYNTHETIC", ReplyRetrievalIntegrationTest.devExamples());
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    @Test
    @DisplayName("Flag on: parse(text, k examples) with the most similar labelled examples")
    @SuppressWarnings("unchecked")
    void flagOnSendsExamples() {
        when(parser.parse(anyString(), anyList())).thenReturn(new ReplyParseOutcome.ModelUnavailable());
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_ron" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaigns.saveAndFlush(campaign);
        replyService.submit(merchantId, campaign.getId(), "please stop messaging me", TestUsers.memberOf(merchantId));
        job.runOnce();
        ArgumentCaptor<List<ReplyExample>> examples = ArgumentCaptor.forClass(List.class);
        verify(parser).parse(eq("please stop messaging me"), examples.capture());
        assertEquals(2, examples.getValue().size());
        assertEquals("OPT_OUT", examples.getValue().get(0).intent());
        verify(parser, never()).parse(anyString());
    }

    @Test
    @DisplayName("A retrieval failure costs no parse attempt: the reply is parsed without examples")
    void retrievalFailureFallsBack() {
        when(parser.parse(anyString(), anyList())).thenReturn(new ReplyParseOutcome.ModelUnavailable());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down")).when(store).similar(anyString(), anyInt());
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_rfb" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaigns.saveAndFlush(campaign);
        replyService.submit(merchantId, campaign.getId(), "please stop", TestUsers.memberOf(merchantId));
        job.runOnce();
        verify(parser).parse("please stop", List.of());
    }
}

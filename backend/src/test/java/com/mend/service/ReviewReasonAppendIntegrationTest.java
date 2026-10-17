package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.ReviewQueueStatus;
import com.mend.domain.repository.AuditLogRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.ReviewQueueRepository;
import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Bug sweep item 3: a second reason for a campaign with a PENDING review is appended to it, not dropped. */
class ReviewReasonAppendIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private CampaignLifecycleService lifecycle;
    @Autowired private ReviewQueueRepository reviews;
    @Autowired private MerchantRepository merchants;
    @Autowired private AuditLogRepository auditLogs;

    private UUID merchantId;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Review Merchant"));
        campaign = lifecycle.getOrCreateCampaign(merchantId, "PAY-BS3-" + UUID.randomUUID(), "CUST-BS3", null);
    }

    private List<ReviewQueue> pendingReviews() {
        return reviews.findByCampaignId(campaign.getId()).stream().filter(r -> r.getStatus() == ReviewQueueStatus.PENDING).toList();
    }

    @Test
    @DisplayName("A second reason is appended to the PENDING review with a timestamp and its code; the first stays first")
    void secondReasonIsAppended() {
        ReviewQueue first = humanApprovalService.createReview(merchantId, campaign.getId(),
                "DUPLICATE_PAYMENT_RISK: original payment pay_1 captured while plink_1 may be payable");
        ReviewQueue second = humanApprovalService.createReview(merchantId, campaign.getId(),
                "CAPTURED_ON_TERMINAL_CAMPAIGN: intent i1 payment pay_2 captured while campaign is RECOVERED");

        assertEquals(first.getId(), second.getId(), "still one review per campaign");
        assertEquals(1, pendingReviews().size());
        String reason = reviews.findById(first.getId()).orElseThrow().getReason();
        assertTrue(reason.startsWith("DUPLICATE_PAYMENT_RISK: original payment pay_1"), reason);
        assertTrue(reason.matches("(?s).*\\n\\[\\d{4}-\\d{2}-\\d{2}T[^]]+Z] \\[CAPTURED_ON_TERMINAL_CAMPAIGN] "
                + "CAPTURED_ON_TERMINAL_CAMPAIGN: intent i1 payment pay_2.*"), reason);
        assertEquals(second.getReason(), reason, "the returned review carries the appended reason");
        assertTrue(auditLogs.findByCampaignIdOrderByCreatedAtAsc(campaign.getId()).stream()
                .anyMatch(a -> "REVIEW_REASON_APPENDED".equals(a.getEventType())));
    }

    @Test
    @DisplayName("The same reason twice is not appended again (createReview stays idempotent)")
    void sameReasonIsNotDuplicated() {
        String text = "MANUAL_REVIEW_REQUIRED: recovery strategy MANUAL_REVIEW needs a human decision";
        ReviewQueue first = humanApprovalService.createReview(merchantId, campaign.getId(), text);
        humanApprovalService.createReview(merchantId, campaign.getId(), text);
        humanApprovalService.createReview(merchantId, campaign.getId(), text);

        assertEquals(text, reviews.findById(first.getId()).orElseThrow().getReason());
    }

    @Test
    @DisplayName("A reason without a CODE: prefix is appended as UNCODED")
    void uncodedReason() {
        ReviewQueue first = humanApprovalService.createReview(merchantId, campaign.getId(), "OPERATOR_ACTION_REQUIRED:NOT_SENT");
        humanApprovalService.createReview(merchantId, campaign.getId(), "free text from an older caller");

        String reason = reviews.findById(first.getId()).orElseThrow().getReason();
        assertTrue(reason.contains("[UNCODED] free text from an older caller"), reason);
        assertEquals("OPERATOR_ACTION_REQUIRED", HumanApprovalService.reasonCode("OPERATOR_ACTION_REQUIRED:NOT_SENT"));
        assertEquals("UNCODED", HumanApprovalService.reasonCode("lower: case"));
    }

    @Test
    @DisplayName("After the review is resolved, a new reason opens a new review")
    void resolvedReviewGetsANewOne() {
        ReviewQueue first = humanApprovalService.createReview(merchantId, campaign.getId(), "DUPLICATE_PAYMENT_RISK: a");
        first.setStatus(ReviewQueueStatus.APPROVED);
        reviews.saveAndFlush(first);

        ReviewQueue next = humanApprovalService.createReview(merchantId, campaign.getId(), "CAPTURED_ON_TERMINAL_CAMPAIGN: b");

        assertNotEquals(first.getId(), next.getId());
        assertEquals("DUPLICATE_PAYMENT_RISK: a", reviews.findById(first.getId()).orElseThrow().getReason());
        assertEquals("CAPTURED_ON_TERMINAL_CAMPAIGN: b", next.getReason());
    }

    @Test
    @DisplayName("Two reasons appended at the same time are both kept (no lost update)")
    void concurrentAppendsKeepBoth() throws Exception {
        ReviewQueue first = humanApprovalService.createReview(merchantId, campaign.getId(), "DUPLICATE_PAYMENT_RISK: base");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                start.await();
                return humanApprovalService.createReview(merchantId, campaign.getId(), "AMBIGUOUS_PROVIDER_REFERENCE: thread A");
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                return humanApprovalService.createReview(merchantId, campaign.getId(), "CAPTURED_ON_TERMINAL_CAMPAIGN: thread B");
            });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String reason = reviews.findById(first.getId()).orElseThrow().getReason();
        assertTrue(reason.startsWith("DUPLICATE_PAYMENT_RISK: base"), reason);
        assertTrue(reason.contains("AMBIGUOUS_PROVIDER_REFERENCE: thread A"), reason);
        assertTrue(reason.contains("CAPTURED_ON_TERMINAL_CAMPAIGN: thread B"), reason);
        assertEquals(1, pendingReviews().size());
    }
}

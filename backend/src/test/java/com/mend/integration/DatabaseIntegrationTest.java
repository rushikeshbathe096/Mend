package com.mend.integration;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@DisplayName("Database Integration Tests")
public class DatabaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired(required = false)
    private org.flywaydb.core.Flyway flyway;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private MerchantUserRepository merchantUserRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ReviewQueueRepository reviewQueueRepository;

    @Autowired
    private MerchantConfigRepository merchantConfigRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private UUID merchantId;
    private UUID userId;
    private UUID roleId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        roleId = UUID.randomUUID();
    }

    // ============================================================
    // 1. Merchant Persistence and Retrieval
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should persist and retrieve merchant")
    void testMerchantPersistence() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchant.setExternalReference("EXT-001");

        Merchant saved = merchantRepository.save(merchant);
        assertNotNull(saved.getId());
        assertEquals("Test Merchant", saved.getName());
        assertEquals("EXT-001", saved.getExternalReference());

        Merchant retrieved = merchantRepository.findById(merchantId).orElseThrow();
        assertEquals("Test Merchant", retrieved.getName());
    }

    // ============================================================
    // 2. User Email Uniqueness Enforcement
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should enforce email uniqueness constraint")
    void testUserEmailUniqueness() {
        User user1 = new User(UUID.randomUUID(), "test@example.com", "hash1");
        User user2 = new User(UUID.randomUUID(), "test@example.com", "hash2");

        userRepository.save(user1);

        assertThrows(DataIntegrityViolationException.class, () -> {
            userRepository.save(user2);
            userRepository.flush();
        });
    }

    // ============================================================
    // 3. Merchant-User Relationship
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should establish merchant-user relationship")
    void testMerchantUserRelationship() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        User user = new User(userId, "user@example.com", "hash");
        Role role = new Role(roleId, "MERCHANT_ADMIN", "Admin role");

        merchantRepository.save(merchant);
        userRepository.save(user);
        roleRepository.save(role);

        MerchantUser merchantUser = new MerchantUser(UUID.randomUUID(), merchantId, userId, roleId);
        MerchantUser saved = merchantUserRepository.save(merchantUser);

        assertNotNull(saved.getId());
        assertEquals(merchantId, saved.getMerchantId());
        assertEquals(userId, saved.getUserId());
    }

    // ============================================================
    // 4. Duplicate Merchant-User Rejection
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should prevent duplicate merchant-user relationships")
    void testDuplicateMerchantUserRejection() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        User user = new User(userId, "user@example.com", "hash");
        Role role = new Role(roleId, "MERCHANT_ADMIN", "Admin role");

        merchantRepository.save(merchant);
        userRepository.save(user);
        roleRepository.save(role);

        MerchantUser mu1 = new MerchantUser(UUID.randomUUID(), merchantId, userId, roleId);
        merchantUserRepository.save(mu1);

        MerchantUser mu2 = new MerchantUser(UUID.randomUUID(), merchantId, userId, roleId);
        assertThrows(DataIntegrityViolationException.class, () -> {
            merchantUserRepository.save(mu2);
            merchantUserRepository.flush();
        });
    }

    // ============================================================
    // 5. Campaign Persistence
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should persist and retrieve campaign")
    void testCampaignPersistence() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaign.setPaymentId("PAY-001");
        campaign.setCurrentState(CampaignStatus.FAILED);

        Campaign saved = campaignRepository.save(campaign);
        assertNotNull(saved.getId());
        assertEquals(CampaignStatus.FAILED, saved.getCurrentState());

        Campaign retrieved = campaignRepository.findById(campaignId).orElseThrow();
        assertEquals("PAY-001", retrieved.getPaymentId());
    }

    // ============================================================
    // 6. Campaign Optimistic Locking/Version
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should enforce optimistic locking on campaign")
    void testCampaignOptimisticLocking() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        Campaign saved = campaignRepository.save(campaign);

        assertEquals(0, saved.getVersion());

        saved.setStrategy("RETRY");
        campaignRepository.saveAndFlush(saved);
        
        Campaign refreshed = campaignRepository.findById(saved.getId()).orElseThrow();
        assertEquals(1, refreshed.getVersion());
    }

    // ============================================================
    // 7. Campaign Attempt Belongs to Campaign
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should link campaign attempt to campaign")
    void testCampaignAttemptRelationship() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        CampaignAttempt attempt = new CampaignAttempt(UUID.randomUUID(), campaignId, 1);
        attempt.setActionType("RETRY");
        attempt.setStatus("PENDING");

        CampaignAttempt saved = campaignAttemptRepository.save(attempt);
        assertEquals(campaignId, saved.getCampaignId());

        var attempts = campaignAttemptRepository.findByCampaignId(campaignId);
        assertEquals(1, attempts.size());
    }

    // ============================================================
    // 8. Duplicate Campaign Attempt Rejection
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should prevent duplicate campaign attempt numbers")
    void testDuplicateCampaignAttemptRejection() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        CampaignAttempt attempt1 = new CampaignAttempt(UUID.randomUUID(), campaignId, 1);
        campaignAttemptRepository.save(attempt1);

        CampaignAttempt attempt2 = new CampaignAttempt(UUID.randomUUID(), campaignId, 1);
        assertThrows(DataIntegrityViolationException.class, () -> {
            campaignAttemptRepository.save(attempt2);
            campaignAttemptRepository.flush();
        });
    }

    // ============================================================
    // 9. Action Intent Persistence
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should persist action intent")
    void testActionIntentPersistence() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        ActionIntent intent = new ActionIntent(UUID.randomUUID(), campaignId, 1, "WEBHOOK_CALLBACK", "idempotent-key-1");
        intent.setStatus(ActionIntentStatus.PENDING);

        ActionIntent saved = actionIntentRepository.save(intent);
        assertNotNull(saved.getId());
        assertEquals(ActionIntentStatus.PENDING, saved.getStatus());
    }

    // ============================================================
    // 10. Duplicate Idempotency Key Rejection
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should enforce idempotency key uniqueness")
    void testIdempotencyKeyUniqueness() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        ActionIntent intent1 = new ActionIntent(UUID.randomUUID(), campaignId, 1, "WEBHOOK", "dup-key");
        actionIntentRepository.save(intent1);

        ActionIntent intent2 = new ActionIntent(UUID.randomUUID(), campaignId, 1, "WEBHOOK", "dup-key");
        assertThrows(DataIntegrityViolationException.class, () -> {
            actionIntentRepository.save(intent2);
            actionIntentRepository.flush();
        });
    }

    // ============================================================
    // 11. Classification Result Persistence
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should persist classification result")
    void testClassificationResultPersistence() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        ClassificationResult result = new ClassificationResult(
            UUID.randomUUID(),
            campaignId,
            "INSUFFICIENT_FUNDS",
            new BigDecimal("0.95")
        );

        ClassificationResult saved = classificationResultRepository.save(result);
        assertNotNull(saved.getId());
        assertEquals("INSUFFICIENT_FUNDS", saved.getFailureClass());
    }

    // ============================================================
    // 12. Multiple Classification Results (History)
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should allow multiple classification results per campaign")
    void testMultipleClassificationResults() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        ClassificationResult result1 = new ClassificationResult(UUID.randomUUID(), campaignId, "INSUFFICIENT_FUNDS", new BigDecimal("0.90"));
        ClassificationResult result2 = new ClassificationResult(UUID.randomUUID(), campaignId, "BANK_TECHNICAL", new BigDecimal("0.85"));

        classificationResultRepository.save(result1);
        classificationResultRepository.save(result2);

        var results = classificationResultRepository.findByCampaignId(campaignId);
        assertEquals(2, results.size());
    }

    // ============================================================
    // 13. Webhook External Event ID Deduplication
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should enforce external event ID uniqueness")
    void testWebhookEventIdDeduplication() {
        WebhookEvent event1 = new WebhookEvent(UUID.randomUUID(), "EVENT-001", "PAYMENT_FAILED");
        webhookEventRepository.save(event1);

        WebhookEvent event2 = new WebhookEvent(UUID.randomUUID(), "EVENT-001", "PAYMENT_FAILED");
        assertThrows(DataIntegrityViolationException.class, () -> {
            webhookEventRepository.save(event2);
            webhookEventRepository.flush();
        });
    }

    // ============================================================
    // 14. Review Queue Item Persistence
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should persist review queue item")
    void testReviewQueuePersistence() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaignRepository.save(campaign);

        ReviewQueue item = new ReviewQueue(UUID.randomUUID(), campaignId, merchantId);
        item.setStatus(ReviewQueueStatus.PENDING);
        item.setReason("Requires manual review");

        ReviewQueue saved = reviewQueueRepository.save(item);
        assertNotNull(saved.getId());
        assertEquals(ReviewQueueStatus.PENDING, saved.getStatus());
    }

    // ============================================================
    // 15. Merchant Config Uniqueness
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should enforce merchant config uniqueness")
    void testMerchantConfigUniqueness() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        MerchantConfig config1 = new MerchantConfig(UUID.randomUUID(), merchantId);
        merchantConfigRepository.save(config1);

        MerchantConfig config2 = new MerchantConfig(UUID.randomUUID(), merchantId);
        assertThrows(DataIntegrityViolationException.class, () -> {
            merchantConfigRepository.save(config2);
            merchantConfigRepository.flush();
        });
    }

    // ============================================================
    // 16. Audit Log Persistence
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should persist audit log")
    void testAuditLogPersistence() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        AuditLog log = new AuditLog(UUID.randomUUID(), "CAMPAIGN_CREATED");
        log.setMerchantId(merchantId);
        log.setEventType("CAMPAIGN_CREATED");

        AuditLog saved = auditLogRepository.save(log);
        assertNotNull(saved.getId());
        assertEquals("CAMPAIGN_CREATED", saved.getEventType());
    }

    // ============================================================
    // 17. Confidence Outside [0,1] Rejection
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should reject confidence values outside [0,1]")
    void testConfidenceValidation() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);

        // Test confidence > 1
        campaign.setConfidence(new BigDecimal("1.5"));
        assertThrows(DataIntegrityViolationException.class, () -> {
            campaignRepository.save(campaign);
            campaignRepository.flush();
        });
    }

    // ============================================================
    // 18. Negative Attempt Count Rejection
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should reject negative attempt counts")
    void testNegativeAttemptCountRejection() {
        Merchant merchant = new Merchant(merchantId, "Test Merchant");
        merchantRepository.save(merchant);

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign(campaignId, merchantId);
        campaign.setAttemptCount(-1);

        assertThrows(DataIntegrityViolationException.class, () -> {
            campaignRepository.save(campaign);
            campaignRepository.flush();
        });
    }

    // ============================================================
    // 19. Foreign Key Constraint Enforcement
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should enforce foreign key constraints")
    void testForeignKeyConstraintEnforcement() {
        // Try to create campaign with non-existent merchant
        UUID nonExistentMerchantId = UUID.randomUUID();
        Campaign campaign = new Campaign(UUID.randomUUID(), nonExistentMerchantId);

        assertThrows(DataIntegrityViolationException.class, () -> {
            campaignRepository.save(campaign);
            campaignRepository.flush();
        });
    }

    // ============================================================
    // 20. Multi-Tenant Isolation
    // ============================================================
    @Test
    @Transactional
    @DisplayName("Should isolate campaigns by merchant (multi-tenant)")
    void testMultiTenantIsolation() {
        // Create two merchants
        UUID merchant1Id = UUID.randomUUID();
        UUID merchant2Id = UUID.randomUUID();

        Merchant merchant1 = new Merchant(merchant1Id, "Merchant 1");
        Merchant merchant2 = new Merchant(merchant2Id, "Merchant 2");

        merchantRepository.save(merchant1);
        merchantRepository.save(merchant2);

        // Create campaigns for each merchant
        UUID campaign1Id = UUID.randomUUID();
        UUID campaign2Id = UUID.randomUUID();

        Campaign campaign1 = new Campaign(campaign1Id, merchant1Id);
        campaign1.setPaymentId("PAY-M1-001");

        Campaign campaign2 = new Campaign(campaign2Id, merchant2Id);
        campaign2.setPaymentId("PAY-M2-001");

        campaignRepository.save(campaign1);
        campaignRepository.save(campaign2);

        // Verify isolation
        var merchant1Campaigns = campaignRepository.findByMerchantId(merchant1Id);
        var merchant2Campaigns = campaignRepository.findByMerchantId(merchant2Id);

        assertEquals(1, merchant1Campaigns.size());
        assertEquals(1, merchant2Campaigns.size());
        assertEquals(merchant1Id, merchant1Campaigns.get(0).getMerchantId());
        assertEquals(merchant2Id, merchant2Campaigns.get(0).getMerchantId());
    }
}

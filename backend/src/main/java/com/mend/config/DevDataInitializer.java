package com.mend.config;

import com.mend.taxonomy.FailureClassification;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
@ConditionalOnProperty(name = "mend.dev.seed", havingValue = "true")
public class DevDataInitializer implements ApplicationRunner {

    /**
     * Seeded strategy and compliance rows are chosen per customer segment, not produced by RecoveryStrategyEngine or
     * ComplianceEngine, so they carry this label instead of a real policy version (invariant 3).
     */
    static final String SEED_POLICY_VERSION = "dev-seed";


    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final RoleRepository roleRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final CampaignRepository campaignRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final ReviewQueueRepository reviewQueueRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordHasher passwordHasher;

    public DevDataInitializer(
            UserRepository userRepository,
            MerchantRepository merchantRepository,
            MerchantUserRepository merchantUserRepository,
            RoleRepository roleRepository,
            MerchantConfigRepository merchantConfigRepository,
            WebhookEventRepository webhookEventRepository,
            CampaignRepository campaignRepository,
            ClassificationResultRepository classificationResultRepository,
            RecoveryDecisionRepository recoveryDecisionRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            ActionIntentRepository actionIntentRepository,
            ReviewQueueRepository reviewQueueRepository,
            AuditLogRepository auditLogRepository,
            PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.merchantRepository = merchantRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.roleRepository = roleRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.reviewQueueRepository = reviewQueueRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordHasher = passwordHasher;
    }

    private static class MerchantProfile {
        final String name;
        final String ref;
        final String domain;
        MerchantProfile(String name, String ref, String domain) {
            this.name = name;
            this.ref = ref;
            this.domain = domain;
        }
    }

    private static final List<MerchantProfile> MERCHANTS = List.of(
            new MerchantProfile("Test Merchant", "test_merchant", "testmerchant.com"),
            new MerchantProfile("Acme Enterprise", "acme_enterprise", "acme.com"),
            new MerchantProfile("UrbanCart", "urbancart", "urbancart.com"),
            new MerchantProfile("BrightLabs", "brightlabs", "brightlabs.com"),
            new MerchantProfile("CloudMart", "cloudmart", "cloudmart.com"),
            new MerchantProfile("SwiftRetail", "swiftretail", "swiftretail.com"),
            new MerchantProfile("FinEdge", "finedge", "finedge.com"),
            new MerchantProfile("PixelWorks", "pixelworks", "pixelworks.com"),
            new MerchantProfile("GreenBasket", "greenbasket", "greenbasket.com"),
            new MerchantProfile("MetroGoods", "metrogoods", "metrogoods.com"),
            new MerchantProfile("Orbit Services", "orbit_services", "orbit.com"),
            new MerchantProfile("Zenith Commerce", "zenith_commerce", "zenith.com"),
            new MerchantProfile("Nova Digital", "nova_digital", "novadigital.com")
    );

    private static final String[] INDIAN_NAMES = {
            "Aarav Sharma", "Riya Mehta", "Kabir Patel", "Ananya Rao", "Arjun Nair",
            "Diya Verma", "Vivaan Joshi", "Isha Choudhury", "Aditya Kulkarni", "Sanya Gupta",
            "Rohan Iyer", "Neha Bhatnagar", "Karan Kapoor", "Pooja Reddy", "Siddharth Das",
            "Meera Pillai", "Devendra Singh", "Kavya Saxena", "Tarun Malhotra", "Shruti Pandey",
            "Vikram Sengupta", "Tanvi Agarwal", "Manish Nambiar", "Sneha Menon", "Rahul Deshmukh",
            "Deepika Trivedi", "Gaurav Bhatt", "Swati Rao", "Nikhil D'Souza", "Priya Banerjee"
    };

    private static final BigDecimal[] AMOUNT_TIERS = {
            new BigDecimal("299.00"), new BigDecimal("499.00"), new BigDecimal("799.00"),
            new BigDecimal("999.00"), new BigDecimal("1499.00"), new BigDecimal("2499.00"),
            new BigDecimal("4999.00"), new BigDecimal("9999.00"), new BigDecimal("15000.00"),
            new BigDecimal("25000.00"), new BigDecimal("50000.00"), new BigDecimal("75000.00"),
            new BigDecimal("100000.00")
    };

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<Role> adminRoleOpt = roleRepository.findByName("MERCHANT_ADMIN");
        if (adminRoleOpt.isEmpty()) {
            log.warn("MERCHANT_ADMIN role not found. Skipping dev seed initialization.");
            return;
        }

        Role adminRole = adminRoleOpt.get();

        Merchant testMerchantCheck = merchantRepository.findByExternalReference("test_merchant").orElse(null);
        if (auditLogRepository.existsByEventType("MEND_DEMO_DATASET_V1") && testMerchantCheck != null && campaignRepository.countByMerchantId(testMerchantCheck.getId()) >= 20) {
            log.info("Demo dataset already populated (version MEND_DEMO_DATASET_V1, test_merchant campaigns count={}). Skipping seeder.", campaignRepository.countByMerchantId(testMerchantCheck.getId()));
            return;
        }

        log.info("Starting high-fidelity multi-tenant demo dataset initialization (Dataset Version = MEND_DEMO_DATASET_V1, Fixed Seed = 42L)...");
        Random rand = new Random(42L);

        // Ensure default test admin accounts exist
        seedAccount("admin@testmerchant.com", "AdminPass123!", "Test Merchant Admin", "Test Merchant", "test_merchant", adminRole);
        seedAccount("admin@acme.com", "AdminPass123!", "Acme Admin", "Acme Enterprise", "acme_enterprise", adminRole);

        // Seed 12 Merchants with 250+ customers and 800+ transactions
        for (int mIdx = 0; mIdx < MERCHANTS.size(); mIdx++) {
            MerchantProfile profile = MERCHANTS.get(mIdx);
            Merchant merchant = getOrCreateMerchant(profile.name, profile.ref);

            // Merchant Admin & Support Users
            String adminEmail = "admin@" + profile.domain;
            User adminUser = getOrCreateUser(adminEmail, "AdminPass123!", profile.name + " Admin", adminRole, merchant);

            String opsEmail = "ops@" + profile.domain;
            getOrCreateUser(opsEmail, "AdminPass123!", profile.name + " Ops", adminRole, merchant);

            // Merchant Config
            getOrCreateMerchantConfig(merchant);

            // Generate 20 to 25 customers per merchant (~260 customers total)
            int customerCount = 20 + rand.nextInt(6);
            for (int c = 1; c <= customerCount; c++) {
                String customerName = INDIAN_NAMES[(mIdx * 10 + c) % INDIAN_NAMES.length];
                String customerHash = "cust_demo_" + profile.ref + "_" + String.format("%03d", c);

                // Segment allocation:
                // 0: RELIABLE (25%)
                // 1: RECOVERABLE (30%)
                // 2: PAYMENT_FRICTION (20%)
                // 3: HIGH_VALUE (10%)
                // 4: HIGH_RISK (5%)
                // 5: CHURN_RISK / EXHAUSTED (5%)
                // 6: RECENTLY_RECOVERED (5%)
                int segmentChoice = rand.nextInt(100);
                CustomerSegment segment;
                if (segmentChoice < 25) segment = CustomerSegment.RELIABLE;
                else if (segmentChoice < 55) segment = CustomerSegment.RECOVERABLE;
                else if (segmentChoice < 75) segment = CustomerSegment.PAYMENT_FRICTION;
                else if (segmentChoice < 85) segment = CustomerSegment.HIGH_VALUE;
                else if (segmentChoice < 90) segment = CustomerSegment.HIGH_RISK;
                else if (segmentChoice < 95) segment = CustomerSegment.CHURN_RISK;
                else segment = CustomerSegment.RECENTLY_RECOVERED;

                // Generate 1 to 4 failed payment campaigns for this customer
                int campaignCount = (segment == CustomerSegment.RELIABLE) ? 1 : 1 + rand.nextInt(3);
                for (int comp = 0; comp < campaignCount; comp++) {
                    generateCampaignHistory(merchant, adminUser, customerHash, customerName, segment, rand);
                }
            }
        }

        saveAuditLog(null, null, "MEND_DEMO_DATASET_V1", "SYSTEM", null, "Mend high-fidelity multi-tenant demo dataset initialized (MEND_DEMO_DATASET_V1)", Instant.now());
        log.info("Successfully populated Mend with realistic demo dataset (MEND_DEMO_DATASET_V1). Total campaigns: {}, Webhook events: {}, Audit logs: {}",
                campaignRepository.count(), webhookEventRepository.count(), auditLogRepository.count());
    }

    private enum CustomerSegment {
        RELIABLE, RECOVERABLE, PAYMENT_FRICTION, HIGH_VALUE, HIGH_RISK, CHURN_RISK, RECENTLY_RECOVERED
    }

    private void generateCampaignHistory(Merchant merchant, User adminUser, String customerHash, String customerName, CustomerSegment segment, Random rand) {
        // Time distribution over past 180 days
        int daysAgo;
        if (segment == CustomerSegment.RECENTLY_RECOVERED) {
            daysAgo = rand.nextInt(7);
        } else {
            daysAgo = 1 + rand.nextInt(179);
        }

        Instant createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS).minus(rand.nextInt(1440), ChronoUnit.MINUTES);
        Instant updatedAt = createdAt.plus(rand.nextInt(120), ChronoUnit.MINUTES);

        // Transaction Amount selection
        BigDecimal amount;
        if (segment == CustomerSegment.HIGH_VALUE || segment == CustomerSegment.HIGH_RISK) {
            amount = AMOUNT_TIERS[9 + rand.nextInt(4)]; // ₹25k, ₹50k, ₹75k, ₹100k
        } else {
            amount = AMOUNT_TIERS[rand.nextInt(8)]; // ₹299 to ₹9,999
        }

        String paymentId = "pay_demo_" + merchant.getExternalReference() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String externalEventId = paymentId;

        // Determine the simulated Razorpay failure & strategy based on segment; the class comes from FailureTaxonomy (M3)
        String errorReason;
        String errorSource;
        RecoveryStrategy strategy;
        CampaignStatus finalState;
        int attempts;

        switch (segment) {
            case RELIABLE:
                errorReason = "gateway_technical_error"; errorSource = "gateway";
                strategy = RecoveryStrategy.RETRY_IMMEDIATELY;
                finalState = CampaignStatus.RECOVERED;
                attempts = 1;
                break;
            case RECOVERABLE:
                errorReason = "insufficient_funds"; errorSource = "customer";
                strategy = RecoveryStrategy.RETRY_IMMEDIATELY;
                finalState = CampaignStatus.RECOVERED;
                attempts = 1 + rand.nextInt(2);
                break;
            case PAYMENT_FRICTION:
                errorReason = "card_expired"; errorSource = "customer";
                strategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                finalState = CampaignStatus.RECOVERED;
                attempts = 1;
                break;
            case HIGH_VALUE:
                errorReason = rand.nextBoolean() ? "insufficient_funds" : "authentication_failed"; errorSource = "customer";
                strategy = RecoveryStrategy.RETRY_IMMEDIATELY;
                finalState = rand.nextBoolean() ? CampaignStatus.RECOVERED : CampaignStatus.ELIGIBLE;
                attempts = finalState == CampaignStatus.RECOVERED ? 1 : 0;
                break;
            case HIGH_RISK:
                errorReason = "authentication_failed"; errorSource = "customer";
                strategy = RecoveryStrategy.MANUAL_REVIEW;
                finalState = CampaignStatus.ELIGIBLE;
                attempts = 0;
                break;
            case CHURN_RISK:
                errorReason = rand.nextBoolean() ? "card_declined" : "payment_failed"; errorSource = "customer";   // undocumented actor: UNCLASSIFIED
                // M3 decision B: an UNCLASSIFIED failure gets a payment link only; seeded as one unpaid link
                strategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                finalState = CampaignStatus.EXHAUSTED;
                attempts = 1;
                break;
            case RECENTLY_RECOVERED:
            default:
                errorReason = "insufficient_funds"; errorSource = "customer";
                strategy = RecoveryStrategy.RETRY_IMMEDIATELY;
                finalState = CampaignStatus.RECOVERED;
                attempts = 1;
                break;
        }

        ProviderFailure failure = new ProviderFailure("BAD_REQUEST_ERROR", errorReason, errorSource);
        FailureClassification taxonomy = FailureTaxonomy.classify(failure);
        String failureClass = taxonomy.failureClass().name();

        // 1. Save Failed Webhook Event
        String failedPayload = buildWebhookPayload("payment.failed", paymentId, amount, "failed", errorReason, errorSource);
        WebhookEvent failedEvent = new WebhookEvent(UUID.randomUUID(), externalEventId, "payment.failed", "RAZORPAY", failedPayload);
        failedEvent.setMerchantId(merchant.getId());
        failedEvent.setReceivedAt(createdAt);
        failedEvent.setProcessedAt(createdAt.plusSeconds(2));
        failedEvent.setProcessingStatus(WebhookEventStatus.VERIFIED);
        failedEvent.setPublishStatus(WebhookPublishStatus.PUBLISHED);
        failedEvent.setPublishedAt(createdAt.plusSeconds(2));
        webhookEventRepository.save(failedEvent);

        // 2. Save Campaign Entity
        Campaign campaign = new Campaign(UUID.randomUUID(), merchant.getId());
        campaign.setCustomerIdHash(customerHash);
        campaign.setPaymentId(paymentId);
        campaign.setSubscriptionId("sub_demo_" + UUID.randomUUID().toString().substring(0, 8));
        campaign.setFailureClass(failureClass);
        campaign.setStrategy(strategy.name());
        campaign.setCurrentState(finalState);
        campaign.setAttemptCount(attempts);
        campaign.setCreatedAt(createdAt);
        campaign.setUpdatedAt(updatedAt);
        campaignRepository.save(campaign);

        // 3. Classification Result
        // M3: deterministic row (no confidence, no model); was a random 0.70-0.98 confidence and "gemini-1.5-pro"
        ClassificationResult classification = new ClassificationResult(
                UUID.randomUUID(), failedEvent.getId(), campaign.getId(), failureClass, null,
                null, "Razorpay error_reason '" + errorReason + "' (" + taxonomy.matchedOn() + ")", null
        );
        classification.setTaxonomyVersion(taxonomy.tableVersion());
        classification.setEvidence(taxonomy.evidence(failure));
        classification.setCreatedAt(createdAt.plusSeconds(5));
        classificationResultRepository.save(classification);

        // 4. Recovery Decision Entity
        RecoveryDecisionEntity recoveryDecision = new RecoveryDecisionEntity(
                UUID.randomUUID(), campaign.getId(), merchant.getId(), classification.getId(),
                strategy, "Strategy selected based on customer risk segment and failure class " + failureClass,
                (segment == CustomerSegment.HIGH_RISK || segment == CustomerSegment.HIGH_VALUE) ? "HIGH" : "NORMAL",
                null, SEED_POLICY_VERSION
        );
        recoveryDecision.setEvaluatedAt(createdAt.plusSeconds(10));
        recoveryDecisionRepository.save(recoveryDecision);

        // 5. Compliance Decision Entity
        ComplianceStatus compStatus = (segment == CustomerSegment.HIGH_RISK || (segment == CustomerSegment.HIGH_VALUE && amount.compareTo(new BigDecimal("50000")) > 0))
                ? ComplianceStatus.COMPLIANCE_BLOCKED
                : ComplianceStatus.COMPLIANCE_ALLOWED;

        ComplianceReason compReason = compStatus == ComplianceStatus.COMPLIANCE_BLOCKED ? ComplianceReason.MERCHANT_POLICY_BLOCKED
                : ComplianceReason.ALLOWED;

        ComplianceDecisionEntity complianceDecision = new ComplianceDecisionEntity(
                UUID.randomUUID(), campaign.getId(), merchant.getId(), recoveryDecision.getId(),
                strategy, compStatus, compReason,
                compStatus == ComplianceStatus.COMPLIANCE_BLOCKED ? "Transaction amount ₹" + amount.toPlainString() + " requires merchant human approval" : "Compliance policy allowed execution",
                SEED_POLICY_VERSION
        );
        complianceDecision.setEvaluatedAt(createdAt.plusSeconds(15));
        complianceDecisionRepository.save(complianceDecision);

        // 6. Action Intent & Execution (If Allowed)
        if (compStatus == ComplianceStatus.COMPLIANCE_ALLOWED) {
            String actionType = strategy == RecoveryStrategy.CUSTOMER_ACTION_REQUIRED ? "REQUEST_CUSTOMER_ACTION" : "RETRY_PAYMENT";
            ActionIntentStatus intentStatus = finalState == CampaignStatus.RECOVERED ? ActionIntentStatus.SUCCEEDED : ActionIntentStatus.FAILED;

            ActionIntent intent = new ActionIntent(
                    UUID.randomUUID(), merchant.getId(), campaign.getId(), 1, actionType,
                    strategy.name(), complianceDecision.getId(), intentStatus,
                    "intent_demo_" + UUID.randomUUID(), createdAt.plusSeconds(30)
            );
            intent.setWorkerId("demo-worker");
            intent.setCompletedAt(updatedAt);
            intent.setResponseReference("rzp_ref_" + UUID.randomUUID().toString().substring(0, 8));
            actionIntentRepository.save(intent);
        }

        boolean reviewRequired = compStatus == ComplianceStatus.COMPLIANCE_BLOCKED || segment == CustomerSegment.HIGH_RISK;

        // 8. Human Review Queue (If Review Required)
        if (reviewRequired) {
            ReviewQueue review = new ReviewQueue(UUID.randomUUID(), campaign.getId(), merchant.getId());
            review.setReason("High-value recovery (₹" + amount.toPlainString() + ") requires merchant human approval before execution.");
            review.setStatus(finalState == CampaignStatus.RECOVERED ? ReviewQueueStatus.APPROVED : ReviewQueueStatus.PENDING);
            review.setCreatedAt(createdAt.plusSeconds(20));
            if (finalState == CampaignStatus.RECOVERED) {
                review.setReviewedAt(updatedAt);
                review.setAssignedUserId(adminUser.getId());
                review.setReviewerComment("Approved after verifying customer context.");
            }
            reviewQueueRepository.save(review);
        }

        // 9. Audit Log History
        saveAuditLog(merchant.getId(), campaign.getId(), "PAYMENT_FAILED", "SYSTEM", null, "Payment failure ingested for " + paymentId, createdAt);
        saveAuditLog(merchant.getId(), campaign.getId(), "FAILURE_CLASSIFIED", "SYSTEM", null, "Failure classified as " + failureClass, createdAt.plusSeconds(5));
        saveAuditLog(merchant.getId(), campaign.getId(), "POLICY_EVALUATED", "POLICY_ENGINE", null, "Compliance status evaluated as " + compStatus.name(), createdAt.plusSeconds(15));

        if (finalState == CampaignStatus.RECOVERED) {
            // Save Captured Webhook & Recovered Audit Log
            String capPayload = buildWebhookPayload("payment.captured", paymentId, amount, "captured", null, null);
            WebhookEvent capEvent = new WebhookEvent(UUID.randomUUID(), "evt_cap_" + UUID.randomUUID().toString().substring(0, 8), "payment.captured", "RAZORPAY", capPayload);
            capEvent.setMerchantId(merchant.getId());
            capEvent.setReceivedAt(updatedAt);
            capEvent.setProcessedAt(updatedAt.plusSeconds(1));
            capEvent.setProcessingStatus(WebhookEventStatus.VERIFIED);
            capEvent.setPublishStatus(WebhookPublishStatus.PUBLISHED);
            webhookEventRepository.save(capEvent);

            saveAuditLog(merchant.getId(), campaign.getId(), "PAYMENT_RECOVERED", "SYSTEM", null, "Payment " + paymentId + " successfully recovered for ₹" + amount.toPlainString(), updatedAt);
        } else if (finalState == CampaignStatus.EXHAUSTED) {
            saveAuditLog(merchant.getId(), campaign.getId(), "CAMPAIGN_EXHAUSTED", "SYSTEM", null, "Retry attempts exhausted (" + attempts + "/" + attempts + ") for payment " + paymentId, updatedAt);
        }
    }

    private void saveAuditLog(UUID merchantId, UUID campaignId, String eventType, String actorType, UUID actorId, String reason, Instant createdAt) {
        AuditLog logEntity = new AuditLog(UUID.randomUUID(), eventType);
        logEntity.setMerchantId(merchantId);
        logEntity.setCampaignId(campaignId);
        logEntity.setActorType(actorType);
        logEntity.setActorId(actorId);
        logEntity.setReason(reason);
        logEntity.setCreatedAt(createdAt);
        auditLogRepository.save(logEntity);
    }

    private String buildWebhookPayload(String eventType, String paymentId, BigDecimal amount, String status, String errorReason, String errorSource) {
        long amountPaise = amount.movePointRight(2).longValueExact();
        return """
                {"entity":"event","event":"%s","payload":{"payment":{"entity":{"id":"%s","amount":%d,"currency":"INR","status":"%s","method":"card"%s}}}}
                """.formatted(
                eventType, paymentId, amountPaise, status,
                errorReason != null ? ",\"error_code\":\"BAD_REQUEST_ERROR\",\"error_reason\":\"" + errorReason
                        + "\",\"error_source\":\"" + errorSource + "\",\"error_description\":\"demo failure\"" : ""
        );
    }

    private Merchant getOrCreateMerchant(String name, String ref) {
        return merchantRepository.findByExternalReference(ref)
                .orElseGet(() -> {
                    Merchant m = new Merchant(UUID.randomUUID(), name);
                    m.setExternalReference(ref);
                    return merchantRepository.save(m);
                });
    }

    private User getOrCreateUser(String email, String rawPassword, String displayName, Role role, Merchant merchant) {
        String normalizedEmail = email.trim().toLowerCase();
        String hash = passwordHasher.hashPassword(rawPassword);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseGet(() -> userRepository.save(new User(UUID.randomUUID(), normalizedEmail, hash, displayName)));

        if (merchantUserRepository.findByUserId(user.getId()).isEmpty()) {
            merchantUserRepository.save(new MerchantUser(UUID.randomUUID(), merchant.getId(), user.getId(), role.getId()));
        }
        return user;
    }

    private void getOrCreateMerchantConfig(Merchant merchant) {
        if (merchantConfigRepository.findByMerchantId(merchant.getId()).isEmpty()) {
            MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchant.getId());
            config.setMaxAttempts(3);
            config.setMaxContactAttempts(3);
            config.setContactWindowHours(24);
            merchantConfigRepository.save(config);
        }
    }

    private void seedAccount(String email, String rawPassword, String displayName, String merchantName, String externalRef, Role adminRole) {
        Merchant merchant = getOrCreateMerchant(merchantName, externalRef);
        getOrCreateUser(email, rawPassword, displayName, adminRole, merchant);
        getOrCreateMerchantConfig(merchant);
    }
}

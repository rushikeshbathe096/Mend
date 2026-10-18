package com.mend.reply;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.CustomerReply;
import com.mend.domain.entity.ReplySuggestion;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.service.ActionIntentTransitions;
import com.mend.service.AuditService;
import com.mend.service.HumanApprovalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The deterministic policy that owns every effect of a reply suggestion (ADR-003 §5; M3 invariant 17). LLM output never
 * triggers a financial action: no effect creates, approves, schedules, resends or finalizes an intent, and none marks
 * money recovered (invariant 2). An invalid or abstained suggestion has no effect. Audit rows carry ids and codes only,
 * never the reply text (PII).
 */
@Service
public class ReplyPolicy {

    public static final String NO_EFFECT = "NONE";
    public static final String SHOWN_ONLY = "SHOWN_ONLY";
    public static final String CONTACT_OPTED_OUT = "CONTACT_OPTED_OUT";
    public static final String ALREADY_OPTED_OUT = "ALREADY_OPTED_OUT";
    public static final String PAYMENT_LOOKUP_BROUGHT_FORWARD = "PAYMENT_LOOKUP_BROUGHT_FORWARD";
    public static final String REVIEW_CUSTOMER_SAYS_PAID = "REVIEW_CUSTOMER_SAYS_PAID";
    public static final String REVIEW_CUSTOMER_DISPUTE = "REVIEW_CUSTOMER_DISPUTE";

    private final CampaignRepository campaignRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final ActionIntentTransitions transitions;
    private final HumanApprovalService humanApprovalService;
    private final AuditService auditService;
    /** A lookup due sooner than this (a reconcile lease, or a lookup already brought forward) is left alone. */
    private final java.time.Duration minLookupGap;

    public ReplyPolicy(CampaignRepository campaignRepository, ActionIntentRepository actionIntentRepository,
                       ActionIntentTransitions transitions, HumanApprovalService humanApprovalService, AuditService auditService,
                       @org.springframework.beans.factory.annotation.Value("${mend.reconciler.lease-seconds:60}") long reconcileLeaseSeconds) {
        this.minLookupGap = java.time.Duration.ofSeconds(Math.max(reconcileLeaseSeconds, 300));
        this.campaignRepository = campaignRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.transitions = transitions;
        this.humanApprovalService = humanApprovalService;
        this.auditService = auditService;
    }

    /** Applies the suggestion's effect in the caller's transaction and returns the effect code (stored on the suggestion). */
    @Transactional
    public String apply(CustomerReply reply, ReplySuggestion suggestion) {
        if (!suggestion.isValid() || Boolean.TRUE.equals(suggestion.getAbstain())) {
            return NO_EFFECT;
        }
        return switch (suggestion.getIntent()) {
            case OPT_OUT -> optOut(reply, suggestion);
            case ALREADY_PAID -> alreadyPaid(reply, suggestion);
            case DISPUTE -> review(reply, suggestion, "CUSTOMER_DISPUTE: the customer's reply disputes the charge (reply suggestion "
                    + suggestion.getId() + "). Approving resumes collection; reject if the dispute stands.", REVIEW_CUSTOMER_DISPUTE);
            case PROMISE_TO_PAY, OTHER -> SHOWN_ONLY;
        };
    }

    private String optOut(CustomerReply reply, ReplySuggestion suggestion) {
        if (campaignRepository.markContactOptedOut(reply.getCampaignId(), reply.getMerchantId(), Instant.now()) == 0) {
            return ALREADY_OPTED_OUT;
        }
        audit(reply, "CONTACT_OPTED_OUT", suggestion, "code=CUSTOMER_OPTED_OUT");
        return CONTACT_OPTED_OUT;
    }

    /**
     * Only brings forward the evidence lookup the reconciler runs anyway for AWAITING_PAYMENT link intents; the lookup
     * decides on provider evidence alone (invariant 2). NEEDS_RECONCILIATION is never touched, so a reply can never
     * cause a SAFE_RETRY or any provider POST. With no such intent a human looks at it.
     */
    private String alreadyPaid(CustomerReply reply, ReplySuggestion suggestion) {
        List<ActionIntent> awaiting = actionIntentRepository.findByCampaignId(reply.getCampaignId()).stream()
                .filter(i -> i.getMerchantId().equals(reply.getMerchantId()))
                .filter(i -> i.getStatus() == ActionIntentStatus.AWAITING_PAYMENT)
                .toList();
        Instant now = Instant.now();
        for (ActionIntent intent : awaiting) {
            if (transitions.bringForwardPaymentLookup(intent.getId(), now, now.plus(minLookupGap))) {
                audit(reply, "PAYMENT_LOOKUP_BROUGHT_FORWARD", suggestion, "intent=" + intent.getId());
            }
        }
        if (!awaiting.isEmpty()) {
            return PAYMENT_LOOKUP_BROUGHT_FORWARD;
        }
        return review(reply, suggestion, "CUSTOMER_SAYS_PAID: the customer's reply says the payment was made; no payment link "
                + "awaits evidence, so a human checks (reply suggestion " + suggestion.getId() + "). Approving resumes collection; "
                + "reject if the payment is confirmed.", REVIEW_CUSTOMER_SAYS_PAID);
    }

    private String review(CustomerReply reply, ReplySuggestion suggestion, String reason, String effect) {
        humanApprovalService.createReview(reply.getMerchantId(), reply.getCampaignId(), reason);
        audit(reply, "REPLY_REVIEW_REQUESTED", suggestion, "effect=" + effect);
        return effect;
    }

    private void audit(CustomerReply reply, String eventType, ReplySuggestion suggestion, String detail) {
        auditService.logEvent(reply.getMerchantId(), reply.getCampaignId(), eventType, "SYSTEM", null,
                "reply=" + reply.getId() + " suggestion=" + suggestion.getId() + " intent=" + suggestion.getIntent() + " " + detail);
    }
}

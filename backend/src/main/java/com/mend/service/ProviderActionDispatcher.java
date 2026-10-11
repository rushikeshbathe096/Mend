package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.ActionType;
import com.mend.provider.CreatePaymentLink;
import com.mend.provider.MerchantRef;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.ProviderCapabilities;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import com.mend.provider.mock.SyntheticDebitSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Translates an ActionIntent into a provider call: picks the operation by ActionType, derives the attempt key,
 * resolves the amount in minor units. Never auto-retries a state-changing call.
 */
@Service
public class ProviderActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderActionDispatcher.class);

    static final String RECURRING_DEBIT_NOT_ENABLED = "RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2";
    static final String MANUAL_REVIEW_NOT_AUTOMATED = "MANUAL_REVIEW_NOT_AUTOMATED";

    private final PaymentProvider paymentProvider;
    private final ProviderCapabilities capabilities;
    private final WebhookAmountResolver amountResolver;
    /** Mock mode only; resolved once so a wiring mistake fails at startup instead of hiding as an unknown outcome. */
    private final SyntheticDebitSimulator syntheticDebitSimulator;
    /** M2: every link expires, so AWAITING_PAYMENT always terminates. Razorpay: expire_by ≥ 15 min ahead, ≤ 6 months (DOC). */
    private final Duration linkTtl;

    static final Duration MIN_LINK_TTL = Duration.ofMinutes(15);
    static final Duration MAX_LINK_TTL = Duration.ofDays(180);

    /** Default link TTL (72 h, ADR-002 OD-2). */
    public ProviderActionDispatcher(PaymentProvider paymentProvider,
                                    ProviderCapabilities capabilities,
                                    WebhookAmountResolver amountResolver,
                                    ObjectProvider<SyntheticDebitSimulator> syntheticDebitSimulator) {
        this(paymentProvider, capabilities, amountResolver, syntheticDebitSimulator, 72);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderActionDispatcher(PaymentProvider paymentProvider,
                                    ProviderCapabilities capabilities,
                                    WebhookAmountResolver amountResolver,
                                    ObjectProvider<SyntheticDebitSimulator> syntheticDebitSimulator,
                                    @org.springframework.beans.factory.annotation.Value("${mend.recovery.link-ttl-hours:72}") long linkTtlHours) {
        this.linkTtl = Duration.ofHours(linkTtlHours);
        if (linkTtl.compareTo(MIN_LINK_TTL) < 0 || linkTtl.compareTo(MAX_LINK_TTL) > 0) {
            throw new IllegalStateException("mend.recovery.link-ttl-hours must be between 15 minutes and 180 days, was " + linkTtlHours + "h");
        }
        this.paymentProvider = paymentProvider;
        this.capabilities = capabilities;
        this.amountResolver = amountResolver;
        this.syntheticDebitSimulator = syntheticDebitSimulator.getIfAvailable();
    }

    public ProviderActionOutcome dispatch(ActionIntent intent, Campaign campaign) {
        ActionType actionType = parseActionType(intent.getActionType());
        if (actionType == null) {
            return new ProviderActionOutcome.NotSupported("UNSUPPORTED_ACTION_TYPE");
        }
        MerchantRef merchant = new MerchantRef(intent.getMerchantId());
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        return switch (actionType) {
            case REQUEST_CUSTOMER_ACTION -> issuePaymentLink(merchant, key, intent, campaign);
            case RETRY_PAYMENT -> syntheticDebitOrNotSupported(merchant, key, intent, RECURRING_DEBIT_NOT_ENABLED);
            // M2 U9 (design rule 9): manual review never reaches the provider, in any mode; the executor routes it to review.
            case MANUAL_REVIEW -> new ProviderActionOutcome.NotSupported(MANUAL_REVIEW_NOT_AUTOMATED);
        };
    }

    /** Only the provider call itself is wrapped: an exception there may come after the request was sent (invariant 4). */
    private <T> RequestResult<T> callProvider(ActionIntent intent, ProviderAttemptKey key, java.util.function.Supplier<RequestResult<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.error("event=provider_dispatch_exception intent_id={} attempt_key={} exception={}",
                    intent.getId(), key.value(), e.getClass().getSimpleName());
            log.debug("provider_dispatch_exception detail intent_id={}", intent.getId(), e);
            return new RequestResult.OutcomeUnknown<>("UNEXPECTED_PROVIDER_EXCEPTION", null);
        }
    }

    private ProviderActionOutcome issuePaymentLink(MerchantRef merchant, ProviderAttemptKey key, ActionIntent intent, Campaign campaign) {
        CreatePaymentLink command;
        try {
            Optional<MoneyMinor> amount = amountResolver.resolveOriginalAmount(intent.getMerchantId(), campaign.getPaymentId());
            if (amount.isEmpty()) {
                return new ProviderActionOutcome.Rejected(RejectionKind.LOCAL_PRECONDITION, "AMOUNT_UNRESOLVED", null, null);
            }
            command = new CreatePaymentLink(key, amount.get(), "Payment for campaign " + campaign.getId(), Instant.now().plus(linkTtl),
                    intent.getId());
        } catch (RuntimeException e) {
            // Nothing has been sent yet: a local failure, not an unknown provider outcome.
            log.error("event=provider_precall_exception intent_id={} exception={}", intent.getId(), e.getClass().getSimpleName());
            log.debug("provider_precall_exception detail intent_id={}", intent.getId(), e);
            return new ProviderActionOutcome.NotSent("PRE_CALL_EXCEPTION");
        }
        RequestResult<PaymentLinkSnapshot> result = callProvider(intent, key, () -> paymentProvider.createPaymentLink(merchant, command));
        return switch (result) {
            case RequestResult.Accepted<PaymentLinkSnapshot> accepted -> new ProviderActionOutcome.LinkIssued(accepted.value());
            case RequestResult.Rejected<PaymentLinkSnapshot> rejected ->
                    new ProviderActionOutcome.Rejected(rejected.kind(), rejected.providerCode(), rejected.providerReason(), rejected.httpStatus());
            case RequestResult.NotSent<PaymentLinkSnapshot> notSent -> new ProviderActionOutcome.NotSent(notSent.cause());
            case RequestResult.OutcomeUnknown<PaymentLinkSnapshot> unknown -> new ProviderActionOutcome.Unknown(unknown.cause(), unknown.httpStatus());
        };
    }

    private ProviderActionOutcome syntheticDebitOrNotSupported(MerchantRef merchant, ProviderAttemptKey key, ActionIntent intent,
                                                               String notSupportedReason) {
        SyntheticDebitSimulator simulator = capabilities.supportsRecurringDebit() ? syntheticDebitSimulator : null;
        if (simulator == null) {
            return new ProviderActionOutcome.NotSupported(notSupportedReason);
        }
        RequestResult<String> result = callProvider(intent, key, () -> simulator.simulateDebit(merchant, intent.getCampaignId(), intent.getId(), key));
        return switch (result) {
            case RequestResult.Accepted<String> accepted -> new ProviderActionOutcome.SyntheticCaptured(accepted.value());
            case RequestResult.Rejected<String> rejected ->
                    new ProviderActionOutcome.Rejected(rejected.kind(), rejected.providerCode(), rejected.providerReason(), rejected.httpStatus());
            case RequestResult.NotSent<String> notSent -> new ProviderActionOutcome.NotSent(notSent.cause());
            case RequestResult.OutcomeUnknown<String> unknown -> new ProviderActionOutcome.Unknown(unknown.cause(), unknown.httpStatus());
        };
    }

    private static ActionType parseActionType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ActionType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

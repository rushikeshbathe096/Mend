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

import java.util.Optional;

/**
 * Translates an ActionIntent into a provider call: picks the operation by ActionType, derives the attempt key,
 * resolves the amount in minor units. Never auto-retries a state-changing call.
 */
@Service
public class ProviderActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderActionDispatcher.class);

    static final String RECURRING_DEBIT_NOT_ENABLED = "RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2";

    private final PaymentProvider paymentProvider;
    private final ProviderCapabilities capabilities;
    private final WebhookAmountResolver amountResolver;
    private final ObjectProvider<SyntheticDebitSimulator> syntheticDebitSimulator;

    public ProviderActionDispatcher(PaymentProvider paymentProvider,
                                    ProviderCapabilities capabilities,
                                    WebhookAmountResolver amountResolver,
                                    ObjectProvider<SyntheticDebitSimulator> syntheticDebitSimulator) {
        this.paymentProvider = paymentProvider;
        this.capabilities = capabilities;
        this.amountResolver = amountResolver;
        this.syntheticDebitSimulator = syntheticDebitSimulator;
    }

    public ProviderActionOutcome dispatch(ActionIntent intent, Campaign campaign) {
        ActionType actionType = parseActionType(intent.getActionType());
        if (actionType == null) {
            return new ProviderActionOutcome.NotSupported("UNSUPPORTED_ACTION_TYPE");
        }
        MerchantRef merchant = new MerchantRef(intent.getMerchantId());
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        try {
            return switch (actionType) {
                case REQUEST_CUSTOMER_ACTION -> issuePaymentLink(merchant, key, intent, campaign);
                case RETRY_PAYMENT -> syntheticDebitOrNotSupported(merchant, key, intent, RECURRING_DEBIT_NOT_ENABLED);
                // Unchanged behaviour: mock mode simulates it as before; Razorpay mode never automates manual review.
                case MANUAL_REVIEW -> syntheticDebitOrNotSupported(merchant, key, intent, "MANUAL_REVIEW_NOT_AUTOMATED");
            };
        } catch (RuntimeException e) {
            // The call may have reached the provider before failing: unknown, never failed (invariant 4).
            log.error("event=provider_dispatch_exception intent_id={} attempt_key={} exception={}",
                    intent.getId(), key.value(), e.getClass().getSimpleName());
            return new ProviderActionOutcome.Unknown("UNEXPECTED_PROVIDER_EXCEPTION", null);
        }
    }

    private ProviderActionOutcome issuePaymentLink(MerchantRef merchant, ProviderAttemptKey key, ActionIntent intent, Campaign campaign) {
        Optional<MoneyMinor> amount = amountResolver.resolveOriginalAmount(intent.getMerchantId(), campaign.getPaymentId());
        if (amount.isEmpty()) {
            return new ProviderActionOutcome.Rejected(RejectionKind.LOCAL_PRECONDITION, "AMOUNT_UNRESOLVED", null, null);
        }
        CreatePaymentLink command = new CreatePaymentLink(key, amount.get(), "Payment for campaign " + campaign.getId(), null, intent.getId());
        RequestResult<PaymentLinkSnapshot> result = paymentProvider.createPaymentLink(merchant, command);
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
        SyntheticDebitSimulator simulator = capabilities.supportsRecurringDebit() ? syntheticDebitSimulator.getIfAvailable() : null;
        if (simulator == null) {
            return new ProviderActionOutcome.NotSupported(notSupportedReason);
        }
        RequestResult<String> result = simulator.simulateDebit(merchant, intent.getCampaignId(), intent.getId(), key);
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

package com.mend.provider.mock;

import com.mend.domain.enums.ActionType;
import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RequestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("M2 U5: mock provider reference uniqueness, lookups and resend capability")
class MockPaymentProviderM2Test {

    private final MockPaymentProvider mock = new MockPaymentProvider();
    private final MerchantRef merchant = new MerchantRef(UUID.randomUUID());
    private final UUID intentId = UUID.randomUUID();
    private final ProviderAttemptKey key = ProviderAttemptKey.forIntent(intentId);

    private CreatePaymentLink command() {
        return new CreatePaymentLink(key, new MoneyMinor(49900, "INR"), "test", null, intentId);
    }

    @Test
    void resendCapabilityIsLinksOnly() {
        assertTrue(mock.supportsIdempotentResend(ActionType.REQUEST_CUSTOMER_ACTION));
        assertFalse(mock.supportsIdempotentResend(ActionType.RETRY_PAYMENT), "synthetic debit has no idempotency");
        assertFalse(mock.supportsIdempotentResend(ActionType.MANUAL_REVIEW));
    }

    @Test
    void secondCreateWithTheSameReferenceReturnsTheExistingLinkLikeFixture02() {
        PaymentLinkSnapshot first = ((RequestResult.Accepted<PaymentLinkSnapshot>) mock.createPaymentLink(merchant, command())).value();
        mock.setLinkState(key, PaymentLinkState.PARTIALLY_PAID);
        PaymentLinkSnapshot second = ((RequestResult.Accepted<PaymentLinkSnapshot>) mock.createPaymentLink(merchant, command())).value();

        assertEquals(first.providerLinkId(), second.providerLinkId());
        assertEquals(PaymentLinkState.PARTIALLY_PAID, second.state(), "no second link: the existing one is returned");
        assertTrue(first.providerLinkId().startsWith("plink_mock_"), "SYNTHETIC id prefix");
    }

    @Test
    void lostResponseStoresTheLinkButAnswersUnknown() {
        mock.setSimulatedLostResponse(true);
        assertInstanceOf(RequestResult.OutcomeUnknown.class, mock.createPaymentLink(merchant, command()));
        assertInstanceOf(LookupResult.Found.class, mock.findPaymentLinkByReference(merchant, key));
    }

    @Test
    void paymentEvidenceExistsOnlyForSyntheticLinkPayments() {
        assertInstanceOf(LookupResult.NotFound.class, mock.getPayment(merchant, "pay_anything"));
        mock.createPaymentLink(merchant, command());

        String authorized = mock.payLink(key, PaymentEvidenceStatus.AUTHORIZED_NOT_CAPTURED);
        assertEquals(PaymentLinkState.CREATED, ((LookupResult.Found<PaymentLinkSnapshot>) mock.findPaymentLinkByReference(merchant, key)).value().state(),
                "an authorized payment does not pay the link");
        assertEquals(PaymentEvidenceStatus.AUTHORIZED_NOT_CAPTURED,
                ((LookupResult.Found<PaymentEvidence>) mock.getPayment(merchant, authorized)).value().status());

        String captured = mock.payLink(key, PaymentEvidenceStatus.CAPTURED);
        PaymentLinkSnapshot paid = ((LookupResult.Found<PaymentLinkSnapshot>) mock.getPaymentLink(merchant,
                "plink_mock_" + key.value().replace("mnd_", ""))).value();
        assertEquals(PaymentLinkState.PAID, paid.state());
        assertEquals(java.util.List.of(captured), paid.capturedPaymentIds());
    }

    @Test
    void lookupUnavailableKnob() {
        mock.setSimulatedLookupUnavailable(true);
        assertInstanceOf(LookupResult.Unavailable.class, mock.findPaymentLinkByReference(merchant, key));
        assertInstanceOf(LookupResult.Unavailable.class, mock.getPaymentLink(merchant, "plink_mock_x"));
        assertInstanceOf(LookupResult.Unavailable.class, mock.getPayment(merchant, "pay_x"));
        mock.reset();
        assertInstanceOf(LookupResult.NotFound.class, mock.findPaymentLinkByReference(merchant, key));
    }
}

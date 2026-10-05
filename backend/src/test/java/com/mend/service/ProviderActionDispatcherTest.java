package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentProvider;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.provider.mock.SyntheticDebitSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderActionDispatcherTest {

    private final UUID merchantId = UUID.randomUUID();
    private final Campaign campaign = campaignWithPayment();

    private Campaign campaignWithPayment() {
        Campaign c = new Campaign(UUID.randomUUID(), merchantId);
        c.setPaymentId("pay_dispatch_1");
        return c;
    }

    private ActionIntent intent(ActionType type) {
        return new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, type.name(), "S", null,
                ActionIntentStatus.CLAIMED, "intent:x:attempt_1:" + type.name(), Instant.now());
    }

    private static ProviderActionDispatcher dispatcher(PaymentProvider provider, WebhookAmountResolver resolver, MockPaymentProvider mock) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (mock != null) {
            beans.addBean("mock", mock);
        }
        return new ProviderActionDispatcher(provider, () -> mock != null, resolver, beans.getBeanProvider(SyntheticDebitSimulator.class));
    }

    @Test
    void exceptionBeforeTheProviderCallIsNotSentAndMakesNoCall() {
        PaymentProvider provider = mock(PaymentProvider.class);
        WebhookAmountResolver resolver = mock(WebhookAmountResolver.class);
        when(resolver.resolveOriginalAmount(any(), any())).thenThrow(new IllegalStateException("db down"));

        ProviderActionOutcome outcome = dispatcher(provider, resolver, null).dispatch(intent(ActionType.REQUEST_CUSTOMER_ACTION), campaign);

        assertEquals(new ProviderActionOutcome.NotSent("PRE_CALL_EXCEPTION"), outcome);
        verifyNoInteractions(provider);
    }

    @Test
    void exceptionFromTheProviderCallIsUnknown() {
        PaymentProvider provider = mock(PaymentProvider.class);
        when(provider.createPaymentLink(any(), any())).thenThrow(new IllegalStateException("socket reset"));
        WebhookAmountResolver resolver = mock(WebhookAmountResolver.class);
        when(resolver.resolveOriginalAmount(any(), any())).thenReturn(Optional.of(new MoneyMinor(49900, "INR")));

        ProviderActionOutcome outcome = dispatcher(provider, resolver, null).dispatch(intent(ActionType.REQUEST_CUSTOMER_ACTION), campaign);

        assertEquals(new ProviderActionOutcome.Unknown("UNEXPECTED_PROVIDER_EXCEPTION", null), outcome);
    }

    @Test
    void mockPendingKnobIsUnknownNotSuccess() {
        MockPaymentProvider mock = new MockPaymentProvider();
        mock.setSimulatedStatus(PaymentExecutionStatus.PENDING);

        ProviderActionOutcome outcome = dispatcher(mock, mock(WebhookAmountResolver.class), mock).dispatch(intent(ActionType.RETRY_PAYMENT), campaign);

        assertInstanceOf(ProviderActionOutcome.Unknown.class, outcome);
    }
}

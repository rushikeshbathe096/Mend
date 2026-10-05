package com.mend.client;

import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.ProviderCapabilities;
import com.mend.provider.RequestResult;
import com.mend.provider.mock.MockPaymentProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@DisplayName("Payment Provider Client Spring Context Integration Test")
public class PaymentProviderClientIntegrationTest {

    @Autowired
    private PaymentProvider paymentProvider;

    @Autowired
    private ProviderCapabilities providerCapabilities;

    @Test
    @DisplayName("Spring context should inject PaymentProviderClient interface as MockPaymentProviderClient bean")
    void testPaymentProviderClientBeanInjection() {
        // M1 behaviour change: the port is PaymentProvider and the default bean is MockPaymentProvider, see ADR-001
        assertNotNull(paymentProvider);
        assertInstanceOf(MockPaymentProvider.class, paymentProvider);
        assertTrue(providerCapabilities.supportsRecurringDebit());

        UUID intentId = UUID.randomUUID();
        RequestResult<String> result = ((MockPaymentProvider) paymentProvider).simulateDebit(
                new MerchantRef(UUID.randomUUID()), UUID.randomUUID(), intentId, ProviderAttemptKey.forIntent(intentId));

        assertInstanceOf(RequestResult.Accepted.class, result);
    }
}

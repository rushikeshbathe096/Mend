package com.mend.client;

import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderCapabilities;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.provider.razorpay.RazorpayPaymentProvider;

import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Payment Provider Spring Selection Tests")
public class RazorpayProviderSelectionTest {

    @SpringBootTest(properties = "mend.payment.provider=mock")
    static class MockProviderSelectionTest extends AbstractIntegrationTest {

        @Autowired
        private PaymentProvider paymentProvider;

        @Autowired
        private ProviderCapabilities providerCapabilities;

        @Test
        @DisplayName("Should inject MockPaymentProviderClient when mend.payment.provider=mock")
        void testMockProviderSelected() {
            // M1 behaviour change: the port is PaymentProvider; mock mode selects MockPaymentProvider, see ADR-001
            assertNotNull(paymentProvider);
            assertInstanceOf(MockPaymentProvider.class, paymentProvider);
            assertTrue(providerCapabilities.supportsRecurringDebit());
        }
    }

    @SpringBootTest(properties = "mend.payment.provider=razorpay")
    static class RazorpayProviderSelectionTestClass extends AbstractIntegrationTest {

        @Autowired
        private PaymentProvider paymentProvider;

        @Autowired
        private ProviderCapabilities providerCapabilities;

        @Test
        @DisplayName("Should inject RazorpayPaymentProviderClient when mend.payment.provider=razorpay")
        void testRazorpayProviderSelected() {
            // M1 behaviour change: razorpay mode selects RazorpayPaymentProvider and disables recurring debit, see ADR-001
            assertNotNull(paymentProvider);
            assertInstanceOf(RazorpayPaymentProvider.class, paymentProvider);
            assertFalse(providerCapabilities.supportsRecurringDebit());
        }
    }
}

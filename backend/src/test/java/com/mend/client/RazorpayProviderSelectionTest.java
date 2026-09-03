package com.mend.client;

import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Payment Provider Spring Selection Tests")
public class RazorpayProviderSelectionTest {

    @SpringBootTest(properties = "mend.payment.provider=mock")
    static class MockProviderSelectionTest extends AbstractIntegrationTest {

        @Autowired
        private PaymentProviderClient paymentProviderClient;

        @Test
        @DisplayName("Should inject MockPaymentProviderClient when mend.payment.provider=mock")
        void testMockProviderSelected() {
            assertNotNull(paymentProviderClient);
            assertInstanceOf(MockPaymentProviderClient.class, paymentProviderClient);
        }
    }

    @SpringBootTest(properties = "mend.payment.provider=razorpay")
    static class RazorpayProviderSelectionTestClass extends AbstractIntegrationTest {

        @Autowired
        private PaymentProviderClient paymentProviderClient;

        @Test
        @DisplayName("Should inject RazorpayPaymentProviderClient when mend.payment.provider=razorpay")
        void testRazorpayProviderSelected() {
            assertNotNull(paymentProviderClient);
            assertInstanceOf(RazorpayPaymentProviderClient.class, paymentProviderClient);
        }
    }
}

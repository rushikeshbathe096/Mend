package com.mend.client;

import com.mend.domain.enums.ActionType;
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
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
    private PaymentProviderClient paymentProviderClient;

    @Test
    @DisplayName("Spring context should inject PaymentProviderClient interface as MockPaymentProviderClient bean")
    void testPaymentProviderClientBeanInjection() {
        assertNotNull(paymentProviderClient);
        assertTrue(paymentProviderClient instanceof MockPaymentProviderClient);

        PaymentExecutionRequest request = new PaymentExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "pay_integration_test",
                "sub_integration_test",
                ActionType.RETRY_PAYMENT,
                1,
                "intent:integration:attempt_1:RETRY_PAYMENT"
        );

        PaymentExecutionResult result = paymentProviderClient.executeAction(request);

        assertNotNull(result);
        assertEquals(PaymentExecutionStatus.SUCCESS, result.getStatus());
        assertTrue(result.isSuccess());
        assertEquals("intent:integration:attempt_1:RETRY_PAYMENT", result.getIdempotencyKey());
    }
}

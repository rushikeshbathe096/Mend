package com.mend.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.domain.enums.ActionType;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Razorpay Payment Provider Client Unit Tests")
public class RazorpayPaymentProviderClientTest {

    private RestTemplate restTemplate;
    private MerchantConfigRepository merchantConfigRepository;
    private ObjectMapper objectMapper;
    private RazorpayPaymentProviderClient client;

    private final String testBaseUrl = "https://api.razorpay.com";
    private final String testKeyId = "rzp_test_12345678";
    private final String testKeySecret = "secret_1234567890abcdef";

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        merchantConfigRepository = mock(MerchantConfigRepository.class);
        objectMapper = new ObjectMapper();

        client = new RazorpayPaymentProviderClient(
                testBaseUrl,
                testKeyId,
                testKeySecret,
                restTemplate,
                merchantConfigRepository,
                objectMapper
        );
    }

    private PaymentExecutionRequest createRequest(ActionType actionType, String idempotencyKey) {
        return new PaymentExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "pay_test_orig_100",
                "sub_test_orig_200",
                actionType,
                1,
                idempotencyKey
        );
    }

    @Test
    @DisplayName("Should return ERROR when required Razorpay credentials are missing")
    void testMissingCredentials() {
        RazorpayPaymentProviderClient unconfiguredClient = new RazorpayPaymentProviderClient(
                testBaseUrl, "", "", restTemplate, merchantConfigRepository, objectMapper
        );

        PaymentExecutionRequest req = createRequest(ActionType.RETRY_PAYMENT, "key_test_missing_creds");
        PaymentExecutionResult result = unconfiguredClient.executeAction(req);

        assertNotNull(result);
        assertEquals(PaymentExecutionStatus.ERROR, result.getStatus());
        assertTrue(result.getMessage().contains("Missing required Razorpay credentials"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Should successfully execute payment retry and map 200 OK provider response")
    void testSuccessfulRazorpayExecution() {
        PaymentExecutionRequest req = createRequest(ActionType.RETRY_PAYMENT, "intent:camp_100:attempt_1");

        String mockResponseBody = "{\"id\": \"pay_rzp_success_999\", \"status\": \"captured\", \"amount\": 5000}";
        ResponseEntity<String> mockResponse = new ResponseEntity<>(mockResponseBody, HttpStatus.OK);

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(mockResponse);

        PaymentExecutionResult result = client.executeAction(req);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("pay_rzp_success_999", result.getExternalReference());
        assertEquals("intent:camp_100:attempt_1", result.getIdempotencyKey());
        assertTrue(result.getMessage().contains("Status: captured"));

        verify(restTemplate, times(1)).postForEntity(
                eq("https://api.razorpay.com/v1/payments/pay_test_orig_100/retry"),
                any(HttpEntity.class),
                eq(String.class)
        );
    }

    @Test
    @DisplayName("Should propagate Mend idempotencyKey in request headers")
    void testIdempotencyKeyPropagation() {
        String key = "intent:camp_200:attempt_1:RETRY";
        PaymentExecutionRequest req = createRequest(ActionType.RETRY_PAYMENT, key);

        String mockResponseBody = "{\"id\": \"pay_rzp_777\", \"status\": \"captured\"}";
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(mockResponseBody, HttpStatus.OK));

        client.executeAction(req);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), entityCaptor.capture(), eq(String.class));

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertEquals(key, headers.getFirst("X-Razorpay-Idempotency-Header"));
        assertEquals(key, headers.getFirst("X-Idempotency-Key"));
        assertNotNull(headers.getFirst("Authorization"));
        assertTrue(headers.getFirst("Authorization").startsWith("Basic "));
    }

    @Test
    @DisplayName("Should handle 4xx payment decline cleanly and return FAILURE status")
    void testProviderDecline() {
        PaymentExecutionRequest req = createRequest(ActionType.RETRY_PAYMENT, "intent:camp_300:attempt_1");

        String errorJson = "{\"error\": {\"code\": \"BAD_REQUEST_ERROR\", \"description\": \"Card has expired\"}}";
        HttpClientErrorException declineException = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), errorJson.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8
        );

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(declineException);

        PaymentExecutionResult result = client.executeAction(req);

        assertNotNull(result);
        assertTrue(result.isFailure());
        assertEquals("BAD_REQUEST_ERROR", result.getResponseCode());
        assertEquals("Card has expired", result.getMessage());
        assertEquals("intent:camp_300:attempt_1", result.getIdempotencyKey());
    }

    @Test
    @DisplayName("Should handle gateway connection timeout and return ERROR status")
    void testProviderTimeout() {
        PaymentExecutionRequest req = createRequest(ActionType.RETRY_PAYMENT, "intent:camp_400:attempt_1");

        ResourceAccessException timeoutException = new ResourceAccessException("Read timed out executing POST https://api.razorpay.com");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(timeoutException);

        PaymentExecutionResult result = client.executeAction(req);

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("Razorpay gateway connection error/timeout"));
        assertEquals("intent:camp_400:attempt_1", result.getIdempotencyKey());
    }

    @Test
    @DisplayName("Should handle 5xx server error and return ERROR status (not FAILURE)")
    void testServerError() {
        PaymentExecutionRequest req = createRequest(ActionType.RETRY_PAYMENT, "intent:camp_500:attempt_1");

        HttpServerErrorException serverException = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8
        );

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(serverException);

        PaymentExecutionResult result = client.executeAction(req);

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("HTTP 500"));
    }

    @Test
    @DisplayName("Should reject unsupported action type (MANUAL_REVIEW) without calling Razorpay API")
    void testUnsupportedActionType() {
        PaymentExecutionRequest req = createRequest(ActionType.MANUAL_REVIEW, "intent:camp_600:attempt_1");

        PaymentExecutionResult result = client.executeAction(req);

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(result.getMessage().contains("is not supported for automated Razorpay API execution"));
        verifyNoInteractions(restTemplate);
    }
}

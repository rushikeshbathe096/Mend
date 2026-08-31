package com.mend;

import com.mend.config.RazorpayWebhookProperties;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.security.RazorpaySignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebhookIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RazorpaySignatureVerifier signatureVerifier;

    @Autowired
    private RazorpayWebhookProperties webhookProperties;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    private HttpClient httpClient;
    private Merchant testMerchant;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        webhookEventRepository.deleteAll();

        String externalRef = "acc_integration_test_" + UUID.randomUUID().toString().substring(0, 8);
        Merchant merchant = new Merchant(UUID.randomUUID(), "Integration Merchant");
        merchant.setExternalReference(externalRef);
        merchant.setStatus("ACTIVE");
        testMerchant = merchantRepository.save(merchant);
    }

    @Test
    void razorpayWebhook_ValidSignature_ProcessesAndPersistsEvent() throws Exception {
        String eventId = "evt_test_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format(
                "{\"event_id\":\"%s\",\"event\":\"payment.failed\",\"account_id\":\"%s\",\"created_at\":1700000000}",
                eventId, testMerchant.getExternalReference()
        );

        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
        assertTrue(response.body().contains("ACCEPTED"));

        // Verify database persistence
        List<WebhookEvent> events = webhookEventRepository.findAll();
        assertEquals(1, events.size());

        WebhookEvent event = events.get(0);
        assertEquals(eventId, event.getExternalEventId());
        assertEquals("payment.failed", event.getEventType());
        assertEquals("RAZORPAY", event.getSource());
        assertEquals(payload, event.getRawPayload());
        assertEquals(WebhookEventStatus.VERIFIED, event.getProcessingStatus());
        assertEquals(testMerchant.getId(), event.getMerchantId());
    }

    @Test
    void razorpayWebhook_InvalidSignature_Returns401() throws Exception {
        String payload = "{\"event_id\":\"evt_invalid_sig\",\"event\":\"payment.failed\"}";
        String badSignature = "invalid_signature_hash_value";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", badSignature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertEquals(0, webhookEventRepository.count());
    }

    @Test
    void razorpayWebhook_Idempotency_DuplicateEventNotReProcessed() throws Exception {
        String eventId = "evt_idempotency_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("{\"event_id\":\"%s\",\"event\":\"payment.failed\"}", eventId);
        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        HttpRequest request1 = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        // First attempt -> ACCEPTED
        HttpResponse<String> response1 = httpClient.send(request1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response1.statusCode());
        assertTrue(response1.body().contains("ACCEPTED"));

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        // Second attempt -> DUPLICATE
        HttpResponse<String> response2 = httpClient.send(request2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response2.statusCode());
        assertTrue(response2.body().contains("DUPLICATE"));

        // Verify only 1 row exists in DB
        List<WebhookEvent> events = webhookEventRepository.findAll();
        assertEquals(1, events.size());
        assertEquals(eventId, events.get(0).getExternalEventId());
    }

    @Test
    void securityIsolation_ProtectedEndpointRequiresJwt() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/merchants"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    @Test
    void tenantIsolation_IgnoresHeaderMerchantId() throws Exception {
        String eventId = "evt_tenant_header_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("{\"event_id\":\"%s\",\"event\":\"payment.failed\"}", eventId);
        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        UUID fakeHeaderMerchantId = UUID.randomUUID();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .header("X-Merchant-Id", fakeHeaderMerchantId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        List<WebhookEvent> events = webhookEventRepository.findAll();
        assertEquals(1, events.size());
        // Header should NOT be used to assign merchantId
        assertNull(events.get(0).getMerchantId());
    }
}

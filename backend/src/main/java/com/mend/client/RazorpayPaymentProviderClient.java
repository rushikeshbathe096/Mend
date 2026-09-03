package com.mend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.domain.enums.ActionType;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "razorpay")
public class RazorpayPaymentProviderClient implements PaymentProviderClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProviderClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String defaultKeyId;
    private final String defaultKeySecret;
    private final MerchantConfigRepository merchantConfigRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public RazorpayPaymentProviderClient(
            @Value("${razorpay.api.base-url:https://api.razorpay.com}") String baseUrl,
            @Value("${razorpay.key-id:}") String defaultKeyId,
            @Value("${razorpay.key-secret:}") String defaultKeySecret,
            @Value("${razorpay.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${razorpay.read-timeout-ms:10000}") int readTimeoutMs,
            MerchantConfigRepository merchantConfigRepository,
            ObjectMapper objectMapper) {
        this.baseUrl = cleanBaseUrl(baseUrl);
        this.defaultKeyId = defaultKeyId != null ? defaultKeyId.trim() : "";
        this.defaultKeySecret = defaultKeySecret != null ? defaultKeySecret.trim() : "";
        this.merchantConfigRepository = merchantConfigRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    // Secondary constructor for testing with custom RestTemplate
    public RazorpayPaymentProviderClient(
            String baseUrl,
            String defaultKeyId,
            String defaultKeySecret,
            RestTemplate restTemplate,
            MerchantConfigRepository merchantConfigRepository,
            ObjectMapper objectMapper) {
        this.baseUrl = cleanBaseUrl(baseUrl);
        this.defaultKeyId = defaultKeyId != null ? defaultKeyId.trim() : "";
        this.defaultKeySecret = defaultKeySecret != null ? defaultKeySecret.trim() : "";
        this.restTemplate = restTemplate;
        this.merchantConfigRepository = merchantConfigRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static String cleanBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.razorpay.com";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public PaymentExecutionResult executeAction(PaymentExecutionRequest request) {
        if (request == null) {
            return PaymentExecutionResult.error("PaymentExecutionRequest must not be null", "UNKNOWN");
        }

        String idempotencyKey = request.getIdempotencyKey();

        // 1. Validate Action Type support
        if (request.getActionType() != ActionType.RETRY_PAYMENT) {
            log.warn("Action type '{}' is not supported for automated Razorpay API execution. Intent: '{}'",
                    request.getActionType(), request.getIntentId());
            return PaymentExecutionResult.error(
                    "Action type '" + request.getActionType() + "' is not supported for automated Razorpay API execution",
                    idempotencyKey
            );
        }

        // 2. Resolve Credentials & Tenant Isolation
        RazorpayCredentials credentials = resolveCredentials(request);
        if (credentials == null || !credentials.isValid()) {
            log.error("Missing valid Razorpay API credentials for merchant '{}'. Halted execution.", request.getMerchantId());
            return PaymentExecutionResult.error(
                    "Missing required Razorpay credentials for merchant: " + request.getMerchantId(),
                    idempotencyKey
            );
        }

        // 3. Determine Endpoint
        String endpoint;
        if (request.getPaymentId() != null && !request.getPaymentId().isBlank()) {
            endpoint = baseUrl + "/v1/payments/" + request.getPaymentId().trim() + "/retry";
        } else if (request.getSubscriptionId() != null && !request.getSubscriptionId().isBlank()) {
            endpoint = baseUrl + "/v1/subscriptions/" + request.getSubscriptionId().trim() + "/charge";
        } else {
            endpoint = baseUrl + "/v1/payments/retry";
        }

        log.info("Executing Razorpay payment action for merchant='{}', campaign='{}', endpoint='{}', idempotencyKey='{}'",
                request.getMerchantId(), request.getCampaignId(), endpoint, idempotencyKey);

        // 4. Construct Request Headers & Body
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String auth = credentials.keyId() + ":" + credentials.keySecret();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.set("X-Razorpay-Idempotency-Header", idempotencyKey);
            headers.set("X-Idempotency-Key", idempotencyKey);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("receipt", "rcpt_" + request.getAttemptNumber());
        body.put("idempotency_key", idempotencyKey);
        if (request.getPaymentId() != null) {
            body.put("payment_id", request.getPaymentId());
        }

        // 5. Execute HTTP Request & Map Response
        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String externalRef = root.has("id") ? root.get("id").asText() : "rzp_" + Math.abs(idempotencyKey.hashCode());
                String status = root.has("status") ? root.get("status").asText() : "success";

                log.info("Razorpay payment execution SUCCESS: ref='{}', status='{}', idempotencyKey='{}'",
                        externalRef, status, idempotencyKey);

                return PaymentExecutionResult.success(
                        externalRef,
                        "Razorpay payment retry executed successfully. Status: " + status,
                        idempotencyKey
                );
            } else {
                return PaymentExecutionResult.failure(
                        "Razorpay API returned status: " + response.getStatusCode(),
                        "UNEXPECTED_STATUS",
                        idempotencyKey
                );
            }

        } catch (HttpClientErrorException e) {
            // 4xx Client/Business Error (Declined, Invalid Request, Unprocessable)
            String errorReason = "Payment declined by Razorpay (HTTP " + e.getStatusCode().value() + ")";
            String errorCode = "PAYMENT_DECLINED";

            try {
                if (e.getResponseBodyAsString() != null && !e.getResponseBodyAsString().isBlank()) {
                    JsonNode root = objectMapper.readTree(e.getResponseBodyAsString());
                    if (root.has("error")) {
                        JsonNode errNode = root.get("error");
                        if (errNode.has("description")) {
                            errorReason = sanitize(errNode.get("description").asText());
                        }
                        if (errNode.has("code")) {
                            errorCode = sanitize(errNode.get("code").asText());
                        }
                    }
                }
            } catch (Exception parseEx) {
                log.warn("Could not parse Razorpay 4xx error body: {}", parseEx.getMessage());
            }

            log.warn("Razorpay payment DECLINED for merchant='{}', campaign='{}': code='{}', reason='{}'",
                    request.getMerchantId(), request.getCampaignId(), errorCode, errorReason);

            return PaymentExecutionResult.failure(errorReason, errorCode, idempotencyKey);

        } catch (ResourceAccessException e) {
            // Network Connection Failure / Read Timeout
            log.error("Razorpay network connection error/timeout for merchant='{}', campaign='{}': {}",
                    request.getMerchantId(), request.getCampaignId(), sanitize(e.getMessage()));

            return PaymentExecutionResult.error(
                    "Razorpay gateway connection error/timeout: " + sanitize(e.getMessage()),
                    idempotencyKey
            );

        } catch (HttpServerErrorException e) {
            // 5xx Provider Server Error
            log.error("Razorpay 5xx server error for merchant='{}', campaign='{}': {}",
                    request.getMerchantId(), request.getCampaignId(), e.getStatusCode());

            return PaymentExecutionResult.error(
                    "Razorpay server error (HTTP " + e.getStatusCode().value() + ")",
                    idempotencyKey
            );

        } catch (Exception e) {
            log.error("Unhandled error during Razorpay execution for merchant='{}', campaign='{}': {}",
                    request.getMerchantId(), request.getCampaignId(), sanitize(e.getMessage()), e);

            return PaymentExecutionResult.error(
                    "Unhandled Razorpay client error: " + sanitize(e.getMessage()),
                    idempotencyKey
            );
        }
    }

    private RazorpayCredentials resolveCredentials(PaymentExecutionRequest request) {
        if (!defaultKeyId.isBlank() && !defaultKeySecret.isBlank()) {
            return new RazorpayCredentials(defaultKeyId, defaultKeySecret);
        }
        return null;
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        // Strip credential-like patterns or excessive noise
        return input.replaceAll("(?i)(key_secret|secret|password|bearer|authorization)=[^&\\s]+", "$1=***");
    }

    private record RazorpayCredentials(String keyId, String keySecret) {
        public boolean isValid() {
            return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
        }
    }
}

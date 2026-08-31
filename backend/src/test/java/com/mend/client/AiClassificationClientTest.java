package com.mend.client;

import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.exception.AiClassificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiClassificationClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private FastApiClassificationClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new FastApiClassificationClient("http://localhost:8000", restTemplate);
    }

    @Test
    void classify_ValidRequest_ParsesResponseCorrectly() {
        UUID eventId = UUID.randomUUID();
        ClassificationRequestDto request = ClassificationRequestDto.of(
                eventId, "payment.failed", "insufficient_funds", "Account balance too low", "RAZORPAY", UUID.randomUUID()
        );

        String jsonResponse = """
                {
                    "classification": "INSUFFICIENT_FUNDS",
                    "confidence": 0.95,
                    "recommendedAction": "RETRY_LATER",
                    "reason": "Failure due to insufficient funds",
                    "modelVersion": "v1.0.0-rule-based"
                }
                """;

        mockServer.expect(requestTo("http://localhost:8000/api/v1/classify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        ClassificationResponseDto response = client.classify(request);

        assertNotNull(response);
        assertEquals(FailureClass.INSUFFICIENT_FUNDS, response.classification());
        assertEquals(0.95, response.confidence().doubleValue());
        assertEquals(RecommendedAction.RETRY_LATER, response.recommendedAction());
        assertEquals("Failure due to insufficient funds", response.reason());
        assertEquals("v1.0.0-rule-based", response.modelVersion());
        mockServer.verify();
    }

    @Test
    void classify_ServiceUnavailable_ThrowsAiClassificationException() {
        ClassificationRequestDto request = ClassificationRequestDto.of(
                UUID.randomUUID(), "payment.failed", "error_code", "reason", "RAZORPAY", UUID.randomUUID()
        );

        mockServer.expect(requestTo("http://localhost:8000/api/v1/classify"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(AiClassificationException.class, () -> client.classify(request));
        mockServer.verify();
    }

    @Test
    void classify_BadRequest_ThrowsAiClassificationException() {
        ClassificationRequestDto request = ClassificationRequestDto.of(
                null, "payment.failed", "error_code", "reason", "RAZORPAY", null
        );

        mockServer.expect(requestTo("http://localhost:8000/api/v1/classify"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(AiClassificationException.class, () -> client.classify(request));
        mockServer.verify();
    }
}

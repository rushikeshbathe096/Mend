package com.mend.client;

import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.dto.ai.AgentOrchestrationRequestDto;
import com.mend.dto.ai.AgentOrchestrationResponseDto;
import com.mend.exception.AiClassificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class FastApiClassificationClient implements AiClassificationClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiClassificationClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public FastApiClassificationClient(
            @Value("${mend.ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${mend.ai.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${mend.ai.read-timeout-ms:3000}") int readTimeoutMs) {
        this.baseUrl = baseUrl;
        
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    // Secondary constructor for testing with custom RestTemplate
    public FastApiClassificationClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    @Override
    public ClassificationResponseDto classify(ClassificationRequestDto request) {
        String endpoint = baseUrl + "/api/v1/classify";
        log.info("Sending AI classification request to '{}' for eventId='{}'", endpoint, request.eventId());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String traceId = org.slf4j.MDC.get("traceId");
            if (traceId == null) traceId = org.slf4j.MDC.get("correlationId");
            if (traceId != null) {
                headers.set("X-Correlation-ID", traceId);
                headers.set("X-Trace-ID", traceId);
            }
            HttpEntity<ClassificationRequestDto> entity = new HttpEntity<>(request, headers);

            ResponseEntity<ClassificationResponseDto> response = restTemplate.postForEntity(
                    endpoint, entity, ClassificationResponseDto.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Received AI classification response for eventId='{}': classification='{}', confidence={}",
                        request.eventId(), response.getBody().classification(), response.getBody().confidence());
                return response.getBody();
            } else {
                throw new AiClassificationException("AI service returned unexpected status: " + response.getStatusCode());
            }
        } catch (RestClientException e) {
            log.error("Failed to classify eventId='{}' via AI service at '{}': {}", request.eventId(), endpoint, e.getMessage());
            throw new AiClassificationException("AI classification service call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentOrchestrationResponseDto orchestrateAgent(AgentOrchestrationRequestDto request) {
        String endpoint = baseUrl + "/api/v1/agent/orchestrate";
        log.info("Sending AI Agent Orchestration request to '{}' for campaignId='{}'", endpoint, request.campaignId());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String traceId = org.slf4j.MDC.get("traceId");
            if (traceId == null) traceId = org.slf4j.MDC.get("correlationId");
            if (traceId != null) {
                headers.set("X-Correlation-ID", traceId);
                headers.set("X-Trace-ID", traceId);
            }
            HttpEntity<AgentOrchestrationRequestDto> entity = new HttpEntity<>(request, headers);

            ResponseEntity<AgentOrchestrationResponseDto> response = restTemplate.postForEntity(
                    endpoint, entity, AgentOrchestrationResponseDto.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Received AI Agent response for campaignId='{}': decision='{}', traceId='{}'",
                        request.campaignId(), response.getBody().decision(), response.getBody().agentTraceId());
                return response.getBody();
            } else {
                throw new AiClassificationException("AI service agent endpoint returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to orchestrate AI agent for campaignId='{}': {}", request.campaignId(), e.getMessage());
            throw new AiClassificationException("AI agent orchestration call failed: " + e.getMessage(), e);
        }
    }
}

package com.mend.reply;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** ai-service {@code POST /api/v1/parse_reply}. The reply text is PII: it is sent here and never logged. */
@Component
public class HttpReplyParserClient implements ReplyParserClient {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final RestClient restClient;
    private final String internalToken;
    private final ObjectMapper objectMapper;

    public HttpReplyParserClient(@Value("${mend.ai.base-url:http://localhost:8000}") String baseUrl,
                                 @Value("${mend.ai.connect-timeout-ms:2000}") int connectTimeoutMs,
                                 @Value("${mend.ai.reply-read-timeout-ms:15000}") int readTimeoutMs,
                                 @Value("${mend.ai.internal-token:}") String internalToken,
                                 ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReplyParseOutcome parse(String text) {
        try {
            return restClient.post()
                    .uri("/api/v1/parse_reply")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (!internalToken.isBlank()) {
                            h.set("X-Mend-Internal-Token", internalToken);
                        }
                    })
                    .body(Map.of("text", text))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        // bounded: ai-service output is untrusted; a parse_reply answer is a few hundred bytes
                        String body = new String(response.getBody().readNBytes(MAX_RESPONSE_BYTES), java.nio.charset.StandardCharsets.UTF_8);
                        return toOutcome(status, body);
                    });
        } catch (RestClientException e) {
            return new ReplyParseOutcome.Failed("AI_SERVICE_UNREACHABLE");
        }
    }

    ReplyParseOutcome toOutcome(int status, String body) {
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return new ReplyParseOutcome.Failed("AI_SERVICE_BAD_RESPONSE_" + status);
        }
        if (status == 503 && "model_unavailable".equals(text(json, "error"))) {
            return new ReplyParseOutcome.ModelUnavailable();
        }
        if (status != 200 || json == null || !json.isObject()) {
            return new ReplyParseOutcome.Failed("AI_SERVICE_" + status);
        }
        JsonNode suggestion = json.get("suggestion");
        return new ReplyParseOutcome.Parsed(suggestion == null || suggestion.isNull() ? null : suggestion,
                text(json, "output_error"), text(json, "model_id"), text(json, "prompt_version"));
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}

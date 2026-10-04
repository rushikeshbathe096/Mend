package com.mend.provider.razorpay;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Razorpay transport: Basic auth, JSON, timeouts, and exception → {@link TransportFailureKind}.
 * Never throws and never retries: every call returns exactly one {@link RawResponse}.
 */
@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "razorpay")
public class RazorpayHttp {

    private static final Logger log = LoggerFactory.getLogger(RazorpayHttp.class);

    private final RestClient restClient;
    private final ObjectMapper requestMapper;

    @Autowired
    public RazorpayHttp(RazorpayProperties properties, ObjectMapper objectMapper) {
        this(RestClient.builder().requestFactory(requestFactory(properties)), properties.baseUrl(), objectMapper);
    }

    /** For tests: the builder may be bound to {@code MockRestServiceServer}. */
    public RazorpayHttp(RestClient.Builder builder, String baseUrl, ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.requestMapper = objectMapper.rebuild()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    static JdkClientHttpRequestFactory requestFactory(RazorpayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    public RawResponse post(RazorpayCredentials credentials, String path, Object body) {
        long start = System.nanoTime();
        RawResponse response;
        try {
            String json = requestMapper.writeValueAsString(body);
            response = restClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth(credentials))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .exchange((request, reply) -> new RawResponse.HttpReply(
                            reply.getStatusCode().value(), readBody(reply.getBody()), elapsedMs(start)));
        } catch (Exception e) {
            response = failure(e, start);
        }
        logCall("POST", path, response);
        return response;
    }

    public RawResponse get(RazorpayCredentials credentials, String pathWithQuery) {
        long start = System.nanoTime();
        RawResponse response;
        try {
            response = restClient.get()
                    .uri(pathWithQuery)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth(credentials))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, reply) -> new RawResponse.HttpReply(
                            reply.getStatusCode().value(), readBody(reply.getBody()), elapsedMs(start)));
        } catch (Exception e) {
            response = failure(e, start);
        }
        logCall("GET", pathWithQuery, response);
        return response;
    }

    /** Walks the cause chain. Only failures that prove nothing was sent are NOT_SENT; everything else is conservative. */
    static RawResponse.TransportFailure failure(Throwable error, long startNanos) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 20; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof HttpConnectTimeoutException) {
                return new RawResponse.TransportFailure(TransportFailureKind.NOT_SENT, "CONNECT_TIMEOUT", elapsedMs(startNanos));
            }
            if (t instanceof ConnectException) {
                return new RawResponse.TransportFailure(TransportFailureKind.NOT_SENT, "CONNECTION_REFUSED", elapsedMs(startNanos));
            }
            if (t instanceof UnknownHostException) {
                return new RawResponse.TransportFailure(TransportFailureKind.NOT_SENT, "UNKNOWN_HOST", elapsedMs(startNanos));
            }
        }
        depth = 0;
        for (Throwable t = error; t != null && depth < 20; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof HttpTimeoutException) {
                return new RawResponse.TransportFailure(TransportFailureKind.SENT_NO_RESPONSE, "READ_TIMEOUT", elapsedMs(startNanos));
            }
            if (t instanceof IOException) {
                return new RawResponse.TransportFailure(TransportFailureKind.SENT_NO_RESPONSE, "IO_ERROR", elapsedMs(startNanos));
            }
        }
        return new RawResponse.TransportFailure(TransportFailureKind.SENT_NO_RESPONSE, "UNEXPECTED_ERROR", elapsedMs(startNanos));
    }

    private static String basicAuth(RazorpayCredentials credentials) {
        String pair = credentials.keyId() + ":" + credentials.keySecret();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(InputStream body) throws IOException {
        return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static void logCall(String method, String path, RawResponse response) {
        switch (response) {
            case RawResponse.HttpReply reply -> log.info("event=provider_http method={} path={} status={} latency_ms={} outcome=reply",
                    method, path, reply.status(), reply.latencyMs());
            case RawResponse.TransportFailure failure -> log.info("event=provider_http method={} path={} status=none latency_ms={} outcome={} cause={}",
                    method, path, failure.latencyMs(), failure.kind(), failure.cause());
        }
    }
}

package com.mend.provider.razorpay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets, no mocked exceptions: proves NOT_SENT vs SENT_NO_RESPONSE. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RazorpayHttpSocketTest {

    private static final RazorpayCredentials CREDS = new RazorpayCredentials("rzp_test_key", "secret_value");

    private HttpServer server;
    private ServerSocket rawServer;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (rawServer != null) {
            rawServer.close();
        }
    }

    private static RazorpayHttp transport(int port) {
        RazorpayProperties props = new RazorpayProperties(new RazorpayProperties.Api("http://127.0.0.1:" + port),
                "rzp_test_key", "secret_value", 300, 300, null);
        return new RazorpayHttp(props, new ObjectMapper());
    }

    private HttpServer start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        return server;
    }

    @Test
    void closedPortIsNotSent() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        RawResponse response = transport(port).post(CREDS, "/v1/payment_links", Map.of("amount", 100));
        RawResponse.TransportFailure failure = assertInstanceOf(RawResponse.TransportFailure.class, response);
        assertEquals(TransportFailureKind.NOT_SENT, failure.kind());
        assertEquals("CONNECTION_REFUSED", failure.cause());
    }

    @Test
    void readTimeoutIsSentNoResponseNeverNotSent() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch received = new CountDownLatch(1);
        start().createContext("/", exchange -> {
            requests.incrementAndGet();
            received.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();

        RawResponse response = transport(server.getAddress().getPort()).post(CREDS, "/v1/payment_links", Map.of("amount", 100));

        RawResponse.TransportFailure failure = assertInstanceOf(RawResponse.TransportFailure.class, response);
        assertEquals(TransportFailureKind.SENT_NO_RESPONSE, failure.kind());
        assertEquals("READ_TIMEOUT", failure.cause());
        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertEquals(1, requests.get(), "POST must not be retried");
    }

    @Test
    void connectionClosedAfterRequestIsSentNoResponseAndNotRetried() throws Exception {
        rawServer = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        AtomicInteger connections = new AtomicInteger();
        Thread acceptor = new Thread(() -> {
            while (!rawServer.isClosed()) {
                try (Socket socket = rawServer.accept()) {
                    connections.incrementAndGet();
                    readRequestHead(socket.getInputStream());
                } catch (Exception ignored) {
                    // socket closed by the test or the client
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        RawResponse response = transport(rawServer.getLocalPort()).post(CREDS, "/v1/payment_links", Map.of("amount", 100));

        RawResponse.TransportFailure failure = assertInstanceOf(RawResponse.TransportFailure.class, response);
        assertEquals(TransportFailureKind.SENT_NO_RESPONSE, failure.kind());
        assertEquals(1, connections.get(), "POST must not be retried");
    }

    @Test
    void serverErrorIsAnHttpReplyNotAnException() throws Exception {
        start().createContext("/", exchange -> {
            byte[] body = "{\"error\":{\"code\":\"SERVER_ERROR\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        RawResponse response = transport(server.getAddress().getPort()).get(CREDS, "/v1/payments/pay_1");

        RawResponse.HttpReply reply = assertInstanceOf(RawResponse.HttpReply.class, response);
        assertEquals(503, reply.status());
        assertTrue(reply.body().contains("SERVER_ERROR"));
    }

    @Test
    void sendsBasicAuthJsonHeadersNonNullBodyAndNoIdempotencyHeaders() throws Exception {
        Map<String, List<String>> headers = new ConcurrentHashMap<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        start().createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"plink_1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        RawResponse response = transport(server.getAddress().getPort())
                .post(CREDS, "/v1/payment_links", new Body(100, null));

        assertEquals(200, assertInstanceOf(RawResponse.HttpReply.class, response).status());
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("rzp_test_key:secret_value".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(expectedAuth), headers.get("authorization"));
        assertTrue(headers.get("content-type").get(0).startsWith("application/json"));
        assertEquals(List.of("application/json"), headers.get("accept"));
        assertFalse(headers.containsKey("x-razorpay-idempotency-header"));
        assertFalse(headers.containsKey("x-idempotency-key"));
        assertEquals("{\"amount\":100}", bodies.get(0), "null fields must be omitted");
    }

    @Test
    void oversizedBodyIsSentNoResponseNeverTruncated() throws Exception {
        start().createContext("/", exchange -> {
            byte[] body = new byte[RazorpayHttp.MAX_BODY_BYTES + 1];
            java.util.Arrays.fill(body, (byte) 'a');
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        RawResponse response = transport(server.getAddress().getPort()).post(CREDS, "/v1/payment_links", Map.of("amount", 100));

        RawResponse.TransportFailure failure = assertInstanceOf(RawResponse.TransportFailure.class, response);
        assertEquals(TransportFailureKind.SENT_NO_RESPONSE, failure.kind());
        assertEquals("BODY_TOO_LARGE", failure.cause());
    }

    @Test
    void pathAndQueryAreSentVerbatimWithoutTemplateExpansionOrReEncoding() throws Exception {
        List<String> uris = new CopyOnWriteArrayList<>();
        start().createContext("/", exchange -> {
            uris.add(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        transport(server.getAddress().getPort()).get(CREDS, "/v1/payment_links?reference_id=mnd_a%2Bb");

        assertEquals(List.of("/v1/payment_links?reference_id=mnd_a%2Bb"), uris);
    }

    record Body(long amount, String description) {
    }

    private static void readRequestHead(InputStream in) throws Exception {
        int matched = 0;
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int b;
        while ((b = in.read()) != -1) {
            matched = (b == end[matched]) ? matched + 1 : (b == end[0] ? 1 : 0);
            if (matched == end.length) {
                return;
            }
        }
    }
}

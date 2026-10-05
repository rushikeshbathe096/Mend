package com.mend.provider.razorpay;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * Local stand-in for api.razorpay.com in integration tests: real HTTP on a loopback port, canned responses keyed by
 * method + path(+query), every request recorded. Unmatched requests get 599 so a test can never pass by accident.
 */
public final class RazorpayStubServer implements AutoCloseable {

    public record Request(String method, String pathWithQuery, Map<String, List<String>> headers, String body) {
    }

    private record Stub(int status, UnaryOperator<String> bodyFromRequest, long stallMs) {
    }

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private RazorpayStubServer(HttpServer server) {
        this.server = server;
    }

    public static RazorpayStubServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RazorpayStubServer stub = new RazorpayStubServer(server);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath()
                        + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                stub.requests.add(new Request(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
                Stub match = stub.stubs.get(exchange.getRequestMethod() + " " + path);
                if (match != null && match.stallMs() > 0) {
                    try {
                        Thread.sleep(match.stallMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                int status = match == null ? 599 : match.status();
                byte[] response = (match == null ? "{\"stub\":\"unmatched\"}" : match.bodyFromRequest().apply(body)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                if (response.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(response);
                    }
                }
                exchange.close();
            });
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stub(String method, String pathWithQuery, int status, String body) {
        stubs.put(method + " " + pathWithQuery, new Stub(status, request -> body, 0));
    }

    /** Accepts the request, then answers only after {@code stallMs} (longer than the client read timeout). */
    public void stall(String method, String pathWithQuery, long stallMs) {
        stubs.put(method + " " + pathWithQuery, new Stub(200, request -> "{}", stallMs));
    }

    /** Response body computed from the request body (e.g. echo the request's reference_id into a recorded response). */
    public void stub(String method, String pathWithQuery, int status, UnaryOperator<String> bodyFromRequest) {
        stubs.put(method + " " + pathWithQuery, new Stub(status, bodyFromRequest, 0));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        stubs.clear();
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

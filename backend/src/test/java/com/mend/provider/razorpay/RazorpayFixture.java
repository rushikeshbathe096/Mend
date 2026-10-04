package com.mend.provider.razorpay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** A replayable Razorpay exchange from {@code src/test/resources/razorpay/{recorded,doc-derived}/}. */
record RazorpayFixture(String name, String method, String path, JsonNode requestBody, int status, String responseBody) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static RazorpayFixture recorded(String name) {
        return load("/razorpay/recorded/" + name + ".json", name);
    }

    static RazorpayFixture docDerived(String name) {
        return load("/razorpay/doc-derived/" + name + ".json", name);
    }

    private static RazorpayFixture load(String resource, String name) {
        try (InputStream in = RazorpayFixture.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + resource);
            }
            JsonNode root = MAPPER.readTree(in);
            JsonNode body = root.path("response").path("body");
            return new RazorpayFixture(name, root.path("request").path("method").asString(),
                    root.path("request").path("path").asString(), root.path("request").path("body"),
                    root.path("response").path("status").asInt(), body.isString() ? body.asString() : MAPPER.writeValueAsString(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

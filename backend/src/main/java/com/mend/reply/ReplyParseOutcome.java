package com.mend.reply;

import tools.jackson.databind.JsonNode;

/** What ai-service answered for one reply (M3 U2.4). Only {@link Parsed} carries model output, and it is untrusted. */
public sealed interface ReplyParseOutcome {

    /**
     * The model answered. {@code suggestion} is its JSON exactly as sent (null when it sent no JSON object, then
     * {@code outputError} says why); {@code modelId} is the provider's own model id or null.
     */
    record Parsed(JsonNode suggestion, String outputError, String modelId, String promptVersion) implements ReplyParseOutcome {
    }

    /** No model is configured (ai-service 503 model_unavailable): no output exists. */
    record ModelUnavailable() implements ReplyParseOutcome {
    }

    /** ai-service unreachable, timed out, or failed; retryable. {@code code} is a short code, never reply text. */
    record Failed(String code) implements ReplyParseOutcome {
    }
}

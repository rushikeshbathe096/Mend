package com.mend.reply;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** M3 U2.4: mapping of ai-service answers; the model id is taken as reported, never invented. */
class HttpReplyParserClientTest {

    private final HttpReplyParserClient client =
            new HttpReplyParserClient("http://10.255.255.1:8000", 200, 200, "", new ObjectMapper());

    @Test
    void parsedAnswerKeepsTheSuggestionAndTheReportedModelId() {
        ReplyParseOutcome outcome = client.toOutcome(200,
                "{\"suggestion\":{\"intent\":\"OPT_OUT\",\"promised_date\":null,\"abstain\":false},\"output_error\":null,"
                        + "\"model_id\":\"gemini-2.5-flash-001\",\"prompt_version\":\"reply-parse-v1\"}");
        ReplyParseOutcome.Parsed parsed = assertInstanceOf(ReplyParseOutcome.Parsed.class, outcome);
        assertEquals("OPT_OUT", parsed.suggestion().get("intent").asString());
        assertEquals("gemini-2.5-flash-001", parsed.modelId());
        assertEquals("reply-parse-v1", parsed.promptVersion());
    }

    @Test
    void missingModelIdStaysUnknown() {
        ReplyParseOutcome.Parsed parsed = assertInstanceOf(ReplyParseOutcome.Parsed.class,
                client.toOutcome(200, "{\"suggestion\":null,\"output_error\":\"not_json\",\"model_id\":null,\"prompt_version\":\"reply-parse-v1\"}"));
        assertNull(parsed.modelId());
        assertNull(parsed.suggestion());
        assertEquals("not_json", parsed.outputError());
    }

    @Test
    void modelUnavailableAndFailures() {
        assertInstanceOf(ReplyParseOutcome.ModelUnavailable.class, client.toOutcome(503, "{\"error\":\"model_unavailable\"}"));
        assertEquals(new ReplyParseOutcome.Failed("AI_SERVICE_503"), client.toOutcome(503, "{\"error\":\"other\"}"));
        assertEquals(new ReplyParseOutcome.Failed("AI_SERVICE_502"), client.toOutcome(502, "{\"error\":\"model_error\"}"));
        assertEquals(new ReplyParseOutcome.Failed("AI_SERVICE_BAD_RESPONSE_200"), client.toOutcome(200, "<html>"));
    }

    @Test
    void unreachableServiceIsAFailureNotAnException() {
        assertEquals(new ReplyParseOutcome.Failed("AI_SERVICE_UNREACHABLE"), client.parse("hello"));
    }
}

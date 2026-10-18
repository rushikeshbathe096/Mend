package com.mend.reply;

/** Asks ai-service to parse one reply. Never called on the payment path (ADR-003 §2): only by {@link ReplyParsingJob}. */
public interface ReplyParserClient {

    ReplyParseOutcome parse(String text);

    /**
     * M4 U4.7: the same with retrieved labelled examples (gated experiment, flag off by default). Implementations that cannot
     * send examples parse without them.
     */
    default ReplyParseOutcome parse(String text, java.util.List<ReplyExample> examples) {
        return parse(text);
    }
}

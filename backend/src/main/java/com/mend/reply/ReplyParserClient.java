package com.mend.reply;

/** Asks ai-service to parse one reply. Never called on the payment path (ADR-003 §2): only by {@link ReplyParsingJob}. */
public interface ReplyParserClient {

    ReplyParseOutcome parse(String text);
}

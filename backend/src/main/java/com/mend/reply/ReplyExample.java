package com.mend.reply;

/** M4 U4.7: one labelled example (SYNTHETIC or consented) for the gated few-shot experiment. {@code promisedDate} ISO or null. */
public record ReplyExample(String text, String intent, String promisedDate) {
}

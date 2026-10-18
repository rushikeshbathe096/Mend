package com.mend.reply;

import com.mend.domain.enums.ReplyIntent;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict schema check of a model suggestion (invariant 8: LLM output is untrusted). Exactly the fields {@code intent},
 * {@code promised_date}, {@code abstain}, all present; intent from the closed enum; abstain a JSON boolean; promised_date null or a
 * real ISO date {@code YYYY-MM-DD}, and only with PROMISE_TO_PAY. Nothing is coerced: anything else is invalid.
 */
public final class ReplySuggestionValidator {

    private static final Set<String> FIELDS = Set.of("intent", "promised_date", "abstain");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private ReplySuggestionValidator() {
    }

    public sealed interface Result {
    }

    public record Valid(ReplyIntent intent, LocalDate promisedDate, boolean abstain) implements Result {
    }

    public record Invalid(String error) implements Result {
    }

    public static Result validate(JsonNode suggestion) {
        if (suggestion == null || !suggestion.isObject()) {
            return new Invalid("NOT_AN_OBJECT");
        }
        for (String name : suggestion.propertyNames()) {
            if (!FIELDS.contains(name)) {
                return new Invalid("UNEXPECTED_FIELD");
            }
        }
        JsonNode intentNode = suggestion.get("intent");
        if (intentNode == null || !intentNode.isString()) {
            return new Invalid("INTENT_MISSING");
        }
        ReplyIntent intent;
        try {
            intent = ReplyIntent.valueOf(intentNode.asString());
        } catch (IllegalArgumentException e) {
            return new Invalid("INTENT_NOT_IN_ENUM");
        }
        JsonNode abstainNode = suggestion.get("abstain");
        if (abstainNode == null || !abstainNode.isBoolean()) {
            return new Invalid("ABSTAIN_NOT_BOOLEAN");
        }
        if (!suggestion.has("promised_date")) {
            return new Invalid("PROMISED_DATE_MISSING");
        }
        JsonNode dateNode = suggestion.get("promised_date");
        LocalDate promisedDate = null;
        if (dateNode != null && !dateNode.isNull()) {
            if (!dateNode.isString() || !ISO_DATE.matcher(dateNode.asString()).matches()) {
                return new Invalid("PROMISED_DATE_NOT_ISO");
            }
            try {
                promisedDate = LocalDate.parse(dateNode.asString());
            } catch (DateTimeParseException e) {
                return new Invalid("PROMISED_DATE_NOT_ISO");
            }
            if (intent != ReplyIntent.PROMISE_TO_PAY) {
                return new Invalid("PROMISED_DATE_WITHOUT_PROMISE");
            }
        }
        return new Valid(intent, promisedDate, abstainNode.asBoolean());
    }
}

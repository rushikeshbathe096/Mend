package com.mend.reply;

import com.mend.domain.enums.ReplyIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** M3 U2.4: model output is untrusted (invariant 8); the validator never coerces. */
class ReplySuggestionValidatorTest {

    private final ObjectMapper json = new ObjectMapper();

    private ReplySuggestionValidator.Result validate(String raw) {
        return ReplySuggestionValidator.validate(json.readTree(raw));
    }

    @Test
    void validSuggestions() {
        assertEquals(new ReplySuggestionValidator.Valid(ReplyIntent.OPT_OUT, null, false),
                validate("{\"intent\":\"OPT_OUT\",\"promised_date\":null,\"abstain\":false}"));
        assertEquals(new ReplySuggestionValidator.Valid(ReplyIntent.PROMISE_TO_PAY, LocalDate.of(2026, 11, 2), false),
                validate("{\"intent\":\"PROMISE_TO_PAY\",\"promised_date\":\"2026-11-02\",\"abstain\":false}"));
        assertEquals(new ReplySuggestionValidator.Valid(ReplyIntent.OTHER, null, true),
                validate("{\"intent\":\"OTHER\",\"promised_date\":null,\"abstain\":true}"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[\"OPT_OUT\"]                                                                  | NOT_AN_OBJECT",
            "{\"intent\":\"OTHER\",\"abstain\":true}                                   | PROMISED_DATE_MISSING",
            "{\"intent\":\"OPT_OUT\",\"abstain\":false,\"confidence\":0.99}                 | UNEXPECTED_FIELD",
            "{\"abstain\":false}                                                            | INTENT_MISSING",
            "{\"intent\":1,\"abstain\":false}                                               | INTENT_MISSING",
            "{\"intent\":\"opt_out\",\"abstain\":false}                                     | INTENT_NOT_IN_ENUM",
            "{\"intent\":\"REFUND_NOW\",\"abstain\":false}                                  | INTENT_NOT_IN_ENUM",
            "{\"intent\":\"OPT_OUT\"}                                                       | ABSTAIN_NOT_BOOLEAN",
            "{\"intent\":\"OPT_OUT\",\"abstain\":\"false\"}                                 | ABSTAIN_NOT_BOOLEAN",
            "{\"intent\":\"PROMISE_TO_PAY\",\"promised_date\":\"tomorrow\",\"abstain\":false}  | PROMISED_DATE_NOT_ISO",
            "{\"intent\":\"PROMISE_TO_PAY\",\"promised_date\":\"2026-02-30\",\"abstain\":false} | PROMISED_DATE_NOT_ISO",
            "{\"intent\":\"PROMISE_TO_PAY\",\"promised_date\":\"2026-11-02T10:00\",\"abstain\":false} | PROMISED_DATE_NOT_ISO",
            "{\"intent\":\"OPT_OUT\",\"promised_date\":\"2026-11-02\",\"abstain\":false}       | PROMISED_DATE_WITHOUT_PROMISE",
    })
    void invalidSuggestions(String raw, String error) {
        assertEquals(new ReplySuggestionValidator.Invalid(error), validate(raw));
    }

    @Test
    void nullIsInvalid() {
        assertEquals(new ReplySuggestionValidator.Invalid("NOT_AN_OBJECT"), ReplySuggestionValidator.validate(null));
    }
}

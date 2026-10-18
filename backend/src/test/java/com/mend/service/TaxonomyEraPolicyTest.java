package com.mend.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M3 follow-up: which stored compliance policy versions may authorize a dispatch (v2.0 and later only). */
class TaxonomyEraPolicyTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "v2.0, true", "v2.2, true", "v10.1, true",
            "v1.0, false", "v1.0.0, false", "v1, false", "v1.2, false", "dev-seed, false", "scheduler-tx-v1, false",
            "v2.0-langgraph, false", "v2, false", "'', false", "NULL, false"})
    void onlyDeterministicPolicyVersionsAuthorizeADispatch(String version, boolean expected) {
        assertEquals(expected, ActionExecutionService.isTaxonomyEraPolicy(version));
    }
}

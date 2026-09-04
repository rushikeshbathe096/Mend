package com.mend.dto.ai;

import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import java.math.BigDecimal;
import java.util.Map;

public record ClassificationResponseDto(
    FailureClass classification,
    BigDecimal confidence,
    RecommendedAction recommendedAction,
    String reason,
    String modelVersion,
    Map<String, Object> evidence
) {
    public ClassificationResponseDto(
            FailureClass classification,
            BigDecimal confidence,
            RecommendedAction recommendedAction,
            String reason,
            String modelVersion) {
        this(classification, confidence, recommendedAction, reason, modelVersion, null);
    }
}

package com.mend.dto.ai;

import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import java.math.BigDecimal;

public record ClassificationResponseDto(
    FailureClass classification,
    BigDecimal confidence,
    RecommendedAction recommendedAction,
    String reason,
    String modelVersion
) {}

package com.mend.client;

import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;

public interface AiClassificationClient {
    ClassificationResponseDto classify(ClassificationRequestDto request);
}

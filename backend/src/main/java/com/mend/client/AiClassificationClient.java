package com.mend.client;

import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;

import com.mend.dto.ai.AgentOrchestrationRequestDto;
import com.mend.dto.ai.AgentOrchestrationResponseDto;

public interface AiClassificationClient {
    ClassificationResponseDto classify(ClassificationRequestDto request);
    default AgentOrchestrationResponseDto orchestrateAgent(AgentOrchestrationRequestDto request) {
        return null;
    }
}

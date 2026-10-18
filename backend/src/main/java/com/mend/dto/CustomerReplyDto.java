package com.mend.dto;

import com.mend.domain.entity.CustomerReply;
import com.mend.domain.entity.ReplySuggestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer reply and, once parsed, the model's *suggestion* (M3 U2.4). The suggestion is shown as a suggestion; its
 * only effects are the deterministic policy's, recorded in {@code effect}. {@code modelId} is the provider-reported id.
 */
public record CustomerReplyDto(UUID id, UUID campaignId, String text, String status, int attempts, String lastError,
                               Instant createdAt, Suggestion suggestion) {

    public record Suggestion(boolean valid, String intent, LocalDate promisedDate, Boolean abstain, String validationError,
                             String modelId, String promptVersion, String effect, Instant createdAt) {
    }

    public static CustomerReplyDto from(CustomerReply reply, ReplySuggestion suggestion) {
        Suggestion s = suggestion == null ? null : new Suggestion(suggestion.isValid(),
                suggestion.getIntent() != null ? suggestion.getIntent().name() : null, suggestion.getPromisedDate(),
                suggestion.getAbstain(), suggestion.getValidationError(), suggestion.getModelId(), suggestion.getPromptVersion(),
                suggestion.getEffect(), suggestion.getCreatedAt());
        return new CustomerReplyDto(reply.getId(), reply.getCampaignId(), reply.getText(), reply.getStatus().name(),
                reply.getAttempts(), reply.getLastError(), reply.getCreatedAt(), s);
    }
}

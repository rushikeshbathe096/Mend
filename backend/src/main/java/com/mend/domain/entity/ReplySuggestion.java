package com.mend.domain.entity;

import com.mend.domain.enums.ReplyIntent;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The model's suggestion for one reply (V20). A valid one carries intent and abstain; an invalid one carries only the
 * validation error (the output is never coerced). {@code modelId} is exactly what the provider reported, or null.
 * {@code effect} is what the deterministic {@code ReplyPolicy} did with it.
 */
@Entity
@Table(name = "reply_suggestions")
public class ReplySuggestion {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "reply_id", nullable = false, unique = true, columnDefinition = "UUID")
    private UUID replyId;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(nullable = false)
    private boolean valid;

    @Column(length = 30)
    @Enumerated(EnumType.STRING)
    private ReplyIntent intent;

    @Column(name = "promised_date")
    private LocalDate promisedDate;

    @Column
    private Boolean abstain;

    @Column(name = "validation_error", length = 200)
    private String validationError;

    @Column(name = "model_id", length = 100)
    private String modelId;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(nullable = false, length = 50)
    private String effect;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReplySuggestion() {
    }

    private ReplySuggestion(UUID replyId, UUID merchantId, boolean valid, ReplyIntent intent, LocalDate promisedDate,
                            Boolean abstain, String validationError, String modelId, String promptVersion) {
        this.id = UUID.randomUUID();
        this.replyId = replyId;
        this.merchantId = merchantId;
        this.valid = valid;
        this.intent = intent;
        this.promisedDate = promisedDate;
        this.abstain = abstain;
        this.validationError = validationError;
        this.modelId = modelId;
        this.promptVersion = promptVersion;
        this.effect = "NONE";
        this.createdAt = Instant.now();
    }

    public static ReplySuggestion valid(UUID replyId, UUID merchantId, ReplyIntent intent, LocalDate promisedDate, boolean abstain,
                                        String modelId, String promptVersion) {
        return new ReplySuggestion(replyId, merchantId, true, intent, promisedDate, abstain, null, modelId, promptVersion);
    }

    public static ReplySuggestion invalid(UUID replyId, UUID merchantId, String validationError, String modelId, String promptVersion) {
        return new ReplySuggestion(replyId, merchantId, false, null, null, null, validationError, modelId, promptVersion);
    }

    public UUID getId() { return id; }
    public UUID getReplyId() { return replyId; }
    public UUID getMerchantId() { return merchantId; }
    public boolean isValid() { return valid; }
    public ReplyIntent getIntent() { return intent; }
    public LocalDate getPromisedDate() { return promisedDate; }
    public Boolean getAbstain() { return abstain; }
    public String getValidationError() { return validationError; }
    public String getModelId() { return modelId; }
    public String getPromptVersion() { return promptVersion; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public Instant getCreatedAt() { return createdAt; }
}

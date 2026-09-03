package com.mend.service;

import java.util.UUID;

public class ReconciliationResult {

    public enum Status {
        RECONCILED_SUCCESS,
        RECONCILED_FAILURE,
        SKIPPED_ALREADY_FINALIZED,
        SKIPPED_UNMATCHED,
        SKIPPED_TENANT_MISMATCH,
        SKIPPED_UNSUPPORTED_EVENT
    }

    private final Status status;
    private final String message;
    private final UUID actionIntentId;
    private final UUID campaignId;
    private final String externalReference;

    public ReconciliationResult(Status status, String message, UUID actionIntentId, UUID campaignId, String externalReference) {
        this.status = status;
        this.message = message;
        this.actionIntentId = actionIntentId;
        this.campaignId = campaignId;
        this.externalReference = externalReference;
    }

    public static ReconciliationResult success(UUID actionIntentId, UUID campaignId, String externalRef, String msg) {
        return new ReconciliationResult(Status.RECONCILED_SUCCESS, msg, actionIntentId, campaignId, externalRef);
    }

    public static ReconciliationResult failure(UUID actionIntentId, UUID campaignId, String externalRef, String msg) {
        return new ReconciliationResult(Status.RECONCILED_FAILURE, msg, actionIntentId, campaignId, externalRef);
    }

    public static ReconciliationResult alreadyFinalized(UUID actionIntentId, UUID campaignId, String msg) {
        return new ReconciliationResult(Status.SKIPPED_ALREADY_FINALIZED, msg, actionIntentId, campaignId, null);
    }

    public static ReconciliationResult unmatched(String msg) {
        return new ReconciliationResult(Status.SKIPPED_UNMATCHED, msg, null, null, null);
    }

    public static ReconciliationResult tenantMismatch(String msg) {
        return new ReconciliationResult(Status.SKIPPED_TENANT_MISMATCH, msg, null, null, null);
    }

    public static ReconciliationResult unsupported(String msg) {
        return new ReconciliationResult(Status.SKIPPED_UNSUPPORTED_EVENT, msg, null, null, null);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public UUID getActionIntentId() {
        return actionIntentId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public boolean isReconciled() {
        return status == Status.RECONCILED_SUCCESS || status == Status.RECONCILED_FAILURE;
    }
}

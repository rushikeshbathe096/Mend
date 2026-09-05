package com.mend.dto;

/**
 * Request body for a merchant human-approval decision (approve/reject).
 */
public class ReviewDecisionRequest {

    private String comment;

    public ReviewDecisionRequest() {
    }

    public ReviewDecisionRequest(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}

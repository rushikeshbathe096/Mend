package com.mend.dto;

import java.util.Map;

/**
 * Counts of review items by status for a merchant (used to power the
 * human-review backlog surfaces in the merchant console).
 */
public class ReviewQueueSummaryDto {

    private long pending;
    private long total;
    private Map<String, Long> byStatus;

    public ReviewQueueSummaryDto() {
    }

    public ReviewQueueSummaryDto(long pending, long total, Map<String, Long> byStatus) {
        this.pending = pending;
        this.total = total;
        this.byStatus = byStatus;
    }

    public long getPending() { return pending; }
    public void setPending(long pending) { this.pending = pending; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public Map<String, Long> getByStatus() { return byStatus; }
    public void setByStatus(Map<String, Long> byStatus) { this.byStatus = byStatus; }
}

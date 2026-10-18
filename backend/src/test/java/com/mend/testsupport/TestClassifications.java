package com.mend.testsupport;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.taxonomy.FailureTaxonomy;

/**
 * M3 (ADR-003 §4): a classification fixture made the way every post-M3 row is made, i.e. by the deterministic taxonomy
 * (it carries the taxonomy version). A row without it is a pre-M3, AI-era row and fails closed.
 */
public final class TestClassifications {

    private TestClassifications() {
    }

    public static ClassificationResult deterministic(ClassificationResult row) {
        row.setTaxonomyVersion(FailureTaxonomy.TABLE_VERSION);
        return row;
    }

    /** Saves a deterministic classification row for a fixture campaign built directly with {@code setFailureClass}. */
    public static ClassificationResult saveFor(ClassificationResultRepository repository, Campaign campaign) {
        ClassificationResult row = new ClassificationResult(java.util.UUID.randomUUID(), null, campaign.getId(),
                campaign.getFailureClass(), null, null, "Razorpay error_reason (fixture)", null);
        return repository.saveAndFlush(deterministic(row));
    }
}

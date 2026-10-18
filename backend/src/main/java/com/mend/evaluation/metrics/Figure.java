package com.mend.evaluation.metrics;

import com.mend.evaluation.EvidenceLabel;

/**
 * M4 U4.4 (invariant 20): one reported number. Always carries its n and its label; {@code status} says whether an interval
 * was computed (OK), the n was too small (INSUFFICIENT_DATA), the interval is unstable (UNSTABLE, money with n &lt; 300) or
 * the number cannot be computed (TBD). Values are strings so exact decimals are never rounded by a JSON double.
 */
public record Figure(String scope, String stratum, String name, String value, String low, String high, long n,
                     EvidenceLabel label, Status status, String note) {

    public enum Status { OK, INSUFFICIENT_DATA, UNSTABLE, TBD, DESCRIPTIVE, ALERT }
}

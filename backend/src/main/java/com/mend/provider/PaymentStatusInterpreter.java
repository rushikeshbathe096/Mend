package com.mend.provider;

/** Interprets provider status strings. Unknown values must map to UNRECOGNIZED, never to success or failure. */
public interface PaymentStatusInterpreter {

    PaymentEvidenceStatus mapPaymentStatus(String status);

    PaymentLinkState mapLinkStatus(String status);
}

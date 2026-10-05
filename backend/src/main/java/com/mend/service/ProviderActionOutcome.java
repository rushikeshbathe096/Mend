package com.mend.service;

import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.RejectionKind;

/** MEND-level result of dispatching one ActionIntent to the provider. None of these is recovered money except SyntheticCaptured (mock mode). */
public sealed interface ProviderActionOutcome {

    /** A payment link exists for this intent. Not money: recovery comes only from captured evidence. */
    record LinkIssued(PaymentLinkSnapshot link) implements ProviderActionOutcome {
    }

    record Rejected(RejectionKind kind, String code, String reason, Integer httpStatus) implements ProviderActionOutcome {
    }

    record NotSent(String cause) implements ProviderActionOutcome {
    }

    /** The provider may have acted. Never terminal; resolved by reconciliation (M2). */
    record Unknown(String cause, Integer httpStatus) implements ProviderActionOutcome {
    }

    /** The action cannot run in this provider mode. No provider call was made. */
    record NotSupported(String reason) implements ProviderActionOutcome {
    }

    /** Mock mode only: synthetic captured debit. Labelled SYNTHETIC everywhere it is recorded. */
    record SyntheticCaptured(String reference) implements ProviderActionOutcome {
    }
}

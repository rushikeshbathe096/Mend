package com.mend.provider.razorpay;

import com.mend.domain.enums.ActionType;
import com.mend.provider.ProviderCapabilities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** M1: recurring debit is adapter-level only (U8, conditional) and never wired before M2. */
@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "razorpay")
public class RazorpayProviderCapabilities implements ProviderCapabilities {

    @Override
    public boolean supportsRecurringDebit() {
        return false;
    }

    /** Payment links: Razorpay enforces a unique {@code reference_id} (recorded fixture 02). Nothing else is resend-safe. */
    @Override
    public boolean supportsIdempotentResend(ActionType actionType) {
        return actionType == ActionType.REQUEST_CUSTOMER_ACTION;
    }
}

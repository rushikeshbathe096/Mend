package com.mend.provider.razorpay;

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
}

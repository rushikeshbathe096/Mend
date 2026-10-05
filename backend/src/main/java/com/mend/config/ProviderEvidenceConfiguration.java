package com.mend.config;

import com.mend.provider.PaymentStatusInterpreter;
import com.mend.provider.razorpay.RazorpayEvidenceMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Webhooks are Razorpay-shaped in every provider mode, so the Razorpay interpreter is always the evidence source. */
@Configuration
public class ProviderEvidenceConfiguration {

    @Bean
    public PaymentStatusInterpreter paymentStatusInterpreter() {
        return new RazorpayEvidenceMapper();
    }
}

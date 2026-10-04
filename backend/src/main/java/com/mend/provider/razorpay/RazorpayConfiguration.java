package com.mend.provider.razorpay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "razorpay")
@EnableConfigurationProperties(RazorpayProperties.class)
public class RazorpayConfiguration {
}

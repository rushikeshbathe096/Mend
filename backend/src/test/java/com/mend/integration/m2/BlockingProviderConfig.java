package com.mend.integration.m2;

import com.mend.provider.mock.MockPaymentProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wraps the mock provider in {@link BlockingPaymentProvider}. Top level on purpose (M2 U7): a static nested
 * {@code @TestConfiguration} becomes part of its enclosing test's context key, so classes importing it from elsewhere
 * cannot share that context, and every extra context holds another connection pool against Postgres' 100-connection limit.
 */
@TestConfiguration
public class BlockingProviderConfig {
    @Bean
    @Primary
    BlockingPaymentProvider blockingPaymentProvider(MockPaymentProvider mockPaymentProvider) {
        return new BlockingPaymentProvider(mockPaymentProvider);
    }
}

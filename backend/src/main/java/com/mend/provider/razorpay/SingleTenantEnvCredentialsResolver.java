package com.mend.provider.razorpay;

import com.mend.provider.MerchantRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * M1 resolver: the env key pair belongs to exactly one configured merchant. Fails closed (empty) for any other
 * merchant, when the configured id is blank or invalid, and when the key pair is incomplete.
 * Per-merchant credentials are M5.
 */
@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "razorpay")
public class SingleTenantEnvCredentialsResolver implements RazorpayCredentialsResolver {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantEnvCredentialsResolver.class);

    private final Optional<UUID> configuredMerchantId;
    private final Optional<RazorpayCredentials> credentials;

    public SingleTenantEnvCredentialsResolver(RazorpayProperties properties) {
        this.configuredMerchantId = parseMerchantId(properties.singleTenantMerchantId());
        this.credentials = isBlank(properties.keyId()) || isBlank(properties.keySecret())
                ? Optional.empty()
                : Optional.of(new RazorpayCredentials(properties.keyId().trim(), properties.keySecret().trim()));
    }

    @Override
    public Optional<RazorpayCredentials> resolve(MerchantRef merchant) {
        if (merchant == null || configuredMerchantId.isEmpty() || !configuredMerchantId.get().equals(merchant.merchantId())) {
            return Optional.empty();
        }
        return credentials;
    }

    private static Optional<UUID> parseMerchantId(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("event=razorpay_config_invalid field=single-tenant-merchant-id");
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

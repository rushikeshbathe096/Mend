package com.mend.provider;

import java.util.UUID;

/** The merchant on whose behalf a provider call is made. */
public record MerchantRef(UUID merchantId) {

    public MerchantRef {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
    }
}

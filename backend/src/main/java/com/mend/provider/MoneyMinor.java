package com.mend.provider;

/** A positive amount in minor units (paise). M1 supports INR only. */
public record MoneyMinor(long amountMinor, String currency) {

    public MoneyMinor {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
    }
}

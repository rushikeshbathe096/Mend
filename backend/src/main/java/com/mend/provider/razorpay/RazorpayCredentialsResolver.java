package com.mend.provider.razorpay;

import com.mend.provider.MerchantRef;

import java.util.Optional;

/** Finds the Razorpay credentials for a merchant. Empty means "no credentials": callers must not make a call. */
public interface RazorpayCredentialsResolver {

    Optional<RazorpayCredentials> resolve(MerchantRef merchant);
}

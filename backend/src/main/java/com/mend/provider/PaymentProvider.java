package com.mend.provider;

/**
 * Provider-agnostic payment port. The recovery domain depends only on this interface.
 * State-changing calls return {@link RequestResult}; reads return {@link LookupResult}.
 */
public interface PaymentProvider {

    RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command);

    LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key);

    LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId);

    LookupResult<PaymentEvidence> getPayment(MerchantRef merchant, String providerPaymentId);
}

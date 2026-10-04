package com.mend.provider.razorpay;

/** A 2xx body that lacks a field we require. Operations convert it to an unknown/unavailable result; never to data. */
public class MalformedProviderResponse extends Exception {

    public MalformedProviderResponse(String code) {
        super(code);
    }
}

package com.mend.provider.razorpay.dto;

/** Payment link {@code notify} object. M1 always sends {@code false}/{@code false}: MEND shares no customer contact. */
public record RazorpayLinkNotify(boolean sms, boolean email) {
}

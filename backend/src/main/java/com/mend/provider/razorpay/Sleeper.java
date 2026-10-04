package com.mend.provider.razorpay;

import java.time.Duration;

/** Injected so GET backoff can be verified in tests without real waiting. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper SYSTEM = Thread::sleep;
}

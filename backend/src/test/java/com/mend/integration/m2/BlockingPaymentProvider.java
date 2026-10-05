package com.mend.integration.m2;

import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RequestResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only provider wrapper: delegates to the context's mock provider, counts {@code createPaymentLink} calls per attempt
 * key, and can hold the FIRST create call for a registered key until the test releases it (a provider call "in flight").
 * Unregistered keys (e.g. leftovers from other test classes sharing the database) are never blocked.
 */
public class BlockingPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final Map<ProviderAttemptKey, AtomicInteger> createCalls = new ConcurrentHashMap<>();
    private final Map<ProviderAttemptKey, CountDownLatch> entered = new ConcurrentHashMap<>();
    private final Map<ProviderAttemptKey, CountDownLatch> release = new ConcurrentHashMap<>();

    public BlockingPaymentProvider(PaymentProvider delegate) {
        this.delegate = delegate;
    }

    /** The first create call for {@code key} blocks until {@link #release(ProviderAttemptKey)}. */
    public void blockFirstCreate(ProviderAttemptKey key) {
        entered.put(key, new CountDownLatch(1));
        release.put(key, new CountDownLatch(1));
    }

    public boolean awaitEntered(ProviderAttemptKey key) throws InterruptedException {
        return entered.get(key).await(10, TimeUnit.SECONDS);
    }

    public void release(ProviderAttemptKey key) {
        CountDownLatch latch = release.get(key);
        if (latch != null) {
            latch.countDown();
        }
    }

    public int createCalls(ProviderAttemptKey key) {
        AtomicInteger count = createCalls.get(key);
        return count == null ? 0 : count.get();
    }

    public void reset() {
        release.values().forEach(CountDownLatch::countDown);
        createCalls.clear();
        entered.clear();
        release.clear();
    }

    @Override
    public RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command) {
        int call = createCalls.computeIfAbsent(command.key(), k -> new AtomicInteger()).incrementAndGet();
        CountDownLatch enteredLatch = entered.get(command.key());
        CountDownLatch releaseLatch = release.get(command.key());
        if (call == 1 && enteredLatch != null && releaseLatch != null) {
            enteredLatch.countDown();
            try {
                releaseLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return delegate.createPaymentLink(merchant, command);
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key) {
        return delegate.findPaymentLinkByReference(merchant, key);
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId) {
        return delegate.getPaymentLink(merchant, providerLinkId);
    }

    @Override
    public LookupResult<PaymentEvidence> getPayment(MerchantRef merchant, String providerPaymentId) {
        return delegate.getPayment(merchant, providerPaymentId);
    }
}

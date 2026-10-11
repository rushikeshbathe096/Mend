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
public class BlockingPaymentProvider implements PaymentProvider, com.mend.provider.mock.SyntheticDebitSimulator {

    private final PaymentProvider delegate;
    /** M2 U7: the synthetic debit (mock mode) can be held in flight the same way; null when the delegate has none. */
    private final com.mend.provider.mock.SyntheticDebitSimulator debitDelegate;
    private final Map<ProviderAttemptKey, AtomicInteger> debitCalls = new ConcurrentHashMap<>();
    private final Map<ProviderAttemptKey, AtomicInteger> createCalls = new ConcurrentHashMap<>();
    private final Map<ProviderAttemptKey, CountDownLatch> entered = new ConcurrentHashMap<>();
    private final Map<ProviderAttemptKey, CountDownLatch> release = new ConcurrentHashMap<>();
    /** M2 U9: a create call for the key answers this result instead of reaching the delegate (e.g. RATE_LIMITED). */
    private final Map<ProviderAttemptKey, RequestResult<PaymentLinkSnapshot>> createAnswers = new ConcurrentHashMap<>();

    public BlockingPaymentProvider(PaymentProvider delegate) {
        this.delegate = delegate;
        this.debitDelegate = delegate instanceof com.mend.provider.mock.SyntheticDebitSimulator simulator ? simulator : null;
    }

    /** The first create (or synthetic debit) call for {@code key} blocks until {@link #release(ProviderAttemptKey)}. */
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

    public int debitCalls(ProviderAttemptKey key) {
        AtomicInteger count = debitCalls.get(key);
        return count == null ? 0 : count.get();
    }

    /** Every create call for {@code key} returns {@code answer} (counted, never forwarded to the delegate). */
    public void answerCreate(ProviderAttemptKey key, RequestResult<PaymentLinkSnapshot> answer) {
        createAnswers.put(key, answer);
    }

    public void reset() {
        createAnswers.clear();
        release.values().forEach(CountDownLatch::countDown);
        createCalls.clear();
        debitCalls.clear();
        entered.clear();
        release.clear();
    }

    @Override
    public RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command) {
        int call = createCalls.computeIfAbsent(command.key(), k -> new AtomicInteger()).incrementAndGet();
        holdIfFirst(command.key(), call);
        RequestResult<PaymentLinkSnapshot> answer = createAnswers.get(command.key());
        return answer != null ? answer : delegate.createPaymentLink(merchant, command);
    }

    @Override
    public RequestResult<String> simulateDebit(MerchantRef merchant, java.util.UUID campaignId, java.util.UUID intentId, ProviderAttemptKey key) {
        if (debitDelegate == null) {
            throw new IllegalStateException("delegate has no synthetic debit");
        }
        int call = debitCalls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        holdIfFirst(key, call);
        return debitDelegate.simulateDebit(merchant, campaignId, intentId, key);
    }

    private void holdIfFirst(ProviderAttemptKey key, int call) {
        CountDownLatch enteredLatch = entered.get(key);
        CountDownLatch releaseLatch = release.get(key);
        if (call == 1 && enteredLatch != null && releaseLatch != null) {
            enteredLatch.countDown();
            try {
                releaseLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

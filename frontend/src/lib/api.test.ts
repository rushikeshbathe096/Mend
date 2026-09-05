import { fetchApi } from './api';

describe('API Client Merchant ID Propagation and Stale Recovery', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    localStorage.clear();
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ success: true }),
    });
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it('attaches valid X-Merchant-Id when merchantId option is provided', async () => {
    const user = {
      id: 'u-1',
      email: 'admin@testmerchant.com',
      memberships: [{ merchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65', merchantName: 'Test Merchant' }],
    };
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_token', 'mock-jwt-token');

    await fetchApi('/payments', { merchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65' });

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/payments'),
      expect.objectContaining({
        headers: expect.objectContaining({
          'Authorization': 'Bearer mock-jwt-token',
          'X-Merchant-Id': '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
        }),
      })
    );
  });

  it('recovers from stale localStorage merchant ID by selecting valid user membership', async () => {
    const user = {
      id: 'u-1',
      email: 'admin@testmerchant.com',
      memberships: [{ merchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65', merchantName: 'Test Merchant' }],
    };
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_token', 'mock-jwt-token');
    // Stale merchant ID from an old session
    localStorage.setItem('mend_merchant_id', 'invalid-stale-merchant-id-999');

    await fetchApi('/payments');

    // Asserts X-Merchant-Id was corrected to valid membership
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/payments'),
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Merchant-Id': '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
        }),
      })
    );
    // Asserts localStorage was updated to valid ID
    expect(localStorage.getItem('mend_merchant_id')).toBe('89ac3047-cc4c-47cb-91a9-bf3505aa6a65');
  });

  it('rejects cross-tenant invalid merchantId option and falls back to validated membership', async () => {
    const user = {
      id: 'u-1',
      email: 'admin@testmerchant.com',
      memberships: [{ merchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65', merchantName: 'Test Merchant' }],
    };
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_token', 'mock-jwt-token');

    // Passing a cross-tenant merchant ID that user does not belong to
    await fetchApi('/payments', { merchantId: 'wrong-tenant-id-123' });

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/payments'),
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Merchant-Id': '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
        }),
      })
    );
  });

  it('omits X-Merchant-Id header when user has missing memberships', async () => {
    const user = { id: 'u-1', email: 'no-memberships@test.com', memberships: [] };
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_token', 'mock-jwt-token');
    localStorage.setItem('mend_merchant_id', 'stale-id');

    await fetchApi('/payments');

    const lastCallHeaders = (global.fetch as jest.Mock).mock.calls[0][1].headers;
    expect(lastCallHeaders['X-Merchant-Id']).toBeUndefined();
    expect(localStorage.getItem('mend_merchant_id')).toBeNull();
  });
});

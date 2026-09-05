import { fetchApi, ApiError } from './api';

describe('fetchApi', () => {
  beforeEach(() => {
    localStorage.clear();
    global.fetch = jest.fn();
    jest.restoreAllMocks();
  });

  it('attaches bearer token and merchant header from storage', async () => {
    localStorage.setItem('mend_token', 'jwt-token');
    localStorage.setItem('mend_merchant_id', 'm-456');
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    } as Response);

    await fetchApi('/analytics/overview');

    const [url, init] = (global.fetch as jest.Mock).mock.calls[0];
    expect(url).toContain('/analytics/overview');
    expect(init.headers['Authorization']).toBe('Bearer jwt-token');
    expect(init.headers['X-Merchant-Id']).toBe('m-456');
  });

  it('clears the expired session on 401 and reports the backend message', async () => {
    window.history.replaceState({}, '', '/dashboard');
    localStorage.setItem('mend_token', 'expired-token');
    localStorage.setItem('mend_user', JSON.stringify({ email: 'a@b.com' }));
    localStorage.setItem('mend_merchant_id', 'm-456');
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ message: 'Session expired' }),
    } as Response);

    await expect(fetchApi('/campaigns')).rejects.toMatchObject({
      status: 401,
      message: 'Session expired',
    });

    expect(localStorage.getItem('mend_token')).toBeNull();
    expect(localStorage.getItem('mend_user')).toBeNull();
    expect(localStorage.getItem('mend_merchant_id')).toBeNull();
  });

  it('does not attempt a redirect while already on the login page', async () => {
    window.history.replaceState({}, '', '/login');
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ message: 'Session expired' }),
    } as Response);

    await expect(fetchApi('/campaigns')).rejects.toBeInstanceOf(ApiError);
    expect(localStorage.getItem('mend_token')).toBeNull();
  });

  it('reports network timeouts as a typed ApiError', async () => {
    (global.fetch as jest.Mock).mockImplementation((_url: string, init: RequestInit) => {
      return new Promise((_, reject) => {
        const signal = init?.signal as AbortSignal;
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
      });
    });

    await expect(fetchApi('/campaigns', { timeoutMs: 10 })).rejects.toMatchObject({
      status: 408,
      message: expect.stringContaining('timed out'),
    });
  });
});

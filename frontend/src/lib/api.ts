const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/v1';

/** Error body of a failed API response (the backend's ErrorResponse, or a fallback). */
export type ApiErrorBody = { message?: string; error?: string } & Record<string, unknown>;

export class ApiError extends Error {
  status: number;
  data: ApiErrorBody | undefined;

  constructor(status: number, message: string, data?: ApiErrorBody) {
    super(message);
    this.status = status;
    this.data = data;
    this.name = 'ApiError';
  }
}

export interface RequestOptions extends RequestInit {
  token?: string | null;
  merchantId?: string | null;
  timeoutMs?: number;
}

export async function fetchApi<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const { token, merchantId, timeoutMs = 15000, headers: customHeaders, signal: customSignal, ...customConfig } = options;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(customHeaders as Record<string, string>),
  };

  const storedToken = token !== undefined ? token : (typeof window !== 'undefined' ? localStorage.getItem('mend_token') : null);

  let resolvedMerchantId: string | null = merchantId !== undefined ? merchantId : null;

  if (typeof window !== 'undefined') {
    const storedUserRaw = localStorage.getItem('mend_user');
    let userMemberships: Array<{ merchantId: string }> = [];
    if (storedUserRaw) {
      try {
        const parsedUser = JSON.parse(storedUserRaw);
        userMemberships = parsedUser.memberships || [];
      } catch {
        // ignore JSON parse errors
      }
    }

    const storedMerchantId = localStorage.getItem('mend_merchant_id');

    if (resolvedMerchantId) {
      if (userMemberships.length > 0) {
        const isValid = userMemberships.some((m) => m.merchantId === resolvedMerchantId);
        if (!isValid) {
          resolvedMerchantId = userMemberships[0].merchantId;
          localStorage.setItem('mend_merchant_id', resolvedMerchantId);
        }
      }
    } else {
      if (storedMerchantId && userMemberships.length > 0) {
        const isValid = userMemberships.some((m) => m.merchantId === storedMerchantId);
        if (isValid) {
          resolvedMerchantId = storedMerchantId;
        } else {
          resolvedMerchantId = userMemberships[0].merchantId;
          localStorage.setItem('mend_merchant_id', resolvedMerchantId);
        }
      } else if (userMemberships.length > 0) {
        resolvedMerchantId = userMemberships[0].merchantId;
        localStorage.setItem('mend_merchant_id', resolvedMerchantId);
      } else {
        localStorage.removeItem('mend_merchant_id');
      }
    }
  }

  if (storedToken) {
    headers['Authorization'] = `Bearer ${storedToken}`;
  }

  if (resolvedMerchantId) {
    headers['X-Merchant-Id'] = resolvedMerchantId;
  }

  const url = endpoint.startsWith('http') ? endpoint : `${API_BASE_URL}${endpoint}`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  if (customSignal) {
    customSignal.addEventListener('abort', () => controller.abort());
  }

  try {
    const response = await fetch(url, {
      ...customConfig,
      headers,
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    if (!response.ok) {
      let errorData: ApiErrorBody = {};
      try {
        errorData = await response.json();
      } catch {
        errorData = { message: response.statusText };
      }

      const message = errorData.message || errorData.error || `HTTP ${response.status} Error`;

      if (response.status === 401 && typeof window !== 'undefined') {
        localStorage.removeItem('mend_token');
        localStorage.removeItem('mend_user');
        localStorage.removeItem('mend_merchant_id');
        if (window.location.pathname !== '/login') {
          // A full page load on purpose: this module runs outside React (no router), and the reload drops every in-memory
          // session state. Kept as the one lint warning (@next/next/no-location-assign-relative-destination).
          window.location.assign('/login');
        }
      }

      throw new ApiError(response.status, message, errorData);
    }

    if (response.status === 204) {
      return {} as T;
    }

    return response.json();
  } catch (error: unknown) {
    clearTimeout(timeoutId);
    if (error instanceof Error && error.name === 'AbortError') {
      throw new ApiError(408, 'Request timed out after 15 seconds. Please try again.', { error: 'Request Timeout' });
    }
    throw error;
  }
}

export const api = {
  get: <T>(endpoint: string, options?: RequestOptions) => 
    fetchApi<T>(endpoint, { ...options, method: 'GET' }),

  post: <T>(endpoint: string, body?: unknown, options?: RequestOptions) => 
    fetchApi<T>(endpoint, { ...options, method: 'POST', body: body ? JSON.stringify(body) : undefined }),

  put: <T>(endpoint: string, body?: unknown, options?: RequestOptions) => 
    fetchApi<T>(endpoint, { ...options, method: 'PUT', body: body ? JSON.stringify(body) : undefined }),

  delete: <T>(endpoint: string, options?: RequestOptions) => 
    fetchApi<T>(endpoint, { ...options, method: 'DELETE' }),
};

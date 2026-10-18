import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import type { RequestOptions } from '@/lib/api';
import DashboardPage from './page';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';

jest.mock('@/lib/api', () => ({
  ...jest.requireActual('@/lib/api'),
  api: {
    get: jest.fn(),
  },
}));

jest.mock('@/components/layout/ConsoleLayout', () => ({
  ConsoleLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/dashboard',
}));

jest.mock('@/context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

const mockOverviewData = {
  totalCampaigns: 38,
  recoveredCampaigns: 29,
  activeCampaigns: 5,
  failedCampaigns: 4,
  recoveryRate: 76.32,
  totalAttempts: 0,
  totalActionIntents: 33,
  successfulIntents: 29,
  revenueAtRisk: 421167.0,
  amountRecovered: 43571.0,
  amountRemainingAtRisk: 377596.0,
  totalPaymentFailures: 67,
  successfulRecoveries: 29,
  averageIngestionToCampaignLatencyMs: 1567,
  averageExecutionLatencyMs: 4155454,
  complianceBlocks: 5,
};

const mockCampaignsData = {
  content: [
    {
      id: 'cmp-1',
      merchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
      paymentId: 'pay_demo_test_merchant_513ec372',
      customerIdHash: 'cust_001',
      currentState: 'EXHAUSTED',
      failureClass: 'BANK_DECLINED',
      confidence: 0.93, // legacy field a pre-M3 API row may still carry
      attemptCount: 3,
      createdAt: '2026-09-02T18:57:35Z',
      updatedAt: '2026-09-02T20:07:35Z',
    },
  ],
  page: 0,
  size: 5,
  totalElements: 1,
  totalPages: 1,
  last: true,
};

const mockActionsData = {
  content: [
    {
      id: 'act-1',
      merchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
      campaignId: 'cmp-1',
      attemptNumber: 1,
      actionType: 'RETRY_PAYMENT',
      status: 'SUCCEEDED',
      idempotencyKey: 'intent_demo_1d24df2b',
      scheduledAt: '2026-06-14T07:24:05Z',
      createdAt: '2026-09-05T18:08:35Z',
    },
  ],
  page: 0,
  size: 5,
  totalElements: 1,
  totalPages: 1,
  last: true,
};

const mockReviewSummaryData = {
  pending: 5,
  total: 5,
  byStatus: { PENDING: 5 },
};

const mockFunnelData = {
  totalPaymentFailures: 67,
  stages: [
    { stageName: 'FAILED_PAYMENTS', count: 67, conversionRatePercent: 100.0, dropOffCount: 29 },
    { stageName: 'RECOVERED', count: 29, conversionRatePercent: 43.3, dropOffCount: 0 },
  ],
};

describe('DashboardPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('1. waits for auth state hydration before dashboard fetch', () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: null,
      currentMerchantId: null,
      user: null,
      isLoading: true,
    });

    render(<DashboardPage />);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('2. fetches dashboard APIs and renders real backend values on success', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'test-jwt',
      currentMerchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
      user: { email: 'admin@testmerchant.com' },
      isLoading: false,
    });

    (api.get as jest.Mock).mockImplementation((url: string, options?: RequestOptions) => {
      expect(options?.merchantId).toBe('89ac3047-cc4c-47cb-91a9-bf3505aa6a65');
      if (url === '/analytics/overview') return Promise.resolve(mockOverviewData);
      if (url.startsWith('/campaigns')) return Promise.resolve(mockCampaignsData);
      if (url.startsWith('/recovery/actions')) return Promise.resolve(mockActionsData);
      if (url === '/reviews/summary') return Promise.resolve(mockReviewSummaryData);
      if (url === '/analytics/funnel') return Promise.resolve(mockFunnelData);
      return Promise.resolve({});
    });

    render(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('₹4,21,167.00')).toBeInTheDocument();
      expect(screen.getByText('₹43,571.00')).toBeInTheDocument();
      expect(screen.getByText('₹3,77,596.00')).toBeInTheDocument();
      expect(screen.getByText('76.32% Rate')).toBeInTheDocument();
      expect(screen.getByText('pay_demo_test_merchant_513ec372')).toBeInTheDocument();
      // M3 U2.3 regression: a legacy confidence in the API row is never shown (was "93% Conf"; null rendered "0% Conf")
      expect(screen.queryByText(/% Conf/)).not.toBeInTheDocument();
      expect(screen.getByText('intent_demo_1d24df2b', { exact: false })).toBeInTheDocument();
    });
  });

  it('3. handles dashboard API failure without showing fake zero business data', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'test-jwt',
      currentMerchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
      user: { email: 'admin@testmerchant.com' },
      isLoading: false,
    });

    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url === '/analytics/overview') return Promise.reject(new ApiError(500, 'Failed to fetch overview metrics'));
      return Promise.resolve({});
    });

    render(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('Failed to fetch overview metrics')).toBeInTheDocument();
      expect(screen.queryByText('₹4,21,167.00')).not.toBeInTheDocument();
    });
  });

  it('4. retries dashboard fetch upon clicking Retry button', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'test-jwt',
      currentMerchantId: '89ac3047-cc4c-47cb-91a9-bf3505aa6a65',
      user: { email: 'admin@testmerchant.com' },
      isLoading: false,
    });

    let calls = 0;
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url === '/analytics/overview') {
        calls++;
        if (calls === 1) return Promise.reject(new ApiError(500, 'Network Error'));
        return Promise.resolve(mockOverviewData);
      }
      return Promise.resolve({});
    });

    render(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('Network Error')).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: /retry/i });
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(screen.getByText('₹4,21,167.00')).toBeInTheDocument();
    });
  });

  it('5. correctly renders real zero-data response (₹0.00)', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'test-jwt',
      currentMerchantId: 'zero-merchant',
      user: { email: 'zero@test.com' },
      isLoading: false,
    });

    const zeroOverview = {
      ...mockOverviewData,
      totalCampaigns: 0,
      recoveredCampaigns: 0,
      activeCampaigns: 0,
      failedCampaigns: 0,
      recoveryRate: 0,
      totalActionIntents: 0,
      successfulIntents: 0,
      revenueAtRisk: 0.0,
      amountRecovered: 0.0,
      amountRemainingAtRisk: 0.0,
      totalPaymentFailures: 0,
    };

    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url === '/analytics/overview') return Promise.resolve(zeroOverview);
      return Promise.resolve({ content: [] });
    });

    render(<DashboardPage />);

    await waitFor(() => {
      const zeros = screen.getAllByText('₹0.00');
      expect(zeros.length).toBeGreaterThan(0);
    });
  });
});

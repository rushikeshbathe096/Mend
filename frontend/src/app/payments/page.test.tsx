import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import PaymentsPage from './page';
import { api } from '@/lib/api';

jest.mock('@/lib/api');
jest.mock('@/components/layout/ConsoleLayout', () => ({
  ConsoleLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/payments',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: { memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }] },
    isLoading: false,
  }),
}));

const paymentRow = {
  paymentId: 'pay_test_123',
  customerIdHash: 'cust_test_abc',
  merchantId: 'm-456',
  failureClass: 'INSUFFICIENT_FUNDS',
  amount: 4999,
  currentState: 'ACTION_PENDING',
  strategy: 'RETRY_IMMEDIATELY',
  attemptCount: 1,
  campaignId: 'cmp-1',
  createdAt: '2026-09-04T10:00:00Z',
  updatedAt: '2026-09-04T10:05:00Z',
};

const emptyPage = { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0, last: true };
const onePage = { content: [paymentRow], page: 0, size: 10, totalElements: 1, totalPages: 1, last: true };

describe('PaymentsPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders payments from the backend and links to details', async () => {
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/analytics/recovery')) return Promise.resolve({ failureClassBreakdown: {} });
      if (url.startsWith('/payments')) return Promise.resolve(onePage);
      return Promise.resolve({});
    });

    render(<PaymentsPage />);

    await waitFor(() => {
      expect(screen.getByText('pay_test_123')).toBeInTheDocument();
      expect(screen.getAllByText('INSUFFICIENT_FUNDS').length).toBeGreaterThan(0);
      expect(screen.getByText('RETRY_IMMEDIATELY')).toBeInTheDocument();
      expect(screen.getByText('₹4,999.00')).toBeInTheDocument();
    });
    expect(api.get).toHaveBeenCalledWith('/payments?page=0&size=10');
  });

  it('applies status and failure class filters server-side', async () => {
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/analytics/recovery')) return Promise.resolve({ failureClassBreakdown: { NETWORK_TIMEOUT: 2 } });
      if (url.startsWith('/payments')) return Promise.resolve(onePage);
      return Promise.resolve({});
    });

    render(<PaymentsPage />);
    await waitFor(() => expect(screen.getByText('pay_test_123')).toBeInTheDocument());

    fireEvent.change(screen.getAllByDisplayValue('ALL')[0], { target: { value: 'RECOVERED' } });

    await waitFor(() => {
      expect(api.get).toHaveBeenCalledWith('/payments?page=0&size=10&status=RECOVERED');
    });
  });

  it('renders empty state when no payments exist', async () => {
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/analytics/recovery')) return Promise.resolve({ failureClassBreakdown: {} });
      if (url.startsWith('/payments')) return Promise.resolve(emptyPage);
      return Promise.resolve({});
    });

    render(<PaymentsPage />);

    await waitFor(() => {
      expect(screen.getByText('No Failed Payments Found')).toBeInTheDocument();
    });
  });

  it('renders error state when the API fails', async () => {
    (api.get as jest.Mock).mockRejectedValue(new Error('Failed to load payments from the backend.'));

    render(<PaymentsPage />);

    await waitFor(() => {
      expect(screen.getByText(/Failed to load payments from the backend/i)).toBeInTheDocument();
    });
  });
});

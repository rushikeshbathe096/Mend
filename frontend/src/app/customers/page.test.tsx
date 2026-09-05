import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import CustomersPage from './page';
import { api } from '@/lib/api';

jest.mock('@/lib/api', () => ({
  ...jest.requireActual('@/lib/api'),
  api: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  },
}));
jest.mock('@/components/layout/ConsoleLayout', () => ({
  ConsoleLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/customers',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: { memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }] },
    isLoading: false,
  }),
}));

const customers = [
  {
    customerIdHash: 'cust_alpha_001',
    merchantId: 'm-456',
    totalCampaigns: 5,
    activeCampaigns: 2,
    recoveredCampaigns: 3,
    totalFailedAmount: 50000,
    totalRecoveredAmount: 30000,
    riskSignals: ['REPEAT_FAILURES', 'ACTIVE_RECOVERY_PENDING'],
    lastActivityAt: '2026-09-05T08:00:00Z',
  },
];

describe('CustomersPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders customer recovery profiles from the backend', async () => {
    (api.get as jest.Mock).mockResolvedValue(customers);

    render(<CustomersPage />);

    await waitFor(() => {
      expect(screen.getByText('cust_alpha_001')).toBeInTheDocument();
      expect(screen.getByText('REPEAT_FAILURES')).toBeInTheDocument();
    });
    expect(api.get).toHaveBeenCalledWith('/customers');
  });

  it('filters customers client-side by hash', async () => {
    (api.get as jest.Mock).mockResolvedValue(customers);

    render(<CustomersPage />);
    await waitFor(() => expect(screen.getByText('cust_alpha_001')).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText('Search customer hash...'), { target: { value: 'nonexistent' } });

    await waitFor(() => {
      expect(screen.getByText('No Customer Profiles Found')).toBeInTheDocument();
    });
  });
});

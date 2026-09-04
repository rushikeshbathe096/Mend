import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SettingsPage from './page';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';

jest.mock('@/lib/api');
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/settings',
}));

jest.mock('@/context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

const mockConfig = {
  id: 'cfg-123',
  merchantId: 'm-456',
  maxAttempts: 3,
  maxContactAttempts: 2,
  contactWindowHours: 24,
  retryStrategy: 'SMART_RETRY',
  escalationThreshold: 2,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
};

describe('SettingsPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders loading state initially and fetches merchant config', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'jwt-token',
      currentMerchantId: 'm-456',
      user: {
        memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }],
      },
      isLoading: false,
    });

    (api.get as jest.Mock).mockResolvedValue(mockConfig);

    render(<SettingsPage />);

    await waitFor(() => {
      expect(screen.getByDisplayValue('3')).toBeInTheDocument();
      expect(screen.getByText(/Merchant Recovery Settings/i)).toBeInTheDocument();
    });

    expect(api.get).toHaveBeenCalledWith('/merchants/config');
  });

  it('allows user to edit and submit updated settings', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'jwt-token',
      currentMerchantId: 'm-456',
      currentMerchantName: 'Test Merchant',
      user: {
        memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }],
      },
      isLoading: false,
    });

    (api.get as jest.Mock).mockResolvedValue(mockConfig);
    (api.put as jest.Mock).mockResolvedValue({
      ...mockConfig,
      maxAttempts: 5,
    });

    render(<SettingsPage />);

    await waitFor(() => {
      expect(screen.getByDisplayValue('3')).toBeInTheDocument();
    });

    const input = screen.getByDisplayValue('3');
    fireEvent.change(input, { target: { value: '5' } });

    const saveButton = screen.getByRole('button', { name: /Save Merchant Settings/i });
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(api.put).toHaveBeenCalledWith('/merchants/config', {
        maxAttempts: 5,
        maxContactAttempts: 2,
        contactWindowHours: 24,
        retryStrategy: 'SMART_RETRY',
        escalationThreshold: 2,
        enabledRecoveryActions: ['PAYMENT_RETRY', 'CUSTOMER_NOTIFY'],
      });
      expect(screen.getByText(/Merchant recovery configuration saved successfully!/i)).toBeInTheDocument();
    });
  });
});


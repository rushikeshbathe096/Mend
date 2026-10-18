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

    expect(api.get).toHaveBeenCalledWith('/merchants/config', { merchantId: 'm-456' });
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
      expect(api.put).toHaveBeenCalledWith(
        '/merchants/config',
        {
          maxAttempts: 5,
          maxContactAttempts: 2,
          contactWindowHours: 24,
          retryStrategy: 'SMART_RETRY',
          escalationThreshold: 2,
          enabledRecoveryActions: ['PAYMENT_RETRY', 'CUSTOMER_NOTIFY'],
        },
        { merchantId: 'm-456' }
      );
      expect(screen.getByText(/Merchant recovery configuration saved successfully!/i)).toBeInTheDocument();
    });
  });

  // M3 U2.3 regression: the slider was a 0.1-1.0 "AI confidence"; the backend accepts only an integer 1-10
  it('edits the escalation threshold as an integer from 1 to 10, not as an AI confidence', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      token: 'jwt-token',
      currentMerchantId: 'm-456',
      user: { memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }] },
      isLoading: false,
    });
    (api.get as jest.Mock).mockResolvedValue(mockConfig);
    (api.put as jest.Mock).mockResolvedValue({ ...mockConfig, escalationThreshold: 7 });

    render(<SettingsPage />);

    const slider = await screen.findByRole('slider', { name: /Escalation Threshold/i });
    expect(slider).toHaveAttribute('min', '1');
    expect(slider).toHaveAttribute('max', '10');
    expect(slider).toHaveAttribute('step', '1');
    expect(screen.getByText(/Escalation Threshold \(2 of 10\)/)).toBeInTheDocument();
    expect(screen.queryByText(/confidence threshold/i)).not.toBeInTheDocument();

    fireEvent.change(slider, { target: { value: '7' } });
    fireEvent.click(screen.getByRole('button', { name: /Save Merchant Settings/i }));

    await waitFor(() => {
      expect(api.put).toHaveBeenCalledWith('/merchants/config', expect.objectContaining({ escalationThreshold: 7 }),
        { merchantId: 'm-456' });
    });
  });
});

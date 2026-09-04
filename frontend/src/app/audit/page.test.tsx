import { render, screen, waitFor } from '@testing-library/react';
import AuditLogsPage from './page';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';

jest.mock('@/lib/api');
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/audit',
}));

jest.mock('@/context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

const mockAuditResponse = {
  content: [
    {
      id: 'audit-1',
      merchantId: 'm-456',
      campaignId: 'cmp-789',
      eventType: 'CLASSIFIED',
      actorType: 'AI_AGENT',
      reason: 'AI classification completed with high confidence',
      createdAt: '2026-09-04T10:00:00Z',
    },
  ],
  page: 0,
  size: 15,
  totalElements: 1,
  totalPages: 1,
  last: true,
};

describe('AuditLogsPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({
      token: 'jwt-token',
      currentMerchantId: 'm-456',
      user: {
        memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }],
      },
      isLoading: false,
    });
  });

  it('renders loading state and fetches audit records', async () => {
    (api.get as jest.Mock).mockResolvedValue(mockAuditResponse);

    render(<AuditLogsPage />);

    await waitFor(() => {
      expect(screen.getByText('CLASSIFIED')).toBeInTheDocument();
      expect(screen.getAllByText('AI_AGENT').length).toBeGreaterThan(0);
      expect(screen.getByText('cmp-789')).toBeInTheDocument();
    });

    expect(api.get).toHaveBeenCalledWith('/audit?page=0&size=10');
  });

  it('renders error state when API request fails', async () => {
    (api.get as jest.Mock).mockRejectedValue(new Error('Failed to fetch audit log'));

    render(<AuditLogsPage />);

    await waitFor(() => {
      expect(screen.getByText(/Failed to fetch audit log/i)).toBeInTheDocument();
    });
  });
});


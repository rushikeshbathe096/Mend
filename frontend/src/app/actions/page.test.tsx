import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import RecoveryActionsPage from './page';
import { api, ApiError } from '@/lib/api';

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
  usePathname: () => '/actions',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: {
      memberships: [{ merchantId: 'm-456', merchantName: 'Acme', roleName: 'MERCHANT_ADMIN' }],
    },
    isLoading: false,
  }),
}));

const pendingReview = {
  id: 'review-1',
  merchantId: 'm-456',
  campaignId: 'cmp-789',
  paymentId: 'pay_high_risk_1',
  failureClass: 'AUTHENTICATION_FAILED',
  confidence: 0.9,
  campaignState: 'ELIGIBLE',
  strategy: 'RETRY_IMMEDIATELY',
  amount: 72000,
  reason: 'High-value recovery requires merchant approval.',
  status: 'PENDING',
  createdAt: '2026-09-05T10:00:00Z',
  // pre-M3 fields an old API row may still carry; the page must not render them (M3, ADR-003 §3)
  agentDecision: 'RETRY_PAYMENT',
  agentSelectedAction: 'RETRY_PAYMENT',
  agentConfidence: 0.9,
  agentReasoning: 'High-value transaction exceeds the automated-retry threshold.',
  agentRequiresHumanApproval: true,
};

describe('RecoveryActionsPage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders the approval queue with the failure class and strategy, never agent consensus', async () => {
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/reviews?status=PENDING')) {
        return Promise.resolve({ content: [pendingReview], page: 0, size: 50, totalElements: 1, totalPages: 1, last: true });
      }
      if (url.startsWith('/reviews/summary')) {
        return Promise.resolve({ pending: 1, total: 1, byStatus: { PENDING: 1 } });
      }
      if (url.startsWith('/recovery/actions')) {
        return Promise.resolve({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0, last: true });
      }
      return Promise.resolve({});
    });

    render(<RecoveryActionsPage />);

    await waitFor(() => {
      expect(screen.getByText('pay_high_risk_1')).toBeInTheDocument();
      expect(screen.getByText(/High-value recovery requires merchant approval/i)).toBeInTheDocument();
      // M3 behaviour change: was the agent decision 'RETRY_PAYMENT' (and its 90% confidence)
      expect(screen.getByText('AUTHENTICATION_FAILED')).toBeInTheDocument();
      expect(screen.getByText('RETRY_IMMEDIATELY')).toBeInTheDocument();
    });
    expect(screen.queryByText('RETRY_PAYMENT')).not.toBeInTheDocument();
    expect(screen.queryByText(/Confidence/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/exceeds the automated-retry threshold/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Agent/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve Recovery' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
  });

  it('submits an approve decision to the backend and shows the result', async () => {
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/reviews?status=PENDING')) {
        return Promise.resolve({ content: [pendingReview], page: 0, size: 50, totalElements: 1, totalPages: 1, last: true });
      }
      if (url.startsWith('/reviews/summary')) {
        return Promise.resolve({ pending: 1, total: 1, byStatus: { PENDING: 1 } });
      }
      if (url.startsWith('/recovery/actions')) {
        return Promise.resolve({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0, last: true });
      }
      return Promise.resolve({});
    });

    (api.post as jest.Mock).mockResolvedValue({
      reviewId: 'review-1',
      campaignId: 'cmp-789',
      decision: 'APPROVED',
      message: 'Recovery approved. ActionIntent scheduled for execution.',
      decidedAt: '2026-09-05T10:05:00Z',
      review: { ...pendingReview, status: 'APPROVED' },
    });

    render(<RecoveryActionsPage />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Approve Recovery' })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Approve Recovery' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Confirm Approval' })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approval' }));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith('/reviews/review-1/approve', {}, { merchantId: 'm-456' });
      expect(screen.getByText(/Recovery approved. ActionIntent scheduled for execution/i)).toBeInTheDocument();
    });
  });

  it('shows a clear error when the decision conflicts with the backend state', async () => {
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/reviews?status=PENDING')) {
        return Promise.resolve({ content: [pendingReview], page: 0, size: 50, totalElements: 1, totalPages: 1, last: true });
      }
      if (url.startsWith('/reviews/summary')) {
        return Promise.resolve({ pending: 1, total: 1, byStatus: { PENDING: 1 } });
      }
      if (url.startsWith('/recovery/actions')) {
        return Promise.resolve({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0, last: true });
      }
      return Promise.resolve({});
    });

    const conflictError = new ApiError(400, 'Review has already been resolved (status: APPROVED)');
    (api.post as jest.Mock).mockRejectedValue(conflictError);

    render(<RecoveryActionsPage />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve Recovery' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Approve Recovery' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirm Approval' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Approval' }));

    await waitFor(() => {
      expect(screen.getByText(/Review has already been resolved/i)).toBeInTheDocument();
    });
  });
  it('offers "Resolve without collecting" only for reply reviews and posts to resolve-no-collect (M4 U4.8)', async () => {
    const disputeReview = { ...pendingReview, id: 'review-dispute', reason: 'CUSTOMER_DISPUTE: the reply disputes the charge', kind: 'RESOLVE_NO_COLLECT' };
    const manualReview = { ...pendingReview, id: 'review-manual', kind: 'COLLECT' };
    (api.get as jest.Mock).mockImplementation((url: string) => {
      if (url.startsWith('/reviews?status=PENDING')) {
        return Promise.resolve({ content: [disputeReview, manualReview], page: 0, size: 50, totalElements: 2, totalPages: 1, last: true });
      }
      if (url.startsWith('/reviews/summary')) {
        return Promise.resolve({ pending: 2, total: 2, byStatus: { PENDING: 2 } });
      }
      if (url.startsWith('/recovery/actions')) {
        return Promise.resolve({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0, last: true });
      }
      return Promise.resolve({});
    });
    (api.post as jest.Mock).mockResolvedValue({ message: 'Resolved without collecting. Campaign cmp-789 closed; no money was requested.' });

    render(<RecoveryActionsPage />);
    const buttons = await screen.findAllByRole('button', { name: 'Resolve without collecting' });
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]);
    fireEvent.click(await screen.findByRole('button', { name: /Confirm Resolution/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/reviews/review-dispute/resolve-no-collect', {}, { merchantId: 'm-456' }));
    expect(await screen.findByText(/no money was requested/)).toBeInTheDocument();
  });
});

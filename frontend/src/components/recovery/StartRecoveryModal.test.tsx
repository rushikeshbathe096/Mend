import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StartRecoveryModal } from './StartRecoveryModal';
import { api } from '@/lib/api';

jest.mock('@/lib/api');
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/dashboard',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: { memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }] },
    isLoading: false,
  }),
}));

const samplePayment = {
  paymentId: 'pay_demo_test_123',
  customerIdHash: 'cust_hash_1',
  merchantId: 'm-456',
  failureClass: 'BANK_DECLINED',
  amount: 4750,
  currentState: 'ELIGIBLE',
  attemptCount: 1,
  campaignId: 'cmp-123',
  createdAt: '2026-09-04T10:00:00Z',
  updatedAt: '2026-09-04T10:05:00Z',
};

const mockStartRecoveryResponse = {
  campaign: {
    id: 'cmp-123',
    merchantId: 'm-456',
    paymentId: 'pay_demo_test_123',
    customerIdHash: 'cust_hash_1',
    currentState: 'RECOVERED',
    failureClass: 'BANK_DECLINED',
    attemptCount: 2,
    createdAt: '2026-09-04T10:00:00Z',
    updatedAt: '2026-09-04T10:05:00Z',
  },
  actionIntent: {
    id: 'intent-123',
    merchantId: 'm-456',
    campaignId: 'cmp-123',
    attemptNumber: 2,
    actionType: 'RETRY_PAYMENT',
    status: 'SUCCEEDED',
    idempotencyKey: 'key-123',
    createdAt: '2026-09-04T10:00:00Z',
  },
  executionResult: {
    status: 'SUCCESS',
    externalReference: 'ref_razorpay_999',
    message: 'Payment recovered via provider',
  },
  // M3: a pre-M3 backend sent agent decisions; the modal must not render them (field kept to prove that)
  agentDecisions: [
    {
      id: 'ad-1',
      campaignId: 'cmp-123',
      merchantId: 'm-456',
      decision: 'RETRY_PAYMENT',
      selectedAction: 'RETRY_PAYMENT',
      confidence: 0.95,
      reasoning: 'Transient bank decline. Immediate retry authorized.',
      requiresHumanApproval: false,
      createdAt: '2026-09-04T10:00:00Z',
    },
  ],
  complianceStatus: 'COMPLIANCE_ALLOWED',
  requiresHumanApproval: false,
  message: 'Mock provider simulated a capture (SYNTHETIC, no real money moved).',
};

describe('StartRecoveryModal', () => {
  const onClose = jest.fn();
  const onRecoveryComplete = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('fetches and renders eligible payments in step 1', async () => {
    (api.get as jest.Mock).mockResolvedValue({
      content: [samplePayment],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      last: true,
    });

    render(
      <StartRecoveryModal isOpen={true} onClose={onClose} onRecoveryComplete={onRecoveryComplete} />
    );

    await waitFor(() => {
      expect(screen.getByText('pay_demo_test_123')).toBeInTheDocument();
      expect(screen.getByText('BANK_DECLINED')).toBeInTheDocument();
      expect(screen.getByText('₹4,750.00')).toBeInTheDocument();
    });

    expect(api.get).toHaveBeenCalledWith('/payments?page=0&size=50', { merchantId: 'm-456' });
  });

  it('allows user to select payment, review context, and trigger end-to-end start-recovery', async () => {
    (api.get as jest.Mock).mockResolvedValue({
      content: [samplePayment],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      last: true,
    });
    (api.post as jest.Mock).mockResolvedValue(mockStartRecoveryResponse);

    render(
      <StartRecoveryModal isOpen={true} onClose={onClose} onRecoveryComplete={onRecoveryComplete} />
    );

    await waitFor(() => expect(screen.getByText('pay_demo_test_123')).toBeInTheDocument());

    // Select the payment row radio
    const radio = screen.getByRole('radio');
    fireEvent.click(radio);

    // Click Continue to Review
    const continueBtn = screen.getByRole('button', { name: /Continue to Review/i });
    fireEvent.click(continueBtn);

    // Verify Review step
    await waitFor(() => {
      expect(screen.getByText('Authoritative Recovery Pipeline Execution:')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^Start Recovery$/i })).toBeInTheDocument();
    });

    // Click Start Recovery
    const startBtn = screen.getByRole('button', { name: /^Start Recovery$/i });
    fireEvent.click(startBtn);

    // Verify API call and outcome
    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        '/campaigns/start-recovery',
        { paymentId: 'pay_demo_test_123', customerIdHash: 'cust_hash_1' },
        { merchantId: 'm-456' }
      );
      // U2.3 review fix: a mock SUCCESS is never headed as recovered money, even with the campaign RECOVERED
      expect(screen.getByText('Simulated Capture (mock provider)')).toBeInTheDocument();
      expect(screen.queryByText('Payment Recovered')).not.toBeInTheDocument();
      expect(screen.getByText(/SYNTHETIC, no real money moved/)).toBeInTheDocument();
      expect(onRecoveryComplete).toHaveBeenCalled();
    });
    // M3 behaviour change (ADR-003 §3): agent decisions and confidences are not shown
    expect(screen.queryByText(/Transient bank decline/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/% Confidence/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/agent/i)).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('Simulated Capture (mock provider)')).toBeInTheDocument();
    });
  });
});

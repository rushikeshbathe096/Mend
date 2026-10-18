import { render, screen, waitFor } from '@testing-library/react';
import CustomerProfilePage from './page';
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
  usePathname: () => '/customers/cust_alpha_001',
  useParams: () => ({ customerIdHash: 'cust_alpha_001' }),
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: { memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }] },
    isLoading: false,
  }),
}));

const summary = {
  customerIdHash: 'cust_alpha_001',
  merchantId: 'm-456',
  totalCampaigns: 5,
  activeCampaigns: 1,
  recoveredCampaigns: 1,
  totalFailedAmount: 1149,
  totalRecoveredAmount: 499,
  riskSignals: ['ACTIVE_RECOVERY_PENDING'],
  lastActivityAt: '2026-09-05T08:00:00Z',
};

function profile(extra: Record<string, unknown>) {
  return {
    summary: { ...summary, ...extra },
    campaigns: [],
    paymentHistory: [],
    disputeHistory: [],
    auditHistory: [],
  };
}

function card(title: string): HTMLElement {
  return screen.getByText(title).closest('div')!.parentElement!;
}

describe('CustomerProfilePage attribution and unknown amounts', () => {
  beforeEach(() => jest.clearAllMocks());

  it('shows MEND recoveries, late capture, unknown attribution and unknown amounts separately', async () => {
    (api.get as jest.Mock).mockResolvedValue(
      profile({
        recoveredLateCaptureCount: 2,
        amountRecoveredLateCapture: 250,
        recoveredAttributionUnknownCount: 3,
        amountRecoveredAttributionUnknown: 100,
        amountUnknownCount: 4,
      }),
    );

    render(<CustomerProfilePage />);

    await waitFor(() => expect(screen.getByText('Recovered by MEND')).toBeInTheDocument());
    expect(card('Late Capture')).toHaveTextContent('2');
    expect(card('Late Capture')).toHaveTextContent('₹250.00');
    expect(card('Attribution Unknown')).toHaveTextContent('3');
    expect(card('Attribution Unknown')).toHaveTextContent('₹100.00');
    expect(card('Amount Unknown')).toHaveTextContent('4');
    expect(card('Recovered Value')).toHaveTextContent('₹499.00');
  });

  it('shows SYNTHETIC (mock-provider) recoveries apart, as never revenue (M4 U4.8)', async () => {
    (api.get as jest.Mock).mockResolvedValue(profile({ recoveredSyntheticCount: 5, amountRecoveredSynthetic: 300 }));
    render(<CustomerProfilePage />);
    await waitFor(() => expect(screen.getByText('Recovered by MEND')).toBeInTheDocument());
    expect(card('SYNTHETIC (simulated)')).toHaveTextContent('5');
    expect(card('SYNTHETIC (simulated)')).toHaveTextContent('₹300.00');
    expect(card('SYNTHETIC (simulated)')).toHaveTextContent('never revenue');
  });

  it('shows a dash, not 0, when the backend did not send the new fields', async () => {
    (api.get as jest.Mock).mockResolvedValue(profile({}));

    render(<CustomerProfilePage />);

    await waitFor(() => expect(screen.getByText('Amount Unknown')).toBeInTheDocument());
    expect(card('Amount Unknown')).toHaveTextContent('—');
    expect(card('Amount Unknown')).not.toHaveTextContent(/\b0\b/);
    expect(card('Late Capture')).toHaveTextContent('—');
  });
});

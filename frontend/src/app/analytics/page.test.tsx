import { render, screen } from '@testing-library/react';
import AnalyticsPage from './page';
import { api } from '@/lib/api';

jest.mock('@/lib/api', () => ({
  ...jest.requireActual('@/lib/api'),
  api: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));
jest.mock('@/components/layout/ConsoleLayout', () => ({
  ConsoleLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/analytics',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({ token: 'jwt-token', currentMerchantId: 'm-1', user: { memberships: [] }, isLoading: false }),
}));

const overview = {
  totalCampaigns: 10, recoveredCampaigns: 2, activeCampaigns: 3, failedCampaigns: 1, recoveryRate: 20, totalAttempts: 4,
  totalActionIntents: 4, successfulIntents: 2, amountRecovered: 998, recoveredLateCaptureCount: 3, amountRecoveredLateCapture: 1497,
  recoveredAttributionUnknownCount: 1, amountRecoveredAttributionUnknown: 499, recoveredSyntheticCount: 5, amountRecoveredSynthetic: 2495,
  amountUnknownCount: 2,
};

function mockApi(experiment: unknown) {
  (api.get as jest.Mock).mockImplementation((url: string) => {
    if (url.startsWith('/analytics/overview')) return Promise.resolve(overview);
    if (url.startsWith('/analytics/recovery')) return Promise.resolve({});
    if (url.startsWith('/analytics/experiment')) return Promise.resolve(experiment);
    return Promise.resolve(null);
  });
}

describe('AnalyticsPage (M4 U4.8)', () => {
  beforeEach(() => jest.clearAllMocks());

  it('shows late capture, attribution unknown, SYNTHETIC and amount unknown apart, and calls MEND recoveries observed', async () => {
    mockApi(null);
    render(<AnalyticsPage />);
    expect(await screen.findByText('Late Capture')).toBeInTheDocument();
    expect(screen.getByText('Attribution Unknown')).toBeInTheDocument();
    expect(screen.getByText('SYNTHETIC (simulated)')).toBeInTheDocument();
    expect(screen.getByText(/mock provider: never revenue/)).toBeInTheDocument();
    expect(screen.getByText('Amount Unknown')).toBeInTheDocument();
    expect(screen.getByText('Recovered on a MEND Action (observed)')).toBeInTheDocument();
    expect(screen.queryByText('Total Revenue Recovered')).not.toBeInTheDocument();
  });

  it('shows causal figures only from a snapshot, with their label and n; without one they are TBD', async () => {
    mockApi({
      holdoutRateBp: 2000, assignedTreatment: 80, assignedControl: 20, snapshot: null, note: 'Arm counts are operational (live, not causal).',
      figures: [{ stratum: '-', name: 'incremental recovery rate (TREATMENT − CONTROL)', value: 'TBD', n: 0, label: 'TBD', status: 'TBD' }],
    });
    render(<AnalyticsPage />);
    expect(await screen.findByText('Holdout Experiment (treatment vs control)')).toBeInTheDocument();
    expect(screen.getByText(/Holdout 20.00 %/)).toBeInTheDocument();
    expect(screen.getByText(/causal figures are TBD/)).toBeInTheDocument();
    expect(screen.getAllByText('TBD').length).toBeGreaterThan(0);
  });

  it('labels a SYNTHETIC snapshot as not revenue and INTERIM', async () => {
    mockApi({
      holdoutRateBp: 2000, assignedTreatment: 7989, assignedControl: 2011, note: 'n',
      snapshot: { id: 'abcdef12-0000-0000-0000-000000000000', asOf: '2026-02-12T00:00:00Z', horizonDays: 14, providerMode: 'simulator', label: 'SYNTHETIC', interim: true },
      figures: [{ stratum: 'holdout 2000 bp', name: 'incremental recovery rate (TREATMENT − CONTROL)', value: '8.24 pp', low: '6.61 pp', high: '9.77 pp', n: 10000, label: 'SYNTHETIC', status: 'OK' }],
    });
    render(<AnalyticsPage />);
    expect(await screen.findByText('8.24 pp')).toBeInTheDocument();
    expect(screen.getByText('6.61 pp … 9.77 pp')).toBeInTheDocument();
    expect(screen.getByText(/SYNTHETIC: not revenue/)).toBeInTheDocument();
    expect(screen.getByText(/INTERIM — not for decision/)).toBeInTheDocument();
  });
});

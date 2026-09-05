import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import DemoEnginePage from './page';
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
  usePathname: () => '/demo',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: { memberships: [{ merchantId: 'm-456', merchantName: 'Acme', roleName: 'MERCHANT_ADMIN' }] },
    isLoading: false,
  }),
}));

const scenarios = [
  {
    id: 'LOW_RISK_RETRY',
    title: 'Low-Risk Automated Retry',
    description: 'Transient failure retried automatically.',
    flow: ['Failed payment', 'Classification', 'Recovered'],
  },
  {
    id: 'HIGH_RISK_HUMAN_REVIEW',
    title: 'High-Risk Human Review',
    description: 'High-value failure waits for merchant approval.',
    flow: ['Failed payment', 'HUMAN_APPROVAL'],
  },
];

const runResult = {
  scenario: 'LOW_RISK_RETRY',
  status: 'SUCCESS',
  campaignId: 'cmp-demo-1',
  paymentId: 'pay_demo_low_risk_retry_1',
  amount: 4999,
  message: 'Scenario completed: campaign reached RECOVERED.',
  finalCampaignState: 'RECOVERED',
  executionSteps: ['Provider webhook ingested.', 'Compliance ALLOWED.', 'Recovered via provider.'],
};

describe('DemoEnginePage', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders the scenario catalog from the backend', async () => {
    (api.get as jest.Mock).mockResolvedValue(scenarios);
    render(<DemoEnginePage />);

    await waitFor(() => {
      expect(screen.getByText('Low-Risk Automated Retry')).toBeInTheDocument();
      expect(screen.getByText('High-Risk Human Review')).toBeInTheDocument();
    });
    expect(screen.getAllByText('Run Scenario').length).toBe(2);
    expect(api.get).toHaveBeenCalledWith('/demo/scenarios');
  });

  it('runs a scenario and renders the authoritative execution trace', async () => {
    (api.get as jest.Mock).mockResolvedValue(scenarios);
    (api.post as jest.Mock).mockResolvedValue(runResult);

    render(<DemoEnginePage />);
    await waitFor(() => expect(screen.getAllByText('Run Scenario').length).toBe(2));

    fireEvent.click(screen.getAllByText('Run Scenario')[0]);

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith('/demo/trigger-scenario', { scenario: 'LOW_RISK_RETRY' });
      expect(screen.getByText(/campaign reached RECOVERED/i)).toBeInTheDocument();
      expect(screen.getByText('Provider webhook ingested.')).toBeInTheDocument();
      expect(screen.getAllByText('RECOVERED').length).toBeGreaterThan(0);
    });
  });

  it('surfaces backend errors from the demo engine', async () => {
    (api.get as jest.Mock).mockResolvedValue(scenarios);
    (api.post as jest.Mock).mockRejectedValue(new Error('Scenario execution failed'));

    render(<DemoEnginePage />);
    await waitFor(() => expect(screen.getAllByText('Run Scenario').length).toBe(2));
    fireEvent.click(screen.getAllByText('Run Scenario')[0]);

    await waitFor(() => {
      expect(screen.getByText(/Failed to reach the demo engine/i)).toBeInTheDocument();
    });
  });
});

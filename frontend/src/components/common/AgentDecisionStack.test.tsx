import { fireEvent, render, screen } from '@testing-library/react';
import { AgentDecisionStack } from './AgentDecisionStack';

const decision = {
  id: 'decision-1',
  campaignId: 'campaign-1',
  merchantId: 'merchant-1',
  decision: 'RETRY_PAYMENT',
  selectedAction: 'RETRY_PAYMENT',
  confidence: 0.9,
  reasoning: 'Transient failure and low risk support a retry.',
  evidence: 'NETWORK_FAILURE; LOW_RISK',
  modelVersion: 'test-agent-v1',
  requiresHumanApproval: false,
  createdAt: '2026-09-05T12:00:00Z',
};

describe('AgentDecisionStack', () => {
  it('reveals persisted structured evidence on expansion', () => {
    render(<AgentDecisionStack decisions={[decision]} />);

    expect(screen.getByText('Risk & Fraud')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /RETRY_PAYMENT/i })).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(screen.getByRole('button', { name: /RETRY_PAYMENT/i }));

    expect(screen.getByRole('button', { name: /RETRY_PAYMENT/i })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Transient failure and low risk support a retry.')).toBeInTheDocument();
    expect(screen.getByText('NETWORK_FAILURE; LOW_RISK')).toBeInTheDocument();
  });
});

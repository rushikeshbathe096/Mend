import { render, screen } from '@testing-library/react';
import { FailureClassificationPanel } from './FailureClassificationPanel';
import { ClassificationResultDto } from '@/types';

const base: ClassificationResultDto = {
  id: 'c-1',
  campaignId: 'camp-1',
  failureClass: 'INSUFFICIENT_FUNDS',
  createdAt: '2026-10-18T00:00:00Z',
};

describe('FailureClassificationPanel', () => {
  it('shows the class, the Razorpay reason and source, the rule table and the docs link; never a confidence', () => {
    render(
      <FailureClassificationPanel
        classification={{
          ...base,
          taxonomyVersion: 'razorpay-error-taxonomy-v1',
          evidence: {
            errorReason: 'insufficient_funds',
            errorSource: 'customer',
            documentation: 'https://razorpay.com/docs/errors/payments/list/',
          },
        }}
      />,
    );
    const panel = screen.getByTestId('failure-classification');
    expect(panel).toHaveTextContent('INSUFFICIENT_FUNDS');
    expect(panel).toHaveTextContent('insufficient_funds');
    expect(panel).toHaveTextContent('customer');
    expect(panel).toHaveTextContent('razorpay-error-taxonomy-v1');
    expect(screen.getByText('Razorpay error reference')).toHaveAttribute('href', 'https://razorpay.com/docs/errors/payments/list/');
    expect(panel).not.toHaveTextContent('%');
  });

  it('labels a pre-M3 row as legacy and does not show its AI-era confidence or model', () => {
    // a legacy API row may still carry its old fields; the type no longer declares confidence
    const legacy = { ...base, confidence: 0.95, modelVersion: 'v1.0.0-bounded-heuristic' };
    render(<FailureClassificationPanel classification={legacy} />);
    const panel = screen.getByTestId('failure-classification');
    expect(panel).toHaveTextContent('Legacy classification');
    expect(panel).not.toHaveTextContent('95');
    expect(panel).not.toHaveTextContent('v1.0.0-bounded-heuristic');
  });

  it('says "not sent" when Razorpay sent no error_reason', () => {
    render(<FailureClassificationPanel classification={{ ...base, failureClass: 'GATEWAY_ERROR', taxonomyVersion: 'razorpay-error-taxonomy-v1', evidence: { errorSource: 'gateway' } }} />);
    expect(screen.getByTestId('failure-classification')).toHaveTextContent('not sent');
  });

  it('links only to Razorpay documentation (stored evidence is data)', () => {
    render(<FailureClassificationPanel classification={{ ...base, taxonomyVersion: 'razorpay-error-taxonomy-v1', evidence: { errorReason: 'insufficient_funds', documentation: 'https://evil.example/phish' } }} />);
    expect(screen.queryByText('Razorpay error reference')).not.toBeInTheDocument();
  });
});

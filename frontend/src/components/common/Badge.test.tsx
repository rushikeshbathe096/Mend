import { render, screen } from '@testing-library/react';
import { Badge } from './Badge';

describe('Badge Component', () => {
  it('renders status text correctly', () => {
    render(<Badge status="RECOVERED" />);
    expect(screen.getByText('RECOVERED')).toBeInTheDocument();
  });

  it('handles unknown status gracefully', () => {
    render(<Badge status="UNKNOWN_STATE" />);
    expect(screen.getByText('UNKNOWN_STATE')).toBeInTheDocument();
  });

  it('styles the M2 intent states instead of falling back to the neutral default', () => {
    const cases: Array<[string, string]> = [
      ['EXECUTING', 'bg-indigo-50'],
      ['AWAITING_PAYMENT', 'bg-indigo-50'],
      ['NEEDS_RECONCILIATION', 'bg-amber-50'],
    ];
    for (const [status, style] of cases) {
      const { unmount } = render(<Badge status={status} />);
      expect(screen.getByText(status)).toHaveClass(style);
      unmount();
    }
    render(<Badge status="UNKNOWN_STATE" />);
    expect(screen.getByText('UNKNOWN_STATE')).toHaveClass('bg-slate-50');
  });
});

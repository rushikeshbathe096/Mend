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
});

import { render, screen } from '@testing-library/react';
import { RecoveryLifecycle } from './RecoveryLifecycle';

describe('RecoveryLifecycle', () => {
  it('emphasizes the backend current state and settles completed states', () => {
    render(<RecoveryLifecycle currentState="EXECUTING" />);

    expect(screen.getAllByText('EXECUTING').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Current state')).toBeInTheDocument();
    expect(screen.getAllByText('Completed').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Awaiting state').length).toBeGreaterThan(0);
  });

  it('uses the review path when a real pending review exists', () => {
    render(<RecoveryLifecycle currentState="ACTION_PENDING" hasReview />);

    expect(screen.getByText('HUMAN APPROVAL')).toBeInTheDocument();
    expect(screen.getByText('APPROVED')).toBeInTheDocument();
  });
});

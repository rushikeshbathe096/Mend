import { intentStepStatus } from './intentStatus';

describe('intentStepStatus', () => {
  it('treats only SUCCEEDED as success', () => {
    expect(intentStepStatus('SUCCEEDED')).toBe('COMPLETED');
    // No backend intent status COMPLETED exists; it must not count as success.
    expect(intentStepStatus('COMPLETED')).toBe('PENDING');
  });

  it('shows the M2 in-flight states as in progress, never as success or failure', () => {
    for (const status of ['EXECUTING', 'NEEDS_RECONCILIATION', 'AWAITING_PAYMENT']) {
      expect(intentStepStatus(status)).toBe('IN_PROGRESS');
    }
  });

  it('keeps the existing mapping for the other statuses', () => {
    expect(intentStepStatus('SCHEDULED')).toBe('IN_PROGRESS');
    expect(intentStepStatus('READY')).toBe('IN_PROGRESS');
    expect(intentStepStatus('CLAIMED')).toBe('IN_PROGRESS');
    expect(intentStepStatus('FAILED')).toBe('FAILED');
    expect(intentStepStatus('EXPIRED')).toBe('FAILED');
    expect(intentStepStatus('PENDING')).toBe('PENDING');
    expect(intentStepStatus('CANCELLED')).toBe('PENDING');
    expect(intentStepStatus(undefined)).toBe('PENDING');
  });
});

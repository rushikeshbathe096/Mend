/**
 * Timeline step for an ActionIntent status (backend ActionIntentStatus). The M2 in-flight states (EXECUTING,
 * NEEDS_RECONCILIATION, AWAITING_PAYMENT) are in progress: the outcome is not known yet, so they are neither success
 * nor failure. Only SUCCEEDED is success; the backend has no COMPLETED intent status.
 */
export type IntentStepStatus = 'COMPLETED' | 'FAILED' | 'IN_PROGRESS' | 'PENDING';

const IN_PROGRESS = new Set(['SCHEDULED', 'READY', 'CLAIMED', 'EXECUTING', 'NEEDS_RECONCILIATION', 'AWAITING_PAYMENT']);

export function intentStepStatus(status: string | null | undefined): IntentStepStatus {
  if (status === 'SUCCEEDED') return 'COMPLETED';
  if (status === 'FAILED' || status === 'EXPIRED') return 'FAILED';
  if (status && IN_PROGRESS.has(status)) return 'IN_PROGRESS';
  return 'PENDING';
}

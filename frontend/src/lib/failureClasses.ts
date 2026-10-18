/** The deterministic taxonomy classes (backend `PaymentFailureClass`, ADR-003 §1). Any other stored name is pre-M3. */
export const TAXONOMY_CLASSES = [
  'INSUFFICIENT_FUNDS',
  'LIMIT_EXCEEDED',
  'BANK_TECHNICAL',
  'GATEWAY_ERROR',
  'AUTHENTICATION_FAILURE',
  'CUSTOMER_DECLINED',
  'INSTRUMENT_INVALID',
  'MANDATE_INVALID',
] as const;

export interface FailureClassCounts {
  classified: number;
  unclassified: number;
  /** Pre-M3 (AI-era) class names such as UNKNOWN or BANK_DECLINED: neither classified nor UNCLASSIFIED. */
  legacy: number;
}

export function countFailureClasses(breakdown: Record<string, number> | undefined | null): FailureClassCounts {
  const counts: FailureClassCounts = { classified: 0, unclassified: 0, legacy: 0 };
  for (const [failureClass, count] of Object.entries(breakdown ?? {})) {
    if (failureClass === 'UNCLASSIFIED') counts.unclassified += count;
    else if ((TAXONOMY_CLASSES as readonly string[]).includes(failureClass)) counts.classified += count;
    else counts.legacy += count;
  }
  return counts;
}

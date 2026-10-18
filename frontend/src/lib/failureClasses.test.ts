import { countFailureClasses } from './failureClasses';

describe('countFailureClasses', () => {
  // U2.3 review fix: legacy names (UNKNOWN, BANK_DECLINED) were counted as "classified"
  it('counts taxonomy classes, UNCLASSIFIED and legacy pre-M3 names separately', () => {
    expect(countFailureClasses({ INSUFFICIENT_FUNDS: 3, GATEWAY_ERROR: 1, UNCLASSIFIED: 2, UNKNOWN: 4, BANK_DECLINED: 1 }))
      .toEqual({ classified: 4, unclassified: 2, legacy: 5 });
  });

  it('is all zero without data', () => {
    expect(countFailureClasses(undefined)).toEqual({ classified: 0, unclassified: 0, legacy: 0 });
  });
});

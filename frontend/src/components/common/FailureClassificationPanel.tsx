import React from 'react';
import { ClassificationResultDto } from '@/types';

interface FailureClassificationPanelProps {
  classification: ClassificationResultDto;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined;
}

/**
 * M3 (ADR-003): the failure class and the Razorpay evidence it was derived from. Deterministic rows carry the taxonomy
 * version and no confidence; rows from before M3 are labelled as legacy and their AI-era numbers are not shown.
 */
export const FailureClassificationPanel: React.FC<FailureClassificationPanelProps> = ({ classification }) => {
  const evidence = classification.evidence ?? {};
  const reason = text(evidence.errorReason);
  const source = text(evidence.errorSource);
  // stored evidence is data: only a Razorpay docs URL becomes a link
  const documentation = text(evidence.documentation)?.startsWith('https://razorpay.com/') ? text(evidence.documentation) : undefined;
  const deterministic = Boolean(classification.taxonomyVersion);

  return (
    <div className="space-y-2 text-xs" data-testid="failure-classification">
      <div className="font-bold text-gray-900 dark:text-white">{classification.failureClass}</div>
      {deterministic ? (
        <>
          <div className="text-gray-600 dark:text-gray-300">
            Razorpay error_reason: <span className="font-mono">{reason ?? 'not sent'}</span>
            {source && (
              <>
                {' '}· error_source: <span className="font-mono">{source}</span>
              </>
            )}
          </div>
          <div className="text-gray-500">
            Deterministic rule table {classification.taxonomyVersion}; no model, no confidence.
            {documentation && (
              <>
                {' '}
                <a href={documentation} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                  Razorpay error reference
                </a>
              </>
            )}
          </div>
        </>
      ) : (
        <div className="text-amber-700 dark:text-amber-400">
          Legacy classification recorded before M3; it is not used for any decision.
        </div>
      )}
    </div>
  );
};

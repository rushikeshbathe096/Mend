import React from 'react';
import { ExperimentSummaryDto } from '@/types';

/**
 * M4 U4.8: the holdout experiment. Arm counts are operational; causal figures come only from the latest VALID evaluation
 * snapshot and keep their label (MEASURED / SYNTHETIC / TBD) and n. SYNTHETIC figures are never revenue.
 */
export function ExperimentPanel({ experiment }: { experiment: ExperimentSummaryDto | null }) {
  if (!experiment) {
    return null;
  }
  const rate = (experiment.holdoutRateBp / 100).toFixed(2);
  return (
    <section aria-label="Holdout experiment" className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-2xl p-6 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-bold text-gray-900 dark:text-white">Holdout Experiment (treatment vs control)</h2>
        <span className="text-xs text-gray-500">
          {experiment.holdoutRateBp === 0 ? 'Holdout off (0 %)' : `Holdout ${rate} %`} · TREATMENT {experiment.assignedTreatment} ·
          CONTROL {experiment.assignedControl} (live counts, not causal)
        </span>
      </div>
      <p className="text-xs text-gray-500">{experiment.note}</p>
      {experiment.snapshot ? (
        <p className="text-xs text-gray-600 dark:text-gray-400">
          From snapshot <span className="font-mono">{experiment.snapshot.id.substring(0, 8)}</span> as of {experiment.snapshot.asOf} ·
          horizon {experiment.snapshot.horizonDays} d · label <strong>{experiment.snapshot.label}</strong>
          {experiment.snapshot.interim && ' · INTERIM — not for decision'}
          {experiment.snapshot.label === 'SYNTHETIC' && ' · SYNTHETIC: not revenue'}
        </p>
      ) : (
        <p className="text-xs text-gray-600 dark:text-gray-400">No VALID evaluation snapshot covers this merchant yet: causal figures are TBD.</p>
      )}
      <table className="w-full text-xs">
        <thead>
          <tr className="text-left text-gray-500">
            <th className="py-1">Figure</th>
            <th>Value</th>
            <th>95 % interval</th>
            <th>n</th>
            <th>Label</th>
          </tr>
        </thead>
        <tbody>
          {experiment.figures.map((f) => (
            <tr key={`${f.stratum}-${f.name}`} className="border-t border-gray-100 dark:border-gray-800">
              <td className="py-1 pr-2 text-gray-800 dark:text-gray-200">{f.name}</td>
              <td className="font-mono">{f.value}</td>
              <td className="font-mono">{f.low ? `${f.low} … ${f.high}` : '—'}</td>
              <td>{f.n}</td>
              <td>{f.label}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

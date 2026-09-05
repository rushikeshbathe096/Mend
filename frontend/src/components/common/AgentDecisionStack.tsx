'use client';

import React, { useState } from 'react';
import { AgentDecisionRecordDto } from '@/types';
import { Badge } from '@/components/common/Badge';

interface AgentDecisionStackProps {
  decisions: AgentDecisionRecordDto[];
}

function agentLabel(decision: AgentDecisionRecordDto) {
  if (decision.decision.includes('RISK') || decision.reasoning.toLowerCase().includes('risk')) return 'Risk & Fraud';
  if (decision.selectedAction?.includes('CUSTOMER')) return 'Customer Engagement';
  if (decision.selectedAction?.includes('RETRY') || decision.decision.includes('RETRY')) return 'Recovery Decision';
  if (decision.selectedAction?.includes('REVIEW') || decision.requiresHumanApproval) return 'Recovery Strategy';
  return 'Supervisor';
}

export const AgentDecisionStack: React.FC<AgentDecisionStackProps> = ({ decisions }) => {
  const [expandedId, setExpandedId] = useState<string | null>(null);

  if (decisions.length === 0) {
    return <div className="rounded-xl border border-dashed border-gray-300 p-6 text-center text-sm text-gray-500 dark:border-gray-700">No persisted agent decision records are available for this campaign.</div>;
  }

  return (
    <div className="space-y-3" aria-label="Recovery intelligence decisions">
      {decisions.map((decision, index) => {
        const expanded = expandedId === decision.id;
        const label = agentLabel(decision);
        return (
          <article key={decision.id} className="overflow-hidden rounded-xl border border-gray-200 bg-white transition duration-200 hover:border-blue-200 dark:border-gray-800 dark:bg-gray-900">
            <button type="button" onClick={() => setExpandedId(expanded ? null : decision.id)} aria-expanded={expanded} className="flex w-full items-center justify-between gap-4 p-4 text-left">
              <div className="flex min-w-0 items-center gap-3">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-xs font-black text-slate-600 dark:bg-gray-800 dark:text-gray-300">{index + 1}</div>
                <div className="min-w-0"><div className="text-xs font-bold uppercase tracking-wide text-gray-500">{label}</div><div className="mt-1 truncate font-mono text-sm font-bold text-gray-900 dark:text-white">{decision.selectedAction || decision.decision}</div></div>
              </div>
              <div className="flex shrink-0 items-center gap-3"><Badge status={decision.requiresHumanApproval ? 'REVIEW_REQUIRED' : decision.executionStatus || 'RECORDED'} /><span className="text-gray-400 transition-transform duration-200">{expanded ? '−' : '+'}</span></div>
            </button>
            <div className={`grid transition-[grid-template-rows,opacity] duration-200 ease-out ${expanded ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}><div className="min-h-0 overflow-hidden"><div className="border-t border-gray-100 px-4 pb-4 pt-3 dark:border-gray-800"><div className="grid gap-3 text-xs sm:grid-cols-3"><div><div className="mb-1 font-bold uppercase tracking-wide text-gray-400">Confidence</div><div className="font-mono font-bold text-gray-800 dark:text-gray-200">{decision.confidence !== undefined ? `${(decision.confidence * 100).toFixed(0)}%` : 'N/A'}</div></div><div><div className="mb-1 font-bold uppercase tracking-wide text-gray-400">Version</div><div className="font-mono text-gray-600 dark:text-gray-300">{decision.modelVersion || 'Not supplied'}</div></div><div><div className="mb-1 font-bold uppercase tracking-wide text-gray-400">Recorded</div><div className="font-mono text-gray-600 dark:text-gray-300">{new Date(decision.createdAt).toLocaleString()}</div></div></div><div className="mt-4 rounded-lg bg-slate-50 p-3 text-xs leading-relaxed text-gray-700 dark:bg-gray-800/70 dark:text-gray-300"><div className="mb-1 font-bold text-gray-900 dark:text-white">Structured reasoning summary</div>{decision.reasoning}</div>{decision.evidence && <div className="mt-3 text-xs text-gray-500"><span className="font-bold text-gray-700 dark:text-gray-300">Evidence: </span>{decision.evidence}</div>}</div></div></div>
          </article>
        );
      })}
    </div>
  );
};

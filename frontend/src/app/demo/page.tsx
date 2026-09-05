'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { CardSkeleton } from '@/components/common/Skeleton';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { DemoScenarioDto, DemoTriggerResponse } from '@/types';

const FLOW_STYLE: Record<string, string> = {
  'Failed payment': 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800',
  'Payment failure': 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800',
  'Recovered': 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800',
  'Merchant approval': 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800',
  'HUMAN_APPROVAL': 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:border-amber-800',
  'Supervisor consensus': 'bg-indigo-50 text-indigo-700 border-indigo-200 dark:bg-indigo-950/50 dark:text-indigo-400 dark:border-indigo-800',
};

const flowTone = (step: string): string => {
  const lowered = step.toLowerCase();
  if (lowered.includes('fail') || lowered.includes('declin')) return FLOW_STYLE['Failed payment'];
  if (lowered.includes('recovered') || lowered.includes('approved') || lowered.includes('completes payment') || lowered.includes('authoritative')) return FLOW_STYLE['Recovered'];
  if (lowered.includes('review') || lowered.includes('uncertain') || lowered.includes('timeout') || lowered.includes('duplicate') || lowered.includes('fallback')) return FLOW_STYLE['HUMAN_APPROVAL'];
  if (lowered.includes('consensus') || lowered.includes('reconciliation') || lowered.includes('strategy')) return FLOW_STYLE['Supervisor consensus'];
  return 'bg-gray-50 text-gray-700 border-gray-200 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-700';
};

export default function DemoEnginePage() {
  const { currentMerchantId } = useAuth();

  const [scenarios, setScenarios] = useState<DemoScenarioDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [runningScenario, setRunningScenario] = useState<string | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [result, setResult] = useState<DemoTriggerResponse | null>(null);

  const fetchScenarios = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<DemoScenarioDto[]>('/demo/scenarios');
      setScenarios(data || []);
    } catch (err: any) {
      setError(err.message || 'Failed to load the demo scenario catalog.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId]);

  useEffect(() => {
    fetchScenarios();
  }, [fetchScenarios]);

  const runScenario = async (scenarioId: string) => {
    setRunningScenario(scenarioId);
    setRunError(null);
    setResult(null);
    try {
      const res = await api.post<DemoTriggerResponse>('/demo/trigger-scenario', { scenario: scenarioId });
      setResult(res);
    } catch (err: any) {
      if (err instanceof ApiError) {
        setRunError(err.message || 'The demo scenario failed to run.');
      } else {
        setRunError('Failed to reach the demo engine.');
      }
    } finally {
      setRunningScenario(null);
    }
  };

  const terminalState = result?.finalCampaignState;

  return (
    <ConsoleLayout>
      <div className="space-y-8">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <div className="flex items-center space-x-3">
              <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">Demo Engine</h1>
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-50 text-amber-700 dark:bg-amber-950/60 dark:text-amber-400 border border-amber-200 dark:border-amber-800">
                Test Workspace
              </span>
            </div>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Deterministic scenarios that run through Mend&apos;s real recovery services - no manual database editing
            </p>
          </div>
        </div>

        <div className="p-4 rounded-2xl bg-blue-50 dark:bg-blue-950/30 border border-blue-200 dark:border-blue-800 text-xs text-blue-800 dark:text-blue-300 leading-relaxed">
          Demo runs execute the same production code paths (campaign state machine, agent decision records, compliance-gated
          ActionIntents, provider execution and reconciliation). Rows are isolated with the <code className="font-mono font-bold">pay_demo_*</code>{' '}
          reference prefix and are audit-logged under your merchant. Provider calls run against the configured{' '}
          <code className="font-mono font-bold">mend.payment.provider</code> (mock/test-mode by default) - no real money moves.
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchScenarios} />}
        {runError && <ErrorAlert message={runError} />}

        {loading ? (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
          </div>
        ) : scenarios.length === 0 ? (
          <EmptyState title="No Demo Scenarios Available" description="The demo catalog could not be loaded from the backend." />
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {scenarios.map((scenario) => {
              const isRunning = runningScenario === scenario.id;
              const isDone = result?.scenario === scenario.id && result.status === 'SUCCESS';
              return (
                <div
                  key={scenario.id}
                  className={`bg-white dark:bg-gray-900 rounded-2xl border p-6 shadow-sm space-y-4 transition-colors ${
                    isDone ? 'border-emerald-300 dark:border-emerald-800' : 'border-gray-200 dark:border-gray-800'
                  }`}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div>
                      <div className="text-[10px] font-bold uppercase tracking-widest text-blue-600 dark:text-blue-400 font-mono mb-1">
                        {scenario.id}
                      </div>
                      <h2 className="text-lg font-bold text-gray-900 dark:text-white leading-snug">{scenario.title}</h2>
                      <p className="text-xs text-gray-500 dark:text-gray-400 mt-1.5 leading-relaxed">{scenario.description}</p>
                    </div>
                    {isDone && <Badge status="SUCCESS" />}
                  </div>

                  <div className="flex flex-wrap gap-1.5">
                    {scenario.flow.map((step, i) => (
                      <span key={`${step}-${i}`} className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${flowTone(step)}`}>
                        {step}
                      </span>
                    ))}
                  </div>

                  <div className="pt-2">
                    <button
                      onClick={() => runScenario(scenario.id)}
                      disabled={isRunning || runningScenario !== null}
                      className="w-full py-2.5 rounded-xl bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-xs font-bold transition-colors flex items-center justify-center space-x-2"
                    >
                      {isRunning && <span className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />}
                      <span>{isRunning ? 'Running scenario...' : 'Run Scenario'}</span>
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {result && result.status === 'SUCCESS' && (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-5">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
              <div>
                <div className="flex items-center space-x-3">
                  <h2 className="text-lg font-bold text-gray-900 dark:text-white">{result.scenario}</h2>
                  {terminalState && <Badge status={terminalState} />}
                  {result.reviewId && <Badge status="REVIEW_REQUIRED" />}
                </div>
                <p className="text-sm text-gray-600 dark:text-gray-400 mt-1">{result.message}</p>
              </div>

              <div className="flex flex-wrap gap-2 shrink-0">
                {result.campaignId && (
                  <Link
                    href={`/campaigns/${result.campaignId}`}
                    className="px-4 py-2 bg-blue-50 dark:bg-blue-950/60 text-blue-700 dark:text-blue-400 border border-blue-200 dark:border-blue-800 rounded-xl text-xs font-bold hover:bg-blue-100 transition-colors"
                  >
                    View Campaign &rarr;
                  </Link>
                )}
                {result.paymentId && (
                  <Link
                    href={`/payments/${encodeURIComponent(result.paymentId)}`}
                    className="px-4 py-2 border border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
                  >
                    View Payment
                  </Link>
                )}
                {result.reviewId && (
                  <Link
                    href="/actions"
                    className="px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white rounded-xl text-xs font-bold transition-colors"
                  >
                    Open Approval Queue
                  </Link>
                )}
              </div>
            </div>

            {result.campaignId && (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                <div className="p-3 rounded-xl bg-gray-50 dark:bg-gray-800/70 border border-gray-200 dark:border-gray-700">
                  <div className="text-gray-500 font-semibold uppercase tracking-wide text-[10px] mb-1">Campaign</div>
                  <div className="font-mono font-bold text-gray-900 dark:text-white truncate">{result.campaignId}</div>
                </div>
                <div className="p-3 rounded-xl bg-gray-50 dark:bg-gray-800/70 border border-gray-200 dark:border-gray-700">
                  <div className="text-gray-500 font-semibold uppercase tracking-wide text-[10px] mb-1">Payment</div>
                  <div className="font-mono font-bold text-gray-900 dark:text-white truncate">{result.paymentId || 'N/A'}</div>
                </div>
                <div className="p-3 rounded-xl bg-gray-50 dark:bg-gray-800/70 border border-gray-200 dark:border-gray-700">
                  <div className="text-gray-500 font-semibold uppercase tracking-wide text-[10px] mb-1">Review</div>
                  <div className="font-mono font-bold text-gray-900 dark:text-white truncate">{result.reviewId || 'None'}</div>
                </div>
                <div className="p-3 rounded-xl bg-gray-50 dark:bg-gray-800/70 border border-gray-200 dark:border-gray-700">
                  <div className="text-gray-500 font-semibold uppercase tracking-wide text-[10px] mb-1">Final State</div>
                  <div className="font-mono font-bold text-gray-900 dark:text-white truncate">{result.finalCampaignState || 'N/A'}</div>
                </div>
              </div>
            )}

            <div className="border border-gray-200 dark:border-gray-700 rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 bg-gray-50 dark:bg-gray-800/70 border-b border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wide">
                Execution Trace
              </div>
              <ol className="divide-y divide-gray-100 dark:divide-gray-800">
                {(result.executionSteps || []).map((step, i) => (
                  <li key={i} className="flex items-start space-x-3 px-4 py-3 text-xs">
                    <span className="w-5 h-5 rounded-full bg-blue-50 dark:bg-blue-950 text-blue-700 dark:text-blue-400 flex items-center justify-center font-mono font-bold text-[10px] shrink-0 mt-px">
                      {i + 1}
                    </span>
                    <span className="text-gray-700 dark:text-gray-300 font-medium leading-relaxed">{step}</span>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        )}
      </div>
    </ConsoleLayout>
  );
}

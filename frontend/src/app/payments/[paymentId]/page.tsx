'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { MetricCard, formatCurrency } from '@/components/common/MetricCard';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { DetailSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { PaymentDetailDto } from '@/types';

export default function PaymentDetailPage() {
  const params = useParams();
  const paymentId = params?.paymentId as string;
  const { currentMerchantId } = useAuth();

  const [detail, setDetail] = useState<PaymentDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchPayment = useCallback(async () => {
    if (!currentMerchantId || !paymentId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<PaymentDetailDto>(`/payments/${encodeURIComponent(paymentId)}`);
      setDetail(data);
    } catch (err: any) {
      setError(err.message || 'Failed to load payment details from the backend.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, paymentId]);

  useEffect(() => {
    fetchPayment();
  }, [fetchPayment]);

  const summary = detail?.paymentSummary;

  return (
    <ConsoleLayout>
      <div className="space-y-8">
        <div className="flex items-center space-x-2 text-xs font-semibold text-gray-500">
          <Link href="/payments" className="hover:text-blue-600 transition-colors">
            Payments
          </Link>
          <span>&rarr;</span>
          <span className="text-gray-900 dark:text-white font-mono truncate max-w-[220px]">{paymentId}</span>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchPayment} />}

        {loading ? (
          <DetailSkeleton />
        ) : !detail || !summary ? (
          <EmptyState
            title="Payment Not Found"
            description="This payment could not be found for the current merchant context. Verify the reference and tenant."
          />
        ) : (
          <>
            <div className="bg-white dark:bg-gray-900 p-6 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-6">
              <div>
                <div className="flex items-center space-x-3 flex-wrap gap-y-2">
                  <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white font-mono">{summary.paymentId}</h1>
                  <Badge status={summary.currentState} />
                </div>
                <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                  Failed at {new Date(summary.createdAt).toLocaleString()}
                  {summary.updatedAt && ` &bull; Last activity ${new Date(summary.updatedAt).toLocaleString()}`}
                </p>
              </div>
              <div className="flex items-center space-x-3">
                {detail.campaign && (
                  <Link
                    href={`/campaigns/${detail.campaign.id}`}
                    className="px-4 py-2 bg-blue-50 dark:bg-blue-950/60 text-blue-700 dark:text-blue-400 border border-blue-200 dark:border-blue-800 rounded-xl text-xs font-bold hover:bg-blue-100 dark:hover:bg-blue-950 transition-colors"
                  >
                    View Campaign &rarr;
                  </Link>
                )}
                <button
                  onClick={fetchPayment}
                  className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 transition-colors shadow-sm"
                >
                  Refresh
                </button>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
              <MetricCard
                title="Transaction Value"
                value={formatCurrency(summary.amount)}
                subtext={summary.amount === null || summary.amount === undefined ? 'Amount not present in provider payload' : 'Authoritative provider payload amount'}
                variant="rose"
              />
              <MetricCard
                title="Failure Class"
                value={summary.failureClass || 'UNKNOWN'}
                subtext="AI-classified root cause"
                variant="indigo"
              />
              <MetricCard
                title="Recovery Strategy"
                value={summary.strategy || 'NOT_EVALUATED'}
                subtext="Selected by the recovery strategy engine"
                variant="amber"
              />
              <MetricCard
                title="Recovery Attempts"
                value={summary.attemptCount ?? 0}
                subtext="Executed provider attempts"
                variant="default"
              />
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
              {/* Customer context */}
              <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
                <h2 className="text-base font-bold text-gray-900 dark:text-white mb-4">Customer Context</h2>
                {summary.customerIdHash ? (
                  <div className="space-y-3">
                    <div>
                      <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Customer Hash</span>
                      <Link
                        href={`/customers/${encodeURIComponent(summary.customerIdHash)}`}
                        className="block font-mono font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 hover:underline break-all mt-1"
                      >
                        {summary.customerIdHash}
                      </Link>
                    </div>
                    <div>
                      <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Campaign Owner</span>
                      <div className="text-sm font-mono text-gray-700 dark:text-gray-300 mt-1">{summary.merchantId}</div>
                    </div>
                  </div>
                ) : (
                  <p className="text-sm text-gray-500">This payment is not attributed to a customer profile.</p>
                )}
              </div>

              {/* Recovery outcome summary */}
              <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
                <h2 className="text-base font-bold text-gray-900 dark:text-white mb-4">Recovery Outcome</h2>
                <div className="space-y-3">
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Current State</span>
                    <Badge status={summary.currentState} />
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Action Intents</span>
                    <span className="font-mono font-bold">{detail.actionIntents?.length ?? 0}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Provider Attempts</span>
                    <span className="font-mono font-bold">{detail.attempts?.length ?? 0}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Compliance Evaluations</span>
                    <span className="font-mono font-bold">{detail.complianceDecisions?.length ?? 0}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Agent Decisions</span>
                    <span className="font-mono font-bold">{detail.agentDecisions?.length ?? 0}</span>
                  </div>
                  <p className="text-[11px] text-gray-400 pt-2 border-t border-gray-100 dark:border-gray-800">
                    Provider credentials are never displayed. All financial and state values originate from the authoritative backend.
                  </p>
                </div>
              </div>
            </div>

            {/* Agent Decision Transparency */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
              <div className="flex items-center justify-between mb-4">
                <div>
                  <h2 className="text-base font-bold text-gray-900 dark:text-white">AI Decision Transparency</h2>
                  <p className="text-xs text-gray-500 dark:text-gray-400">Structured evidence summaries - never hidden chain-of-thought</p>
                </div>
              </div>

              {!detail.agentDecisions || detail.agentDecisions.length === 0 ? (
                <p className="text-sm text-gray-500 italic py-6 text-center">
                  No structured agent decision records exist for this payment yet.
                </p>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {detail.agentDecisions.map((ad) => (
                    <div key={ad.id} className="p-5 rounded-2xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/50 space-y-3">
                      <div className="flex items-center justify-between gap-2 flex-wrap">
                        <div className="flex items-center space-x-2">
                          <span className="text-sm font-bold text-gray-900 dark:text-white">{ad.decision || 'DECISION'}</span>
                          {ad.confidence !== undefined && ad.confidence !== null && (
                            <span className="px-2 py-0.5 rounded text-[11px] font-mono font-bold bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300">
                              {(ad.confidence * 100).toFixed(0)}%
                            </span>
                          )}
                        </div>
                        <span className="text-[10px] text-gray-400 font-mono">{new Date(ad.createdAt).toLocaleString()}</span>
                      </div>
                      {ad.selectedAction && (
                        <div className="text-xs">
                          <span className="font-bold text-gray-500 uppercase tracking-wide">Proposed action:</span>{' '}
                          <span className="font-mono text-gray-800 dark:text-gray-200">{ad.selectedAction}</span>
                        </div>
                      )}
                      <div className="text-xs text-gray-700 dark:text-gray-300 bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 p-3 font-medium">
                        {ad.reasoning || 'No reasoning summary stored.'}
                      </div>
                      {ad.evidence && ad.evidence !== '' && (
                        <div className="text-[11px] text-gray-500 font-mono break-words">Evidence: {ad.evidence}</div>
                      )}
                      {ad.requiresHumanApproval && (
                        <div className="flex items-center space-x-2 text-[11px] font-bold text-amber-700 dark:text-amber-400">
                          <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
                          Required merchant human approval
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Execution history: ActionIntents */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
              <h2 className="text-base font-bold text-gray-900 dark:text-white mb-4">Execution History</h2>
              {!detail.actionIntents || detail.actionIntents.length === 0 ? (
                <p className="text-sm text-gray-500 italic py-6 text-center">No ActionIntents have been created for this payment.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-gray-50 dark:bg-gray-800 text-gray-500 font-bold uppercase border-b border-gray-200 dark:border-gray-800">
                      <tr>
                        <th className="px-4 py-3">Attempt</th>
                        <th className="px-4 py-3">Action</th>
                        <th className="px-4 py-3">Status</th>
                        <th className="px-4 py-3">Idempotency Key</th>
                        <th className="px-4 py-3">Provider Ref</th>
                        <th className="px-4 py-3">Scheduled</th>
                        <th className="px-4 py-3">Completed</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-mono">
                      {detail.actionIntents.map((ai) => (
                        <tr key={ai.id} className="hover:bg-gray-50 dark:hover:bg-gray-800/40">
                          <td className="px-4 py-3 font-bold">#{ai.attemptNumber}</td>
                          <td className="px-4 py-3 font-sans font-bold">{ai.actionType}</td>
                          <td className="px-4 py-3">
                            <Badge status={ai.status} type="action" />
                          </td>
                          <td className="px-4 py-3 text-gray-500 text-[11px] truncate max-w-[180px]">{ai.idempotencyKey}</td>
                          <td className="px-4 py-3 text-gray-500">{ai.responseReference || 'N/A'}</td>
                          <td className="px-4 py-3 text-gray-500">{new Date(ai.scheduledAt).toLocaleString()}</td>
                          <td className="px-4 py-3 text-gray-500">{ai.completedAt ? new Date(ai.completedAt).toLocaleString() : 'N/A'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* Attempts & compliance */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
              <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
                <h2 className="text-base font-bold text-gray-900 dark:text-white mb-4">Recovery Attempts</h2>
                {!detail.attempts || detail.attempts.length === 0 ? (
                  <p className="text-sm text-gray-500 italic py-6 text-center">No provider attempts recorded.</p>
                ) : (
                  <div className="space-y-3">
                    {detail.attempts.map((a) => (
                      <div key={a.id} className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/50 flex items-start justify-between">
                        <div>
                          <div className="text-sm font-bold font-mono">Attempt #{a.attemptNumber}</div>
                          <div className="text-xs text-gray-500 font-mono mt-0.5">{a.actionType || 'N/A'}</div>
                          {a.failureReason && <div className="text-xs text-rose-600 dark:text-rose-400 mt-1">{a.failureReason}</div>}
                          {a.externalReference && <div className="text-[11px] text-gray-400 font-mono mt-0.5">Ref: {a.externalReference}</div>}
                        </div>
                        <Badge status={a.status || 'UNKNOWN'} />
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
                <h2 className="text-base font-bold text-gray-900 dark:text-white mb-4">Compliance Decisions</h2>
                {!detail.complianceDecisions || detail.complianceDecisions.length === 0 ? (
                  <p className="text-sm text-gray-500 italic py-6 text-center">No compliance evaluations recorded for this payment.</p>
                ) : (
                  <div className="space-y-3">
                    {detail.complianceDecisions.map((cd) => (
                      <div key={cd.id} className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/50">
                        <div className="flex items-center justify-between">
                          <span className="text-sm font-bold font-mono">{cd.strategy}</span>
                          <Badge status={cd.status} />
                        </div>
                        <p className="text-xs text-gray-600 dark:text-gray-300 mt-2">{cd.reason}</p>
                        {cd.detailMessage && <p className="text-xs text-gray-500 font-mono mt-1">{cd.detailMessage}</p>}
                        <div className="text-[10px] text-gray-400 font-mono mt-1">Policy {cd.policyVersion} &bull; {new Date(cd.evaluatedAt).toLocaleString()}</div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Audit trail */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
              <h2 className="text-base font-bold text-gray-900 dark:text-white mb-4">Audit Trail</h2>
              {!detail.auditLogs || detail.auditLogs.length === 0 ? (
                <p className="text-sm text-gray-500 italic py-6 text-center">No audit events recorded for this payment.</p>
              ) : (
                <div className="space-y-2">
                  {detail.auditLogs.map((al) => (
                    <div key={al.id} className="flex items-start justify-between gap-4 p-3 rounded-lg border border-gray-100 dark:border-gray-800 bg-gray-50/40 dark:bg-gray-800/30 text-xs">
                      <div>
                        <span className="font-bold font-mono text-gray-900 dark:text-white">{al.eventType}</span>
                        <span className="text-gray-500 ml-2">Actor: {al.actorType || 'SYSTEM'}</span>
                        {al.reason && <p className="text-gray-600 dark:text-gray-400 mt-0.5">{al.reason}</p>}
                      </div>
                      <span className="text-[11px] text-gray-400 font-mono shrink-0">{new Date(al.createdAt).toLocaleString()}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </ConsoleLayout>
  );
}

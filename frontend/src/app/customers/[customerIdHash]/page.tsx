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
import { CustomerProfileDto } from '@/types';

export default function CustomerProfilePage() {
  const params = useParams();
  const customerIdHash = params?.customerIdHash as string;
  const { currentMerchantId } = useAuth();

  const [profile, setProfile] = useState<CustomerProfileDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'payments' | 'campaigns' | 'activity'>('payments');

  const fetchProfile = useCallback(async () => {
    if (!currentMerchantId || !customerIdHash) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<CustomerProfileDto>(`/customers/${encodeURIComponent(customerIdHash)}`);
      setProfile(data);
    } catch (err: any) {
      setError(err.message || 'Failed to load the customer recovery profile.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, customerIdHash]);

  useEffect(() => {
    fetchProfile();
  }, [fetchProfile]);

  const summary = profile?.summary;

  return (
    <ConsoleLayout>
      <div className="space-y-8">
        <div className="flex items-center space-x-2 text-xs font-semibold text-gray-500">
          <Link href="/customers" className="hover:text-blue-600 transition-colors">
            Customers
          </Link>
          <span>&rarr;</span>
          <span className="text-gray-900 dark:text-white font-mono truncate max-w-[220px]">{customerIdHash}</span>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchProfile} />}

        {loading ? (
          <DetailSkeleton />
        ) : !profile || !summary ? (
          <EmptyState
            title="Customer Profile Not Found"
            description="This customer could not be found for the current merchant context."
          />
        ) : (
          <>
            <div className="bg-white dark:bg-gray-900 p-6 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm">
              <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                  <div className="flex items-center space-x-3 flex-wrap gap-y-2">
                    <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white font-mono break-all">{summary.customerIdHash}</h1>
                  </div>
                  <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                    Recovery profile for merchant {summary.merchantId}
                    {summary.lastActivityAt && ` &bull; Last activity ${new Date(summary.lastActivityAt).toLocaleString()}`}
                  </p>
                </div>
                <button
                  onClick={fetchProfile}
                  className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 transition-colors shadow-sm self-start md:self-auto"
                >
                  Refresh
                </button>
              </div>

              <div className="flex flex-wrap gap-2 mt-4">
                {(summary.riskSignals || []).map((signal) => (
                  <span
                    key={signal}
                    className={`px-2.5 py-1 rounded-full text-[11px] font-bold border ${
                      signal.includes('REPEAT') || signal.includes('DISPUTE')
                        ? 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:border-amber-800'
                        : signal.includes('ACTIVE')
                        ? 'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-950/50 dark:text-blue-400 dark:border-blue-800'
                        : 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800'
                    }`}
                  >
                    {signal}
                  </span>
                ))}
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
              <MetricCard title="Total Campaigns" value={summary.totalCampaigns} subtext="Recovery campaigns attributed" variant="default" />
              <MetricCard title="Active Recovery" value={summary.activeCampaigns} subtext="Campaigns in progress" variant="amber" />
              <MetricCard title="Recovered" value={summary.recoveredCampaigns} subtext="Successful recoveries" variant="emerald" />
              <MetricCard
                title="Recovered Value"
                value={formatCurrency(summary.totalRecoveredAmount)}
                subtext="Of failed volume"
                variant="indigo"
              />
            </div>

            {profile.disputeHistory && profile.disputeHistory.length > 0 && (
              <div className="bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-900 rounded-2xl p-5">
                <h2 className="text-sm font-bold text-rose-800 dark:text-rose-300 mb-2">Dispute History</h2>
                <ul className="list-disc list-inside text-xs text-rose-700 dark:text-rose-300/80 space-y-1">
                  {profile.disputeHistory.map((d, i) => (
                    <li key={i}>{d}</li>
                  ))}
                </ul>
              </div>
            )}

            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
              <div className="flex border-b border-gray-200 dark:border-gray-800 px-6 bg-gray-50/50 dark:bg-gray-800/40 space-x-6 overflow-x-auto no-scrollbar">
                {[
                  { id: 'payments', label: `Payment History (${profile.paymentHistory?.length || 0})` },
                  { id: 'campaigns', label: `Recovery Campaigns (${profile.campaigns?.length || 0})` },
                  { id: 'activity', label: `Activity & Audit (${profile.auditHistory?.length || 0})` },
                ].map((tab) => (
                  <button
                    key={tab.id}
                    onClick={() => setActiveTab(tab.id as any)}
                    className={`py-4 text-xs font-bold whitespace-nowrap border-b-2 transition-colors ${
                      activeTab === tab.id
                        ? 'border-blue-600 text-blue-600 dark:text-blue-400 dark:border-blue-400'
                        : 'border-transparent text-gray-500 hover:text-gray-900 dark:hover:text-white'
                    }`}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>

              <div className="p-6">
                {activeTab === 'payments' && (
                  <div className="space-y-3">
                    {!profile.paymentHistory || profile.paymentHistory.length === 0 ? (
                      <p className="text-sm text-gray-500 italic text-center py-6">No payment history for this customer.</p>
                    ) : (
                      <div className="space-y-2">
                        {profile.paymentHistory.map((p) => (
                          <div key={p.paymentId} className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/40 flex items-center justify-between gap-3">
                            <div className="min-w-0">
                              <Link
                                href={`/payments/${encodeURIComponent(p.paymentId)}`}
                                className="font-mono font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 hover:underline"
                              >
                                {p.paymentId}
                              </Link>
                              <div className="text-[11px] text-gray-500 font-mono mt-0.5">
                                {p.failureClass || 'UNKNOWN'} &bull; {p.strategy || 'NO_STRATEGY'}
                              </div>
                            </div>
                            <div className="text-right shrink-0">
                              <div className="font-mono font-bold text-gray-900 dark:text-white">{formatCurrency(p.amount)}</div>
                              <div className="mt-1">
                                <Badge status={p.currentState} />
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {activeTab === 'campaigns' && (
                  <div className="space-y-3">
                    {!profile.campaigns || profile.campaigns.length === 0 ? (
                      <p className="text-sm text-gray-500 italic text-center py-6">No recovery campaigns for this customer.</p>
                    ) : (
                      profile.campaigns.map((c) => (
                        <div key={c.id} className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/40 flex items-center justify-between gap-3">
                          <div className="min-w-0">
                            <Link
                              href={`/campaigns/${c.id}`}
                              className="font-mono font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 hover:underline"
                            >
                              {c.paymentId}
                            </Link>
                            <div className="text-[11px] text-gray-500 font-mono mt-0.5">
                              {c.id} &bull; Attempts: {c.attemptCount ?? 0}
                            </div>
                          </div>
                          <div className="shrink-0">
                            <Badge status={c.currentState} />
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                )}

                {activeTab === 'activity' && (
                  <div className="space-y-2">
                    {!profile.auditHistory || profile.auditHistory.length === 0 ? (
                      <p className="text-sm text-gray-500 italic text-center py-6">No activity history recorded.</p>
                    ) : (
                      profile.auditHistory.map((al) => (
                        <div key={al.id} className="p-3 rounded-lg border border-gray-100 dark:border-gray-800 bg-gray-50/40 dark:bg-gray-800/30 flex items-start justify-between gap-4 text-xs">
                          <div>
                            <span className="font-bold font-mono text-gray-900 dark:text-white">{al.eventType}</span>
                            <span className="text-gray-500 ml-2">Actor: {al.actorType || 'SYSTEM'}</span>
                            {al.reason && <p className="text-gray-600 dark:text-gray-400 mt-0.5">{al.reason}</p>}
                          </div>
                          <span className="text-[11px] text-gray-400 font-mono shrink-0">{new Date(al.createdAt).toLocaleString()}</span>
                        </div>
                      ))
                    )}
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </div>
    </ConsoleLayout>
  );
}

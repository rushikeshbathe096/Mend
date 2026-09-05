'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { MetricCard, formatCurrency } from '@/components/common/MetricCard';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton, CardSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { AnalyticsOverviewDto, CampaignDto, ActionIntentDto, PageResponse, ReviewQueueSummaryDto, AnalyticsFunnelDto } from '@/types';

export default function DashboardPage() {
  const { currentMerchantId } = useAuth();

  const [overview, setOverview] = useState<AnalyticsOverviewDto | null>(null);
  const [recentCampaigns, setRecentCampaigns] = useState<CampaignDto[]>([]);
  const [pendingActions, setPendingActions] = useState<ActionIntentDto[]>([]);
  const [reviewSummary, setReviewSummary] = useState<ReviewQueueSummaryDto | null>(null);
  const [funnel, setFunnel] = useState<AnalyticsFunnelDto | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchDashboardData = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      const [overviewData, campaignsData, actionsData, reviewsData, funnelData] = await Promise.all([
        api.get<AnalyticsOverviewDto>('/analytics/overview'),
        api.get<PageResponse<CampaignDto>>('/campaigns?page=0&size=5&sortBy=createdAt&sortOrder=desc'),
        api.get<PageResponse<ActionIntentDto>>('/recovery/actions?page=0&size=5'),
        api.get<ReviewQueueSummaryDto>('/reviews/summary').catch(() => null),
        api.get<AnalyticsFunnelDto>('/analytics/funnel').catch(() => null),
      ]);

      setOverview(overviewData);
      setRecentCampaigns(campaignsData.content || []);
      setPendingActions(actionsData.content || []);
      setReviewSummary(reviewsData);
      setFunnel(funnelData);
    } catch (err: any) {
      setError(err.message || 'Failed to load dashboard metrics from backend server.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId]);

  useEffect(() => {
    fetchDashboardData();
  }, [fetchDashboardData]);

  return (
    <ConsoleLayout>
      <div className="space-y-8">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white flex items-center space-x-3">
              <span>Recovery Command Center</span>
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400 border border-emerald-200 dark:border-emerald-800">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mr-1.5 animate-pulse" />
                Live Feed
              </span>
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Automated payment recovery analytics, active campaign queues, and execution telemetry.
            </p>
          </div>
          <button
            onClick={fetchDashboardData}
            disabled={loading}
            className="self-start sm:self-auto px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Sync Live Telemetry</span>
          </button>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchDashboardData} />}

        {/* Primary Financial Metric Cards */}
        {loading ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
            <MetricCard
              title="Revenue at Risk"
              value={formatCurrency(overview?.revenueAtRisk ?? 0)}
              subtext={`${overview?.totalPaymentFailures ?? 0} total payment failures ingested`}
              variant="rose"
              icon={
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              }
            />

            <MetricCard
              title="Revenue Recovered"
              value={formatCurrency(overview?.amountRecovered ?? 0)}
              subtext={`${overview?.successfulRecoveries ?? overview?.recoveredCampaigns ?? 0} campaigns successfully recovered`}
              variant="emerald"
              trend={{ value: `${overview?.recoveryRate ?? 0}% Rate`, positive: true }}
              icon={
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              }
            />

            <MetricCard
              title="Remaining at Risk"
              value={formatCurrency(overview?.amountRemainingAtRisk ?? 0)}
              subtext={`${overview?.activeCampaigns ?? 0} active campaigns in progress`}
              variant="amber"
              icon={
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              }
            />

            <MetricCard
              title="Action Intents"
              value={overview?.totalActionIntents ?? 0}
              subtext={`${overview?.successfulIntents ?? 0} executed successfully`}
              variant="indigo"
              icon={
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              }
            />
          </div>
        )}

        {/* Recovery Funnel (authoritative backend stages) */}
        {!loading && funnel && funnel.stages && funnel.stages.length > 0 && (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
            <div className="flex items-center justify-between mb-5">
              <div>
                <h2 className="text-base font-bold text-gray-900 dark:text-white">Recovery Funnel</h2>
                <p className="text-xs text-gray-500 dark:text-gray-400">Live campaign pipeline from the analytics service</p>
              </div>
              <Link href="/analytics" className="text-xs font-bold text-blue-600 hover:text-blue-700 dark:text-blue-400">
                Full Analytics &rarr;
              </Link>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-3">
              {funnel.stages.map((stage, idx) => (
                <Link
                  key={stage.stageName}
                  href={stage.stageName === 'RECOVERED' ? '/campaigns?status=RECOVERED' : '/campaigns'}
                  className="rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/60 dark:bg-gray-800/40 p-3 hover:border-blue-300 dark:hover:border-blue-700 transition-colors"
                >
                  <div className="text-[10px] font-bold uppercase tracking-wide text-gray-500 truncate" title={stage.stageName}>
                    {idx + 1}. {stage.stageName.replace(/_/g, ' ')}
                  </div>
                  <div className="text-xl font-black font-mono text-gray-900 dark:text-white mt-1">{stage.count}</div>
                  <div className="text-[10px] font-mono text-gray-400 mt-0.5">{stage.conversionRatePercent}%</div>
                </Link>
              ))}
            </div>
          </div>
        )}

        {/* Latency & Governance Telemetry Banner */}
        {!loading && overview && (
          <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-2xl p-6 shadow-sm grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
            <div className="flex items-center space-x-4 pt-4 md:pt-0">
              <div className="w-10 h-10 rounded-xl bg-blue-50 dark:bg-blue-950/50 flex items-center justify-center text-blue-600 dark:text-blue-400">
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <div>
                <span className="text-xs font-semibold text-gray-500 dark:text-gray-400">Ingestion Latency</span>
                <div className="text-lg font-bold text-gray-900 dark:text-white font-mono">
                  {overview.averageIngestionToCampaignLatencyMs ?? 0} ms
                </div>
                <span className="text-[11px] text-gray-400">Webhook to Campaign creation</span>
              </div>
            </div>

            <div className="flex items-center space-x-4 pt-4 md:pt-0 md:pl-6">
              <div className="w-10 h-10 rounded-xl bg-emerald-50 dark:bg-emerald-950/50 flex items-center justify-center text-emerald-600 dark:text-emerald-400">
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              </div>
              <div>
                <span className="text-xs font-semibold text-gray-500 dark:text-gray-400">Execution Latency</span>
                <div className="text-lg font-bold text-gray-900 dark:text-white font-mono">
                  {overview.averageExecutionLatencyMs ?? 0} ms
                </div>
                <span className="text-[11px] text-gray-400">Scheduled to Intent completed</span>
              </div>
            </div>              <div className="flex items-center space-x-4 pt-4 md:pt-0">
              <div className="w-10 h-10 rounded-xl bg-amber-50 dark:bg-amber-950/50 flex items-center justify-center text-amber-600 dark:text-amber-400">
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
                </svg>
              </div>
              <div>
                <span className="text-xs font-semibold text-gray-500 dark:text-gray-400">Compliance Blocks</span>
                <div className="text-lg font-bold text-gray-900 dark:text-white font-mono">
                  {overview.complianceBlocks ?? 0} actions
                </div>
                <span className="text-[11px] text-gray-400">Safely blocked by policy engine</span>
              </div>
            </div>

            <div className="flex items-center space-x-4 pt-4 md:pt-0">
              <div className="w-10 h-10 rounded-xl bg-rose-50 dark:bg-rose-950/50 flex items-center justify-center text-rose-600 dark:text-rose-400">
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2m14-12a4 4 0 11-8 0 4 4 0 018 0z" />
                </svg>
              </div>
              <Link href="/actions" className="block">
                <span className="text-xs font-semibold text-gray-500 dark:text-gray-400">Human Review Backlog</span>
                <div className="text-lg font-bold text-gray-900 dark:text-white font-mono">
                  {reviewSummary?.pending ?? 0} pending
                </div>
                <span className="text-[11px] text-gray-400">Approvals waiting in the review queue</span>
              </Link>
            </div>
          </div>
        )}

        {/* Activity & Action Queue Grid */}
        {loading ? (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
            <TableSkeleton rows={4} columns={3} />
            <TableSkeleton rows={4} columns={3} />
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
            {/* Active Campaigns Section */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between mb-4 pb-3 border-b border-gray-100 dark:border-gray-800">
                  <div>
                    <h2 className="text-base font-bold text-gray-900 dark:text-white">Recent Recovery Campaigns</h2>
                    <p className="text-xs text-gray-500 dark:text-gray-400">Active and recently resolved recovery pipelines</p>
                  </div>
                  <Link
                    href="/campaigns"
                    className="text-xs font-bold text-blue-600 hover:text-blue-700 dark:text-blue-400 transition-colors"
                  >
                    View All &rarr;
                  </Link>
                </div>

                {recentCampaigns.length === 0 ? (
                  <EmptyState
                    title="No Active Recovery Campaigns"
                    description="No payment failure campaigns are currently active. New webhooks will automatically trigger recovery campaigns."
                  />
                ) : (
                  <div className="divide-y divide-gray-100 dark:divide-gray-800">
                    {recentCampaigns.map((c) => (
                      <div key={c.id} className="py-3.5 flex items-center justify-between hover:bg-gray-50/50 dark:hover:bg-gray-800/30 px-2 rounded-xl transition-colors">
                        <div>
                          <Link
                            href={`/campaigns/${c.id}`}
                            className="text-sm font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 transition-colors font-mono"
                          >
                            {c.paymentId}
                          </Link>
                          <div className="flex items-center space-x-2 text-xs text-gray-500 dark:text-gray-400 mt-0.5">
                            <span className="font-semibold text-gray-700 dark:text-gray-300">{c.failureClass || 'UNCLASSIFIED'}</span>
                            <span>&bull;</span>
                            <span>Attempts: {c.attemptCount}</span>
                          </div>
                        </div>
                        <div className="flex items-center space-x-3">
                          {c.confidence !== undefined && (
                            <span className="text-xs font-mono font-semibold text-gray-500">
                              {(c.confidence * 100).toFixed(0)}% Conf
                            </span>
                          )}
                          <Badge status={c.currentState} />
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Recent Action Intents Queue */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between mb-4 pb-3 border-b border-gray-100 dark:border-gray-800">
                  <div>
                    <h2 className="text-base font-bold text-gray-900 dark:text-white">Action Execution Queue</h2>
                    <p className="text-xs text-gray-500 dark:text-gray-400">Scheduled, ready, and executed recovery actions</p>
                  </div>
                  <Link
                    href="/actions"
                    className="text-xs font-bold text-blue-600 hover:text-blue-700 dark:text-blue-400 transition-colors"
                  >
                    View All &rarr;
                  </Link>
                </div>

                {pendingActions.length === 0 ? (
                  <EmptyState
                    title="No Pending Recovery Actions"
                    description="Action scheduler queue is clear. No scheduled recovery actions pending."
                  />
                ) : (
                  <div className="divide-y divide-gray-100 dark:divide-gray-800">
                    {pendingActions.map((a) => (
                      <div key={a.id} className="py-3.5 flex items-center justify-between hover:bg-gray-50/50 dark:hover:bg-gray-800/30 px-2 rounded-xl transition-colors">
                        <div>
                          <div className="text-sm font-bold text-gray-900 dark:text-white font-mono">
                            {a.actionType} <span className="text-xs text-gray-500 font-sans">(Attempt #{a.attemptNumber})</span>
                          </div>
                          <div className="text-[11px] text-gray-400 font-mono truncate max-w-xs mt-0.5">
                            Key: {a.idempotencyKey}
                          </div>
                        </div>
                        <Badge status={a.status} />
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </ConsoleLayout>
  );
}

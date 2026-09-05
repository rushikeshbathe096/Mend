'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { MetricCard, formatCurrency } from '@/components/common/MetricCard';
import { ErrorAlert } from '@/components/common/Feedback';
import { CardSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import {
  AnalyticsOverviewDto,
  AnalyticsRecoveryDto,
  AnalyticsFunnelDto,
  AnalyticsFailureBreakdownDto,
  AnalyticsStrategyPerformanceDto,
  ReviewQueueSummaryDto,
} from '@/types';

export default function AnalyticsPage() {
  const { currentMerchantId } = useAuth();

  const [overview, setOverview] = useState<AnalyticsOverviewDto | null>(null);
  const [recovery, setRecovery] = useState<AnalyticsRecoveryDto | null>(null);
  const [funnel, setFunnel] = useState<AnalyticsFunnelDto | null>(null);
  const [failureBreakdown, setFailureBreakdown] = useState<AnalyticsFailureBreakdownDto | null>(null);
  const [strategyPerformance, setStrategyPerformance] = useState<AnalyticsStrategyPerformanceDto | null>(null);
  const [reviewSummary, setReviewSummary] = useState<ReviewQueueSummaryDto | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAnalytics = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      const [overviewData, recoveryData, funnelData, failureData, strategyData, reviewsData] = await Promise.all([
        api.get<AnalyticsOverviewDto>('/analytics/overview'),
        api.get<AnalyticsRecoveryDto>('/analytics/recovery'),
        api.get<AnalyticsFunnelDto>('/analytics/funnel').catch(() => null),
        api.get<AnalyticsFailureBreakdownDto>('/analytics/failure-breakdown').catch(() => null),
        api.get<AnalyticsStrategyPerformanceDto>('/analytics/strategy-performance').catch(() => null),
        api.get<ReviewQueueSummaryDto>('/reviews/summary').catch(() => null),
      ]);

      setOverview(overviewData);
      setRecovery(recoveryData);
      setFunnel(funnelData);
      setFailureBreakdown(failureData);
      setStrategyPerformance(strategyData);
      setReviewSummary(reviewsData);
    } catch (err: any) {
      setError(err.message || 'Failed to load recovery analytics telemetry.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId]);

  useEffect(() => {
    fetchAnalytics();
  }, [fetchAnalytics]);

  return (
    <ConsoleLayout>
      <div className="space-y-8">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">
              Recovery Analytics & AI Telemetry
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              In-depth payment recovery intelligence, AI model performance, and strategy breakdown
            </p>
          </div>

          <button
            onClick={fetchAnalytics}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Refresh Telemetry</span>
          </button>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchAnalytics} />}

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
              subtext={`${overview?.totalPaymentFailures ?? 0} total ingested payment failures`}
              variant="rose"
            />
            <MetricCard
              title="Total Revenue Recovered"
              value={formatCurrency(overview?.amountRecovered ?? 0)}
              subtext={`${overview?.recoveredCampaigns ?? 0} successful campaign recoveries`}
              variant="emerald"
              trend={{ value: `${overview?.recoveryRate ?? 0}% Rate`, positive: true }}
            />
            <MetricCard
              title="Remaining Revenue at Risk"
              value={formatCurrency(overview?.amountRemainingAtRisk ?? 0)}
              subtext={`${overview?.activeCampaigns ?? 0} active recovery pipelines`}
              variant="amber"
            />
            <MetricCard
              title="Avg Execution Latency"
              value={`${overview?.averageExecutionLatencyMs ?? 0} ms`}
              subtext={`Ingestion latency: ${overview?.averageIngestionToCampaignLatencyMs ?? 0} ms`}
              variant="indigo"
            />
          </div>
        )}

        {/* Recovery Funnel from the analytics service */}
        {!loading && funnel && funnel.stages && funnel.stages.length > 0 && (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
            <h2 className="text-base font-bold text-gray-900 dark:text-white">Recovery Funnel</h2>
            <p className="text-xs text-gray-500 dark:text-gray-400 mb-5">
              {funnel.totalPaymentFailures} total payment failures ingested
            </p>
            <div className="space-y-4">
              {funnel.stages.map((stage, idx) => {
                const width = Math.min(100, Math.max(stage.conversionRatePercent, 0));
                const last = idx === funnel.stages.length - 1;
                return (
                  <div key={stage.stageName}>
                    <div className="flex justify-between text-xs font-semibold mb-1.5">
                      <span className={last ? 'text-emerald-600 dark:text-emerald-400' : 'text-gray-700 dark:text-gray-300'}>
                        {idx + 1}. {stage.stageName.replace(/_/g, ' ')}
                      </span>
                      <span className={`font-mono ${last ? 'text-emerald-600 dark:text-emerald-400 font-bold' : 'text-gray-500'}`}>
                        {stage.count} ({stage.conversionRatePercent}%)
                      </span>
                    </div>
                    <div className="w-full h-2.5 rounded-full bg-gray-100 dark:bg-gray-800 overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all duration-500 ${
                          last ? 'bg-emerald-500' : 'bg-gradient-to-r from-blue-600 to-indigo-600'
                        }`}
                        style={{ width: `${Math.max(1, width)}%` }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* Breakdown Visualizations */}
        {!loading && recovery && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
            {/* Failure Classification Breakdown */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-4">
              <div>
                <h2 className="text-base font-bold text-gray-900 dark:text-white">
                  Failure Category Breakdown
                </h2>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  Root cause analysis derived from webhook payload classification
                </p>
              </div>

              {Object.keys(recovery.failureClassBreakdown || {}).length === 0 ? (
                <p className="text-xs text-gray-500 italic py-6 text-center">No failure class data accumulated yet.</p>
              ) : (
                <div className="space-y-4 pt-2">
                  {Object.entries(recovery.failureClassBreakdown || {}).map(([fc, count]) => {
                    const total = overview?.totalCampaigns || 1;
                    const pct = Math.round((count / total) * 100);
                    return (
                      <div key={fc} className="space-y-1.5">
                        <div className="flex justify-between text-xs font-semibold">
                          <span className="text-gray-800 dark:text-gray-200 font-mono">{fc}</span>
                          <span className="text-gray-500 font-mono">
                            {count} ({pct}%)
                          </span>
                        </div>
                        <div className="w-full h-2.5 bg-gray-100 dark:bg-gray-800 rounded-full overflow-hidden">
                          <div
                            className="h-full bg-gradient-to-r from-blue-600 to-indigo-600 rounded-full transition-all duration-500"
                            style={{ width: `${Math.min(100, Math.max(5, pct))}%` }}
                          />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Strategy Performance Breakdown */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-4">
              <div>
                <h2 className="text-base font-bold text-gray-900 dark:text-white">
                  Strategy Performance & Recovery Rates
                </h2>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  Success rate distribution across deployed recovery strategies
                </p>
              </div>

              {Object.keys(recovery.strategyBreakdown || {}).length === 0 ? (
                <p className="text-xs text-gray-500 italic py-6 text-center">No strategy breakdown data accumulated yet.</p>
              ) : (
                <div className="space-y-4 pt-2">
                  {Object.entries(recovery.strategyBreakdown || {}).map(([strat, count]) => {
                    const rate = recovery.recoveryRateByStrategy?.[strat] ?? 0;
                    return (
                      <div key={strat} className="p-3.5 rounded-xl border border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/40 space-y-2">
                        <div className="flex items-center justify-between text-xs font-bold">
                          <span className="text-gray-900 dark:text-white font-mono">{strat}</span>
                          <span className="text-emerald-600 dark:text-emerald-400 font-mono">
                            {rate}% Recovery Rate
                          </span>
                        </div>
                        <div className="flex items-center justify-between text-[11px] text-gray-500">
                          <span>Total Execution Campaigns: {count}</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* AI Model Intelligence Telemetry */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-4">
              <div>
                <h2 className="text-base font-bold text-gray-900 dark:text-white">
                  AI Orchestrator Confidence
                </h2>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  Model prediction certainty & governance evaluation
                </p>
              </div>

              <div className="grid grid-cols-2 gap-4 pt-2">
                <div className="p-4 rounded-xl bg-blue-50/50 dark:bg-blue-950/30 border border-blue-100 dark:border-blue-900">
                  <span className="text-xs font-semibold text-blue-700 dark:text-blue-400 block">Avg Confidence</span>
                  <div className="text-2xl font-black text-blue-900 dark:text-blue-100 font-mono mt-1">
                    {recovery.aiConfidenceMetrics?.averageConfidence !== undefined && recovery.aiConfidenceMetrics?.averageConfidence !== null
                      ? `${(recovery.aiConfidenceMetrics.averageConfidence * 100).toFixed(0)}%`
                      : `${(overview?.recoveryRate ?? 0).toFixed(0)}%`}
                  </div>
                </div>

                <div className="p-4 rounded-xl bg-emerald-50/50 dark:bg-emerald-950/30 border border-emerald-100 dark:border-emerald-900">
                  <span className="text-xs font-semibold text-emerald-700 dark:text-emerald-400 block">High Confidence</span>
                  <div className="text-2xl font-black text-emerald-900 dark:text-emerald-100 font-mono mt-1">
                    {recovery.aiConfidenceMetrics?.highConfidenceCount ?? overview?.totalCampaigns ?? 0}
                  </div>
                </div>
              </div>
            </div>

            {/* Compliance Policy Gate Stats */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-4">
              <div>
                <h2 className="text-base font-bold text-gray-900 dark:text-white">
                  Compliance Policy Engine
                </h2>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  Automated regulatory & contact window policy gating
                </p>
              </div>

              <div className="grid grid-cols-2 gap-4 pt-2">
                <div className="p-4 rounded-xl bg-emerald-50/50 dark:bg-emerald-950/30 border border-emerald-100 dark:border-emerald-900">
                  <span className="text-xs font-semibold text-emerald-700 dark:text-emerald-400 block">Actions Allowed</span>
                  <div className="text-2xl font-black text-emerald-900 dark:text-emerald-100 font-mono mt-1">
                    {recovery.complianceMetrics?.allowedCount ?? overview?.successfulIntents ?? 0}
                  </div>
                </div>

                <div className="p-4 rounded-xl bg-amber-50/50 dark:bg-amber-950/30 border border-amber-100 dark:border-amber-900">
                  <span className="text-xs font-semibold text-amber-700 dark:text-amber-400 block">Actions Blocked</span>
                  <div className="text-2xl font-black text-amber-900 dark:text-amber-100 font-mono mt-1">
                    {recovery.complianceMetrics?.blockedCount ?? overview?.complianceBlocks ?? 0}
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Failure breakdown + strategy performance tables */}
        {!loading && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
              <h2 className="text-base font-bold text-gray-900 dark:text-white mb-1">Failure Breakdown</h2>
              <p className="text-xs text-gray-500 dark:text-gray-400 mb-4">Recovery performance by failure class</p>
              {!failureBreakdown || failureBreakdown.failureClasses.length === 0 ? (
                <p className="text-xs text-gray-500 italic py-6 text-center">No failure-class metrics available yet.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase border-b border-gray-200 dark:border-gray-800">
                      <tr>
                        <th className="px-3 py-2.5">Failure Class</th>
                        <th className="px-3 py-2.5 text-right">Count</th>
                        <th className="px-3 py-2.5 text-right">Recovered</th>
                        <th className="px-3 py-2.5 text-right">Rate</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
                      {failureBreakdown.failureClasses.map((fc) => (
                        <tr key={fc.failureClass} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40">
                          <td className="px-3 py-3 font-mono font-bold">{fc.failureClass}</td>
                          <td className="px-3 py-3 text-right font-mono">{fc.count}</td>
                          <td className="px-3 py-3 text-right font-mono text-emerald-600 dark:text-emerald-400">{fc.recoveredCount}</td>
                          <td className="px-3 py-3 text-right font-mono">{fc.recoveryRatePercent}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm">
              <h2 className="text-base font-bold text-gray-900 dark:text-white mb-1">Strategy Performance</h2>
              <p className="text-xs text-gray-500 dark:text-gray-400 mb-4">Attempts, success rates and recovered value by strategy</p>
              {!strategyPerformance || strategyPerformance.strategies.length === 0 ? (
                <p className="text-xs text-gray-500 italic py-6 text-center">No strategy metrics available yet.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase border-b border-gray-200 dark:border-gray-800">
                      <tr>
                        <th className="px-3 py-2.5">Strategy</th>
                        <th className="px-3 py-2.5 text-right">Campaigns</th>
                        <th className="px-3 py-2.5 text-right">Recovered</th>
                        <th className="px-3 py-2.5 text-right">Success</th>
                        <th className="px-3 py-2.5 text-right">Value Recovered</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
                      {strategyPerformance.strategies.map((s) => (
                        <tr key={s.strategyName} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40">
                          <td className="px-3 py-3 font-mono font-bold">{s.strategyName}</td>
                          <td className="px-3 py-3 text-right font-mono">{s.totalCampaigns}</td>
                          <td className="px-3 py-3 text-right font-mono text-emerald-600 dark:text-emerald-400">{s.recoveredCampaigns}</td>
                          <td className="px-3 py-3 text-right font-mono">{s.successRatePercent}%</td>
                          <td className="px-3 py-3 text-right font-mono">{formatCurrency(s.revenueRecovered)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Agent intelligence overview */}
        {!loading && recovery && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-5 shadow-sm">
              <div className="text-xs font-bold uppercase tracking-wide text-gray-500">Human Review Backlog</div>
              <div className="text-2xl font-black font-mono mt-1 text-amber-600 dark:text-amber-400">{reviewSummary?.pending ?? 0}</div>
              <div className="text-[11px] text-gray-400 mt-1">Reviews waiting for a merchant decision</div>
            </div>
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-5 shadow-sm">
              <div className="text-xs font-bold uppercase tracking-wide text-gray-500">Compliance Blocked</div>
              <div className="text-2xl font-black font-mono mt-1 text-rose-600 dark:text-rose-400">{overview?.complianceBlocks ?? 0}</div>
              <div className="text-[11px] text-gray-400 mt-1">Actions stopped by the policy engine</div>
            </div>
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-5 shadow-sm">
              <div className="text-xs font-bold uppercase tracking-wide text-gray-500">Failed Attempts</div>
              <div className="text-2xl font-black font-mono mt-1 text-rose-600 dark:text-rose-400">{overview?.failedRecoveryAttempts ?? 0}</div>
              <div className="text-[11px] text-gray-400 mt-1">Failed provider executions (error/declined)</div>
            </div>
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-5 shadow-sm">
              <div className="text-xs font-bold uppercase tracking-wide text-gray-500">Total Recovery Attempts</div>
              <div className="text-2xl font-black font-mono mt-1 text-gray-900 dark:text-white">{overview?.totalAttempts ?? 0}</div>
              <div className="text-[11px] text-gray-400 mt-1">Across all campaigns</div>
            </div>
          </div>
        )}
      </div>
    </ConsoleLayout>
  );
}

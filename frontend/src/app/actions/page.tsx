'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { Modal } from '@/components/common/Modal';
import { Pagination } from '@/components/common/Pagination';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { formatCurrency } from '@/components/common/MetricCard';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import {
  ActionIntentDto,
  PageResponse,
  ReviewItemDto,
  ReviewQueueSummaryDto,
  ReviewDecisionResponse,
} from '@/types';

const ACTION_STATUSES = ['ALL', 'SCHEDULED', 'READY', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED'];

type Tab = 'approvals' | 'intents';

export default function RecoveryActionsPage() {
  const { currentMerchantId, user } = useAuth();
  const currentRole = user?.memberships?.find((m) => m.merchantId === currentMerchantId)?.roleName || '';
  const canDecide = currentRole === 'MERCHANT_ADMIN' || currentRole === 'REVIEWER' || currentRole === 'SYSTEM_ADMIN';

  const [tab, setTab] = useState<Tab>('approvals');

  // Intents state
  const [actions, setActions] = useState<ActionIntentDto[]>([]);
  const [intentsPage, setIntentsPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [selectedStatus, setSelectedStatus] = useState('ALL');

  // Approvals state
  const [reviews, setReviews] = useState<ReviewItemDto[]>([]);
  const [summary, setSummary] = useState<ReviewQueueSummaryDto | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Decision modal state
  const [activeReview, setActiveReview] = useState<ReviewItemDto | null>(null);
  const [decisionAction, setDecisionAction] = useState<'approve' | 'reject' | null>(null);
  const [decisionComment, setDecisionComment] = useState('');
  const [decisionLoading, setDecisionLoading] = useState(false);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  const [decisionResult, setDecisionResult] = useState<string | null>(null);

  const fetchIntents = useCallback(async () => {
    if (!currentMerchantId) return;
    try {
      let query = `/recovery/actions?page=${intentsPage}&size=${pageSize}`;
      if (selectedStatus !== 'ALL') {
        query += `&status=${selectedStatus}`;
      }
      const res = await api.get<PageResponse<ActionIntentDto>>(query);
      setActions(res.content || []);
      setTotalPages(res.totalPages || 1);
      setTotalElements(res.totalElements || 0);
    } catch (err: any) {
      setError(err.message || 'Failed to load recovery action intents.');
    }
  }, [currentMerchantId, intentsPage, pageSize, selectedStatus]);

  const fetchApprovals = useCallback(async () => {
    if (!currentMerchantId) return;
    try {
      const [pending, summaryData] = await Promise.all([
        api.get<PageResponse<ReviewItemDto>>('/reviews?status=PENDING&page=0&size=50'),
        api.get<ReviewQueueSummaryDto>('/reviews/summary'),
      ]);
      setReviews(pending.content || []);
      setSummary(summaryData);
    } catch (err: any) {
      setError(err.message || 'Failed to load the human approval queue.');
    }
  }, [currentMerchantId]);

  const fetchAll = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    await Promise.all([fetchIntents(), fetchApprovals()]);
    setLoading(false);
  }, [currentMerchantId, fetchIntents, fetchApprovals]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  const openDecisionModal = (review: ReviewItemDto, action: 'approve' | 'reject') => {
    setActiveReview(review);
    setDecisionAction(action);
    setDecisionComment('');
    setDecisionError(null);
    setDecisionResult(null);
  };

  const closeDecisionModal = () => {
    setActiveReview(null);
    setDecisionAction(null);
    setDecisionResult(null);
    setDecisionError(null);
  };

  const submitDecision = async () => {
    if (!activeReview || !decisionAction) return;
    setDecisionLoading(true);
    setDecisionError(null);
    setDecisionResult(null);
    try {
      const result = await api.post<ReviewDecisionResponse>(
        `/reviews/${activeReview.id}/${decisionAction}`,
        decisionComment.trim() ? { comment: decisionComment.trim() } : {},
      );
      setDecisionResult(result.message || `Review ${decisionAction}ed successfully.`);
      await Promise.all([fetchApprovals(), fetchIntents()]);
    } catch (err: any) {
      if (err instanceof ApiError) {
        setDecisionError(err.message || 'The decision could not be recorded.');
      } else {
        setDecisionError('Failed to reach the backend approval service.');
      }
    } finally {
      setDecisionLoading(false);
    }
  };

  const summaryChips = [
    { label: 'Waiting for Approval', value: summary?.pending ?? 0, tone: 'amber' },
    { label: 'Approved', value: summary?.byStatus?.APPROVED ?? 0, tone: 'emerald' },
    { label: 'Rejected', value: summary?.byStatus?.REJECTED ?? 0, tone: 'rose' },
    { label: 'Escalated', value: summary?.byStatus?.ESCALATED ?? 0, tone: 'amber' },
    { label: 'Total Reviews', value: summary?.total ?? 0, tone: 'gray' },
  ];

  const toneClasses: Record<string, string> = {
    amber: 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:border-amber-800',
    emerald: 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800',
    rose: 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800',
    gray: 'bg-gray-50 text-gray-700 border-gray-200 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-700',
  };

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">Recovery Action Center</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Scheduled execution intents and the merchant human-approval queue
            </p>
          </div>

          <button
            onClick={fetchAll}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Refresh</span>
          </button>
        </div>

        {/* Summary chips */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          {summaryChips.map((chip) => (
            <div key={chip.label} className={`rounded-2xl border px-4 py-3 ${toneClasses[chip.tone]}`}>
              <div className="text-2xl font-black font-mono">{chip.value}</div>
              <div className="text-[11px] font-semibold mt-0.5">{chip.label}</div>
            </div>
          ))}
        </div>

        {!canDecide && (
          <div className="p-3 rounded-xl bg-blue-50 dark:bg-blue-950/40 border border-blue-200 dark:border-blue-800 text-xs text-blue-800 dark:text-blue-300">
            Your role ({currentRole || 'MEMBER'}) can view recovery actions. Only REVIEWER and MERCHANT_ADMIN roles can approve or reject items.
          </div>
        )}

        {/* Tabs */}
        <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
          <div className="flex border-b border-gray-200 dark:border-gray-800 px-6 bg-gray-50/50 dark:bg-gray-800/40 space-x-6 overflow-x-auto no-scrollbar">
            <button
              onClick={() => setTab('approvals')}
              className={`py-4 text-xs font-bold whitespace-nowrap border-b-2 transition-colors flex items-center space-x-2 ${
                tab === 'approvals'
                  ? 'border-blue-600 text-blue-600 dark:text-blue-400 dark:border-blue-400'
                  : 'border-transparent text-gray-500 hover:text-gray-900 dark:hover:text-white'
              }`}
            >
              <span>Waiting for Approval</span>
              {(summary?.pending ?? 0) > 0 && (
                <span className="px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300 text-[10px] font-bold">
                  {summary?.pending}
                </span>
              )}
            </button>
            <button
              onClick={() => setTab('intents')}
              className={`py-4 text-xs font-bold whitespace-nowrap border-b-2 transition-colors ${
                tab === 'intents'
                  ? 'border-blue-600 text-blue-600 dark:text-blue-400 dark:border-blue-400'
                  : 'border-transparent text-gray-500 hover:text-gray-900 dark:hover:text-white'
              }`}
            >
              Action Intents ({totalElements})
            </button>
          </div>

          <div className="p-6">
            {error && <ErrorAlert message={error} onRetry={fetchAll} />}

            {loading ? (
              <TableSkeleton rows={5} columns={5} />
            ) : tab === 'approvals' ? (
              reviews.length === 0 ? (
                <EmptyState
                  title="No Reviews Waiting for Approval"
                  description="The human-approval queue is clear. High-risk recoveries requiring a merchant decision will appear here."
                />
              ) : (
                <div className="space-y-4">
                  {reviews.map((review) => {
                    const expired = review.expiresAt ? new Date(review.expiresAt).getTime() < Date.now() : false;
                    return (
                      <div
                        key={review.id}
                        className="border border-gray-200 dark:border-gray-800 rounded-2xl bg-gray-50/50 dark:bg-gray-800/40 p-5 space-y-4"
                      >
                        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
                          <div className="flex items-center space-x-3">
                            <div className="w-10 h-10 rounded-xl bg-amber-50 dark:bg-amber-950/50 border border-amber-200 dark:border-amber-800 flex items-center justify-center text-amber-600 dark:text-amber-400">
                              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                              </svg>
                            </div>
                            <div>
                              <div className="flex items-center space-x-2 flex-wrap">
                                <span className="font-mono font-bold text-gray-900 dark:text-white">
                                  {review.amount !== undefined && review.amount !== null ? formatCurrency(review.amount) : 'Value N/A'}
                                </span>
                                <Badge status="REVIEW_REQUIRED" />
                                {expired && <Badge status="ESCALATED" />}
                              </div>
                              <div className="text-[11px] text-gray-500 font-mono mt-0.5">
                                {review.paymentId || review.campaignId}
                              </div>
                            </div>
                          </div>

                          <div className="flex items-center space-x-2">
                            <span className="text-[11px] text-gray-400 font-mono">
                              {new Date(review.createdAt).toLocaleString()}
                              {review.expiresAt && ` • Expires ${new Date(review.expiresAt).toLocaleString()}`}
                            </span>
                          </div>
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
                          <div className="bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-800 p-3.5">
                            <div className="text-[10px] font-bold uppercase tracking-wide text-gray-500 mb-1">Why this needs review</div>
                            <p className="text-gray-700 dark:text-gray-300 font-medium leading-relaxed">{review.reason || 'Pending merchant review'}</p>
                          </div>
                          <div className="bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-800 p-3.5">
                            <div className="text-[10px] font-bold uppercase tracking-wide text-gray-500 mb-1">Agent consensus</div>
                            <div className="space-y-1">
                              <div className="font-mono font-bold text-gray-900 dark:text-white">
                                {review.agentDecision || review.agentSelectedAction || 'REVIEW_REQUIRED'}
                              </div>
                              {review.agentConfidence !== undefined && review.agentConfidence !== null && (
                                <div className="text-gray-500">Confidence: {(review.agentConfidence * 100).toFixed(0)}%</div>
                              )}
                              {review.failureClass && <div className="text-gray-500">Failure: {review.failureClass}</div>}
                            </div>
                          </div>
                          <div className="bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-800 p-3.5">
                            <div className="text-[10px] font-bold uppercase tracking-wide text-gray-500 mb-1">Proposed recovery</div>
                            <div className="space-y-1">
                              <div className="font-mono font-bold text-gray-900 dark:text-white">{review.strategy || 'N/A'}</div>
                              <div className="text-gray-500">Campaign state: {review.campaignState || 'N/A'}</div>
                              <Link
                                href={`/campaigns/${review.campaignId}`}
                                className="text-blue-600 dark:text-blue-400 hover:underline font-mono"
                              >
                                Open campaign &rarr;
                              </Link>
                            </div>
                          </div>
                        </div>

                        {review.agentReasoning && (
                          <div className="text-xs text-gray-600 dark:text-gray-400 bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-800 p-3.5 font-medium">
                            <span className="font-bold text-gray-900 dark:text-white block mb-0.5">Agent reasoning (structured summary):</span>
                            {review.agentReasoning}
                          </div>
                        )}

                        {canDecide && (
                          <div className="flex justify-end space-x-3 pt-2 border-t border-gray-200 dark:border-gray-800">
                            <button
                              onClick={() => openDecisionModal(review, 'reject')}
                              disabled={expired}
                              className="px-4 py-2 border border-rose-300 dark:border-rose-800 text-rose-700 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-950/40 rounded-lg text-xs font-bold disabled:opacity-40 transition-colors"
                            >
                              Reject
                            </button>
                            <button
                              onClick={() => openDecisionModal(review, 'approve')}
                              disabled={expired}
                              className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-40 text-white rounded-lg text-xs font-bold transition-colors"
                            >
                              Approve Recovery
                            </button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )
            ) : (
              <>
                <div className="flex justify-end mb-4">
                  <select
                    value={selectedStatus}
                    onChange={(e) => {
                      setSelectedStatus(e.target.value);
                      setIntentsPage(0);
                    }}
                    className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
                  >
                    {ACTION_STATUSES.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </div>

                {actions.length === 0 ? (
                  <EmptyState
                    title="No Action Intents Found"
                    description={selectedStatus !== 'ALL' ? 'No action intents match the selected status.' : 'No recovery action intents exist for this merchant.'}
                  />
                ) : (
                  <>
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-xs">
                        <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                          <tr>
                            <th className="px-6 py-3.5">Action Type</th>
                            <th className="px-6 py-3.5">Attempt</th>
                            <th className="px-6 py-3.5">Campaign</th>
                            <th className="px-6 py-3.5">Status</th>
                            <th className="px-6 py-3.5">Scheduled</th>
                            <th className="px-6 py-3.5">Completed</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                          {actions.map((a) => (
                            <tr key={a.id} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors">
                              <td className="px-6 py-4">
                                <span className="font-mono font-bold text-gray-900 dark:text-white">{a.actionType}</span>
                                {a.sourceStrategy && (
                                  <div className="text-[10px] text-gray-400 font-sans mt-0.5">Strategy: {a.sourceStrategy}</div>
                                )}
                              </td>
                              <td className="px-6 py-4 font-mono font-bold">#{a.attemptNumber}</td>
                              <td className="px-6 py-4">
                                <Link
                                  href={`/campaigns/${a.campaignId}`}
                                  className="font-mono font-semibold text-blue-600 dark:text-blue-400 hover:underline truncate max-w-[130px] block"
                                >
                                  {a.campaignId}
                                </Link>
                              </td>
                              <td className="px-6 py-4">
                                <Badge status={a.status} type="action" />
                              </td>
                              <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                                {new Date(a.scheduledAt).toLocaleString()}
                              </td>
                              <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                                {a.completedAt ? new Date(a.completedAt).toLocaleString() : 'N/A'}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>

                    <div className="mt-4 border-t border-gray-100 dark:border-gray-800 pt-4">
                      <Pagination
                        currentPage={intentsPage}
                        totalPages={totalPages}
                        totalElements={totalElements}
                        pageSize={pageSize}
                        onPageChange={(p) => setIntentsPage(p)}
                      />
                    </div>
                  </>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {/* Decision modal */}
      <Modal
        isOpen={activeReview !== null && decisionAction !== null}
        onClose={closeDecisionModal}
        title={decisionAction === 'approve' ? 'Approve Recovery Action' : 'Reject Recovery Action'}
        subtitle={`${activeReview?.paymentId || activeReview?.campaignId} - ${decisionAction === 'approve' ? 'authorize execution after backend revalidation' : 'terminate this recovery safely'}`}
        maxWidth="lg"
      >
        <div className="space-y-4">
          {decisionResult ? (
            <div className="p-4 rounded-xl bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-sm font-medium text-emerald-800 dark:text-emerald-300">
              {decisionResult}
            </div>
          ) : (
            <>
              {decisionError && (
                <div className="p-4 rounded-xl bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-sm font-medium text-rose-800 dark:text-rose-300">
                  {decisionError}
                </div>
              )}

              <div className="text-sm text-gray-700 dark:text-gray-300 space-y-3">
                <p>
                  {decisionAction === 'approve'
                    ? 'The backend will revalidate tenant ownership, campaign state, ActionIntent state, compliance policy and your authorization before any action is authorized. Execution always runs through the provider boundary - never directly from this screen.'
                    : 'Rejecting will cancel pending action intents and transition the campaign to a cancelled state through the authoritative state machine.'}
                </p>
                <div className="rounded-xl bg-gray-50 dark:bg-gray-800/70 border border-gray-200 dark:border-gray-700 p-3.5 text-xs">
                  <div className="font-bold text-gray-900 dark:text-white mb-1">Agent recommendation</div>
                  <div className="font-mono text-gray-700 dark:text-gray-300">
                    {activeReview?.agentDecision || activeReview?.agentSelectedAction || 'N/A'}
                    {activeReview?.agentConfidence !== undefined && activeReview?.agentConfidence !== null
                      ? ` at ${(activeReview.agentConfidence * 100).toFixed(0)}% confidence`
                      : ''}
                  </div>
                  {activeReview?.agentReasoning && (
                    <p className="text-gray-500 mt-2">{activeReview.agentReasoning}</p>
                  )}
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5 uppercase tracking-wider">
                  {decisionAction === 'approve' ? 'Approval comment (optional)' : 'Rejection reason (optional)'}
                </label>
                <textarea
                  value={decisionComment}
                  onChange={(e) => setDecisionComment(e.target.value)}
                  rows={3}
                  placeholder="Recorded in the audit trail with this decision."
                  className="w-full px-3.5 py-2.5 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-sm text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-600"
                />
              </div>

              <div className="flex justify-end space-x-3 pt-2">
                <button
                  onClick={closeDecisionModal}
                  className="px-4 py-2 border border-gray-300 dark:border-gray-700 rounded-lg text-xs font-bold text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={submitDecision}
                  disabled={decisionLoading}
                  className={`px-5 py-2 rounded-lg text-xs font-bold text-white transition-colors disabled:opacity-50 flex items-center space-x-2 ${
                    decisionAction === 'approve' ? 'bg-emerald-600 hover:bg-emerald-700' : 'bg-rose-600 hover:bg-rose-700'
                  }`}
                >
                  {decisionLoading && <span className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />}
                  <span>{decisionAction === 'approve' ? 'Confirm Approval' : 'Confirm Rejection'}</span>
                </button>
              </div>
            </>
          )}
        </div>
      </Modal>
    </ConsoleLayout>
  );
}

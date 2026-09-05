'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { Pagination } from '@/components/common/Pagination';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { formatCurrency } from '@/components/common/MetricCard';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { PaymentSummaryDto, PageResponse, AnalyticsRecoveryDto } from '@/types';

const STATUS_OPTIONS = [
  'ALL',
  'CREATED',
  'CLASSIFIED',
  'ELIGIBLE',
  'SCHEDULED',
  'ACTION_PENDING',
  'EXECUTING',
  'RECOVERED',
  'FAILED',
  'EXHAUSTED',
  'CANCELLED',
];

const COMMON_FAILURE_CLASSES = [
  'INSUFFICIENT_FUNDS',
  'CARD_EXPIRED',
  'EXPIRED_CARD',
  'AUTHENTICATION_FAILED',
  'NETWORK_TIMEOUT',
  'PROCESSING_ERROR',
  'BANK_DECLINE',
  'SUSPICIOUS_TRANSACTION',
  'UNKNOWN',
];

export default function PaymentsPage() {
  const { currentMerchantId } = useAuth();

  const [payments, setPayments] = useState<PaymentSummaryDto[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  const [selectedStatus, setSelectedStatus] = useState('ALL');
  const [selectedFailureClass, setSelectedFailureClass] = useState('ALL');
  const [searchInput, setSearchInput] = useState('');
  const [activeSearch, setActiveSearch] = useState('');
  const [failureClassOptions, setFailureClassOptions] = useState<string[]>(COMMON_FAILURE_CLASSES);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchFailureClasses = useCallback(async () => {
    try {
      const recovery = await api.get<AnalyticsRecoveryDto>('/analytics/recovery');
      const keys = Object.keys(recovery.failureClassBreakdown || {}).filter((k) => k && k !== 'UNKNOWN');
      if (keys.length > 0) {
        setFailureClassOptions(Array.from(new Set([...keys, ...COMMON_FAILURE_CLASSES])));
      }
    } catch {
      // Failure-class options remain at the common defaults when analytics are unavailable
    }
  }, []);

  const fetchPayments = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      let query = `/payments?page=${page}&size=${pageSize}`;
      if (selectedStatus !== 'ALL') {
        query += `&status=${selectedStatus}`;
      }
      if (selectedFailureClass !== 'ALL') {
        query += `&failureClass=${encodeURIComponent(selectedFailureClass)}`;
      }
      if (activeSearch.trim() !== '') {
        query += `&search=${encodeURIComponent(activeSearch.trim())}`;
      }

      const res = await api.get<PageResponse<PaymentSummaryDto>>(query);
      setPayments(res.content || []);
      setTotalPages(res.totalPages || 1);
      setTotalElements(res.totalElements || 0);
    } catch (err: any) {
      setError(err.message || 'Failed to load payments from the backend.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, page, pageSize, selectedStatus, selectedFailureClass, activeSearch]);

  useEffect(() => {
    fetchPayments();
  }, [fetchPayments]);

  useEffect(() => {
    fetchFailureClasses();
  }, [fetchFailureClasses]);

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setActiveSearch(searchInput);
  };

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">Failed Payments</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Every failed payment Mend is tracking, with its classification and recovery status ({totalElements} total)
            </p>
          </div>

          <button
            onClick={fetchPayments}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Refresh</span>
          </button>
        </div>

        <div className="bg-white dark:bg-gray-900 p-4 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <form onSubmit={handleSearchSubmit} className="relative flex-1">
            <svg className="w-4 h-4 absolute left-3.5 top-3 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search payment reference, customer hash..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="w-full pl-10 pr-24 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <button
              type="submit"
              className="absolute right-1.5 top-1.5 px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-[11px] font-bold"
            >
              Search
            </button>
          </form>

          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center space-x-2">
              <span className="text-xs font-bold text-gray-500">Status:</span>
              <select
                value={selectedStatus}
                onChange={(e) => {
                  setSelectedStatus(e.target.value);
                  setPage(0);
                }}
                className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
              >
                {STATUS_OPTIONS.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex items-center space-x-2">
              <span className="text-xs font-bold text-gray-500">Class:</span>
              <select
                value={selectedFailureClass}
                onChange={(e) => {
                  setSelectedFailureClass(e.target.value);
                  setPage(0);
                }}
                className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
              >
                <option value="ALL">ALL</option>
                {failureClassOptions.map((f) => (
                  <option key={f} value={f}>
                    {f}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchPayments} />}

        {loading ? (
          <TableSkeleton rows={8} columns={6} />
        ) : payments.length === 0 ? (
          <EmptyState
            title="No Failed Payments Found"
            description={
              selectedStatus !== 'ALL' || selectedFailureClass !== 'ALL' || activeSearch
                ? 'No payments match the selected filters. Try broadening the status, failure class or search terms.'
                : 'No failed payment campaigns exist yet. Trigger a webhook or a demo scenario to get started.'
            }
          />
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                  <tr>
                    <th className="px-6 py-3.5">Payment Ref</th>
                    <th className="px-6 py-3.5 text-right">Amount</th>
                    <th className="px-6 py-3.5">Customer</th>
                    <th className="px-6 py-3.5">Failure Class</th>
                    <th className="px-6 py-3.5">Recovery Status</th>
                    <th className="px-6 py-3.5">Strategy</th>
                    <th className="px-6 py-3.5">Attempts</th>
                    <th className="px-6 py-3.5">Failed At</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                  {payments.map((p) => (
                    <tr key={p.paymentId} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-4">
                        <Link
                          href={`/payments/${encodeURIComponent(p.paymentId)}`}
                          className="font-mono font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 transition-colors"
                        >
                          {p.paymentId}
                        </Link>
                        {p.campaignId && (
                          <div className="text-[10px] text-gray-400">
                            <Link href={`/campaigns/${p.campaignId}`} className="hover:underline">
                              Campaign: {p.campaignId.slice(0, 12)}&hellip;
                            </Link>
                          </div>
                        )}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <span className="font-mono font-bold text-gray-900 dark:text-white">{formatCurrency(p.amount)}</span>
                      </td>
                      <td className="px-6 py-4">
                        {p.customerIdHash ? (
                          <Link
                            href={`/customers/${encodeURIComponent(p.customerIdHash)}`}
                            className="font-mono text-gray-600 dark:text-gray-400 hover:text-blue-600 dark:hover:text-blue-400 hover:underline"
                          >
                            {p.customerIdHash.slice(0, 22)}&hellip;
                          </Link>
                        ) : (
                          <span className="text-gray-400">Unattributed</span>
                        )}
                      </td>
                      <td className="px-6 py-4">
                        <span className="font-semibold text-gray-800 dark:text-gray-200">{p.failureClass || 'UNKNOWN'}</span>
                      </td>
                      <td className="px-6 py-4">
                        <Badge status={p.currentState} />
                      </td>
                      <td className="px-6 py-4 font-semibold text-gray-700 dark:text-gray-300">{p.strategy || 'N/A'}</td>
                      <td className="px-6 py-4 font-mono">{p.attemptCount ?? 0}</td>
                      <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                        {new Date(p.createdAt).toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="border-t border-gray-100 dark:border-gray-800">
              <Pagination
                currentPage={page}
                totalPages={totalPages}
                totalElements={totalElements}
                pageSize={pageSize}
                onPageChange={(newPage) => setPage(newPage)}
              />
            </div>
          </div>
        )}
      </div>
    </ConsoleLayout>
  );
}

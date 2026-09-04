'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { Pagination } from '@/components/common/Pagination';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { ActionIntentDto, PageResponse } from '@/types';

const ACTION_STATUSES = [
  'ALL',
  'SCHEDULED',
  'READY',
  'CLAIMED',
  'EXECUTED',
  'SUCCEEDED',
  'FAILED',
  'EXPAIRED',
  'CANCELLED',
  'WAITING_FOR_APPROVAL',
];

export default function RecoveryActionsPage() {
  const { currentMerchantId } = useAuth();

  const [actions, setActions] = useState<ActionIntentDto[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  const [selectedStatus, setSelectedStatus] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchActions = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      let query = `/recovery/actions?page=${page}&size=${pageSize}`;
      if (selectedStatus !== 'ALL') {
        query += `&status=${selectedStatus}`;
      }

      const res = await api.get<PageResponse<ActionIntentDto>>(query);
      setActions(res.content || []);
      setTotalPages(res.totalPages || 1);
      setTotalElements(res.totalElements || 0);
    } catch (err: any) {
      setError(err.message || 'Failed to load recovery actions from backend.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, page, pageSize, selectedStatus]);

  useEffect(() => {
    fetchActions();
  }, [fetchActions]);

  const filteredActions = actions.filter((a) => {
    return (
      searchTerm.trim() === '' ||
      a.id?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      a.campaignId?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      a.idempotencyKey?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      a.actionType?.toLowerCase().includes(searchTerm.toLowerCase())
    );
  });

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">
              Recovery Action Intents
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Scheduled, executing, and completed payment recovery action intents ({totalElements} total)
            </p>
          </div>

          <button
            onClick={fetchActions}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Refresh Queue</span>
          </button>
        </div>

        {/* Filters */}
        <div className="bg-white dark:bg-gray-900 p-4 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4">
          {/* Search Box */}
          <div className="relative flex-1">
            <svg className="w-4 h-4 absolute left-3.5 top-3 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search by Action ID, Campaign ID, Action Type, or Idempotency Key..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Status Filter */}
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
              {ACTION_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchActions} />}

        {/* Table Content */}
        {loading ? (
          <TableSkeleton rows={6} columns={6} />
        ) : filteredActions.length === 0 ? (
          <EmptyState
            title="No Action Intents Found"
            description={
              selectedStatus !== 'ALL' || searchTerm
                ? 'No action intents match your filter criteria.'
                : 'No recovery action intents currently exist for this merchant.'
            }
          />
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                  <tr>
                    <th className="px-6 py-3.5">Action Type</th>
                    <th className="px-6 py-3.5">Attempt #</th>
                    <th className="px-6 py-3.5">Campaign ID</th>
                    <th className="px-6 py-3.5">Idempotency Key</th>
                    <th className="px-6 py-3.5">Status</th>
                    <th className="px-6 py-3.5">Scheduled At</th>
                    <th className="px-6 py-3.5">Completed At</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                  {filteredActions.map((a) => (
                    <tr key={a.id} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-4">
                        <span className="font-mono font-bold text-gray-900 dark:text-white">
                          {a.actionType}
                        </span>
                        {a.sourceStrategy && (
                          <div className="text-[10px] text-gray-400 font-sans mt-0.5">
                            Strategy: {a.sourceStrategy}
                          </div>
                        )}
                      </td>
                      <td className="px-6 py-4 font-mono font-bold">
                        #{a.attemptNumber}
                      </td>
                      <td className="px-6 py-4">
                        <Link
                          href={`/campaigns/${a.campaignId}`}
                          className="font-mono font-semibold text-blue-600 hover:text-blue-700 dark:text-blue-400 hover:underline transition-colors truncate max-w-[130px] block"
                        >
                          {a.campaignId}
                        </Link>
                      </td>
                      <td className="px-6 py-4 font-mono text-[11px] text-gray-500 truncate max-w-[180px]">
                        {a.idempotencyKey}
                      </td>
                      <td className="px-6 py-4">
                        <Badge status={a.status} />
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

            {/* Pagination */}
            <div className="p-4 border-t border-gray-100 dark:border-gray-800">
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

'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Pagination } from '@/components/common/Pagination';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { AuditLogDto, PageResponse } from '@/types';

const ACTOR_TYPES = ['ALL', 'SYSTEM', 'USER', 'AI_AGENT', 'WORKER'];

export default function AuditLogsPage() {
  const { currentMerchantId } = useAuth();

  const [logs, setLogs] = useState<AuditLogDto[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  const [selectedActorType, setSelectedActorType] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchAuditLogs = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      const query = `/audit?page=${page}&size=${pageSize}`;
      const res = await api.get<PageResponse<AuditLogDto>>(query);
      setLogs(res.content || []);
      setTotalPages(res.totalPages || 1);
      setTotalElements(res.totalElements || 0);
    } catch (err: any) {
      setError(err.message || 'Failed to load system audit log from backend.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, page, pageSize]);

  useEffect(() => {
    fetchAuditLogs();
  }, [fetchAuditLogs]);

  const filteredLogs = logs.filter((l) => {
    const matchesActor = selectedActorType === 'ALL' || (l.actorType || 'SYSTEM') === selectedActorType;
    const matchesSearch =
      searchTerm.trim() === '' ||
      l.eventType?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      l.campaignId?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      l.actorId?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      l.reason?.toLowerCase().includes(searchTerm.toLowerCase());

    return matchesActor && matchesSearch;
  });

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">
              System Audit Trail Log
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Read-only immutable governance trail of system actions, decisions, and policy checks ({totalElements} total)
            </p>
          </div>

          <button
            onClick={fetchAuditLogs}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Refresh Trail</span>
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
              placeholder="Search by Event Type, Campaign ID, Actor ID, or Reason..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Actor Type Filter */}
          <div className="flex items-center space-x-2">
            <span className="text-xs font-bold text-gray-500">Actor Type:</span>
            <select
              value={selectedActorType}
              onChange={(e) => setSelectedActorType(e.target.value)}
              className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
            >
              {ACTOR_TYPES.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </div>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchAuditLogs} />}

        {/* Audit Log Table */}
        {loading ? (
          <TableSkeleton rows={6} columns={5} />
        ) : filteredLogs.length === 0 ? (
          <EmptyState
            title="No Audit Entries Recorded"
            description={
              selectedActorType !== 'ALL' || searchTerm
                ? 'No audit log entries match your filter criteria.'
                : 'No governance audit records exist for this merchant.'
            }
          />
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                  <tr>
                    <th className="px-6 py-3.5">Timestamp</th>
                    <th className="px-6 py-3.5">Event Type</th>
                    <th className="px-6 py-3.5">Actor</th>
                    <th className="px-6 py-3.5">Campaign Reference</th>
                    <th className="px-6 py-3.5">Reason / Details</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                  {filteredLogs.map((l) => (
                    <tr key={l.id} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                        {new Date(l.createdAt).toLocaleString()}
                      </td>
                      <td className="px-6 py-4">
                        <span className="font-mono font-bold text-gray-900 dark:text-white">
                          {l.eventType}
                        </span>
                      </td>
                      <td className="px-6 py-4 font-mono text-xs">
                        <span className="px-2 py-0.5 rounded bg-gray-100 dark:bg-gray-800 text-gray-800 dark:text-gray-300 font-bold">
                          {l.actorType || 'SYSTEM'}
                        </span>
                        {l.actorId && <span className="text-[10px] text-gray-400 block mt-0.5 truncate max-w-[120px]">{l.actorId}</span>}
                      </td>
                      <td className="px-6 py-4 font-mono">
                        {l.campaignId ? (
                          <Link
                            href={`/campaigns/${l.campaignId}`}
                            className="text-blue-600 dark:text-blue-400 hover:underline font-bold truncate max-w-[130px] block"
                          >
                            {l.campaignId}
                          </Link>
                        ) : (
                          <span className="text-gray-400">Global</span>
                        )}
                      </td>
                      <td className="px-6 py-4 text-gray-700 dark:text-gray-300 max-w-md">
                        {l.reason || 'Executed within standard policy boundaries.'}
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

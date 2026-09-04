'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { Modal } from '@/components/common/Modal';
import { Pagination } from '@/components/common/Pagination';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { WebhookEventDetailDto, PageResponse } from '@/types';

const WEBHOOK_STATUSES = [
  'ALL',
  'RECEIVED',
  'UNVERIFIED',
  'VERIFIED',
  'PROCESSING',
  'PROCESSED',
  'FAILED',
  'IGNORED',
  'DUPLICATE',
];

export default function WebhooksPage() {
  const { currentMerchantId } = useAuth();

  const [webhooks, setWebhooks] = useState<WebhookEventDetailDto[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  const [selectedStatus, setSelectedStatus] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedWebhook, setSelectedWebhook] = useState<WebhookEventDetailDto | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchWebhooks = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      let query = `/webhooks?page=${page}&size=${pageSize}`;
      if (selectedStatus !== 'ALL') {
        query += `&status=${selectedStatus}`;
      }

      const res = await api.get<PageResponse<WebhookEventDetailDto>>(query);
      setWebhooks(res.content || []);
      setTotalPages(res.totalPages || 1);
      setTotalElements(res.totalElements || 0);
    } catch (err: any) {
      setError(err.message || 'Failed to load webhook event log from backend.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, page, pageSize, selectedStatus]);

  useEffect(() => {
    fetchWebhooks();
  }, [fetchWebhooks]);

  const filteredWebhooks = webhooks.filter((w) => {
    return (
      searchTerm.trim() === '' ||
      w.externalEventId?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      w.eventType?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      w.source?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      w.id?.toLowerCase().includes(searchTerm.toLowerCase())
    );
  });

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">
              Webhook Event Ingestion Log
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Ingested payment webhooks, signature verification status, and processor logs ({totalElements} total)
            </p>
          </div>

          <button
            onClick={fetchWebhooks}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Sync Webhooks</span>
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
              placeholder="Search by External Event ID, Event Type, or Provider Source..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Status Filter */}
          <div className="flex items-center space-x-2">
            <span className="text-xs font-bold text-gray-500">Processing Status:</span>
            <select
              value={selectedStatus}
              onChange={(e) => {
                setSelectedStatus(e.target.value);
                setPage(0);
              }}
              className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
            >
              {WEBHOOK_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchWebhooks} />}

        {/* Table Content */}
        {loading ? (
          <TableSkeleton rows={6} columns={6} />
        ) : filteredWebhooks.length === 0 ? (
          <EmptyState
            title="No Webhook Events Found"
            description={
              selectedStatus !== 'ALL' || searchTerm
                ? 'No webhook events match your filter criteria.'
                : 'No external payment webhooks have been received yet.'
            }
          />
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                  <tr>
                    <th className="px-6 py-3.5">Event Type</th>
                    <th className="px-6 py-3.5">External Event ID</th>
                    <th className="px-6 py-3.5">Provider Source</th>
                    <th className="px-6 py-3.5">Processing Status</th>
                    <th className="px-6 py-3.5">Publish Status</th>
                    <th className="px-6 py-3.5">Received At</th>
                    <th className="px-6 py-3.5 text-right">Inspect</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                  {filteredWebhooks.map((w) => (
                    <tr key={w.id} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-4">
                        <span className="font-mono font-bold text-gray-900 dark:text-white">
                          {w.eventType}
                        </span>
                      </td>
                      <td className="px-6 py-4 font-mono text-[11px] text-gray-600 dark:text-gray-300 truncate max-w-[160px]">
                        {w.externalEventId}
                      </td>
                      <td className="px-6 py-4 font-bold text-indigo-600 dark:text-indigo-400">
                        {w.source || 'RAZORPAY'}
                      </td>
                      <td className="px-6 py-4">
                        <Badge status={w.processingStatus} />
                      </td>
                      <td className="px-6 py-4">
                        <Badge status={w.publishStatus || 'N/A'} />
                      </td>
                      <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                        {new Date(w.receivedAt).toLocaleString()}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <button
                          onClick={() => setSelectedWebhook(w)}
                          className="px-3 py-1.5 bg-gray-100 dark:bg-gray-800 text-gray-800 dark:text-gray-200 hover:bg-gray-200 dark:hover:bg-gray-700 rounded-lg text-[11px] font-bold transition-colors"
                        >
                          Payload &rarr;
                        </button>
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

        {/* Safe Webhook Detail Inspector Modal */}
        <Modal
          isOpen={!!selectedWebhook}
          onClose={() => setSelectedWebhook(null)}
          title={`Webhook Event: ${selectedWebhook?.eventType || ''}`}
          subtitle={`External ID: ${selectedWebhook?.externalEventId || ''}`}
        >
          {selectedWebhook && (
            <div className="space-y-6">
              {/* Event Metadata Grid */}
              <div className="grid grid-cols-2 gap-4 p-4 rounded-xl bg-gray-50 dark:bg-gray-800/50 border border-gray-200 dark:border-gray-700 text-xs font-mono">
                <div>
                  <span className="text-gray-400 font-sans font-bold block">Internal ID</span>
                  <span className="text-gray-900 dark:text-white font-bold">{selectedWebhook.id}</span>
                </div>
                <div>
                  <span className="text-gray-400 font-sans font-bold block">Source Provider</span>
                  <span className="text-indigo-600 dark:text-indigo-400 font-bold">{selectedWebhook.source}</span>
                </div>
                <div>
                  <span className="text-gray-400 font-sans font-bold block">Processing Status</span>
                  <Badge status={selectedWebhook.processingStatus} />
                </div>
                <div>
                  <span className="text-gray-400 font-sans font-bold block">Publish Status</span>
                  <Badge status={selectedWebhook.publishStatus || 'N/A'} />
                </div>
                <div>
                  <span className="text-gray-400 font-sans font-bold block">Received Timestamp</span>
                  <span>{new Date(selectedWebhook.receivedAt).toLocaleString()}</span>
                </div>
                <div>
                  <span className="text-gray-400 font-sans font-bold block">Processed Timestamp</span>
                  <span>{selectedWebhook.processedAt ? new Date(selectedWebhook.processedAt).toLocaleString() : 'N/A'}</span>
                </div>
              </div>

              {/* Payload Security & Hash Info */}
              <div className="space-y-2">
                <h4 className="text-xs font-bold text-gray-900 dark:text-white uppercase tracking-wider">
                  Ingestion Cryptographic Integrity
                </h4>
                <div className="p-3 bg-gray-900 text-emerald-400 font-mono text-[11px] rounded-xl overflow-x-auto border border-gray-800">
                  <div>SHA-256 Payload Hash:</div>
                  <div className="font-bold text-white mt-0.5">{selectedWebhook.payloadHash || 'Verified HMAC Digest'}</div>
                </div>
              </div>

              {/* Error Details if Failed */}
              {selectedWebhook.errorMessage && (
                <div className="p-4 rounded-xl bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-300 text-xs font-mono">
                  <span className="font-bold block mb-1 font-sans">Processing Error Trace:</span>
                  {selectedWebhook.errorMessage}
                </div>
              )}
            </div>
          )}
        </Modal>
      </div>
    </ConsoleLayout>
  );
}

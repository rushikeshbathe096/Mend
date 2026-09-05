'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { Modal } from '@/components/common/Modal';
import { Pagination } from '@/components/common/Pagination';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { CampaignDto, PageResponse } from '@/types';

const CAMPAIGN_STATES = [
  'ALL',
  'CREATED',
  'CLASSIFIED',
  'ELIGIBLE',
  'ACTION_PENDING',
  'EXECUTING',
  'RECOVERED',
  'FAILED',
  'EXHAUSTED',
  'REVIEW_REQUIRED',
  'BLOCKED',
  'CANCELLED',
];

const FAILURE_CLASSES = [
  'ALL',
  'INSUFFICIENT_FUNDS',
  'EXPIRED_CARD',
  'AUTHENTICATION_FAILED',
  'NETWORK_TIMEOUT',
  'PROCESSING_ERROR',
  'BANK_DECLINE',
];

export default function CampaignsPage() {
  const { currentMerchantId } = useAuth();

  const [campaigns, setCampaigns] = useState<CampaignDto[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  // Campaign creation state
  const [createOpen, setCreateOpen] = useState(false);
  const [createPaymentId, setCreatePaymentId] = useState('');
  const [createCustomerHash, setCreateCustomerHash] = useState('');
  const [createSubscriptionId, setCreateSubscriptionId] = useState('');
  const [createErrors, setCreateErrors] = useState<Record<string, string>>({});
  const [createError, setCreateError] = useState<string | null>(null);
  const [createLoading, setCreateLoading] = useState(false);
  const [createSuccess, setCreateSuccess] = useState<string | null>(null);

  const [selectedState, setSelectedState] = useState('ALL');
  const [selectedFailureClass, setSelectedFailureClass] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchCampaigns = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      let query = `/campaigns?page=${page}&size=${pageSize}&sortBy=createdAt&sortOrder=desc`;
      if (selectedState !== 'ALL') {
        query += `&status=${selectedState}`;
      }

      const res = await api.get<PageResponse<CampaignDto>>(query);
      setCampaigns(res.content || []);
      setTotalPages(res.totalPages || 1);
      setTotalElements(res.totalElements || 0);
    } catch (err: any) {
      setError(err.message || 'Failed to load campaigns from backend.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, page, pageSize, selectedState]);

  useEffect(() => {
    fetchCampaigns();
  }, [fetchCampaigns]);

  const resetCreateForm = () => {
    setCreatePaymentId('');
    setCreateCustomerHash('');
    setCreateSubscriptionId('');
    setCreateErrors({});
    setCreateError(null);
    setCreateSuccess(null);
  };

  const handleCreateCampaign = async (e: React.FormEvent) => {
    e.preventDefault();
    const errors: Record<string, string> = {};
    if (!createPaymentId.trim()) errors.paymentId = 'Payment reference is required.';
    if (!createCustomerHash.trim()) errors.customerHash = 'Customer identifier (hash) is required to attribute recovery context.';
    setCreateErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setCreateLoading(true);
    setCreateError(null);
    setCreateSuccess(null);
    try {
      const created = await api.post<CampaignDto>('/campaigns', {
        paymentId: createPaymentId.trim(),
        customerIdHash: createCustomerHash.trim(),
        subscriptionId: createSubscriptionId.trim() || undefined,
      });
      setCreateSuccess(`Campaign ${created.id} created for payment ${created.paymentId}. Trigger a webhook to begin classification.`);
      resetCreateForm();
      setCreateOpen(false);
      setPage(0);
      fetchCampaigns();
    } catch (err: any) {
      setCreateError(err.message || 'Campaign creation failed on the backend.');
    } finally {
      setCreateLoading(false);
    }
  };

  // Client-side search and failure class filtering
  const filteredCampaigns = campaigns.filter((c) => {
    const matchesSearch =
      searchTerm.trim() === '' ||
      c.paymentId?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      c.id?.toLowerCase().includes(searchTerm.toLowerCase()) ||
      c.customerIdHash?.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesFailureClass =
      selectedFailureClass === 'ALL' || c.failureClass === selectedFailureClass;

    return matchesSearch && matchesFailureClass;
  });

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">
              Recovery Campaigns
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Active payment recovery lifecycles, failure classifications, and strategy status ({totalElements} total)
            </p>
          </div>

          <div className="flex items-center space-x-3 self-start sm:self-auto">
            <button
              onClick={() => {
                resetCreateForm();
                setCreateOpen(true);
              }}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-xl text-xs font-bold transition-colors flex items-center space-x-2 shadow-sm"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
              </svg>
              <span>New Campaign</span>
            </button>
            <button
              onClick={fetchCampaigns}
              disabled={loading}
              className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm"
            >
              <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
              <span>Refresh</span>
            </button>
          </div>
        </div>

        {createSuccess && (
          <div className="p-4 rounded-xl bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-300 text-sm font-medium">
            {createSuccess}
          </div>
        )}

        {/* Filter Controls */}
        <div className="bg-white dark:bg-gray-900 p-4 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4">
          {/* Search Box */}
          <div className="relative flex-1">
            <svg className="w-4 h-4 absolute left-3.5 top-3 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search by Payment Ref, Customer Hash, or Campaign ID..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          <div className="flex flex-wrap items-center gap-3">
            {/* State Filter */}
            <div className="flex items-center space-x-2">
              <span className="text-xs font-bold text-gray-500">State:</span>
              <select
                value={selectedState}
                onChange={(e) => {
                  setSelectedState(e.target.value);
                  setPage(0);
                }}
                className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
              >
                {CAMPAIGN_STATES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>

            {/* Failure Class Filter */}
            <div className="flex items-center space-x-2">
              <span className="text-xs font-bold text-gray-500">Failure Class:</span>
              <select
                value={selectedFailureClass}
                onChange={(e) => setSelectedFailureClass(e.target.value)}
                className="bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-xs font-bold text-gray-900 dark:text-white rounded-xl px-3 py-2 focus:outline-none cursor-pointer"
              >
                {FAILURE_CLASSES.map((f) => (
                  <option key={f} value={f}>
                    {f}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchCampaigns} />}

        {/* Table Content */}
        {loading ? (
          <TableSkeleton rows={6} columns={6} />
        ) : filteredCampaigns.length === 0 ? (
          <EmptyState
            title="No Recovery Campaigns Found"
            description={
              selectedState !== 'ALL' || selectedFailureClass !== 'ALL' || searchTerm
                ? 'No campaigns match your filter criteria. Try broadening your filters.'
                : 'No recovery campaigns exist in this merchant account yet.'
            }
          />
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                  <tr>
                    <th className="px-6 py-3.5">Payment Ref</th>
                    <th className="px-6 py-3.5">Failure Class</th>
                    <th className="px-6 py-3.5">AI Confidence</th>
                    <th className="px-6 py-3.5">Strategy</th>
                    <th className="px-6 py-3.5">Attempts</th>
                    <th className="px-6 py-3.5">State</th>
                    <th className="px-6 py-3.5">Created</th>
                    <th className="px-6 py-3.5 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                  {filteredCampaigns.map((c) => (
                    <tr
                      key={c.id}
                      className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors"
                    >
                      <td className="px-6 py-4">
                        <Link
                          href={`/campaigns/${c.id}`}
                          className="font-mono font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 transition-colors"
                        >
                          {c.paymentId}
                        </Link>
                        {c.customerIdHash && (
                          <div className="text-[10px] text-gray-400 font-mono truncate max-w-[140px]">
                            Cust: {c.customerIdHash}
                          </div>
                        )}
                        <div className="text-[10px]">
                          <Link
                            href={`/payments/${encodeURIComponent(c.paymentId || '')}`}
                            className="text-blue-600 dark:text-blue-400 hover:underline font-mono"
                          >
                            Payment &rarr;
                          </Link>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <span className="font-semibold text-gray-800 dark:text-gray-200">
                          {c.failureClass || 'UNCLASSIFIED'}
                        </span>
                      </td>
                      <td className="px-6 py-4 font-mono font-semibold">
                        {c.confidence !== undefined && c.confidence !== null ? (
                          <span
                            className={
                              c.confidence >= 0.8
                                ? 'text-emerald-600 dark:text-emerald-400'
                                : c.confidence >= 0.5
                                ? 'text-amber-600 dark:text-amber-400'
                                : 'text-rose-600 dark:text-rose-400'
                            }
                          >
                            {(c.confidence * 100).toFixed(0)}%
                          </span>
                        ) : (
                          <span className="text-gray-400">N/A</span>
                        )}
                      </td>
                      <td className="px-6 py-4 font-semibold text-gray-700 dark:text-gray-300">
                        {c.strategy || 'PENDING'}
                      </td>
                      <td className="px-6 py-4 font-mono">
                        {c.attemptCount ?? 0}
                      </td>
                      <td className="px-6 py-4">
                        <Badge status={c.currentState} />
                      </td>
                      <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                        {new Date(c.createdAt).toLocaleDateString()}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <Link
                          href={`/campaigns/${c.id}`}
                          className="px-3 py-1.5 bg-blue-50 dark:bg-blue-950/60 text-blue-700 dark:text-blue-400 border border-blue-200 dark:border-blue-800 rounded-lg text-[11px] font-bold hover:bg-blue-100 transition-colors"
                        >
                          Lifecycle &rarr;
                        </Link>
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

      {/* Create Campaign Modal */}
      <Modal
        isOpen={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Create Recovery Campaign"
        subtitle="Server-side validation applies. Campaign state remains backend-authoritative."
        maxWidth="lg"
      >
        <form onSubmit={handleCreateCampaign} className="space-y-5">
          {createError && (
            <div className="p-4 rounded-xl bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-sm font-medium text-rose-800 dark:text-rose-300">
              {createError}
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5 uppercase tracking-wider">
              Payment Reference <span className="text-rose-500">*</span>
            </label>
            <input
              type="text"
              required
              value={createPaymentId}
              onChange={(e) => setCreatePaymentId(e.target.value)}
              placeholder="pay_xxxxxxxxxx"
              className={`w-full px-4 py-2.5 rounded-xl border text-sm outline-none transition-all focus:ring-2 focus:ring-blue-600 ${
                createErrors.paymentId
                  ? 'border-rose-400 bg-rose-50/50 dark:bg-rose-950/20'
                  : 'border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white'
              }`}
            />
            {createErrors.paymentId && <p className="text-xs text-rose-600 dark:text-rose-400 mt-1 font-semibold">{createErrors.paymentId}</p>}
            <p className="text-[11px] text-gray-400 mt-1">The provider payment reference whose failure should be recovered.</p>
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5 uppercase tracking-wider">
              Customer Hash <span className="text-rose-500">*</span>
            </label>
            <input
              type="text"
              required
              value={createCustomerHash}
              onChange={(e) => setCreateCustomerHash(e.target.value)}
              placeholder="cust_xxxxxxxxxx"
              className={`w-full px-4 py-2.5 rounded-xl border text-sm outline-none transition-all focus:ring-2 focus:ring-blue-600 ${
                createErrors.customerHash
                  ? 'border-rose-400 bg-rose-50/50 dark:bg-rose-950/20'
                  : 'border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white'
              }`}
            />
            {createErrors.customerHash && <p className="text-xs text-rose-600 dark:text-rose-400 mt-1 font-semibold">{createErrors.customerHash}</p>}
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5 uppercase tracking-wider">
              Subscription ID
            </label>
            <input
              type="text"
              value={createSubscriptionId}
              onChange={(e) => setCreateSubscriptionId(e.target.value)}
              placeholder="sub_xxxxxxxxxx (optional)"
              className="w-full px-4 py-2.5 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white text-sm outline-none transition-all focus:ring-2 focus:ring-blue-600"
            />
          </div>

          <div className="p-3.5 rounded-xl bg-blue-50 dark:bg-blue-950/30 border border-blue-200 dark:border-blue-800 text-xs text-blue-800 dark:text-blue-300">
            New campaigns start in the CREATED state. Failure classification, strategy selection, compliance gating and execution
            all run through Mend&apos;s backend authority chain.
          </div>

          <div className="flex justify-end space-x-3 pt-2">
            <button
              type="button"
              onClick={() => setCreateOpen(false)}
              className="px-4 py-2 border border-gray-300 dark:border-gray-700 rounded-lg text-xs font-bold text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={createLoading}
              className="px-5 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded-lg text-xs font-bold transition-colors flex items-center space-x-2"
            >
              {createLoading && <span className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />}
              <span>Create Campaign</span>
            </button>
          </div>
        </form>
      </Modal>
    </ConsoleLayout>
  );
}

'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { ErrorAlert, EmptyState } from '@/components/common/Feedback';
import { TableSkeleton } from '@/components/common/Skeleton';
import { formatCurrency } from '@/components/common/MetricCard';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { CustomerSummaryDto } from '@/types';

const RISK_STYLE: Record<string, string> = {
  REPEAT_FAILURES: 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:border-amber-800',
  ACTIVE_RECOVERY_PENDING: 'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-950/50 dark:text-blue-400 dark:border-blue-800',
  PRIOR_DISPUTE: 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800',
  LOW_RISK_PROFILE: 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800',
};

export default function CustomersPage() {
  const { currentMerchantId } = useAuth();

  const [customers, setCustomers] = useState<CustomerSummaryDto[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchCustomers = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<CustomerSummaryDto[]>('/customers');
      setCustomers(data || []);
    } catch (err: any) {
      setError(err.message || 'Failed to load customer recovery profiles.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId]);

  useEffect(() => {
    fetchCustomers();
  }, [fetchCustomers]);

  const filtered = customers.filter(
    (c) =>
      searchTerm.trim() === '' ||
      c.customerIdHash?.toLowerCase().includes(searchTerm.trim().toLowerCase())
  );

  return (
    <ConsoleLayout>
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">Customer Recovery Profiles</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
              Payment failures, recoveries and risk context per customer ({customers.length} customers)
            </p>
          </div>
          <button
            onClick={fetchCustomers}
            disabled={loading}
            className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors flex items-center space-x-2 shadow-sm self-start sm:self-auto"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            <span>Refresh</span>
          </button>
        </div>

        <div className="bg-white dark:bg-gray-900 p-4 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm">
          <div className="relative">
            <svg className="w-4 h-4 absolute left-3.5 top-3 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search customer hash..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchCustomers} />}

        {loading ? (
          <TableSkeleton rows={6} columns={6} />
        ) : filtered.length === 0 ? (
          <EmptyState
            title="No Customer Profiles Found"
            description={
              searchTerm
                ? 'No customers match your search. Verify the customer hash.'
                : 'No customer profiles exist yet. Customers appear once a failed payment is attributed to them.'
            }
          />
        ) : (
          <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider border-b border-gray-200 dark:border-gray-800">
                  <tr>
                    <th className="px-6 py-3.5">Customer</th>
                    <th className="px-6 py-3.5">Campaigns</th>
                    <th className="px-6 py-3.5">Active</th>
                    <th className="px-6 py-3.5">Recovered</th>
                    <th className="px-6 py-3.5 text-right">Failed Volume</th>
                    <th className="px-6 py-3.5 text-right">Recovered Value</th>
                    <th className="px-6 py-3.5">Risk Signals</th>
                    <th className="px-6 py-3.5">Last Activity</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                  {filtered.map((c) => (
                    <tr key={c.customerIdHash} className="hover:bg-gray-50/60 dark:hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-4">
                        <Link
                          href={`/customers/${encodeURIComponent(c.customerIdHash)}`}
                          className="font-mono font-bold text-gray-900 dark:text-white hover:text-blue-600 dark:hover:text-blue-400 transition-colors"
                        >
                          {c.customerIdHash}
                        </Link>
                      </td>
                      <td className="px-6 py-4 font-mono font-bold">{c.totalCampaigns}</td>
                      <td className="px-6 py-4 font-mono">{c.activeCampaigns}</td>
                      <td className="px-6 py-4 font-mono text-emerald-600 dark:text-emerald-400">{c.recoveredCampaigns}</td>
                      <td className="px-6 py-4 text-right font-mono font-bold">{formatCurrency(c.totalFailedAmount)}</td>
                      <td className="px-6 py-4 text-right font-mono text-emerald-600 dark:text-emerald-400">
                        {formatCurrency(c.totalRecoveredAmount)}
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex flex-wrap gap-1.5">
                          {(c.riskSignals || []).map((signal) => (
                            <span
                              key={signal}
                              className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${RISK_STYLE[signal] || 'bg-gray-100 text-gray-600 border-gray-200 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-700'}`}
                            >
                              {signal}
                            </span>
                          ))}
                        </div>
                      </td>
                      <td className="px-6 py-4 font-mono text-gray-500 whitespace-nowrap">
                        {c.lastActivityAt ? new Date(c.lastActivityAt).toLocaleDateString() : 'N/A'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </ConsoleLayout>
  );
}

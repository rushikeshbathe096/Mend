'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Toast } from '@/components/common/Toast';
import { ErrorAlert } from '@/components/common/Feedback';
import { DetailSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { MerchantConfigDto, UpdateMerchantConfigRequest } from '@/types';

const RETRY_STRATEGIES = [
  { id: 'EXPONENTIAL_BACKOFF', label: 'Exponential Backoff (Recommended)' },
  { id: 'LINEAR_BACKOFF', label: 'Linear Backoff' },
  { id: 'SMART_SCHEDULE', label: 'Smart AI Schedule' },
  { id: 'IMMEDIATE', label: 'Immediate Execution' },
];

const AVAILABLE_ACTIONS = [
  { id: 'PAYMENT_RETRY', label: 'Automated Payment Retry', desc: 'Attempt card/UPI re-charge via payment gateway' },
  { id: 'CUSTOMER_NOTIFY', label: 'Customer Notification', desc: 'Send SMS / Email payment update reminder' },
  { id: 'SUBSCRIPTION_PAUSE', label: 'Grace Period Pause', desc: 'Pause active subscription until payment resolution' },
  { id: 'DISCOUNT_OFFER', label: 'Retention Offer', desc: 'Offer grace discount or payment plan' },
];

export default function MerchantSettingsPage() {
  const { currentMerchantId, currentMerchantName } = useAuth();

  const [config, setConfig] = useState<MerchantConfigDto | null>(null);
  const [form, setForm] = useState<UpdateMerchantConfigRequest>({
    maxAttempts: 3,
    maxContactAttempts: 2,
    contactWindowHours: 24,
    retryStrategy: 'EXPONENTIAL_BACKOFF',
    escalationThreshold: 0.7,
    enabledRecoveryActions: ['PAYMENT_RETRY', 'CUSTOMER_NOTIFY'],
  });

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const fetchSettings = useCallback(async () => {
    if (!currentMerchantId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      const res = await api.get<MerchantConfigDto>('/merchants/config');
      setConfig(res);
      setForm({
        maxAttempts: res.maxAttempts ?? 3,
        maxContactAttempts: res.maxContactAttempts ?? 2,
        contactWindowHours: res.contactWindowHours ?? 24,
        retryStrategy: res.retryStrategy || 'EXPONENTIAL_BACKOFF',
        escalationThreshold: res.escalationThreshold ?? 0.7,
        enabledRecoveryActions: res.enabledRecoveryActions || ['PAYMENT_RETRY', 'CUSTOMER_NOTIFY'],
      });
    } catch (err: any) {
      setError(err.message || 'Failed to load merchant configuration settings.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId]);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);

    // Frontend validation checks
    if (form.maxAttempts < 1 || form.maxAttempts > 10) {
      setError('Max attempts must be between 1 and 10.');
      setSaving(false);
      return;
    }
    if (form.contactWindowHours < 1 || form.contactWindowHours > 168) {
      setError('Contact window hours must be between 1 and 168 hours (1 week).');
      setSaving(false);
      return;
    }

    try {
      const updated = await api.put<MerchantConfigDto>('/merchants/config', form);
      setConfig(updated);
      setToastMessage('Merchant recovery configuration saved successfully!');
    } catch (err: any) {
      setError(err.message || 'Failed to update merchant configuration.');
    } finally {
      setSaving(false);
    }
  };

  const toggleAction = (actionId: string) => {
    const current = form.enabledRecoveryActions || [];
    if (current.includes(actionId)) {
      setForm({ ...form, enabledRecoveryActions: current.filter((a) => a !== actionId) });
    } else {
      setForm({ ...form, enabledRecoveryActions: [...current, actionId] });
    }
  };

  return (
    <ConsoleLayout>
      <div className="space-y-8 max-w-4xl mx-auto">
        {/* Toast */}
        {toastMessage && (
          <Toast message={toastMessage} onClose={() => setToastMessage(null)} />
        )}

        {/* Header */}
        <div>
          <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white">
            Merchant Recovery Settings
          </h1>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
            Configure recovery attempt thresholds, compliance rules, and active action integrations for{' '}
            <span className="font-bold text-gray-900 dark:text-white">{currentMerchantName || 'Merchant Account'}</span>
          </p>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchSettings} />}

        {loading ? (
          <DetailSkeleton />
        ) : (
          <form onSubmit={handleSave} className="space-y-8">
            {/* Threshold & Retry Controls Card */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-6">
              <h2 className="text-base font-bold text-gray-900 dark:text-white border-b border-gray-100 dark:border-gray-800 pb-3">
                Recovery Pipeline Parameters
              </h2>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Max Recovery Attempts */}
                <div>
                  <label className="block text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wider mb-2">
                    Max Recovery Attempts (1-10)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={10}
                    value={form.maxAttempts}
                    onChange={(e) => setForm({ ...form, maxAttempts: parseInt(e.target.value) || 1 })}
                    className="w-full px-4 py-2.5 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-sm font-semibold text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                  <p className="text-[11px] text-gray-500 mt-1">Maximum retry loops before marking campaign EXHAUSTED.</p>
                </div>

                {/* Max Contact Attempts */}
                <div>
                  <label className="block text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wider mb-2">
                    Max Customer Contact Attempts (1-10)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={10}
                    value={form.maxContactAttempts}
                    onChange={(e) => setForm({ ...form, maxContactAttempts: parseInt(e.target.value) || 1 })}
                    className="w-full px-4 py-2.5 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-sm font-semibold text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                  <p className="text-[11px] text-gray-500 mt-1">Compliance limit for customer outreach messages.</p>
                </div>

                {/* Contact Window Hours */}
                <div>
                  <label className="block text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wider mb-2">
                    Contact Window (Hours: 1-168)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={168}
                    value={form.contactWindowHours}
                    onChange={(e) => setForm({ ...form, contactWindowHours: parseInt(e.target.value) || 24 })}
                    className="w-full px-4 py-2.5 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-sm font-semibold text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                  <p className="text-[11px] text-gray-500 mt-1">Time window allowed between customer notifications.</p>
                </div>

                {/* Escalation Threshold */}
                <div>
                  <label className="block text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wider mb-2">
                    AI Escalation Confidence Threshold ({(form.escalationThreshold * 100).toFixed(0)}%)
                  </label>
                  <input
                    type="range"
                    min={0.1}
                    max={1.0}
                    step={0.05}
                    value={form.escalationThreshold}
                    onChange={(e) => setForm({ ...form, escalationThreshold: parseFloat(e.target.value) })}
                    className="w-full accent-blue-600 cursor-pointer"
                  />
                  <p className="text-[11px] text-gray-500 mt-1">Minimum AI confidence required before auto-executing aggressive actions.</p>
                </div>
              </div>

              {/* Retry Strategy Selector */}
              <div>
                <label className="block text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wider mb-2">
                  Default Retry Strategy Pattern
                </label>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {RETRY_STRATEGIES.map((s) => (
                    <label
                      key={s.id}
                      className={`flex items-center space-x-3 p-3.5 rounded-xl border cursor-pointer transition-colors ${
                        form.retryStrategy === s.id
                          ? 'border-blue-600 bg-blue-50/50 dark:bg-blue-950/40 text-blue-900 dark:text-blue-100 font-bold'
                          : 'border-gray-200 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800/50 text-gray-700 dark:text-gray-300'
                      }`}
                    >
                      <input
                        type="radio"
                        name="retryStrategy"
                        value={s.id}
                        checked={form.retryStrategy === s.id}
                        onChange={() => setForm({ ...form, retryStrategy: s.id })}
                        className="text-blue-600 focus:ring-blue-500"
                      />
                      <span className="text-xs">{s.label}</span>
                    </label>
                  ))}
                </div>
              </div>
            </div>

            {/* Enabled Recovery Actions Selector Card */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-6 shadow-sm space-y-6">
              <h2 className="text-base font-bold text-gray-900 dark:text-white border-b border-gray-100 dark:border-gray-800 pb-3">
                Enabled Recovery Actions & Channels
              </h2>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {AVAILABLE_ACTIONS.map((action) => {
                  const isEnabled = (form.enabledRecoveryActions || []).includes(action.id);
                  return (
                    <div
                      key={action.id}
                      onClick={() => toggleAction(action.id)}
                      className={`p-4 rounded-2xl border cursor-pointer transition-all ${
                        isEnabled
                          ? 'border-emerald-600 bg-emerald-50/40 dark:bg-emerald-950/30 text-gray-900 dark:text-white'
                          : 'border-gray-200 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800/40 opacity-75'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-sm font-bold">{action.label}</span>
                        <input
                          type="checkbox"
                          checked={isEnabled}
                          onChange={() => {}} // Handled by parent div onClick
                          className="w-4 h-4 text-emerald-600 rounded focus:ring-emerald-500 cursor-pointer"
                        />
                      </div>
                      <p className="text-xs text-gray-500 dark:text-gray-400 font-medium">{action.desc}</p>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Save Button Bar */}
            <div className="flex justify-end pt-4">
              <button
                type="submit"
                disabled={saving}
                className="px-6 py-3 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded-xl text-xs transition-colors shadow-md flex items-center space-x-2 disabled:opacity-50"
              >
                {saving && (
                  <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                  </svg>
                )}
                <span>{saving ? 'Saving Configuration...' : 'Save Merchant Settings'}</span>
              </button>
            </div>
          </form>
        )}
      </div>
    </ConsoleLayout>
  );
}

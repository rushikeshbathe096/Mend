'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { Modal } from '@/components/common/Modal';
import { Badge } from '@/components/common/Badge';
import { formatCurrency } from '@/components/common/MetricCard';
import { TableSkeleton } from '@/components/common/Skeleton';
import { ErrorAlert } from '@/components/common/Feedback';
import { errorMessage } from '@/lib/errors';
import { useLoad } from '@/lib/useLoad';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import {
  PaymentSummaryDto,
  PageResponse,
  StartRecoveryResponseDto,
  AgentDecisionRecordDto,
} from '@/types';

interface StartRecoveryModalProps {
  isOpen: boolean;
  onClose: () => void;
  onRecoveryComplete: () => void;
}

type ModalStep = 'select' | 'review' | 'orchestrating' | 'result';

/** Renders a fresh body for every opening, so step, selection and result start over each time it opens. */
export function StartRecoveryModal(props: StartRecoveryModalProps) {
  if (!props.isOpen) return null;
  return <StartRecoveryModalBody {...props} />;
}

function StartRecoveryModalBody({ isOpen, onClose, onRecoveryComplete }: StartRecoveryModalProps) {
  const { currentMerchantId, isLoading: isAuthLoading } = useAuth();

  const [step, setStep] = useState<ModalStep>('select');

  // Step 1: Selection state
  const [payments, setPayments] = useState<PaymentSummaryDto[]>([]);
  const [selectedPayment, setSelectedPayment] = useState<PaymentSummaryDto | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [paymentsError, setPaymentsError] = useState<string | null>(null);

  // Step 3 & 4: Orchestration & Result state
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [orchestrationError, setOrchestrationError] = useState<string | null>(null);
  const [resultData, setResultData] = useState<StartRecoveryResponseDto | null>(null);

  const { loading, reload: fetchEligiblePayments } = useLoad(isAuthLoading || !currentMerchantId ? null : currentMerchantId, async (isCurrent) => {
    try {
      const res = await api.get<PageResponse<PaymentSummaryDto>>('/payments?page=0&size=50', {
        merchantId: currentMerchantId,
      });
      if (!isCurrent()) return;
      setPayments(res.content || []);
      setPaymentsError(null);
    } catch (err: unknown) {
      if (isCurrent()) setPaymentsError(errorMessage(err, 'Failed to load merchant payment failures.'));
    }
  });
  const loadingPayments = loading || isAuthLoading;

  const filteredPayments = payments.filter((p) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase().trim();
    return (
      p.paymentId.toLowerCase().includes(q) ||
      (p.customerIdHash && p.customerIdHash.toLowerCase().includes(q)) ||
      (p.failureClass && p.failureClass.toLowerCase().includes(q))
    );
  });

  const handleStartRecoverySubmit = async () => {
    if (!selectedPayment || !currentMerchantId || isSubmitting) return;

    setIsSubmitting(true);
    setOrchestrationError(null);
    setStep('orchestrating');

    try {
      const response = await api.post<StartRecoveryResponseDto>(
        '/campaigns/start-recovery',
        {
          paymentId: selectedPayment.paymentId,
          customerIdHash: selectedPayment.customerIdHash,
        },
        { merchantId: currentMerchantId }
      );

      setResultData(response);
      setStep('result');
      onRecoveryComplete();
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setOrchestrationError(err.message || 'Recovery orchestration failed.');
      } else {
        setOrchestrationError('Failed to reach backend recovery service.');
      }
      setStep('review');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCloseModal = () => {
    if (step === 'result') {
      onRecoveryComplete();
    }
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleCloseModal}
      title="Start AI Payment Recovery"
      subtitle="Authoritative multi-agent orchestration, compliance gating, and execution pipeline"
      maxWidth="2xl"
    >
      <div className="space-y-6">
        {/* Step Indicator Header */}
        <div className="flex items-center justify-between border-b border-gray-100 dark:border-gray-800 pb-4">
          <div className="flex items-center space-x-2">
            <span
              className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold ${
                step === 'select'
                  ? 'bg-blue-600 text-white'
                  : 'bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
              }`}
            >
              1
            </span>
            <span className={`text-xs font-bold ${step === 'select' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>
              Select Failed Payment
            </span>
          </div>

          <div className="w-8 h-px bg-gray-200 dark:bg-gray-800" />

          <div className="flex items-center space-x-2">
            <span
              className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold ${
                step === 'review'
                  ? 'bg-blue-600 text-white'
                  : step === 'orchestrating' || step === 'result'
                  ? 'bg-emerald-600 text-white'
                  : 'bg-gray-100 text-gray-400 dark:bg-gray-800'
              }`}
            >
              2
            </span>
            <span
              className={`text-xs font-bold ${
                step === 'review' || step === 'orchestrating' || step === 'result'
                  ? 'text-gray-900 dark:text-white'
                  : 'text-gray-400'
              }`}
            >
              Review Context
            </span>
          </div>

          <div className="w-8 h-px bg-gray-200 dark:bg-gray-800" />

          <div className="flex items-center space-x-2">
            <span
              className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold ${
                step === 'orchestrating'
                  ? 'bg-blue-600 text-white animate-pulse'
                  : step === 'result'
                  ? 'bg-emerald-600 text-white'
                  : 'bg-gray-100 text-gray-400 dark:bg-gray-800'
              }`}
            >
              3
            </span>
            <span className={`text-xs font-bold ${step === 'result' ? 'text-gray-900 dark:text-white' : 'text-gray-400'}`}>
              Agent Orchestration & Outcome
            </span>
          </div>
        </div>

        {/* STEP 1: SELECT FAILED PAYMENT */}
        {step === 'select' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div className="relative flex-1">
                <svg className="w-4 h-4 absolute left-3.5 top-3 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <input
                  type="text"
                  placeholder="Search failed payment reference or customer hash..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full pl-10 pr-4 py-2 bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl text-xs font-medium text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
              <button
                onClick={fetchEligiblePayments}
                disabled={loadingPayments}
                className="px-3 py-2 border border-gray-300 dark:border-gray-700 rounded-xl text-xs font-semibold text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
              >
                Refresh List
              </button>
            </div>

            {paymentsError && <ErrorAlert message={paymentsError} onRetry={fetchEligiblePayments} />}

            {loadingPayments ? (
              <TableSkeleton rows={5} columns={5} />
            ) : filteredPayments.length === 0 ? (
              <div className="py-12 text-center text-xs text-gray-500 space-y-2">
                <p className="font-bold text-gray-700 dark:text-gray-300">No Eligible Failed Payments Found</p>
                <p>All payments for this merchant are either recovered, undergoing active execution, or exhausted.</p>
              </div>
            ) : (
              <div className="border border-gray-200 dark:border-gray-800 rounded-2xl overflow-hidden max-h-[320px] overflow-y-auto">
                <table className="w-full text-left text-xs">
                  <thead className="bg-gray-50 dark:bg-gray-800/80 text-gray-500 dark:text-gray-400 font-bold uppercase tracking-wider sticky top-0 border-b border-gray-200 dark:border-gray-800">
                    <tr>
                      <th className="px-4 py-3">Select</th>
                      <th className="px-4 py-3">Payment Reference</th>
                      <th className="px-4 py-3 text-right">Amount</th>
                      <th className="px-4 py-3">Failure Reason</th>
                      <th className="px-4 py-3">State</th>
                      <th className="px-4 py-3">Attempts</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-medium">
                    {filteredPayments.map((p) => {
                      const isSelected = selectedPayment?.paymentId === p.paymentId;
                      return (
                        <tr
                          key={p.paymentId}
                          onClick={() => setSelectedPayment(p)}
                          className={`cursor-pointer transition-colors ${
                            isSelected
                              ? 'bg-blue-50/80 dark:bg-blue-950/60 font-semibold'
                              : 'hover:bg-gray-50 dark:hover:bg-gray-800/40'
                          }`}
                        >
                          <td className="px-4 py-3">
                            <input
                              type="radio"
                              name="paymentSelection"
                              checked={isSelected}
                              onChange={() => setSelectedPayment(p)}
                              className="text-blue-600 focus:ring-blue-500 cursor-pointer"
                            />
                          </td>
                          <td className="px-4 py-3 font-mono font-bold text-gray-900 dark:text-white">
                            {p.paymentId}
                            {p.customerIdHash && (
                              <div className="text-[10px] text-gray-400 font-normal">{p.customerIdHash.slice(0, 18)}&hellip;</div>
                            )}
                          </td>
                          <td className="px-4 py-3 text-right font-mono font-bold text-gray-900 dark:text-white">
                            {formatCurrency(p.amount)}
                          </td>
                          <td className="px-4 py-3 font-semibold text-gray-800 dark:text-gray-200">
                            {p.failureClass || 'UNKNOWN'}
                          </td>
                          <td className="px-4 py-3">
                            <Badge status={p.currentState} />
                          </td>
                          <td className="px-4 py-3 font-mono text-gray-600 dark:text-gray-400">{p.attemptCount ?? 0}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <div className="flex justify-end pt-2">
              <button
                onClick={() => setStep('review')}
                disabled={!selectedPayment}
                className="px-5 py-2.5 bg-blue-600 hover:bg-blue-700 disabled:opacity-40 text-white rounded-xl text-xs font-bold transition-colors shadow-sm flex items-center space-x-2"
              >
                <span>Continue to Review</span>
                <span>&rarr;</span>
              </button>
            </div>
          </div>
        )}

        {/* STEP 2: REVIEW RECOVERY */}
        {step === 'review' && selectedPayment && (
          <div className="space-y-6">
            {orchestrationError && <ErrorAlert message={orchestrationError} />}

            <div className="bg-gray-50/70 dark:bg-gray-800/50 p-5 rounded-2xl border border-gray-200 dark:border-gray-700 space-y-4">
              <div className="flex items-center justify-between flex-wrap gap-2 border-b border-gray-200 dark:border-gray-700 pb-3">
                <div>
                  <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">Target Payment</span>
                  <h3 className="text-lg font-black font-mono text-gray-900 dark:text-white">{selectedPayment.paymentId}</h3>
                </div>
                <div className="text-right">
                  <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">Transaction Value</span>
                  <div className="text-xl font-black font-mono text-rose-600 dark:text-rose-400">
                    {formatCurrency(selectedPayment.amount)}
                  </div>
                </div>
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs font-mono">
                <div>
                  <span className="text-gray-500 font-sans block text-[11px]">Failure Class</span>
                  <span className="font-bold text-gray-900 dark:text-white">{selectedPayment.failureClass || 'UNKNOWN'}</span>
                </div>
                <div>
                  <span className="text-gray-500 font-sans block text-[11px]">Customer Hash</span>
                  <span className="font-bold text-gray-900 dark:text-white truncate block max-w-[120px]">
                    {selectedPayment.customerIdHash || 'Unattributed'}
                  </span>
                </div>
                <div>
                  <span className="text-gray-500 font-sans block text-[11px]">Current State</span>
                  <Badge status={selectedPayment.currentState} />
                </div>
                <div>
                  <span className="text-gray-500 font-sans block text-[11px]">Previous Attempts</span>
                  <span className="font-bold text-gray-900 dark:text-white">{selectedPayment.attemptCount ?? 0}</span>
                </div>
              </div>
            </div>

            <div className="p-4 rounded-xl bg-blue-50 dark:bg-blue-950/40 border border-blue-200 dark:border-blue-800 text-xs text-blue-900 dark:text-blue-300 space-y-2 leading-relaxed">
              <span className="font-bold block">Authoritative Recovery Pipeline Execution:</span>
              <p>
                Mend will analyze payment telemetry, customer history, risk signals, strategy performance, and compliance rules using the 6-agent architecture before executing authorized payment recovery through the payment provider.
              </p>
            </div>

            <div className="flex justify-between items-center pt-2">
              <button
                onClick={() => setStep('select')}
                disabled={isSubmitting}
                className="px-4 py-2 border border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 rounded-xl text-xs font-bold transition-colors"
              >
                &larr; Select Different Payment
              </button>

              <button
                onClick={handleStartRecoverySubmit}
                disabled={isSubmitting}
                className="px-6 py-2.5 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 text-white rounded-xl text-xs font-bold transition-colors shadow-md flex items-center space-x-2"
              >
                {isSubmitting && <span className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />}
                <span>Start AI Recovery</span>
              </button>
            </div>
          </div>
        )}

        {/* STEP 3: LIVE ORCHESTRATION IN PROGRESS */}
        {step === 'orchestrating' && (
          <div className="py-12 space-y-6 text-center">
            <div className="w-12 h-12 border-4 border-blue-600 border-t-transparent rounded-full animate-spin mx-auto" />
            <div className="space-y-1">
              <h3 className="text-base font-bold text-gray-900 dark:text-white">Orchestrating Recovery Pipeline</h3>
              <p className="text-xs text-gray-500">Evaluating multi-agent consensus, compliance safety rules, and provider execution...</p>
            </div>

            <div className="max-w-md mx-auto bg-gray-50 dark:bg-gray-800/50 rounded-2xl border border-gray-200 dark:border-gray-700 p-4 text-left space-y-2 text-xs font-mono">
              <div className="flex items-center space-x-2 text-emerald-600 dark:text-emerald-400 font-bold">
                <span>✓</span>
                <span>Payment context & tenant ownership verified</span>
              </div>
              <div className="flex items-center space-x-2 text-emerald-600 dark:text-emerald-400 font-bold">
                <span>✓</span>
                <span>Risk & Fraud Agent evaluation</span>
              </div>
              <div className="flex items-center space-x-2 text-emerald-600 dark:text-emerald-400 font-bold">
                <span>✓</span>
                <span>Recovery Strategy & Decision Engine</span>
              </div>
              <div className="flex items-center space-x-2 text-emerald-600 dark:text-emerald-400 font-bold">
                <span>✓</span>
                <span>Compliance Policy Safety Gate</span>
              </div>
              <div className="flex items-center space-x-2 text-blue-600 dark:text-blue-400 font-bold animate-pulse">
                <span>&rarr;</span>
                <span>Creating ActionIntent & Dispatching Provider Worker...</span>
              </div>
            </div>
          </div>
        )}

        {/* STEP 4: RESULT & AGENT DECISION EVIDENCE */}
        {step === 'result' && resultData && (
          <div className="space-y-6">
            {/* Outcome Banner */}
            {resultData.executionResult?.status === 'SUCCESS' || resultData.campaign?.currentState === 'RECOVERED' ? (
              <div className="p-5 rounded-2xl bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 space-y-2">
                <div className="flex items-center space-x-2 text-emerald-800 dark:text-emerald-300 font-bold text-sm">
                  <span className="w-5 h-5 rounded-full bg-emerald-500 text-white flex items-center justify-center text-xs">✓</span>
                  <span>Payment Recovery Successful!</span>
                </div>
                <p className="text-xs text-emerald-700 dark:text-emerald-400 font-medium">
                  {resultData.message || 'The payment was successfully recovered through the provider execution boundary.'}
                </p>
                {resultData.executionResult?.externalReference && (
                  <div className="text-[11px] font-mono text-emerald-800 dark:text-emerald-300 font-bold pt-1">
                    Provider Reference: {resultData.executionResult.externalReference}
                  </div>
                )}
              </div>
            ) : resultData.requiresHumanApproval || resultData.campaign?.currentState === 'ELIGIBLE' ? (
              <div className="p-5 rounded-2xl bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-800 space-y-2">
                <div className="flex items-center space-x-2 text-amber-800 dark:text-amber-300 font-bold text-sm">
                  <span className="w-5 h-5 rounded-full bg-amber-500 text-white flex items-center justify-center text-xs">!</span>
                  <span>Merchant Human Approval Required</span>
                </div>
                <p className="text-xs text-amber-700 dark:text-amber-400 font-medium">
                  {resultData.message || 'Agent consensus requires human review before execution. Item routed to review queue.'}
                </p>
              </div>
            ) : resultData.complianceStatus === 'COMPLIANCE_BLOCKED' ? (
              <div className="p-5 rounded-2xl bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 space-y-2">
                <div className="flex items-center space-x-2 text-rose-800 dark:text-rose-300 font-bold text-sm">
                  <span className="w-5 h-5 rounded-full bg-rose-500 text-white flex items-center justify-center text-xs">&times;</span>
                  <span>Recovery Blocked by Compliance Policy</span>
                </div>
                <p className="text-xs text-rose-700 dark:text-rose-400 font-medium">
                  {resultData.message || 'Action intent stopped by compliance policy rules (contact window or attempt limits).'}
                </p>
              </div>
            ) : (
              <div className="p-5 rounded-2xl bg-gray-50 dark:bg-gray-800/60 border border-gray-200 dark:border-gray-700 space-y-2">
                <div className="flex items-center space-x-2 text-gray-900 dark:text-white font-bold text-sm">
                  <span>Recovery Execution Outcome:</span>
                  <Badge status={resultData.campaign?.currentState || 'ACTION_PENDING'} />
                </div>
                <p className="text-xs text-gray-600 dark:text-gray-300">{resultData.message}</p>
              </div>
            )}

            {/* AI Decision Transparency Cards */}
            <div className="space-y-3">
              <h4 className="text-xs font-bold uppercase tracking-wider text-gray-700 dark:text-gray-300">
                AI Orchestrator Evidence & Governance
              </h4>

              {resultData.agentDecisions && resultData.agentDecisions.length > 0 ? (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {resultData.agentDecisions.map((ad: AgentDecisionRecordDto) => (
                    <div
                      key={ad.id}
                      className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/40 space-y-2 text-xs"
                    >
                      <div className="flex items-center justify-between font-bold">
                        <span className="text-gray-900 dark:text-white">{ad.decision}</span>
                        {ad.confidence !== undefined && ad.confidence !== null && (
                          <span className="px-2 py-0.5 rounded font-mono font-bold bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300 text-[10px]">
                            {(ad.confidence * 100).toFixed(0)}% Confidence
                          </span>
                        )}
                      </div>
                      <p className="text-gray-600 dark:text-gray-300 leading-relaxed font-medium">{ad.reasoning}</p>
                      {ad.selectedAction && (
                        <div className="text-[11px] font-mono text-gray-500">Proposed: {ad.selectedAction}</div>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50 text-xs text-gray-500 font-mono">
                  No decision records stored.
                </div>
              )}
            </div>

            {/* Direct Navigation Links */}
            <div className="flex flex-wrap items-center justify-between gap-3 pt-4 border-t border-gray-100 dark:border-gray-800">
              <div className="flex items-center space-x-3 text-xs font-bold">
                {resultData.campaign && (
                  <Link
                    href={`/campaigns/${resultData.campaign.id}`}
                    onClick={handleCloseModal}
                    className="text-blue-600 dark:text-blue-400 hover:underline font-mono"
                  >
                    View Campaign &rarr;
                  </Link>
                )}
                {selectedPayment && (
                  <Link
                    href={`/payments/${encodeURIComponent(selectedPayment.paymentId)}`}
                    onClick={handleCloseModal}
                    className="text-blue-600 dark:text-blue-400 hover:underline font-mono"
                  >
                    View Payment &rarr;
                  </Link>
                )}
                <Link
                  href="/audit"
                  onClick={handleCloseModal}
                  className="text-blue-600 dark:text-blue-400 hover:underline font-mono"
                >
                  View Audit Trail &rarr;
                </Link>
              </div>

              <button
                onClick={handleCloseModal}
                className="px-5 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-xl text-xs font-bold transition-colors shadow-sm"
              >
                Done
              </button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}

'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ConsoleLayout } from '@/components/layout/ConsoleLayout';
import { Badge } from '@/components/common/Badge';
import { MetricCard } from '@/components/common/MetricCard';
import { Modal } from '@/components/common/Modal';
import { Timeline, TimelineStep } from '@/components/common/Timeline';
import { ErrorAlert } from '@/components/common/Feedback';
import { DetailSkeleton } from '@/components/common/Skeleton';
import { api } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';
import { CampaignDto, CampaignTimelineDto, AgentDecisionRecordDto, PageResponse, ReviewItemDto, CampaignDto as CD } from '@/types';

export default function CampaignDetailPage() {
  const params = useParams();
  const campaignId = params?.id as string;
  const { currentMerchantId } = useAuth();

  const [campaign, setCampaign] = useState<CampaignDto | null>(null);
  const [timeline, setTimeline] = useState<CampaignTimelineDto | null>(null);
  const [agentDecisions, setAgentDecisions] = useState<AgentDecisionRecordDto[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'timeline' | 'actions' | 'compliance' | 'ai' | 'audit'>('timeline');

  const [pendingReview, setPendingReview] = useState<ReviewItemDto | null>(null);
  const [controlLoading, setControlLoading] = useState(false);
  const [controlError, setControlError] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<'terminate' | 'pause' | null>(null);

  const fetchCampaignDetails = useCallback(async () => {
    if (!currentMerchantId || !campaignId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);

    try {
      const [cData, tData, dData] = await Promise.all([
        api.get<CampaignDto>(`/campaigns/${campaignId}`),
        api.get<CampaignTimelineDto>(`/campaigns/${campaignId}/timeline`),
        api.get<AgentDecisionRecordDto[]>(`/campaigns/${campaignId}/decisions`).catch(() => []),
      ]);

      setCampaign(cData);
      setTimeline(tData);
      setAgentDecisions(dData || []);

      // Detect an active human-approval review for this campaign
      const reviews = await api.get<PageResponse<ReviewItemDto>>('/reviews?status=PENDING&page=0&size=50').catch(() => null);
      const mine = reviews?.content?.find((r) => r.campaignId === campaignId) || null;
      setPendingReview(mine);
    } catch (err: any) {
      setError(err.message || 'Failed to load campaign lifecycle details.');
    } finally {
      setLoading(false);
    }
  }, [currentMerchantId, campaignId]);

  useEffect(() => {
    fetchCampaignDetails();
  }, [fetchCampaignDetails]);

  // Construct structured timeline steps
  const buildTimelineSteps = (): TimelineStep[] => {
    if (!timeline) return [];
    const steps: TimelineStep[] = [];

    // Step 1: Payment Ingestion
    steps.push({
      id: 'ingestion',
      title: 'Payment Failure Ingestion',
      subtitle: `Payment Reference: ${timeline.campaign?.paymentId || 'N/A'}`,
      timestamp: timeline.campaign?.createdAt,
      status: 'COMPLETED',
      details: (
        <div className="space-y-1 font-mono text-[11px]">
          <div>Subscription ID: {timeline.campaign?.subscriptionId || 'N/A'}</div>
          <div>Customer Hash: {timeline.campaign?.customerIdHash || 'N/A'}</div>
        </div>
      ),
    });

    // Step 2: AI Classification
    if (timeline.classification) {
      steps.push({
        id: 'classification',
        title: 'Failure Classification',
        subtitle: `Classified as ${timeline.classification.failureClass}`,
        timestamp: timeline.classification.createdAt,
        status: 'COMPLETED',
        details: (
          <div className="space-y-1">
            <div className="font-semibold text-gray-800 dark:text-gray-200">
              Confidence: {(timeline.classification.confidence * 100).toFixed(0)}%
            </div>
            {timeline.classification.reasoning && (
              <p className="text-gray-500 italic">&ldquo;{timeline.classification.reasoning}&rdquo;</p>
            )}
          </div>
        ),
      });
    }

    // Step 3: Strategy Decisions
    if (timeline.recoveryDecisions && timeline.recoveryDecisions.length > 0) {
      timeline.recoveryDecisions.forEach((rd, idx) => {
        steps.push({
          id: `decision-${rd.id || idx}`,
          title: `Recovery Strategy Decision (${rd.strategy})`,
          subtitle: `Priority: ${rd.priority} • Policy: ${rd.policyVersion}`,
          timestamp: rd.evaluatedAt,
          status: 'COMPLETED',
          details: <p className="text-gray-600 dark:text-gray-300 font-medium">{rd.reason}</p>,
        });
      });
    }

    // Step 4: Compliance Gating
    if (timeline.complianceDecisions && timeline.complianceDecisions.length > 0) {
      timeline.complianceDecisions.forEach((cd, idx) => {
        const isAllowed = cd.status === 'ALLOWED' || cd.status === 'PASSED';
        steps.push({
          id: `compliance-${cd.id || idx}`,
          title: `Compliance Evaluation (${cd.strategy})`,
          subtitle: `Status: ${cd.status} • Policy: ${cd.policyVersion}`,
          timestamp: cd.evaluatedAt,
          status: isAllowed ? 'COMPLETED' : 'BLOCKED',
          details: (
            <p className={isAllowed ? 'text-emerald-600' : 'text-rose-600 font-semibold'}>
              {cd.reason} {cd.detailMessage ? `(${cd.detailMessage})` : ''}
            </p>
          ),
        });
      });
    }

    // Step 5: Action Intents & Execution
    if (timeline.actionIntents && timeline.actionIntents.length > 0) {
      timeline.actionIntents.forEach((ai, idx) => {
        const isSuccess = ai.status === 'SUCCEEDED' || ai.status === 'COMPLETED';
        const isFailed = ai.status === 'FAILED' || ai.status === 'EXPIRED';
        const isPending = ai.status === 'SCHEDULED' || ai.status === 'CLAIMED' || ai.status === 'READY';

        steps.push({
          id: `action-${ai.id || idx}`,
          title: `Action Intent: ${ai.actionType} (Attempt #${ai.attemptNumber})`,
          subtitle: `Key: ${ai.idempotencyKey}`,
          timestamp: ai.completedAt || ai.scheduledAt || ai.createdAt,
          status: isSuccess ? 'COMPLETED' : isFailed ? 'FAILED' : isPending ? 'IN_PROGRESS' : 'PENDING',
          details: (
            <div className="space-y-1 font-mono text-[11px]">
              <div>Status: {ai.status}</div>
              {ai.responseReference && <div>Ref: {ai.responseReference}</div>}
              {ai.workerId && <div>Worker: {ai.workerId}</div>}
            </div>
          ),
        });
      });
    }

    // Final State Step
    steps.push({
      id: 'final-state',
      title: `Current Pipeline State: ${timeline.currentState}`,
      subtitle: `Campaign status evaluated by orchestrator`,
      timestamp: timeline.campaign?.updatedAt,
      status:
        timeline.currentState === 'RECOVERED'
          ? 'COMPLETED'
          : timeline.currentState === 'FAILED' || timeline.currentState === 'EXHAUSTED'
          ? 'FAILED'
          : 'IN_PROGRESS',
    });

    return steps;
  };

  return (
    <ConsoleLayout>
      <div className="space-y-8">
        {/* Navigation Breadcrumb */}
        <div className="flex items-center space-x-2 text-xs font-semibold text-gray-500">
          <Link href="/campaigns" className="hover:text-blue-600 transition-colors">
            Campaigns
          </Link>
          <span>&rarr;</span>
          <span className="text-gray-900 dark:text-white font-mono">{campaignId}</span>
        </div>

        {error && <ErrorAlert message={error} onRetry={fetchCampaignDetails} />}

        {loading ? (
          <DetailSkeleton />
        ) : !campaign ? (
          <ErrorAlert message="Campaign entity not found for this merchant." />
        ) : (
          <>
            {/* Header Section */}
            <div className="bg-white dark:bg-gray-900 p-6 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-6">
              <div>
                <div className="flex items-center space-x-3">
                  <h1 className="text-2xl font-black tracking-tight text-gray-900 dark:text-white font-mono">
                    {campaign.paymentId}
                  </h1>
                  <Badge status={campaign.currentState} />
                </div>
                <p className="text-xs text-gray-500 dark:text-gray-400 mt-1 font-mono">
                  Campaign ID: {campaign.id} &bull; Created: {new Date(campaign.createdAt).toLocaleString()}
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-3">
                {pendingReview && (
                  <Link
                    href="/actions"
                    className="px-4 py-2 bg-amber-50 dark:bg-amber-950/60 text-amber-800 dark:text-amber-300 border border-amber-300 dark:border-amber-700 rounded-xl text-xs font-bold hover:bg-amber-100 transition-colors"
                  >
                    Pending Approval
                  </Link>
                )}
                {(campaign.currentState === 'ACTION_PENDING' || campaign.currentState === 'ELIGIBLE') && (
                  <button
                    onClick={() => setConfirmAction(campaign.currentState === 'ACTION_PENDING' ? 'pause' : 'terminate')}
                    disabled={controlLoading}
                    className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 transition-colors shadow-sm disabled:opacity-50"
                  >
                    {campaign.currentState === 'ACTION_PENDING' ? 'Pause' : 'Terminate'}
                  </button>
                )}
                {campaign.currentState === 'CREATED' && (
                  <button
                    onClick={() => setConfirmAction('terminate')}
                    disabled={controlLoading}
                    className="px-4 py-2 border border-rose-300 dark:border-rose-800 text-rose-700 dark:text-rose-400 rounded-xl text-xs font-bold hover:bg-rose-50 dark:hover:bg-rose-950/40 transition-colors disabled:opacity-50"
                  >
                    Cancel Campaign
                  </button>
                )}
                <button
                  onClick={fetchCampaignDetails}
                  className="px-4 py-2 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 rounded-xl text-xs font-bold hover:bg-gray-50 transition-colors shadow-sm"
                >
                  Sync State
                </button>
              </div>
            </div>

            {controlError && <ErrorAlert message={controlError} onRetry={() => setControlError(null)} />}

            {pendingReview && (
              <div className="bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-800 rounded-2xl p-5 flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                  <div className="flex items-center space-x-2">
                    <span className="w-2 h-2 rounded-full bg-amber-500" />
                    <span className="text-sm font-bold text-amber-900 dark:text-amber-300">Merchant human approval required</span>
                  </div>
                  <p className="text-xs text-amber-800/80 dark:text-amber-400/80 mt-1 max-w-3xl">
                    {pendingReview.reason || 'This campaign cannot proceed until a reviewer approves or rejects the proposed recovery.'}
                  </p>
                </div>
                <Link
                  href="/actions"
                  className="px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white rounded-xl text-xs font-bold transition-colors shrink-0 text-center"
                >
                  Review in Action Center &rarr;
                </Link>
              </div>
            )}

            {/* Metric Overview Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
              <MetricCard
                title="Failure Class"
                value={campaign.failureClass || 'UNCLASSIFIED'}
                subtext="AI-classified root cause"
                variant="indigo"
              />
              <MetricCard
                title="AI Confidence"
                value={
                  campaign.confidence !== undefined && campaign.confidence !== null
                    ? `${(campaign.confidence * 100).toFixed(0)}%`
                    : 'N/A'
                }
                subtext="Model prediction certainty score"
                variant="emerald"
              />
              <MetricCard
                title="Active Strategy"
                value={campaign.strategy || 'DEFAULT'}
                subtext="Orchestration recovery plan"
                variant="amber"
              />
              <MetricCard
                title="Execution Attempts"
                value={campaign.attemptCount ?? 0}
                subtext={`Next action: ${
                  campaign.nextActionAt ? new Date(campaign.nextActionAt).toLocaleTimeString() : 'None scheduled'
                }`}
                variant="default"
              />
            </div>

            {/* Detail Tabs */}
            <div className="bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm overflow-hidden">
              {/* Tab Navigation */}
              <div className="flex border-b border-gray-200 dark:border-gray-800 px-6 bg-gray-50/50 dark:bg-gray-800/40 space-x-6 overflow-x-auto no-scrollbar">
                {[
                  { id: 'timeline', label: 'Recovery Lifecycle' },
                  { id: 'actions', label: `Action Intents (${timeline?.actionIntents?.length || 0})` },
                  { id: 'compliance', label: `Compliance (${timeline?.complianceDecisions?.length || 0})` },
                  { id: 'ai', label: `AI Agent Decisions (${agentDecisions.length})` },
                  { id: 'audit', label: `Audit Log (${timeline?.auditLogs?.length || 0})` },
                ].map((tab) => (
                  <button
                    key={tab.id}
                    onClick={() => setActiveTab(tab.id as any)}
                    className={`py-4 text-xs font-bold whitespace-nowrap border-b-2 transition-colors ${
                      activeTab === tab.id
                        ? 'border-blue-600 text-blue-600 dark:text-blue-400 dark:border-blue-400'
                        : 'border-transparent text-gray-500 hover:text-gray-900 dark:hover:text-white'
                    }`}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>

              {/* Tab Content */}
              <div className="p-6">
                {activeTab === 'timeline' && (
                  <div className="space-y-6">
                    <h3 className="text-base font-bold text-gray-900 dark:text-white mb-4">
                      Deterministic Lifecycle Progression
                    </h3>
                    <Timeline steps={buildTimelineSteps()} />
                  </div>
                )}

                {activeTab === 'actions' && (
                  <div className="space-y-4">
                    {timeline?.actionIntents?.length === 0 ? (
                      <p className="text-sm text-gray-500 italic py-6 text-center">No action intents generated yet.</p>
                    ) : (
                      <div className="overflow-x-auto">
                        <table className="w-full text-left text-xs">
                          <thead className="bg-gray-50 dark:bg-gray-800 text-gray-500 font-bold uppercase border-b border-gray-200 dark:border-gray-800">
                            <tr>
                              <th className="px-4 py-3">Attempt #</th>
                              <th className="px-4 py-3">Action Type</th>
                              <th className="px-4 py-3">Idempotency Key</th>
                              <th className="px-4 py-3">Status</th>
                              <th className="px-4 py-3">Scheduled At</th>
                              <th className="px-4 py-3">Completed At</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-gray-100 dark:divide-gray-800 font-mono">
                            {timeline?.actionIntents?.map((ai) => (
                              <tr key={ai.id} className="hover:bg-gray-50 dark:hover:bg-gray-800/40">
                                <td className="px-4 py-3 font-bold">#{ai.attemptNumber}</td>
                                <td className="px-4 py-3 font-sans font-bold">{ai.actionType}</td>
                                <td className="px-4 py-3 text-gray-500 text-[11px] truncate max-w-[200px]">{ai.idempotencyKey}</td>
                                <td className="px-4 py-3">
                                  <Badge status={ai.status} />
                                </td>
                                <td className="px-4 py-3 text-gray-500">{new Date(ai.scheduledAt).toLocaleString()}</td>
                                <td className="px-4 py-3 text-gray-500">{ai.completedAt ? new Date(ai.completedAt).toLocaleString() : 'N/A'}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                )}

                {activeTab === 'compliance' && (
                  <div className="space-y-4">
                    {timeline?.complianceDecisions?.length === 0 ? (
                      <p className="text-sm text-gray-500 italic py-6 text-center">No compliance evaluations recorded.</p>
                    ) : (
                      <div className="space-y-4">
                        {timeline?.complianceDecisions?.map((cd) => (
                          <div key={cd.id} className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/50 space-y-2">
                            <div className="flex items-center justify-between">
                              <span className="text-sm font-bold text-gray-900 dark:text-white font-mono">Strategy: {cd.strategy}</span>
                              <Badge status={cd.status} />
                            </div>
                            <p className="text-xs text-gray-700 dark:text-gray-300 font-medium">{cd.reason}</p>
                            {cd.detailMessage && <p className="text-xs text-rose-600 font-mono">{cd.detailMessage}</p>}
                            <div className="text-[10px] text-gray-400 font-mono">Policy Version: {cd.policyVersion} &bull; Evaluated: {new Date(cd.evaluatedAt).toLocaleString()}</div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {activeTab === 'ai' && (
                  <div className="space-y-4">
                    {agentDecisions.length === 0 ? (
                      <div className="p-6 text-center border-2 border-dashed border-gray-200 dark:border-gray-800 rounded-xl">
                        <p className="text-sm text-gray-500 font-medium">No dedicated AI Agent decision records for this campaign.</p>
                        <p className="text-xs text-gray-400 mt-1">Rule-based or heuristic orchestrator decisions apply directly.</p>
                      </div>
                    ) : (
                      <div className="space-y-4">
                        {agentDecisions.map((ad) => (
                          <div key={ad.id} className="p-5 rounded-2xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/50 space-y-3">
                            <div className="flex items-center justify-between">
                              <div className="flex items-center space-x-2">
                                <span className="text-sm font-bold text-gray-900 dark:text-white">{ad.decision}</span>
                                {ad.confidence !== undefined && (
                                  <span className="px-2 py-0.5 rounded text-[11px] font-mono font-bold bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300">
                                    {(ad.confidence * 100).toFixed(0)}% Conf
                                  </span>
                                )}
                              </div>
                              <span className="text-xs text-gray-400 font-mono">{new Date(ad.createdAt).toLocaleString()}</span>
                            </div>
                            <div className="bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-700 text-xs text-gray-700 dark:text-gray-300 font-medium">
                              <span className="font-bold text-gray-900 dark:text-white block mb-1">Reasoning Explanation:</span>
                              {ad.reasoning}
                            </div>
                            {ad.nextStep && <div className="text-xs font-mono text-gray-500">Next Step: {ad.nextStep}</div>}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {activeTab === 'audit' && (
                  <div className="space-y-4">
                    {timeline?.auditLogs?.length === 0 ? (
                      <p className="text-sm text-gray-500 italic py-6 text-center">No audit log entries found for this campaign.</p>
                    ) : (
                      <div className="space-y-3">
                        {timeline?.auditLogs?.map((al) => (
                          <div key={al.id} className="p-3.5 rounded-xl border border-gray-200 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/50 flex items-center justify-between text-xs">
                            <div>
                              <span className="font-bold font-mono text-gray-900 dark:text-white">{al.eventType}</span>
                              <span className="text-gray-500 ml-2 font-mono">Actor: {al.actorType || 'SYSTEM'} ({al.actorId || 'N/A'})</span>
                              {al.reason && <p className="text-gray-600 dark:text-gray-400 mt-0.5">{al.reason}</p>}
                            </div>
                            <span className="text-[11px] text-gray-400 font-mono">{new Date(al.createdAt).toLocaleString()}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Lifecycle control confirmation */}
      <Modal
        isOpen={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        title={confirmAction === 'terminate' ? 'Terminate this campaign?' : 'Pause this campaign?'}
        subtitle={confirmAction === 'terminate' ? 'This ends the recovery workflow. Pending intents are cancelled.' : 'Pauses the campaign at ACTION_PENDING. Resume to continue.'}
        maxWidth="sm"
      >
        <div className="space-y-4">
          <p className="text-sm text-gray-700 dark:text-gray-300">
            {confirmAction === 'terminate'
              ? 'The backend state machine will transition this campaign to CANCELLED. This cannot be undone from the UI.'
              : 'The backend state machine will transition this campaign to ACTION_PENDING. No further action intents are created while paused.'}
          </p>
          <div className="flex justify-end space-x-3 pt-2">
            <button
              onClick={() => setConfirmAction(null)}
              className="px-4 py-2 border border-gray-300 dark:border-gray-700 rounded-lg text-xs font-bold text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={async () => {
                if (!campaign || !confirmAction) return;
                setControlLoading(true);
                setControlError(null);
                try {
                  const endpoint = confirmAction === 'terminate' ? 'terminate' : 'pause';
                  await api.post<CD>(`/campaigns/${campaign.id}/${endpoint}`);
                  setConfirmAction(null);
                  await fetchCampaignDetails();
                } catch (err: any) {
                  setControlError(err.message || `Failed to ${confirmAction} the campaign on the backend.`);
                  setConfirmAction(null);
                } finally {
                  setControlLoading(false);
                }
              }}
              disabled={controlLoading}
              className={`px-5 py-2 rounded-lg text-xs font-bold text-white transition-colors disabled:opacity-50 ${
                confirmAction === 'terminate' ? 'bg-rose-600 hover:bg-rose-700' : 'bg-amber-600 hover:bg-amber-700'
              }`}
            >
              {controlLoading ? 'Applying...' : confirmAction === 'terminate' ? 'Terminate Campaign' : 'Pause Campaign'}
            </button>
          </div>
        </div>
      </Modal>
    </ConsoleLayout>
  );
}

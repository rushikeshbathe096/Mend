export interface Membership {
  merchantId: string;
  merchantName: string;
  roleName: string;
}

export interface User {
  id: string;
  email: string;
  displayName: string;
  status?: string;
  memberships: Membership[];
}

export interface LoginResponse {
  token: string;
  expiresIn: number;
  user: User;
}

export interface BootstrapResponse {
  merchantId: string;
  userId: string;
  merchantName: string;
  userEmail: string;
  roleName: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface CampaignDto {
  id: string;
  merchantId: string;
  paymentId: string;
  customerIdHash: string;
  subscriptionId?: string;
  currentState: string;
  failureClass?: string;
  /** M3: set when the customer opted out of contact (compliance blocks CUSTOMER_OPTED_OUT). */
  contactOptedOutAt?: string | null;
  strategy?: string;
  attemptCount: number;
  nextActionAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentSummaryDto {
  paymentId: string;
  customerIdHash: string;
  merchantId: string;
  failureClass: string;
  amount: number;
  currentState: string;
  strategy?: string;
  attemptCount: number;
  campaignId: string;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentDetailDto {
  paymentSummary: PaymentSummaryDto;
  campaign: CampaignDto;
  attempts: CampaignAttemptDto[];
  actionIntents: ActionIntentDto[];
  complianceDecisions: ComplianceDecisionDto[];
  auditLogs: AuditLogDto[];
}

export interface CustomerSummaryDto {
  customerIdHash: string;
  merchantId: string;
  totalCampaigns: number;
  activeCampaigns: number;
  /** Recovered by MEND only (recovery_attribution = MEND), the same rule as analytics. */
  recoveredCampaigns: number;
  /** Exact sum of the known amounts; campaigns without one are in amountUnknownCount. */
  totalFailedAmount: number;
  /** MEND recoveries only. */
  totalRecoveredAmount: number;
  /** RECOVERED by a late capture of the original payment: not a MEND recovery. */
  recoveredLateCaptureCount?: number;
  amountRecoveredLateCapture?: number;
  /** RECOVERED with no attribution (before V15). */
  recoveredAttributionUnknownCount?: number;
  amountRecoveredAttributionUnknown?: number;
  /** Campaigns with no resolvable amount: excluded from every sum, never defaulted. */
  amountUnknownCount?: number;
  /** Mock-provider simulated captures (SYNTHETIC): never MEND's, never revenue. */
  recoveredSyntheticCount?: number;
  amountRecoveredSynthetic?: number;
  riskSignals: string[];
  lastActivityAt: string;
}

export interface CustomerProfileDto {
  summary: CustomerSummaryDto;
  campaigns: CampaignDto[];
  paymentHistory: PaymentSummaryDto[];
  disputeHistory: string[];
  auditHistory: AuditLogDto[];
}

export interface ClassificationResultDto {
  id: string;
  campaignId: string;
  eventId?: string;
  failureClass: string;
  /** Taxonomy table version of a deterministic row (M3); null/absent on a legacy pre-M3 row. */
  taxonomyVersion?: string | null;
  strategyRecommendation?: string;
  reasoning?: string;
  evidence?: Record<string, unknown>;
  modelVersion?: string;
  createdAt: string;
}

export interface RecoveryDecisionDto {
  id: string;
  campaignId: string;
  merchantId: string;
  classificationResultId?: string;
  strategy: string;
  reason: string;
  priority: string;
  policyVersion: string;
  evaluatedAt: string;
}

export interface ComplianceDecisionDto {
  id: string;
  campaignId: string;
  merchantId: string;
  recoveryDecisionId?: string;
  strategy: string;
  status: string;
  reason: string;
  detailMessage?: string;
  policyVersion: string;
  evaluatedAt: string;
}

export interface ActionIntentDto {
  id: string;
  merchantId: string;
  campaignId: string;
  attemptNumber: number;
  actionType: string;
  sourceStrategy?: string;
  complianceDecisionId?: string;
  status: string;
  idempotencyKey: string;
  requestHash?: string;
  responseReference?: string;
  scheduledAt: string;
  claimedAt?: string;
  workerId?: string;
  expiresAt?: string;
  createdAt: string;
  completedAt?: string;
}

export interface CampaignAttemptDto {
  id: string;
  campaignId: string;
  attemptNumber: number;
  actionType?: string;
  status?: string;
  scheduledAt?: string;
  startedAt?: string;
  completedAt?: string;
  failureReason?: string;
  externalReference?: string;
  createdAt: string;
}

export interface AuditLogDto {
  id: string;
  merchantId: string;
  campaignId?: string;
  eventType: string;
  actorType?: string;
  actorId?: string;
  reason?: string;
  evidence?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  createdAt: string;
}

export interface CampaignTimelineDto {
  campaignId: string;
  currentState: string;
  campaign: CampaignDto;
  classification?: ClassificationResultDto;
  recoveryDecisions: RecoveryDecisionDto[];
  complianceDecisions: ComplianceDecisionDto[];
  actionIntents: ActionIntentDto[];
  attempts: CampaignAttemptDto[];
  auditLogs: AuditLogDto[];
}

export interface WebhookEventDetailDto {
  id: string;
  externalEventId: string;
  eventType: string;
  source: string;
  receivedAt: string;
  eventCreatedAt?: string;
  payloadHash?: string;
  processingStatus: string;
  processedAt?: string;
  errorMessage?: string;
  merchantId?: string;
  publishStatus?: string;
  publishedAt?: string;
}

export interface AnalyticsOverviewDto {
  totalCampaigns: number;
  recoveredCampaigns: number;
  activeCampaigns: number;
  failedCampaigns: number;
  recoveryRate: number;
  totalAttempts: number;
  totalActionIntents: number;
  successfulIntents: number;
  totalPaymentFailures?: number;
  revenueAtRisk?: number;
  amountRecovered?: number;
  amountRemainingAtRisk?: number;
  campaignsEligible?: number;
  actionsAttempted?: number;
  successfulRecoveries?: number;
  failedRecoveryAttempts?: number;
  complianceBlocks?: number;
  averageIngestionToCampaignLatencyMs?: number;
  averageExecutionLatencyMs?: number;
  /** Campaigns with no verified amount: excluded from every sum, never defaulted. */
  amountUnknownCount?: number;
  /** RECOVERED by a late capture of the original payment: not a MEND recovery. */
  recoveredLateCaptureCount?: number;
  amountRecoveredLateCapture?: number;
  /** RECOVERED with no attribution (before V15). */
  recoveredAttributionUnknownCount?: number;
  amountRecoveredAttributionUnknown?: number;
  /** Mock-provider simulated captures (SYNTHETIC): never revenue. */
  recoveredSyntheticCount?: number;
  amountRecoveredSynthetic?: number;
}

/** M4 U4.8: holdout arm counts (operational) and causal figures from the latest VALID evaluation snapshot. */
export interface ExperimentFigureDto {
  stratum: string;
  name: string;
  value: string;
  low?: string | null;
  high?: string | null;
  n: number;
  label: 'MEASURED' | 'SYNTHETIC' | 'TBD';
  status: string;
  note?: string;
}

export interface ExperimentSummaryDto {
  holdoutRateBp: number;
  assignedTreatment: number;
  assignedControl: number;
  snapshot: { id: string; asOf: string; horizonDays: number; providerMode: string; label: string; interim: boolean } | null;
  figures: ExperimentFigureDto[];
  note: string;
}

export interface ComplianceMetrics {
  totalEvaluated?: number;
  allowedCount?: number;
  blockedCount?: number;
}

export interface RetryMetrics {
  totalAttempts?: number;
  averageAttemptsPerCampaign?: number;
}

export interface AnalyticsRecoveryDto {
  strategyBreakdown: Record<string, number>;
  failureClassBreakdown: Record<string, number>;
  statusBreakdown: Record<string, number>;
  recoveryRateByStrategy: Record<string, number>;
  recoveryRateByFailureClass?: Record<string, number>;
  actionTypeBreakdown?: Record<string, number>;
  providerOutcomes?: Record<string, number>;
  complianceMetrics?: ComplianceMetrics;
  retryMetrics?: RetryMetrics;
}

export interface MerchantConfigDto {
  id: string;
  merchantId: string;
  maxAttempts: number;
  maxContactAttempts: number;
  contactWindowHours: number;
  retryStrategy: string;
  escalationThreshold: number;
  enabledRecoveryActions?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface UpdateMerchantConfigRequest {
  maxAttempts: number;
  maxContactAttempts: number;
  contactWindowHours: number;
  retryStrategy: string;
  escalationThreshold: number;
  enabledRecoveryActions?: string[];
}

export interface ReviewItemDto {
  id: string;
  merchantId: string;
  campaignId: string;
  paymentId?: string;
  customerIdHash?: string;
  failureClass?: string;
  campaignState?: string;
  strategy?: string;
  attemptCount?: number;
  amount?: number;
  reason?: string;
  status: string;
  reviewerComment?: string;
  assignedUserId?: string;
  createdAt: string;
  reviewedAt?: string;
  expiresAt?: string;
  /** M4 U4.8: RESOLVE_NO_COLLECT for reply reviews (dispute / customer says paid), else COLLECT. */
  kind?: 'COLLECT' | 'RESOLVE_NO_COLLECT';
}

export interface ReviewDecisionResponse {
  reviewId: string;
  campaignId: string;
  decision: string;
  message: string;
  decidedAt: string;
  review: ReviewItemDto;
  actionIntent?: ActionIntentDto;
  campaign?: CampaignDto;
  validationSummary?: string[];
}

export interface ReviewQueueSummaryDto {
  pending: number;
  total: number;
  byStatus: Record<string, number>;
}

export interface DemoScenarioDto {
  id: string;
  title: string;
  description: string;
  flow: string[];
}

export interface DemoTriggerResponse {
  scenario: string;
  status: string;
  campaignId?: string;
  paymentId?: string;
  customerIdHash?: string;
  reviewId?: string;
  amount?: number;
  message: string;
  finalCampaignState?: string;
  executionSteps: string[];
}

export interface FunnelStageDto {
  stageName: string;
  count: number;
  conversionRatePercent: number;
  dropOffCount: number;
}

export interface AnalyticsFunnelDto {
  totalPaymentFailures: number;
  stages: FunnelStageDto[];
}

export interface FailureClassMetricDto {
  failureClass: string;
  count: number;
  recoveredCount: number;
  recoveryRatePercent: number;
  revenueAtRisk: number;
  revenueRecovered: number;
}

export interface AnalyticsFailureBreakdownDto {
  failureClasses: FailureClassMetricDto[];
}

export interface StrategyMetricDto {
  strategyName: string;
  totalCampaigns: number;
  recoveredCampaigns: number;
  successRatePercent: number;
  revenueRecovered: number;
}

export interface AnalyticsStrategyPerformanceDto {
  strategies: StrategyMetricDto[];
}

/** Only measured values (M3); null = not measured. */
export interface OperationalHealthDto {
  status: 'UP' | 'UNKNOWN';
  redisStreamLag: number | null;
  dlqEventCount: number | null;
  webhookProcessingLatencyMs: number | null;
  timestamp: string;
}

export interface StartRecoveryRequest {
  paymentId: string;
  customerIdHash?: string;
  subscriptionId?: string;
}

export interface PaymentExecutionResult {
  status: string;
  externalReference?: string;
  message?: string;
  executedAt?: string;
  idempotencyKey?: string;
}

export interface StartRecoveryResponseDto {
  campaign: CampaignDto;
  actionIntent?: ActionIntentDto;
  executionResult?: PaymentExecutionResult;
  /** Status of the latest stored compliance decision; null when none was evaluated. */
  complianceStatus: string | null;
  requiresHumanApproval: boolean;
  message: string;
}

/** A model *suggestion* for a customer reply (M3). Only the deterministic policy acts on it; `effect` is what it did. */
export interface ReplySuggestionDto {
  valid: boolean;
  intent: 'OPT_OUT' | 'ALREADY_PAID' | 'DISPUTE' | 'PROMISE_TO_PAY' | 'OTHER' | null;
  promisedDate: string | null;
  abstain: boolean | null;
  validationError: string | null;
  /** Exactly as the model provider reported it; null when it reported none. */
  modelId: string | null;
  promptVersion: string | null;
  effect: string;
  createdAt: string;
}

export interface CustomerReplyDto {
  id: string;
  campaignId: string;
  text: string;
  status: 'PENDING' | 'PARSED' | 'FAILED';
  attempts: number;
  lastError: string | null;
  createdAt: string;
  suggestion: ReplySuggestionDto | null;
}

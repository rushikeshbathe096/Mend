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
  confidence?: number;
  strategy?: string;
  attemptCount: number;
  nextActionAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ClassificationResultDto {
  id: string;
  campaignId: string;
  eventId?: string;
  failureClass: string;
  confidence: number;
  strategyRecommendation?: string;
  reasoning?: string;
  evidence?: Record<string, any>;
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
  confidence?: number;
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
  evidence?: Record<string, any>;
  metadata?: Record<string, any>;
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
}

export interface AiConfidenceMetrics {
  averageConfidence?: number;
  highConfidenceCount?: number;
  mediumConfidenceCount?: number;
  lowConfidenceCount?: number;
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
  aiConfidenceMetrics?: AiConfidenceMetrics;
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

export interface AgentDecisionRecordDto {
  id: string;
  campaignId: string;
  merchantId: string;
  paymentId?: string;
  decision: string;
  selectedAction?: string;
  confidence?: number;
  reasoning: string;
  evidence?: string;
  nextStep?: string;
  stopReason?: string;
  modelVersion?: string;
  requiresHumanApproval: boolean;
  complianceStatus?: string;
  executionStatus?: string;
  createdAt: string;
}

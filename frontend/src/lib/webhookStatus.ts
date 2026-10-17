/**
 * Webhook processing statuses, exactly the backend WebhookEventStatus enum (checked by webhookStatus.test.ts). The
 * webhook filter offers only these; the backend has no UNVERIFIED or DUPLICATE status.
 */
export const WEBHOOK_EVENT_STATUSES = ['RECEIVED', 'VERIFIED', 'PROCESSED', 'FAILED'] as const;

export type WebhookEventStatus = (typeof WEBHOOK_EVENT_STATUSES)[number];

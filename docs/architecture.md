# Architecture (Historical Notes + Current Model)

> The canonical current architecture is [ARCHITECTURE.md](ARCHITECTURE.md). The original
> phase notes below are retained for history; references to “Planned” describe the phase in
> which they were written, not the current implementation state.

## Components

1. **Next.js (Frontend)**: User interface for dashboards and metrics. Does not own business logic.
2. **Java Spring Boot (Backend)**: Core authoritative business/control layer. Manages compliance, state machine, business rules, action authorization, and financial execution orchestration.
3. **Python FastAPI (AI Service)**: Recommends recovery strategies based on failure classification, confidence, and outcome analysis. Does NOT execute actions directly.
5. **PostgreSQL**: Source of truth for business state.
6. **Redis**: Queues, scheduling, and event transport.
7. **Razorpay**: Provider abstraction with mock and TEST MODE paths.

## Phase 3 Architecture: Auth, RBAC, & Multi-Tenancy

### 1. Authentication & Token Management
- **Stateless Bearer JWT**: Authenticated endpoints require `Authorization: Bearer <jwt_token>` header.
- **Token Claims**: Contains `sub` (email), `userId`, `roles`, `iat`, and `exp`.
- **Password Security**: Uses salted PBKDF2 with HMAC-SHA256 (`65,536` iterations) with constant-time byte comparison. Passwords are never stored in plaintext and never exposed in API responses.
- **Endpoints**:
  - `POST /api/v1/auth/login`: Identity authentication & JWT token issuance.
  - `GET /api/v1/auth/me`: Resolves current user principal and active merchant memberships.
  - `POST /api/v1/auth/bootstrap`: Clean system/merchant admin bootstrap endpoint.

### 2. Multi-Tenancy & Security Context
- **Tenant Context Isolation**: `TenantContext` holds ThreadLocal user & target merchant state during request execution.
- **`X-Merchant-Id` Header**: Requests targeting specific tenant resources validate `X-Merchant-Id` against the user's active merchant memberships.
- **Tenant Boundary Security**: Cross-tenant requests (User A attempting to access Merchant B resources) are blocked with `403 Forbidden` or `404 Not Found` to prevent data leakage.

### 3. Role-Based Access Control (RBAC)
- **Roles**:
  - `SYSTEM_ADMIN`: Platform-wide governance and cross-tenant maintenance capability.
  - `MERCHANT_ADMIN`: Full administrative access to member roles and configurations within a specific merchant.
  - `REVIEWER`: Read and campaign management access within a specific merchant; blocked from administrative member management.

### 4. Authorization Matrix

| Role | Login & /me | View Merchant Members | Add/Remove Merchant Member | Change Member Role | Cross-Tenant Access |
|---|---|---|---|---|---|
| `SYSTEM_ADMIN` | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Allowed |
| `MERCHANT_ADMIN` | ✅ Yes | ✅ Yes (Own Merchant) | ✅ Yes (Own Merchant) | ✅ Yes (Own Merchant) | ❌ Blocked (403) |
| `REVIEWER` | ✅ Yes | ✅ Yes (Own Merchant) | ❌ Blocked (403) | ❌ Blocked (403) | ❌ Blocked (403) |
| `UNAUTHENTICATED` | ❌ Blocked (401) | ❌ Blocked (401) | ❌ Blocked (401) | ❌ Blocked (401) | ❌ Blocked (401) |

## Phase 4 Architecture: Webhook Gateway

### 1. Webhook Reception & Unauthenticated Endpoint
- **Public Endpoint**: `POST /api/v1/webhooks/razorpay` bypasses Mend User JWT authentication in `SecurityFilter`.
- **Security Boundary**: Only public endpoints (`/api/v1/auth/*`, `/api/v1/webhooks/razorpay`, `/api/health`) allow unauthenticated access. All domain APIs (`/api/v1/merchants/*`, `/api/v1/campaigns/*`) strictly require valid JWT tokens.

### 2. HMAC-SHA256 Signature Verification
- **Verification Engine**: `RazorpaySignatureVerifier` computes HMAC-SHA256 over raw string payloads using `RAZORPAY_WEBHOOK_SECRET`.
- **Constant-Time Comparison**: Uses `MessageDigest.isEqual` to compare computed signature hex bytes with incoming `X-Razorpay-Signature` header to prevent timing side-channel attacks.
- **Error Handling**: Invalid or missing signatures immediately throw `WebhookSignatureException`, resulting in HTTP 401 Unauthorized responses.

### 3. Idempotency & Raw Payload Auditability
- **Database Idempotency**: `webhook_events.external_event_id` has a unique database constraint. Concurrent or repeated events return `DUPLICATE` without duplicating persistence or triggering duplicate downstream actions.
- **Raw Payload Persistence**: Stores unmodified raw payload string in `webhook_events.raw_payload` along with calculated `payload_hash` (SHA-256) for auditability, replay capability, and debug trace.

### 4. Payload-Driven Merchant Resolution
- **Tenant Isolation**: Does NOT trust client-controlled `X-Merchant-Id` headers.
- **Payload Inspection**: Safely resolves merchant association by checking root `account_id` or entity `notes.merchant_id` against `merchants.external_reference`. Unmapped events safely set `merchantId = null` without guessing.

### 5. Phase 5 Handoff Abstraction
- **Publisher Component**: `WebhookEventPublisher` interface decoupling Phase 4 reception/verification from Phase 5 Redis queue streaming pipeline.

## Phase 5 Architecture: Redis Event Pipeline

### 1. Redis Streams Event Transport
- **Asynchronous Transport**: Webhook verification and initial database persistence are completely decoupled from downstream event processing using Redis Streams (`mend:webhook-events`).
- **PostgreSQL Source of Truth**: Database persistence always occurs FIRST with `publish_status = PENDING`. Publication to Redis follows immediately.
- **Fail-Safe Persistence**: If Redis is unavailable, the PostgreSQL record remains committed with `publish_status = PUBLISH_FAILED`. PostgreSQL data is never rolled back or deleted due to Redis unavailability.

### 2. Event Envelope Contract
- **Contract Schema**: Standardized JSON event envelope (`WebhookEventEnvelope`) containing:
  - `eventId`: Provider event ID
  - `schemaVersion`: Version counter (currently `1`)
  - `provider`: Event provider (e.g. `"RAZORPAY"`)
  - `providerEventId`: External event ID
  - `eventType`: Provider event type (e.g. `"payment.failed"`, `"payment.captured"`)
  - `merchantId`: Resolved merchant UUID (or `null` if unknown)
  - `webhookDatabaseId`: PostgreSQL primary key UUID
  - `occurredAt`: Instant when event occurred
  - `receivedAt`: Instant when webhook was received by Mend gateway
  - `payloadHash`: SHA-256 payload checksum

### 3. Consumer Group & Delivery Guarantees
- **At-Least-Once Delivery**: Messages are processed via consumer group `mend-processors`.
- **Explicit Acknowledgment (XACK)**: Messages are acknowledged (`XACK`) only after successful downstream handling (`WebhookEventHandler.handle()`).
- **Fault Recovery**: Un-ACKed messages remain in Redis pending entries list (`XPENDING`) and are automatically recovered on consumer restart via `processPendingMessages()`.
- **Idempotency**: Downstream handlers check PostgreSQL `processing_status` before processing. If an event is already marked `PROCESSED`, it is acknowledged safely without duplicate side effects.

### 4. Retry & Observability
- **Retry Mechanism**: `WebhookPublisherRetryService` queries `publish_status = PUBLISH_FAILED` records and re-publishes them once Redis connectivity is restored.
- **Structured Logging**: All stream operations, failures, and recoveries are logged cleanly without leaking sensitive payload data or security tokens.




---

# Current System Architecture (Phase 20)

## System Narrative

A merchant receives a failed-payment event from Razorpay. Mend ingests it, understands it,
runs six specialized AI agents over it, reaches a safe consensus, validates it against
compliance policy, records an ActionIntent (the only object allowed to reach the payment
provider), executes through the provider boundary, reconciles the authoritative outcome,
and lets the merchant see the whole story in the console.

## Authority Chain (financial safety contract)

```
Agents (recommendations only)
        |
        v
Supervisor consensus
        |
        v
Spring Boot validation + Compliance engine
        |
        v
ActionIntent (idempotent, CLAIMED-gated, expires)
        |
        v
Provider abstraction -> Razorpay TEST MODE / mock
        |
        v
Reconciliation (provider state authoritative for RECOVERED)
        |
        v
Outcome analysis -> Campaign state -> Analytics / Audit
```

- Agents never call Razorpay directly, never mutate authoritative campaign state, and never
  mark a payment recovered. Every agent output is advisory; the Spring Boot boundary
  revalidates campaign state, tenant, compliance, expiry, and intent status before execution.
- PostgreSQL is the authoritative business store. Redis, the AI service, and the frontend
  are never authoritative for financial state.

## Layer Map

| Layer | Technology | Responsibility | Authority |
|---|---|---|---|
| Ingestion | WebhookController -> WebhookService | HMAC verify, dedupe, persist | none (event truth in DB) |
| Pipeline | Redis Streams + consumer group | at-least-once transport, retry/DLQ | transport only |
| Intelligence | Python FastAPI + LangGraph (6 agents) | classification + consensus recommendation | advisory only |
| Safety | ComplianceEngine, CampaignStateMachine, ActionIntentService | policy gate, state machine, intent idempotency | binding |
| Execution | ActionExecutionService -> PaymentProviderClient | claim + revalidate + execute + finalize | executes only intents |
| Provider | RazorpayPaymentProviderClient / MockPaymentProviderClient | captured/declined/error + reconciliation status | outcome evidence |
| Reconciliation | DefaultPaymentReconciliationService | reconcile webhook outcome vs intent | RECOVERED requires evidence |
| Observability | AuditService, traceId/correlationId, structured logs | tamper-evident hash-chained audit | read-only |
| Product | Next.js console | dashboards, approvals, analytics | read + approve only |
| Tenant isolation | SecurityFilter + TenantContext + service revalidation | merchant scope on every path | enforced at every layer |

## Data Flow

```
HTTP webhook (signed)
  -> HMAC-SHA256 constant-time verify (RazorpaySignatureVerifier)
  -> parse; merchant resolved ONLY from payload (account_id / notes.merchant_id)
  -> dedupe on webhook_events.external_event_id (unique constraint)
  -> persist raw payload + payload_hash
  -> publish to Redis Stream (mend:webhooks)
  -> consumer (XACK only after success; retry stream; DLQ on deserialization)
  -> classify (AI service / deterministic fallback)
  -> campaign created (idempotent per merchant+payment)
  -> 6-agent LangGraph graph (risk, decision, strategy, engagement, supervisor consensus)
  -> Spring persists agent decision records
  -> ComplianceEngine gate
  -> ActionIntent created (idempotency key intent:{campaign}:attempt_{n}:{action})
  -> scheduled -> READY -> atomic CLAIM (worker token) -> CLAIMED
  -> executeActionIntent: re-validate CLAIMED/owner/expiry/campaign/compliance/tenant
  -> provider (mock or Razorpay TEST MODE), provider call OUTSIDE the DB transaction
  -> finalize: campaign_attempt (unique campaign+attempt), intent terminal state,
     campaign state machine transition (RECOVERED only on provider success)
  -> reconciliation of late/duplicate provider webhooks
  -> audit events hash-chained at every transition
  -> console: dashboard/payments/campaigns/actions/approvals/customers/analytics/audit/demo
```

## Key Idempotency Controls (duplicate financial execution is prevented by)

1. Webhook dedupe - unique `external_event_id`; concurrent insert race caught on the unique
   constraint and re-read.
2. Campaign creation - unique index per (merchant_id, payment_id).
3. ActionIntent - unique `idempotency_key`; `saveAndFlush` + constraint-violation re-read.
4. Execution - intent must be CLAIMED by the same worker; expiration checked; campaign must
   be in an executable state; terminal intents skip finalization.
5. Attempt persistence - unique (campaign_id, attempt_number).
6. Reconciliation - an already-SUCCEEDED intent + duplicate success event is a no-op; only
   provider-captured evidence transitions a campaign to RECOVERED.

## Scalability & Durability Classification (honest labels)

- LangGraph checkpointer (`DurableMemorySaver`): process-local, file-backed,
  restart-durable on one host. NOT distributed; a horizontal AI deployment requires a
  shared checkpointer (Postgres/Redis-backed). PostgreSQL remains authoritative; the
  checkpointer only resumes in-flight graph execution.
- Redis consumer group: single group supports multiple consumers for the same stream
  (competing consumers); XACK-only-after-processing gives at-least-once semantics.
- Provider integration: `mend.payment.provider=mock` (default) or `razorpay`; credentials
  are environment-driven and never enter agent state, checkpoints, audit, or logs.

## Deployment Notes

- Docker Compose runs PostgreSQL + Redis; backend/frontend/AI run from source or images.
- Backend requires: SPRING_DATASOURCE_URL/USERNAME/PASSWORD, REDIS_HOST/PORT,
  RAZORPAY_WEBHOOK_SECRET (webhook verification), JWT_SECRET, MEND_PAYMENT_PROVIDER.
- Flyway migrations run at startup (V1..V10).
- Health endpoints: backend `/api/health`, AI service `/health`.
- AI service calls use `MEND_AI_INTERNAL_TOKEN` when configured; production must set a strong
  shared token and keep the AI service on a private network. The AI orchestration request's
  `backendUrl` field is compatibility-only and is ignored; the service uses `MEND_BACKEND_URL`
  to avoid caller-controlled SSRF.
- The Compose file is a local-development stack. It does not provide production TLS, Redis
  authentication, secret management, database HA/backups, rate limiting, or distributed
  LangGraph checkpoint storage.

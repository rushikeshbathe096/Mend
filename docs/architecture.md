# Architecture

## Components

1. **Next.js (Frontend)**: User interface for dashboards and metrics. Does not own business logic.
2. **Java Spring Boot (Backend)**: Core authoritative business/control layer. Manages compliance, state machine, business rules, action authorization, and financial execution orchestration.
3. **Python FastAPI (AI Service)**: Recommends recovery strategies based on failure classification, confidence, and outcome analysis. Does NOT execute actions directly.
4. **PostgreSQL**: Source of truth for business state (Planned).
5. **Redis**: Queues, scheduling, temporary state (Planned).
6. **Razorpay**: Payment provider (Planned).

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




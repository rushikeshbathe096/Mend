# Architecture

## Components

1. **Next.js (Frontend)**: User interface for dashboards and metrics. Does not own business logic.
2. **Java Spring Boot (Backend)**: Core authoritative business/control layer. Manages compliance, state machine, business rules, action authorization, and financial execution orchestration.
3. **Python FastAPI (AI Service)**: Recommends recovery strategies based on failure classification, confidence, and outcome analysis. Does NOT execute actions directly.
4. **PostgreSQL**: Source of truth for business state (Planned).
5. **Redis**: Queues, scheduling, temporary state (Planned).
6. **Razorpay**: Payment provider (Planned).

## Data Flow
Next.js <-> Java Spring Boot <-> PostgreSQL / Redis / AI Service <-> Razorpay

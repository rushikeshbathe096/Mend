# Mend
AI-powered payment recovery platform

## Overview
Mend detects failed recurring payments, uses AI to classify the failure and recommend a recovery strategy, applies deterministic policy/compliance rules, schedules recovery actions, executes permitted actions, processes outcomes, and learns from recovery results.

## Architecture
- **Frontend**: Next.js (React, TypeScript, Tailwind CSS)
- **Backend**: Java 21, Spring Boot 4.1.1
- **AI Service**: Python 3.12, FastAPI
- **Database**: PostgreSQL 17 (Phase 2+)
- **Cache**: Redis 7 (Phase 2+)

## Setup Instructions

### Prerequisites
- Node.js 24+
- Java 21+
- Maven
- Python 3.12+
- Docker and Docker Compose (for Phase 2+ infrastructure)

### Phase 2: Infrastructure Setup

Phase 2 adds local PostgreSQL and Redis infrastructure. To run the complete system:

```bash
# Start infrastructure
docker compose up -d

# Verify services are healthy
docker compose ps

# Check services
docker compose exec postgres pg_isready -U mend_user
docker compose exec redis redis-cli ping
```

See [Phase 2 Infrastructure Guide](docs/PHASE2_INFRASTRUCTURE.md) for detailed setup and troubleshooting.

### Frontend
```bash
cd frontend
npm install
npm run dev
```

### Backend
```bash
cd backend
./mvnw spring-boot:run
```

Backend will connect to:
- PostgreSQL at `localhost:5432` (or `postgres:5432` in Docker)
- Redis at `localhost:6379` (or `redis:6379` in Docker)

### AI Service
```bash
cd ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

### Testing
Use the verification script in the root directory:
```bash
./scripts/verify.sh
```

Run backend tests:
```bash
cd backend
./mvnw clean test
```

## Development Workflow

### With Docker Compose Infrastructure

```bash
# Terminal 1: Start infrastructure
docker compose up -d
docker compose logs -f

# Terminal 2: Start backend
cd backend
./mvnw spring-boot:run

# Terminal 3: Start frontend
cd frontend
npm run dev

# Terminal 4: Start AI service
cd ai-service
source .venv/bin/activate
uvicorn main:app --reload
```

### Without Docker (Frontend/AI Service Only)

For local development without database:

```bash
# Frontend
cd frontend && npm run dev

# AI Service
cd ai-service && uvicorn main:app --reload
```

## Phase Documentation

- **Phase 1**: Application foundation (completed)
  - REST API structure
  - Health endpoint
  - Basic project setup

- **Phase 2**: Infrastructure foundation (in progress)
  - PostgreSQL database
  - Redis cache
  - Docker Compose orchestration
  - Environment configuration
  - See [Phase 2 Guide](docs/PHASE2_INFRASTRUCTURE.md)

- **Phase 3+**: Business logic, authentication, and integrations (planned)

## Architecture Details

See [Architecture](docs/architecture.md) for comprehensive system design.

## Environment Configuration

Create a `.env` file based on `.env.example`:

```bash
cp .env.example .env
# Edit .env with your configuration
```

The `.env` file contains:
- PostgreSQL credentials
- Redis configuration
- API service URLs
- JWT secrets (Phase 3+)

**Note**: `.env` is not committed to Git for security.

## Common Commands

### Infrastructure
```bash
docker compose up -d      # Start infrastructure
docker compose down       # Stop infrastructure (keep data)
docker compose down -v    # Stop infrastructure and remove data
docker compose ps         # Check service status
docker compose logs -f    # View logs
```

### Backend
```bash
cd backend
./mvnw clean test         # Run tests
./mvnw spring-boot:run    # Start server
./mvnw compile            # Compile only
```

### Frontend
```bash
cd frontend
npm run dev               # Start dev server
npm run build             # Build for production
npm test                  # Run tests
```

## Troubleshooting

### Infrastructure issues
See [Phase 2 Infrastructure Troubleshooting](docs/PHASE2_INFRASTRUCTURE.md#troubleshooting)

### Backend connection issues
```bash
# Check infrastructure is running and healthy
docker compose ps

# Check backend logs
cd backend && ./mvnw spring-boot:run

# Test database connectivity
docker compose exec postgres pg_isready -U mend_user
```

### Port conflicts
```bash
# Check what's using ports
lsof -i :5432   # PostgreSQL
lsof -i :6379   # Redis  
lsof -i :8080   # Backend
lsof -i :3000   # Frontend
```
- AI Service is a placeholder; real AI models are NOT implemented yet.
- Razorpay integration is NOT implemented yet.
- The Java backend contains only health endpoints.

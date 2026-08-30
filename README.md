# Mend
AI-powered payment recovery platform

## Overview
Mend detects failed recurring payments, uses AI to classify the failure and recommend a recovery strategy, applies deterministic policy/compliance rules, schedules recovery actions, executes permitted actions, processes outcomes, and learns from recovery results.

## Architecture
- **Frontend**: Next.js (React, TypeScript, Tailwind CSS)
- **Backend**: Java 21, Spring Boot
- **AI Service**: Python 3.12, FastAPI

## Setup Instructions

### Prerequisites
- Node.js 24+
- Java 21+
- Maven
- Python 3.12+

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

## Phase 1 Limitations
- Authentication is intentionally NOT implemented yet.
- PostgreSQL and Redis are NOT implemented yet.
- AI Service is a placeholder; real AI models are NOT implemented yet.
- Razorpay integration is NOT implemented yet.
- The Java backend contains only health endpoints.

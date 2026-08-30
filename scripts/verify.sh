#!/bin/bash

set -e

echo "Verifying Mend Application..."

echo "[1/4] Verifying Java Backend..."
cd backend
./mvnw clean test
cd ..

echo "[2/4] Verifying Python AI Service..."
cd ai-service
source .venv/bin/activate
pytest
cd ..

echo "[3/4] Verifying Next.js Frontend Tests..."
cd frontend
npm run test
cd ..

echo "[4/4] Verifying Next.js Production Build..."
cd frontend
npm run build
cd ..

echo "All verifications passed!"

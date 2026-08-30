from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Mend AI Service", description="AI-powered payment recovery platform AI Service")

class HealthResponse(BaseModel):
    status: str
    service: str

@app.get("/health", response_model=HealthResponse)
def health_check():
    return HealthResponse(status="UP", service="mend-ai-service")

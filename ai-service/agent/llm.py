import os
import logging
from typing import Optional, Any

logger = logging.getLogger("mend-ai-service")

def get_llm_provider() -> str:
    if os.getenv("GEMINI_API_KEY"):
        return "GEMINI"
    elif os.getenv("OPENAI_API_KEY"):
        return "OPENAI"
    else:
        return "BOUNDED_HEURISTIC"

def get_llm(model_provider: Optional[str] = None) -> Optional[Any]:
    provider = model_provider or get_llm_provider()
    
    if provider == "GEMINI" and os.getenv("GEMINI_API_KEY"):
        try:
            from langchain_google_genai import ChatGoogleGenerativeAI
            return ChatGoogleGenerativeAI(
                model="gemini-2.5-flash",
                google_api_key=os.getenv("GEMINI_API_KEY"),
                temperature=0.1
            )
        except Exception as e:
            logger.warning(f"Failed to instantiate ChatGoogleGenerativeAI: {e}")
            return None

    if provider == "OPENAI" and os.getenv("OPENAI_API_KEY"):
        try:
            from langchain_openai import ChatOpenAI
            return ChatOpenAI(
                model="gpt-4o-mini",
                api_key=os.getenv("OPENAI_API_KEY"),
                temperature=0.1
            )
        except Exception as e:
            logger.warning(f"Failed to instantiate ChatOpenAI: {e}")
            return None

    return None

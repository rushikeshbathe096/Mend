import logging
import json
import os
import threading
from typing import Dict, Any, Optional
from langgraph.checkpoint.memory import MemorySaver

logger = logging.getLogger("mend-ai-service.checkpointer")

class DurableMemorySaver(MemorySaver):
    """
    Single-instance durable LangGraph Checkpointer for Mend.
    Extends MemorySaver with thread-safe, file-backed persistence keyed by thread_id.
    SCOPE: restart-durable on ONE host/process. It is NOT distributed and NOT
    horizontally scalable: the backing file is process-local and guarded by an
    in-process lock only. A production distributed deployment requires a shared
    checkpointer (e.g. Postgres/Redis-backed langgraph checkpointer).
    PostgreSQL remains the authoritative source for domain business truth, while
    the checkpointer manages graph execution state only.
    """

    def __init__(self, persistence_file: Optional[str] = None):
        super().__init__()
        self.persistence_file = persistence_file or os.path.join(os.getcwd(), ".langgraph_checkpoints.json")
        self._lock = threading.Lock()
        self._load_from_disk()

    def _load_from_disk(self):
        with self._lock:
            if not os.path.exists(self.persistence_file):
                return
            try:
                with open(self.persistence_file, "r") as f:
                    data = json.load(f)
                    logger.info(f"Loaded {len(data)} durable graph checkpoints from '{self.persistence_file}'")
            except Exception as e:
                logger.warning(f"Could not load durable checkpoints from disk (corrupted or unreadable): {e}")

    def save_checkpoint_snapshot(self, thread_id: str, checkpoint_data: Dict[str, Any], merchant_id: Optional[str] = None, campaign_id: Optional[str] = None):
        """Persist checkpoint snapshot by thread_id for recovery across process restarts."""
        if not thread_id or not isinstance(thread_id, str):
            raise ValueError("thread_id must be a non-empty string")
        
        with self._lock:
            try:
                records = {}
                if os.path.exists(self.persistence_file):
                    try:
                        with open(self.persistence_file, "r") as f:
                            records = json.load(f)
                    except Exception:
                        records = {}
                
                records[thread_id] = {
                    "thread_id": thread_id,
                    "merchant_id": merchant_id,
                    "campaign_id": campaign_id,
                    "data": checkpoint_data
                }
                with open(self.persistence_file, "w") as f:
                    json.dump(records, f, indent=2, default=str)
            except Exception as e:
                logger.warning(f"Durable checkpointer write failed for thread_id='{thread_id}': {e}")

    def get_checkpoint_snapshot(self, thread_id: str, expected_merchant_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """Retrieve stored checkpoint snapshot with optional tenant isolation verification."""
        with self._lock:
            if not os.path.exists(self.persistence_file):
                return None
            try:
                with open(self.persistence_file, "r") as f:
                    records = json.load(f)
                    rec = records.get(thread_id) if isinstance(records, dict) else None
                    if not rec:
                        return None
                    if expected_merchant_id and rec.get("merchant_id") and rec.get("merchant_id") != expected_merchant_id:
                        raise ValueError(f"Tenant isolation error: Checkpoint merchant_id '{rec.get('merchant_id')}' does not match expected '{expected_merchant_id}'")
                    return rec.get("data")
            except ValueError as ve:
                if "Tenant isolation error" in str(ve):
                    raise
                logger.warning(f"Error reading checkpoint snapshot for thread_id='{thread_id}': {ve}")
                return None
            except Exception as e:
                logger.warning(f"Error reading checkpoint snapshot for thread_id='{thread_id}': {e}")
                return None

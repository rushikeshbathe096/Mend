import logging
import json
import os
import threading
from typing import Dict, Any, Optional

# Enforce strict MsgPack deserialization rules to prevent MsgPack code execution exploits
os.environ["LANGGRAPH_STRICT_MSGPACK"] = "true"

from langgraph.checkpoint.memory import MemorySaver

logger = logging.getLogger("mend-ai-service.checkpointer")

_durable_memory_saver_instance = None
_postgres_saver_instance = None
_saver_lock = threading.Lock()

def build_tenant_thread_id(merchant_id: str, campaign_id: str, descriptor: str = "recovery") -> str:
    """
    Construct a deterministic, tenant-isolated thread identity.
    Format: merchant:{merchant_id}:campaign:{campaign_id}:{descriptor}
    """
    if not merchant_id or not isinstance(merchant_id, str) or not merchant_id.strip():
        raise ValueError("Tenant isolation violation: merchant_id must be a non-empty string.")
    if not campaign_id or not isinstance(campaign_id, str) or not campaign_id.strip():
        raise ValueError("Tenant isolation violation: campaign_id must be a non-empty string.")
    return f"merchant:{merchant_id.strip()}:campaign:{campaign_id.strip()}:{descriptor}"

def validate_tenant_thread_access(thread_id: str, merchant_id: str):
    """
    Verify that a target thread_id belongs to the authorized merchant_id.
    Fails closed with ValueError if access violates tenant boundary.
    """
    if not merchant_id or not isinstance(merchant_id, str) or not merchant_id.strip():
        raise ValueError("Tenant isolation error: missing or invalid merchant_id.")
    if not thread_id or not isinstance(thread_id, str):
        raise ValueError("Tenant isolation error: missing thread_id.")

    tenant_prefix = f"merchant:{merchant_id.strip()}:"
    if thread_id.startswith("merchant:") and not thread_id.startswith(tenant_prefix):
        raise ValueError(f"Tenant isolation error: Thread '{thread_id}' does not belong to merchant '{merchant_id}'.")

class DurableMemorySaver(MemorySaver):
    """
    Single-instance durable LangGraph Checkpointer for Mend local development and testing.
    Extends MemorySaver with thread-safe, file-backed persistence keyed by thread_id.
    SCOPE: Local development & single-instance execution.
    For multi-instance horizontally scaled production deployments, use PostgresSaver via get_checkpointer().
    """

    def __init__(self, persistence_file: Optional[str] = None):
        super().__init__()
        self.persistence_file = persistence_file or os.path.join(os.getcwd(), ".langgraph_checkpoints.json")
        self._lock = threading.Lock()
        self._records: Dict[str, Any] = {}
        self._load_from_disk()

    def _load_from_disk(self):
        with self._lock:
            if not os.path.exists(self.persistence_file):
                self._records = {}
                return
            try:
                with open(self.persistence_file, "r") as f:
                    data = json.load(f)
                    if isinstance(data, dict):
                        self._records = data
                    else:
                        self._records = {}
                    logger.info(f"Loaded {len(self._records)} durable graph checkpoints from '{self.persistence_file}'")
            except Exception as e:
                logger.warning(f"Could not load durable checkpoints from disk (corrupted or unreadable): {e}")
                self._records = {}

    def save_checkpoint_snapshot(self, thread_id: str, checkpoint_data: Dict[str, Any], merchant_id: Optional[str] = None, campaign_id: Optional[str] = None):
        """Persist checkpoint snapshot by thread_id with optional tenant metadata."""
        if not thread_id or not isinstance(thread_id, str):
            raise ValueError("thread_id must be a non-empty string")
        if merchant_id:
            validate_tenant_thread_access(thread_id, merchant_id)
        
        with self._lock:
            try:
                self._records[thread_id] = {
                    "thread_id": thread_id,
                    "merchant_id": merchant_id,
                    "campaign_id": campaign_id,
                    "data": checkpoint_data
                }
                with open(self.persistence_file, "w") as f:
                    json.dump(self._records, f, indent=2, default=str)
            except Exception as e:
                logger.warning(f"Durable checkpointer write failed for thread_id='{thread_id}': {e}")

    def get_checkpoint_snapshot(self, thread_id: str, expected_merchant_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """Retrieve stored checkpoint snapshot with tenant isolation verification."""
        if expected_merchant_id:
            validate_tenant_thread_access(thread_id, expected_merchant_id)

        with self._lock:
            rec = self._records.get(thread_id)
            if not rec:
                return None
            if expected_merchant_id and rec.get("merchant_id") and rec.get("merchant_id") != expected_merchant_id:
                raise ValueError(f"Tenant isolation error: Checkpoint merchant_id '{rec.get('merchant_id')}' does not match expected '{expected_merchant_id}'")
            return rec.get("data")

def resolve_postgres_uri(connection_string: Optional[str] = None) -> Optional[str]:
    """Parse PostgreSQL connection URI from parameter or environment variables."""
    uri = connection_string or os.getenv("MEND_CHECKPOINT_POSTGRES_URI") or os.getenv("POSTGRES_DB_URI") or os.getenv("SPRING_DATASOURCE_URL")
    if not uri:
        return None
    # Convert JDBC URL format if passed from Java Spring properties
    if uri.startswith("jdbc:postgresql://"):
        uri = uri.replace("jdbc:postgresql://", "postgresql://", 1)
    return uri

def get_checkpointer(connection_string: Optional[str] = None, force_memory: bool = False):
    """
    Factory method providing production-appropriate LangGraph checkpointer.
    Returns PostgresSaver if PostgreSQL connection URI is available (multi-instance production safe),
    otherwise returns DurableMemorySaver (single-instance local dev safe).
    """
    global _postgres_saver_instance, _durable_memory_saver_instance

    if force_memory:
        with _saver_lock:
            if _durable_memory_saver_instance is None:
                _durable_memory_saver_instance = DurableMemorySaver()
            return _durable_memory_saver_instance

    postgres_uri = resolve_postgres_uri(connection_string)
    if postgres_uri:
        with _saver_lock:
            if _postgres_saver_instance is None:
                try:
                    from langgraph.checkpoint.postgres import PostgresSaver
                    # PostgresSaver.from_conn_string instantiates connection pool saver
                    _postgres_saver_instance = PostgresSaver.from_conn_string(postgres_uri)
                    _postgres_saver_instance.setup()
                    logger.info("Successfully initialized multi-instance PostgresSaver for LangGraph checkpoints.")
                except Exception as e:
                    logger.warning(f"Could not initialize PostgresSaver ({e}). Falling back to DurableMemorySaver.")
                    if _durable_memory_saver_instance is None:
                        _durable_memory_saver_instance = DurableMemorySaver()
                    return _durable_memory_saver_instance
            return _postgres_saver_instance

    with _saver_lock:
        if _durable_memory_saver_instance is None:
            _durable_memory_saver_instance = DurableMemorySaver()
        return _durable_memory_saver_instance

def setup_checkpointer_tables(connection_string: Optional[str] = None):
    """Explicit database table initialization hook for production startup."""
    postgres_uri = resolve_postgres_uri(connection_string)
    if postgres_uri:
        try:
            from langgraph.checkpoint.postgres import PostgresSaver
            with PostgresSaver.from_conn_string(postgres_uri) as saver:
                saver.setup()
            logger.info("LangGraph PostgreSQL checkpoint tables setup successfully.")
        except Exception as e:
            logger.warning(f"LangGraph PostgreSQL checkpointer table setup skipped/failed: {e}")

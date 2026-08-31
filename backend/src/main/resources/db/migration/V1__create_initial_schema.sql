-- V1__create_initial_schema.sql
-- Phase 2: Database Foundation
-- Creates core Mend business domain tables

-- ============================================================
-- 1. ROLES TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS roles (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 2. USERS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);

-- ============================================================
-- 3. MERCHANTS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS merchants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    external_reference VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_merchants_status ON merchants(status);
CREATE INDEX idx_merchants_external_reference ON merchants(external_reference);

-- ============================================================
-- 4. MERCHANT_USERS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS merchant_users (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE RESTRICT,
    UNIQUE(merchant_id, user_id)
);

CREATE INDEX idx_merchant_users_merchant_id ON merchant_users(merchant_id);
CREATE INDEX idx_merchant_users_user_id ON merchant_users(user_id);
CREATE INDEX idx_merchant_users_role_id ON merchant_users(role_id);

-- ============================================================
-- 5. CAMPAIGNS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS campaigns (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    customer_id_hash VARCHAR(255),
    payment_id VARCHAR(255),
    subscription_id VARCHAR(255),
    failure_class VARCHAR(50),
    confidence NUMERIC(3, 2) CHECK (confidence >= 0 AND confidence <= 1),
    current_state VARCHAR(50) NOT NULL DEFAULT 'FAILED',
    strategy VARCHAR(100),
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_action_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_campaigns_merchant_id ON campaigns(merchant_id);
CREATE INDEX idx_campaigns_merchant_state ON campaigns(merchant_id, current_state);
CREATE INDEX idx_campaigns_next_action_at ON campaigns(next_action_at);
CREATE INDEX idx_campaigns_payment_id ON campaigns(payment_id);
CREATE INDEX idx_campaigns_created_at ON campaigns(created_at);

-- ============================================================
-- 6. CAMPAIGN_ATTEMPTS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS campaign_attempts (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    attempt_number INT NOT NULL CHECK (attempt_number > 0),
    action_type VARCHAR(50),
    status VARCHAR(20),
    scheduled_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failure_reason TEXT,
    external_reference VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE,
    UNIQUE(campaign_id, attempt_number)
);

CREATE INDEX idx_campaign_attempts_campaign_id ON campaign_attempts(campaign_id);
CREATE INDEX idx_campaign_attempts_status ON campaign_attempts(status);

-- ============================================================
-- 7. ACTION_INTENTS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS action_intents (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    attempt_number INT NOT NULL CHECK (attempt_number > 0),
    action_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(255),
    response_reference VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE
);

CREATE INDEX idx_action_intents_campaign_id ON action_intents(campaign_id);
CREATE INDEX idx_action_intents_status ON action_intents(status);
CREATE INDEX idx_action_intents_idempotency_key ON action_intents(idempotency_key);

-- ============================================================
-- 8. CLASSIFICATION_RESULTS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS classification_results (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    failure_class VARCHAR(50) NOT NULL,
    confidence NUMERIC(3, 2) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    strategy_recommendation TEXT,
    reasoning TEXT,
    evidence JSONB,
    model_version VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE
);

CREATE INDEX idx_classification_results_campaign_id ON classification_results(campaign_id);
CREATE INDEX idx_classification_results_created_at ON classification_results(created_at);
CREATE INDEX idx_classification_results_failure_class ON classification_results(failure_class);

-- ============================================================
-- 9. WEBHOOK_EVENTS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS webhook_events (
    id UUID PRIMARY KEY,
    external_event_id VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    source VARCHAR(50),
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_created_at TIMESTAMP,
    payload_hash VARCHAR(255),
    processing_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    processed_at TIMESTAMP,
    error_message TEXT
);

CREATE INDEX idx_webhook_events_external_event_id ON webhook_events(external_event_id);
CREATE INDEX idx_webhook_events_processing_status ON webhook_events(processing_status);
CREATE INDEX idx_webhook_events_received_at ON webhook_events(received_at);

-- ============================================================
-- 10. REVIEW_QUEUE TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS review_queue (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    reason TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    assigned_user_id UUID,
    reviewer_comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE,
    FOREIGN KEY (assigned_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_review_queue_merchant_status ON review_queue(merchant_id, status);
CREATE INDEX idx_review_queue_status ON review_queue(status);
CREATE INDEX idx_review_queue_campaign_id ON review_queue(campaign_id);
CREATE INDEX idx_review_queue_created_at ON review_queue(created_at);

-- ============================================================
-- 11. MERCHANT_CONFIG TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS merchant_config (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL UNIQUE,
    max_attempts INT NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    max_contact_attempts INT NOT NULL DEFAULT 3 CHECK (max_contact_attempts > 0),
    contact_window_hours INT NOT NULL DEFAULT 24 CHECK (contact_window_hours > 0),
    retry_strategy VARCHAR(50),
    escalation_threshold INT,
    enabled_recovery_actions TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_merchant_config_merchant_id ON merchant_config(merchant_id);

-- ============================================================
-- 12. AUDIT_LOGS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY,
    merchant_id UUID,
    campaign_id UUID,
    actor_type VARCHAR(50),
    actor_id UUID,
    event_type VARCHAR(100) NOT NULL,
    reason TEXT,
    evidence JSONB,
    metadata JSONB,
    previous_hash VARCHAR(255),
    record_hash VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE SET NULL,
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_logs_merchant_id ON audit_logs(merchant_id);
CREATE INDEX idx_audit_logs_campaign_id ON audit_logs(campaign_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);

-- ============================================================
-- SEED DATA
-- ============================================================

-- Insert default roles
INSERT INTO roles (id, name, description) VALUES
    ('550e8400-e29b-41d4-a716-446655440001'::uuid, 'MERCHANT_ADMIN', 'Merchant administrator with full control'),
    ('550e8400-e29b-41d4-a716-446655440002'::uuid, 'REVIEWER', 'Reviewer with campaign review permissions'),
    ('550e8400-e29b-41d4-a716-446655440003'::uuid, 'SYSTEM_ADMIN', 'System administrator with platform-wide control')
ON CONFLICT (name) DO NOTHING;

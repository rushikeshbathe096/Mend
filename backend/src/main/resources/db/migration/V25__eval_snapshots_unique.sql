-- V25__eval_snapshots_unique.sql
-- M4 U4.3 review: one stored snapshot per (window, as_of, horizon, provider mode, filters, content checksum). Concurrent runs
-- (nightly job and CLI) insert with ON CONFLICT DO NOTHING and reuse the winner. Index only: no row is touched.
CREATE UNIQUE INDEX uq_eval_snapshots_identity
    ON eval_snapshots (window_from, window_to, as_of, horizon_days, provider_mode, filters, sha256);

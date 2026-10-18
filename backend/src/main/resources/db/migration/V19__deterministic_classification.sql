-- V19__deterministic_classification.sql
-- M3 U2.2 (ADR-003): failure classification is deterministic (com.mend.taxonomy, Razorpay error_reason/error_source).
-- A deterministic class has no confidence, so classification_results.confidence becomes nullable; the taxonomy table
-- version gets its own column instead of being written into model_version (which is reserved for a real model).
-- Schema only: historical rows keep their (AI-era) confidence, model_version and failure_class values, unchanged.
ALTER TABLE classification_results ALTER COLUMN confidence DROP NOT NULL;
ALTER TABLE classification_results ADD COLUMN taxonomy_version VARCHAR(50);

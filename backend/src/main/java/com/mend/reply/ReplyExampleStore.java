package com.mend.reply;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M4 U4.7 (ADR-004 §8): the labelled example store of the gated retrieval experiment, on PostgreSQL full-text search
 * ({@code reply_label_examples.tsv}, GIN). Only SYNTHETIC or consented examples are accepted; customer replies are never
 * stored here. Retrieval ranks by {@code ts_rank} over an OR of the reply's lexemes ('simple' configuration: no stemming,
 * so it works for any language), ties by id.
 */
@Component
public class ReplyExampleStore {

    /**
     * U4.7 review: only SYNTHETIC until consent capture exists (the table's CHECK also allows CONSENTED, but nothing could prove a
     * CONSENTED row is not a customer reply without a stored consent reference).
     */
    public static final Set<String> SOURCES = Set.of("SYNTHETIC");
    public static final int MAX_K = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final String datasetVersion;

    public ReplyExampleStore(NamedParameterJdbcTemplate jdbc,
                             @org.springframework.beans.factory.annotation.Value("${mend.reply.retrieval.dataset-version:replies-v1-dev}") String datasetVersion) {
        this.jdbc = jdbc;
        this.datasetVersion = datasetVersion;
    }

    /** Adds a dataset's examples (idempotent per dataset version and text). Returns how many were new. */
    @Transactional
    public int importExamples(String datasetVersion, String source, List<ReplyExample> examples) {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("only SYNTHETIC examples may be stored (never customer replies; CONSENTED needs consent capture)");
        }
        // U4.7 review: evaluation data never enters the store (a held-out or blind row as its own example inflates F1)
        if (datasetVersion.endsWith("-test") || datasetVersion.startsWith("replies-v2")) {
            throw new IllegalArgumentException("evaluation datasets (held-out *-test, blind replies-v2) must not be used as examples");
        }
        int added = 0;
        for (ReplyExample e : examples) {
            added += jdbc.update("""
                    INSERT INTO reply_label_examples (id, dataset_version, source, text, intent, promised_date)
                    VALUES (:id, :dataset, :source, :text, :intent, :date)
                    ON CONFLICT (dataset_version, text) DO NOTHING""", new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID()).addValue("dataset", datasetVersion).addValue("source", source)
                    .addValue("text", e.text()).addValue("intent", e.intent())
                    .addValue("date", e.promisedDate() == null ? null : Date.valueOf(LocalDate.parse(e.promisedDate()))));
        }
        return added;
    }

    /**
     * The {@code k} (at most {@value #MAX_K}) most similar examples of the configured dataset version; empty when nothing shares a
     * word with the reply. The OR query is built from the reply's own lexemes, each quoted (U4.7 review: never by rewriting the
     * query text); ties break on the text, so a ranking is the same on every database.
     */
    @Transactional(readOnly = true)
    public List<ReplyExample> similar(String reply, int k) {
        if (reply == null || reply.isBlank() || k <= 0) {
            return List.of();
        }
        return jdbc.query("""
                WITH query AS (
                    SELECT to_tsquery('simple', string_agg(quote_literal(lexeme), ' | ')) AS q
                    FROM unnest(tsvector_to_array(to_tsvector('simple', :reply))) AS lexeme)
                SELECT text, intent, promised_date FROM (
                    SELECT DISTINCT ON (e.text) e.text, e.intent, e.promised_date, ts_rank(e.tsv, query.q) AS rank
                    FROM reply_label_examples e, query
                    WHERE query.q IS NOT NULL AND e.dataset_version = :dataset AND e.tsv @@ query.q
                    ORDER BY e.text) ranked
                ORDER BY rank DESC, text
                LIMIT :k""", new MapSqlParameterSource().addValue("reply", reply).addValue("dataset", datasetVersion)
                        .addValue("k", Math.min(k, MAX_K)),
                (rs, i) -> new ReplyExample(rs.getString(1), rs.getString(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate().toString()));
    }
}

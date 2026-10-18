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

    public static final Set<String> SOURCES = Set.of("SYNTHETIC", "CONSENTED");
    public static final int MAX_K = 5;

    private final NamedParameterJdbcTemplate jdbc;

    public ReplyExampleStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a dataset's examples (idempotent per dataset version and text). Returns how many were new. */
    @Transactional
    public int importExamples(String datasetVersion, String source, List<ReplyExample> examples) {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("only SYNTHETIC or CONSENTED examples may be stored, never customer replies");
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

    /** The {@code k} (at most {@value #MAX_K}) most similar examples; empty when nothing shares a word with the reply. */
    @Transactional(readOnly = true)
    public List<ReplyExample> similar(String reply, int k) {
        if (reply == null || reply.isBlank() || k <= 0) {
            return List.of();
        }
        return jdbc.query("""
                SELECT e.text, e.intent, e.promised_date
                FROM reply_label_examples e,
                     (SELECT NULLIF(replace(plainto_tsquery('simple', :reply)::text, '&', '|'), '')::tsquery AS q) query
                WHERE query.q IS NOT NULL AND e.tsv @@ query.q
                ORDER BY ts_rank(e.tsv, query.q) DESC, e.id
                LIMIT :k""", new MapSqlParameterSource().addValue("reply", reply).addValue("k", Math.min(k, MAX_K)),
                (rs, i) -> new ReplyExample(rs.getString(1), rs.getString(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate().toString()));
    }
}

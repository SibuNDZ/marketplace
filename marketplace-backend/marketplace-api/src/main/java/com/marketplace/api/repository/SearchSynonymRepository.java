package com.marketplace.api.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Query-side lookup into search_synonyms (V21).
 *
 * Plain JdbcTemplate rather than an @Entity: nothing in the application
 * writes synonyms, they are seeded by migration and edited by hand, so an
 * entity, a repository interface and a mapped class would all exist purely
 * to serve one SELECT.
 */
@Repository
public class SearchSynonymRepository {

    private final JdbcTemplate jdbc;

    public SearchSynonymRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Synonyms for the given terms, as term -> [synonyms].
     *
     * ONE query for every term rather than one per term: a three-word search
     * would otherwise be three round trips on the hot path of every search
     * request. Terms are expected to be already lower-cased and sanitised by
     * the caller; the table enforces lower-case storage so anything else
     * simply misses.
     */
    public Map<String, List<String>> findForTerms(Collection<String> terms) {
        if (terms.isEmpty()) return Map.of();

        String placeholders = String.join(",", java.util.Collections.nCopies(terms.size(), "?"));
        Map<String, List<String>> out = new HashMap<>();

        jdbc.query(
                "SELECT term, synonym FROM search_synonyms WHERE term IN (" + placeholders + ")",
                rs -> {
                    out.computeIfAbsent(rs.getString("term"), k -> new ArrayList<>())
                       .add(rs.getString("synonym"));
                },
                terms.toArray());

        return out;
    }
}

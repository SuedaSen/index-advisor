package edu.asu.cse510.indexadvisor.database;

import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes candidates that PostgreSQL already provides through an existing
 * ordinary B-tree index, including primary-key indexes.
 */
public final class ExistingIndexFilter {

    public Set<IndexCandidate> removeCovered(Connection connection, Set<IndexCandidate> candidates)
            throws Exception {
        Map<String, List<List<String>>> existing = existingBtreePrefixes(connection);
        Set<IndexCandidate> filtered = new LinkedHashSet<>();
        for (IndexCandidate candidate : candidates) {
            // We do not recommend an index when an existing B-tree has the same leading columns.
            if (!isCovered(candidate, existing.getOrDefault(candidate.table(), List.of()))) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private Map<String, List<List<String>>> existingBtreePrefixes(Connection connection) throws Exception {
        String sql = """
                SELECT n.nspname || '.' || t.relname AS table_name,
                       string_agg(a.attname, ',' ORDER BY key_column.ordinality) AS index_columns
                FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_class index_relation ON index_relation.oid = i.indexrelid
                JOIN pg_am access_method ON access_method.oid = index_relation.relam
                CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS key_column(attnum, ordinality)
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = key_column.attnum
                WHERE access_method.amname = 'btree'
                  AND i.indpred IS NULL
                  AND key_column.attnum > 0
                GROUP BY n.nspname, t.relname, i.indexrelid
                """;
        Map<String, List<List<String>>> existing = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                String table = result.getString("table_name").toLowerCase();
                List<String> columns = List.of(result.getString("index_columns").toLowerCase().split(","));
                existing.computeIfAbsent(table, ignored -> new ArrayList<>()).add(columns);
            }
        }
        return existing;
    }

    private boolean isCovered(IndexCandidate candidate, List<List<String>> existingIndexes) {
        for (List<String> existing : existingIndexes) {
            if (existing.size() < candidate.columns().size()) {
                continue;
            }
            boolean matchesPrefix = true;
            for (int index = 0; index < candidate.columns().size(); index++) {
                if (!existing.get(index).equals(candidate.columns().get(index))) {
                    matchesPrefix = false;
                    break;
                }
            }
            if (matchesPrefix) {
                return true;
            }
        }
        return false;
    }
}

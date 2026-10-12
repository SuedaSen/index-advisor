package edu.asu.cse510.indexadvisor.candidate;
import edu.asu.cse510.indexadvisor.analysis.ColumnReference;
import edu.asu.cse510.indexadvisor.analysis.QueryFeatures;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
/** We generate bounded single-column and workload-derived composite B-tree candidates. */
public final class CandidateGenerator {
    public Set<IndexCandidate> generate(QueryFeatures features) {
        Set<IndexCandidate> candidates = new LinkedHashSet<>();
        Set<ColumnReference> columns = new TreeSet<>();
        columns.addAll(features.filterColumns()); columns.addAll(features.joinColumns()); columns.addAll(features.orderByColumns());
        columns.forEach(column -> candidates.add(new IndexCandidate(column.table(), List.of(column.column()))));
        Set<String> tables = new TreeSet<>(); columns.forEach(column -> tables.add(column.table()));
        for (String table : tables) {
            List<String> composite = new ArrayList<>();
            add(features.filterColumns(), table, composite); add(features.joinColumns(), table, composite); add(features.orderByColumns(), table, composite);
            if (composite.size() > 1) {
                candidates.add(new IndexCandidate(table, composite));
                // We test both two-column orders because B-tree column order is performance-sensitive.
                if (composite.size() == 2) candidates.add(new IndexCandidate(table, List.of(composite.get(1), composite.get(0))));
            }
        }
        return candidates;
    }
    private void add(Set<ColumnReference> columns, String table, List<String> target) {
        columns.stream().filter(column -> column.table().equals(table)).map(ColumnReference::column)
                .filter(column -> !target.contains(column)).forEach(target::add);
    }
}

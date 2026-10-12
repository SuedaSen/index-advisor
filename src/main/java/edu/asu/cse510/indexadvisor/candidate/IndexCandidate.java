package edu.asu.cse510.indexadvisor.candidate;
import java.util.List;
/** A PostgreSQL B-tree candidate. */
public record IndexCandidate(String table, List<String> columns) {
    public IndexCandidate { columns = List.copyOf(columns); }
    public String definition() { return "CREATE INDEX ON " + table + " (" + String.join(", ", columns) + ")"; }
    @Override public String toString() { return table + "(" + String.join(", ", columns) + ")"; }
}

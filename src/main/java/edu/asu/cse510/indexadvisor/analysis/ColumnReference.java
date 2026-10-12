package edu.asu.cse510.indexadvisor.analysis;

/** A normalized table.column reference, ordered to keep our candidate output reproducible. */
public record ColumnReference(String table, String column) implements Comparable<ColumnReference> {
    @Override public int compareTo(ColumnReference other) {
        int tableOrder = table.compareTo(other.table);
        return tableOrder != 0 ? tableOrder : column.compareTo(other.column);
    }
}

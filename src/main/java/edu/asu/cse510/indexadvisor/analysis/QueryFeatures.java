package edu.asu.cse510.indexadvisor.analysis;
import java.util.SortedSet;
/** The three SQL contexts we use as index-candidate evidence. */
public record QueryFeatures(SortedSet<ColumnReference> filterColumns, SortedSet<ColumnReference> joinColumns,
                            SortedSet<ColumnReference> orderByColumns) { }

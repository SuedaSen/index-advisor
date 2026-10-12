package edu.asu.cse510.indexadvisor.workload;

/** One SQL statement and the number of times we expect it to occur in the workload. */
public record NamedWorkloadQuery(String name, int frequency, String sql) { }

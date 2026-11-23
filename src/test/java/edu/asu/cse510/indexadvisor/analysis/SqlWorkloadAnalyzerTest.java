package edu.asu.cse510.indexadvisor.analysis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for the SQL evidence that drives candidate generation. */
class SqlWorkloadAnalyzerTest {
    private final SqlWorkloadAnalyzer analyzer = new SqlWorkloadAnalyzer();

    @Test
    void extractsFilterJoinAndOrderingColumnsFromAliasedQuery() {
        QueryFeatures features = analyzer.analyze("""
                SELECT o.o_orderkey
                FROM tpch.customer c JOIN tpch.orders o ON o.o_custkey = c.c_custkey
                WHERE c.c_mktsegment = 'BUILDING' AND o.o_orderdate >= DATE '1995-01-01'
                ORDER BY o.o_orderdate DESC
                """);

        assertTrue(features.filterColumns().contains(new ColumnReference("tpch.customer", "c_mktsegment")));
        assertTrue(features.filterColumns().contains(new ColumnReference("tpch.orders", "o_orderdate")));
        assertTrue(features.joinColumns().contains(new ColumnReference("tpch.customer", "c_custkey")));
        assertTrue(features.joinColumns().contains(new ColumnReference("tpch.orders", "o_custkey")));
        assertTrue(features.orderByColumns().contains(new ColumnReference("tpch.orders", "o_orderdate")));
    }

    @Test
    void resolvesAnUnqualifiedColumnWhenOnlyOneTableIsPresent() {
        QueryFeatures features = analyzer.analyze("""
                SELECT o_orderpriority
                FROM tpch.orders
                WHERE o_orderdate >= DATE '1996-01-01'
                ORDER BY o_orderpriority
                """);

        assertTrue(features.filterColumns().contains(new ColumnReference("tpch.orders", "o_orderdate")));
        assertTrue(features.orderByColumns().contains(new ColumnReference("tpch.orders", "o_orderpriority")));
    }
}

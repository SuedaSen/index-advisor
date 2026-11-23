package edu.asu.cse510.indexadvisor.candidate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.asu.cse510.indexadvisor.analysis.SqlWorkloadAnalyzer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for bounded single-column and composite candidate generation. */
class CandidateGeneratorTest {
    @Test
    void generatesSingleAndBothTwoColumnOrdersForAJoinWorkload() {
        Set<IndexCandidate> candidates = new CandidateGenerator().generate(new SqlWorkloadAnalyzer().analyze("""
                SELECT o.o_orderkey
                FROM tpch.customer c JOIN tpch.orders o ON o.o_custkey = c.c_custkey
                WHERE o.o_orderdate >= DATE '1995-01-01'
                ORDER BY o.o_orderdate DESC
                """));

        assertTrue(candidates.contains(new IndexCandidate("tpch.orders", List.of("o_orderdate"))));
        assertTrue(candidates.contains(new IndexCandidate("tpch.orders", List.of("o_custkey"))));
        assertTrue(candidates.contains(new IndexCandidate("tpch.orders", List.of("o_orderdate", "o_custkey"))));
        assertTrue(candidates.contains(new IndexCandidate("tpch.orders", List.of("o_custkey", "o_orderdate"))));
    }

    @Test
    void keepsCandidateDefinitionDeterministicAndColumnsImmutable() {
        IndexCandidate candidate = new IndexCandidate("tpch.orders", List.of("o_orderdate", "o_custkey"));

        assertTrue(candidate.definition().equals("CREATE INDEX ON tpch.orders (o_orderdate, o_custkey)"));
        assertTrue(candidate.columns().equals(List.of("o_orderdate", "o_custkey")));
    }
}

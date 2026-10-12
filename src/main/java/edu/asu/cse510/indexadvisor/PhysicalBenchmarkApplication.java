package edu.asu.cse510.indexadvisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.asu.cse510.indexadvisor.workload.NamedWorkloadQuery;
import edu.asu.cse510.indexadvisor.workload.WorkloadLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * We validate the advisor's 500 MB recommendation with real PostgreSQL indexes.
 * The experiment removes every temporary index and checks TPC-H row counts before
 * and after measurement, so it cannot silently alter the benchmark dataset.
 */
public final class PhysicalBenchmarkApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 7;
    private static final Map<String, String> INDEXES = Map.of(
            "idx_advisor_orders_date", "CREATE INDEX idx_advisor_orders_date ON tpch.orders (o_orderdate)",
            "idx_advisor_lineitem_mode_shipdate", "CREATE INDEX idx_advisor_lineitem_mode_shipdate ON tpch.lineitem (l_shipmode, l_shipdate)",
            "idx_advisor_orders_date_priority", "CREATE INDEX idx_advisor_orders_date_priority ON tpch.orders (o_orderdate, o_orderpriority)",
            "idx_advisor_partsupp_cost", "CREATE INDEX idx_advisor_partsupp_cost ON tpch.partsupp (ps_supplycost)",
            "idx_advisor_partsupp_cost_supplier", "CREATE INDEX idx_advisor_partsupp_cost_supplier ON tpch.partsupp (ps_supplycost, ps_suppkey)");
    private static final Map<String, Long> TPCH_SF1_ROWS = Map.of(
            "region", 5L, "nation", 25L, "supplier", 10_000L, "customer", 150_000L,
            "part", 200_000L, "partsupp", 800_000L, "orders", 1_500_000L, "lineitem", 6_001_215L);

    private PhysicalBenchmarkApplication() { }

    public static void main(String[] args) throws Exception {
        List<NamedWorkloadQuery> workload = new WorkloadLoader().loadResource("/workloads/tpch-demo.json");
        try (Connection connection = connect()) {
            verifyDataset(connection);
            dropIndexes(connection);
            System.out.println("Measuring baseline and physical-index runtimes...");
            Map<String, Measurement> baseline = measureWorkload(connection, workload);
            List<Result> results;
            long indexBytes;
            try {
                createIndexes(connection);
                analyze(connection);
                indexBytes = indexBytes(connection);
                Map<String, Measurement> indexed = measureWorkload(connection, workload);
                results = workload.stream().map(query -> new Result(query.name(), query.frequency(),
                        baseline.get(query.name()), indexed.get(query.name()))).toList();
            } finally {
                dropIndexes(connection);
                analyze(connection);
                verifyDataset(connection);
            }
            print(results, indexBytes);
            writeReport(results, indexBytes);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password"));
    }
    private static void verifyDataset(Connection connection) throws Exception {
        for (Map.Entry<String, Long> entry : TPCH_SF1_ROWS.entrySet()) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT count(*) FROM tpch." + entry.getKey())) {
                result.next(); if (result.getLong(1) != entry.getValue()) throw new IllegalStateException(
                        "TPC-H safety check failed for " + entry.getKey() + "; reload the dataset before benchmarking.");
            }
        }
    }
    private static Map<String, Measurement> measureWorkload(Connection connection, List<NamedWorkloadQuery> workload) throws Exception {
        Map<String, Measurement> output = new LinkedHashMap<>();
        for (NamedWorkloadQuery query : workload) {
            // We warm the buffer cache before collecting samples, reducing cold-cache noise.
            for (int run = 0; run < WARMUP_RUNS; run++) executionTime(connection, query.sql());
            List<Double> samples = new ArrayList<>();
            for (int run = 0; run < MEASURED_RUNS; run++) samples.add(executionTime(connection, query.sql()));
            samples.sort(Comparator.naturalOrder());
            output.put(query.name(), new Measurement(samples.get(samples.size() / 2),
                    samples.get(Math.min(samples.size() - 1, (int) Math.ceil(samples.size() * .95) - 1))));
        }
        return output;
    }
    private static double executionTime(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            result.next(); JsonNode document = JSON.readTree(result.getString(1)); return document.get(0).get("Execution Time").asDouble();
        }
    }
    private static void createIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) { for (String definition : INDEXES.values()) statement.execute(definition); }
    }
    private static void dropIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) { for (String name : INDEXES.keySet()) statement.execute("DROP INDEX IF EXISTS tpch." + name); }
    }
    private static void analyze(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute("ANALYZE tpch.orders"); statement.execute("ANALYZE tpch.lineitem"); statement.execute("ANALYZE tpch.partsupp"); }
    }
    private static long indexBytes(Connection connection) throws Exception {
        String query = "SELECT COALESCE(SUM(pg_relation_size(c.oid)), 0) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'tpch' AND c.relname LIKE 'idx_advisor_%'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) { result.next(); return result.getLong(1); }
    }
    private static void print(List<Result> results, long bytes) {
        System.out.printf("Physical experiment index size: %.1f MB%n%n", bytes / 1048576.0);
        System.out.printf("%-32s %12s %12s %10s%n", "Query", "Base median", "Index median", "Speedup");
        for (Result result : results) System.out.printf("%-32s %12.2f %12.2f %9.2fx%n", result.name, result.baseline.median, result.indexed.median, result.speedup());
        double baseline = results.stream().mapToDouble(Result::weightedBaseline).sum();
        double indexed = results.stream().mapToDouble(Result::weightedIndexed).sum();
        System.out.printf("%nWeighted workload: %.2f -> %.2f ms-equivalent, %.2fx speedup (%.2f%% reduction)%n", baseline, indexed, baseline / indexed, (baseline - indexed) * 100 / baseline);
    }
    private static void writeReport(List<Result> results, long bytes) throws IOException {
        Files.createDirectories(Path.of("output", "reports"));
        double baseline = results.stream().mapToDouble(Result::weightedBaseline).sum(); double indexed = results.stream().mapToDouble(Result::weightedIndexed).sum();
        String rows = results.stream().map(result -> String.format("| `%s` | %d | %.2f | %.2f | %.2fx |%n", result.name, result.frequency, result.baseline.median, result.indexed.median, result.speedup())).collect(Collectors.joining());
        String report = String.format("# TPC-H Physical Index Validation%n%n- Runs per configuration: 3 warm-up + 7 measured%n- Metric: median PostgreSQL execution time%n- Index size: %.1f MB%n- Temporary indexes removed after measurement: yes%n- Frequency-weighted reduction: **%.2f%%** (**%.2fx** speedup)%n%n| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |%n|---|---:|---:|---:|---:|%n%s", bytes / 1048576.0, (baseline - indexed) * 100 / baseline, baseline / indexed, rows);
        Files.writeString(Path.of("output", "reports", "tpch-physical-validation.md"), report);
        System.out.println("Experiment indexes removed. Report written to: " + Path.of("output", "reports").toAbsolutePath());
    }
    private record Measurement(double median, double p95) { }
    private record Result(String name, int frequency, Measurement baseline, Measurement indexed) {
        double speedup() { return baseline.median / indexed.median; }
        double weightedBaseline() { return frequency * baseline.median; }
        double weightedIndexed() { return frequency * indexed.median; }
    }
}

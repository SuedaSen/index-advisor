package edu.asu.cse510.indexadvisor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * We measure the write-side trade-off of the advisor configuration. Every
 * measured INSERT and UPDATE is rolled back, so no benchmark data is retained.
 */
public final class WriteCostApplication {
    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 5;
    private static final int BATCH_SIZE = 10_000;
    private static final Map<String, String> INDEXES = Map.of(
            "idx_advisor_orders_date", "CREATE INDEX idx_advisor_orders_date ON tpch.orders (o_orderdate)",
            "idx_advisor_lineitem_mode_shipdate", "CREATE INDEX idx_advisor_lineitem_mode_shipdate ON tpch.lineitem (l_shipmode, l_shipdate)",
            "idx_advisor_orders_date_priority", "CREATE INDEX idx_advisor_orders_date_priority ON tpch.orders (o_orderdate, o_orderpriority)",
            "idx_advisor_partsupp_cost", "CREATE INDEX idx_advisor_partsupp_cost ON tpch.partsupp (ps_supplycost)",
            "idx_advisor_partsupp_cost_supplier", "CREATE INDEX idx_advisor_partsupp_cost_supplier ON tpch.partsupp (ps_supplycost, ps_suppkey)");
    private static final Map<String, Long> ROWS = Map.of("region", 5L, "nation", 25L, "supplier", 10_000L,
            "customer", 150_000L, "part", 200_000L, "partsupp", 800_000L, "orders", 1_500_000L, "lineitem", 6_001_215L);
    private static final String INSERT = """
            INSERT INTO tpch.orders (o_orderkey, o_custkey, o_orderstatus, o_totalprice, o_orderdate,
                                     o_orderpriority, o_clerk, o_shippriority, o_comment)
            SELECT (SELECT max(o_orderkey) FROM tpch.orders) + gs,
                   1 + (gs %% 150000), 'O', 100.00, DATE '1998-01-01',
                   '1-URGENT', 'Clerk#000000001', 0, 'rollback-only write benchmark'
            FROM generate_series(1, %d) gs
            """.formatted(BATCH_SIZE);
    private static final String UPDATE = """
            UPDATE tpch.orders
            SET o_orderdate = o_orderdate + 1
            WHERE o_orderkey BETWEEN 1 AND %d
            """.formatted(BATCH_SIZE);

    private WriteCostApplication() { }
    public static void main(String[] args) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false); verify(connection); drop(connection); connection.commit();
            Measurement insertBaseline = measure(connection, INSERT); Measurement updateBaseline = measure(connection, UPDATE);
            Measurement insertIndexed; Measurement updateIndexed;
            try {
                create(connection); connection.commit();
                // We measure indexed writes with identical rollback-only transactions.
                insertIndexed = measure(connection, INSERT); updateIndexed = measure(connection, UPDATE);
            } finally {
                connection.rollback(); connection.setAutoCommit(true); drop(connection); verify(connection);
            }
            printAndReport(new Result("INSERT 10,000 orders", insertBaseline, insertIndexed),
                    new Result("UPDATE 10,000 order dates", updateBaseline, updateIndexed));
        }
    }
    private static Connection connect() throws Exception { return DriverManager.getConnection(
            System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor"),
            System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"), System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password")); }
    private static Measurement measure(Connection connection, String sql) throws Exception {
        for (int i = 0; i < WARMUP_RUNS; i++) executeAndRollback(connection, sql);
        List<Double> samples = new ArrayList<>(); for (int i = 0; i < MEASURED_RUNS; i++) samples.add(executeAndRollback(connection, sql));
        samples.sort(Comparator.naturalOrder()); return new Measurement(samples.get(samples.size() / 2));
    }
    private static double executeAndRollback(Connection connection, String sql) throws Exception {
        long start = System.nanoTime(); try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
        finally { connection.rollback(); } return (System.nanoTime() - start) / 1_000_000.0;
    }
    private static void create(Connection connection) throws Exception { try (Statement statement = connection.createStatement()) { for (String definition : INDEXES.values()) statement.execute(definition); } }
    private static void drop(Connection connection) throws Exception { try (Statement statement = connection.createStatement()) { for (String name : INDEXES.keySet()) statement.execute("DROP INDEX IF EXISTS tpch." + name); } }
    private static void verify(Connection connection) throws Exception { for (Map.Entry<String, Long> entry : ROWS.entrySet()) try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT count(*) FROM tpch." + entry.getKey())) { result.next(); if (result.getLong(1) != entry.getValue()) throw new IllegalStateException("TPC-H safety check failed for " + entry.getKey()); } }
    private static void printAndReport(Result... results) throws Exception {
        System.out.printf("%-28s %14s %14s %14s%n", "Operation", "Baseline ms", "Indexed ms", "Write overhead");
        StringBuilder markdown = new StringBuilder("# TPC-H Read/Write Trade-off\n\n- Every write runs in a transaction and is rolled back.\n- Runs: 2 warm-up + 5 measured; metric: median client-observed milliseconds.\n\n| Operation | Baseline (ms) | Indexed (ms) | Write overhead |\n|---|---:|---:|---:|\n");
        for (Result result : results) { System.out.printf("%-28s %14.2f %14.2f %13.2f%%%n", result.operation, result.baseline.median, result.indexed.median, result.overhead()); markdown.append(String.format("| %s | %.2f | %.2f | %.2f%% |%n", result.operation, result.baseline.median, result.indexed.median, result.overhead())); }
        Files.createDirectories(Path.of("output", "reports")); Files.writeString(Path.of("output", "reports", "tpch-write-cost.md"), markdown.toString());
        System.out.println("Experiment indexes removed. Report written to: " + Path.of("output", "reports").toAbsolutePath());
    }
    private record Measurement(double median) { }
    private record Result(String operation, Measurement baseline, Measurement indexed) { double overhead() { return (indexed.median - baseline.median) * 100 / baseline.median; } }
}

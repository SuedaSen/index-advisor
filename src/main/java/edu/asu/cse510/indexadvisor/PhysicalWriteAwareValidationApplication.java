package edu.asu.cse510.indexadvisor;

import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import edu.asu.cse510.indexadvisor.selection.IndexSelectionService;
import edu.asu.cse510.indexadvisor.workload.NamedWorkloadQuery;
import edu.asu.cse510.indexadvisor.workload.WorkloadLoader;
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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Physically validates the write-side trade-off between the read-only and
 * write-aware selections. Every INSERT and UPDATE is rolled back, while the
 * temporary indexes are dropped before the application exits.
 */
public final class PhysicalWriteAwareValidationApplication {
    private static final String PREFIX = "idx_write_aware_validation_";
    private static final int WARMUPS = 2;
    private static final int SAMPLES = 5;
    private static final int BATCH = 10_000;
    private static final String INSERT = """
            INSERT INTO tpch.orders (o_orderkey, o_custkey, o_orderstatus, o_totalprice, o_orderdate,
                                     o_orderpriority, o_clerk, o_shippriority, o_comment)
            SELECT (SELECT max(o_orderkey) FROM tpch.orders) + gs,
                   1 + (gs %% 150000), 'O', 100.00, DATE '1998-01-01',
                   '1-URGENT', 'Clerk#000000001', 0, 'rollback-only write benchmark'
            FROM generate_series(1, %d) gs
            """.formatted(BATCH);
    private static final String UPDATE = """
            UPDATE tpch.orders SET o_orderdate = o_orderdate + 1
            WHERE o_orderkey BETWEEN 1 AND %d
            """.formatted(BATCH);
    private static final Map<String, Long> ROWS = Map.of("orders", 1_500_000L, "lineitem", 6_001_215L,
            "partsupp", 800_000L, "customer", 150_000L, "supplier", 10_000L, "part", 200_000L,
            "nation", 25L, "region", 5L);

    private PhysicalWriteAwareValidationApplication() { }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<NamedWorkloadQuery> workload = new WorkloadLoader().loadFile(options.workload());
        try (Connection connection = connect()) {
            verify(connection);
            dropIndexes(connection);
            IndexSelectionService advisor = new IndexSelectionService();
            List<IndexSelectionService.CandidateScore> scores = advisor.evaluateCandidates(connection, workload);
            double baselineCost = advisor.workloadCost(connection, workload);
            long budget = options.budgetMb() * 1024L * 1024L;
            IndexSelectionService.Selection readOnly = advisor.selectJointly(connection, workload, scores, budget, baselineCost);
            IndexSelectionService.Selection writeAware = advisor.selectWriteAware(connection, workload, scores, budget, baselineCost,
                    new IndexSelectionService.WriteProfile(options.penalty(), Map.of("tpch.orders", 1.0),
                            Map.of("tpch.orders.o_orderdate", 1.0)));

            System.out.println("Measuring rollback-only write costs for the two selected configurations...");
            Measurement insertBaseline = measureRollback(connection, INSERT);
            Measurement updateBaseline = measureRollback(connection, UPDATE);
            List<ConfigurationResult> results = List.of(
                    measureConfiguration(connection, "Read-only joint selection", readOnly, insertBaseline, updateBaseline),
                    measureConfiguration(connection, "Write-aware joint selection", writeAware, insertBaseline, updateBaseline));
            verify(connection);
            print(results);
            writeReport(options, results);
        } finally {
            // The database connection may close after an error; each successful configuration cleans itself first.
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password"));
    }

    private static ConfigurationResult measureConfiguration(Connection connection, String name,
            IndexSelectionService.Selection selection, Measurement insertBaseline, Measurement updateBaseline) throws Exception {
        try {
            createIndexes(connection, selection.candidates());
            long size = indexBytes(connection);
            Measurement insert = measureRollback(connection, INSERT);
            Measurement update = measureRollback(connection, UPDATE);
            return new ConfigurationResult(name, selection, size, insertBaseline, insert, updateBaseline, update);
        } finally {
            dropIndexes(connection);
        }
    }

    private static Measurement measureRollback(Connection connection, String sql) throws Exception {
        connection.setAutoCommit(false);
        try {
            for (int index = 0; index < WARMUPS; index++) executeAndRollback(connection, sql);
            List<Double> values = new ArrayList<>();
            for (int index = 0; index < SAMPLES; index++) values.add(executeAndRollback(connection, sql));
            values.sort(Comparator.naturalOrder());
            return new Measurement(values.get(values.size() / 2));
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static double executeAndRollback(Connection connection, String sql) throws Exception {
        long start = System.nanoTime();
        try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
        finally { connection.rollback(); }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static void createIndexes(Connection connection, List<IndexCandidate> candidates) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (int position = 0; position < candidates.size(); position++) {
                IndexCandidate candidate = candidates.get(position);
                statement.execute("CREATE INDEX " + PREFIX + position + " ON " + candidate.table() + " ("
                        + String.join(", ", candidate.columns()) + ")");
            }
        }
    }

    private static void dropIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT schemaname, indexname FROM pg_indexes WHERE indexname LIKE '" + PREFIX + "%'")) {
            List<String> names = new ArrayList<>();
            while (result.next()) names.add(result.getString(1) + "." + result.getString(2));
            for (String name : names) statement.execute("DROP INDEX IF EXISTS " + name);
        }
    }

    private static long indexBytes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COALESCE(SUM(pg_relation_size(oid)), 0) FROM pg_class WHERE relname LIKE '" + PREFIX + "%'")) {
            result.next(); return result.getLong(1);
        }
    }

    private static void verify(Connection connection) throws Exception {
        for (Map.Entry<String, Long> row : ROWS.entrySet()) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT count(*) FROM tpch." + row.getKey())) {
                result.next();
                if (result.getLong(1) != row.getValue()) throw new IllegalStateException("TPC-H safety check failed for " + row.getKey());
            }
        }
    }

    private static void print(List<ConfigurationResult> results) {
        System.out.printf("%n%-30s %10s %14s %14s%n", "Configuration", "Actual MB", "INSERT overhead", "UPDATE overhead");
        for (ConfigurationResult result : results) System.out.printf(Locale.US, "%-30s %10.1f %13.2f%% %13.2f%%%n",
                result.name(), result.indexBytes() / 1048576.0, result.insertOverhead(), result.updateOverhead());
    }

    private static void writeReport(Options options, List<ConfigurationResult> results) throws Exception {
        Files.createDirectories(Path.of("output", "reports"));
        StringBuilder report = new StringBuilder("# Physical Write-Aware Validation\n\n")
                .append("- Workload: `").append(options.workload()).append("`\n")
                .append("- Budget: ").append(options.budgetMb()).append(" MB; write-penalty weight: ").append(options.penalty()).append("\n")
                .append("- Each operation: ").append(WARMUPS).append(" warm-ups + ").append(SAMPLES)
                .append(" timed rollback-only transactions of ").append(BATCH).append(" orders\n")
                .append("- All temporary indexes were removed and TPC-H row counts were checked after the run.\n\n")
                .append("| Configuration | Physical index MB | INSERT baseline (ms) | INSERT indexed (ms) | INSERT overhead | UPDATE baseline (ms) | UPDATE indexed (ms) | UPDATE overhead |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConfigurationResult result : results) {
            report.append(String.format(Locale.US, "| %s | %.1f | %.2f | %.2f | %.2f%% | %.2f | %.2f | %.2f%% |%n",
                    result.name(), result.indexBytes() / 1048576.0, result.insertBaseline().median(), result.insert().median(),
                    result.insertOverhead(), result.updateBaseline().median(), result.update().median(), result.updateOverhead()));
            report.append("\nSelected indexes: `").append(result.selection().candidates().stream()
                    .map(IndexCandidate::toString).collect(Collectors.joining("; "))).append("`\n\n");
        }
        String name = options.workload().getFileName().toString().replaceFirst("\\.json$", "");
        Path path = Path.of("output", "reports", "physical-write-aware-validation-" + name + "-"
                + options.budgetMb() + "mb.md");
        Files.writeString(path, report.toString());
        System.out.println("Temporary indexes removed. Report written to: " + path.toAbsolutePath());
    }

    private record Measurement(double median) { }
    private record ConfigurationResult(String name, IndexSelectionService.Selection selection, long indexBytes,
            Measurement insertBaseline, Measurement insert, Measurement updateBaseline, Measurement update) {
        double insertOverhead() { return (insert.median() - insertBaseline.median()) * 100.0 / insertBaseline.median(); }
        double updateOverhead() { return (update.median() - updateBaseline.median()) * 100.0 / updateBaseline.median(); }
    }
    private record Options(Path workload, long budgetMb, double penalty) {
        static Options parse(String[] args) {
            Path workload = Path.of("workloads/tpch-balanced-mixed.json"); long budget = 150; double penalty = 1.0;
            for (int index = 0; index < args.length; index++) {
                if ("--workload".equals(args[index]) && index + 1 < args.length) workload = Path.of(args[++index]);
                else if ("--budget".equals(args[index]) && index + 1 < args.length) budget = Long.parseLong(args[++index]);
                else if ("--write-penalty".equals(args[index]) && index + 1 < args.length) penalty = Double.parseDouble(args[++index]);
                else throw new IllegalArgumentException("Usage: --workload workloads/file.json --budget 150 --write-penalty 1.0");
            }
            if (budget <= 0 || penalty < 0) throw new IllegalArgumentException("Budget must be positive and penalty cannot be negative.");
            return new Options(workload, budget, penalty);
        }
    }
}

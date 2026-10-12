package edu.asu.cse510.indexadvisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import edu.asu.cse510.indexadvisor.selection.IndexSelectionService;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validates two advisor strategies using real temporary PostgreSQL indexes.
 *
 * <p>We first let HypoPG select the configurations. We then create precisely
 * those selections as physical indexes, measure the same workload, and remove
 * every experiment index. This separates an optimizer estimate from an actual
 * database-runtime result.</p>
 */
public final class PhysicalStrategyValidationApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 7;
    private static final String INDEX_PREFIX = "idx_physical_validation_";
    private static final Map<String, Long> TPCH_SF1_ROWS = Map.of(
            "region", 5L, "nation", 25L, "supplier", 10_000L, "customer", 150_000L,
            "part", 200_000L, "partsupp", 800_000L, "orders", 1_500_000L, "lineitem", 6_001_215L);

    private PhysicalStrategyValidationApplication() { }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<NamedWorkloadQuery> workload = new WorkloadLoader().loadFile(options.workload());
        try (Connection connection = connect()) {
            verifyDataset(connection);
            dropExperimentIndexes(connection);
            IndexSelectionService advisor = new IndexSelectionService();
            List<IndexSelectionService.CandidateScore> scores = advisor.evaluateCandidates(connection, workload);
            double estimatedBaseline = advisor.workloadCost(connection, workload);
            long budgetBytes = options.budgetMb() * 1024L * 1024L;

            // We compare a naive one-at-a-time ranking with our interaction-aware selector.
            List<StrategyConfiguration> configurations = List.of(
                    new StrategyConfiguration("Independent ranking",
                            advisor.selectIndependent(scores, budgetBytes)),
                    new StrategyConfiguration("Joint re-evaluation (ours)",
                            advisor.selectJointly(connection, workload, scores, budgetBytes, estimatedBaseline)));

            System.out.printf("Physical validation: %s, %d MB budget, %d repetitions%n",
                    options.workload(), options.budgetMb(), options.repetitions());
            System.out.println("Measuring a baseline and both temporary configurations in every repetition...");
            Map<String, List<StrategyResult>> trialResults = new LinkedHashMap<>();
            for (StrategyConfiguration configuration : configurations) {
                trialResults.put(configuration.name(), new ArrayList<>());
            }
            try {
                for (int repetition = 0; repetition < options.repetitions(); repetition++) {
                    System.out.printf("  Repetition %d/%d%n", repetition + 1, options.repetitions());
                    Map<String, Measurement> baseline = measureWorkload(connection, workload);
                    List<StrategyConfiguration> order = new ArrayList<>(configurations);
                    // We alternate order to avoid consistently favoring the first warm-cache configuration.
                    if (repetition % 2 == 1) Collections.reverse(order);
                    for (StrategyConfiguration configuration : order) {
                        trialResults.get(configuration.name()).add(
                                measureConfiguration(connection, workload, baseline, configuration));
                    }
                }
            } finally {
                dropExperimentIndexes(connection);
                verifyDataset(connection);
            }
            List<StrategyResult> results = configurations.stream()
                    .map(configuration -> aggregate(configuration, trialResults.get(configuration.name())))
                    .toList();
            print(results);
            writeReport(options, results, trialResults);
            System.out.println("Experiment indexes removed. Report written to: "
                    + reportPath(options).toAbsolutePath());
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password"));
    }

    private static StrategyResult measureConfiguration(Connection connection, List<NamedWorkloadQuery> workload,
            Map<String, Measurement> baseline, StrategyConfiguration configuration) throws Exception {
        try {
            createIndexes(connection, configuration.selection().candidates());
            long bytes = experimentIndexBytes(connection);
            Map<String, Measurement> indexed = measureWorkload(connection, workload);
            List<QueryResult> queries = workload.stream().map(query -> new QueryResult(query.name(), query.frequency(),
                    baseline.get(query.name()), indexed.get(query.name()))).toList();
            return new StrategyResult(configuration.name(), configuration.selection(), bytes, queries);
        } finally {
            // We always clean up, even if an individual query fails during measurement.
            dropExperimentIndexes(connection);
        }
    }

    private static void createIndexes(Connection connection, List<IndexCandidate> candidates) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (int position = 0; position < candidates.size(); position++) {
                IndexCandidate candidate = candidates.get(position);
                String name = INDEX_PREFIX + position;
                String sql = "CREATE INDEX " + name + " ON " + candidate.table() + " ("
                        + String.join(", ", candidate.columns()) + ")";
                statement.execute(sql);
            }
        }
    }

    private static void dropExperimentIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery("SELECT schemaname, indexname FROM pg_indexes "
                     + "WHERE indexname LIKE '" + INDEX_PREFIX + "%'")) {
            List<String> names = new ArrayList<>();
            while (indexes.next()) names.add(indexes.getString(1) + "." + indexes.getString(2));
            for (String name : names) statement.execute("DROP INDEX IF EXISTS " + name);
        }
    }

    private static Map<String, Measurement> measureWorkload(Connection connection, List<NamedWorkloadQuery> workload)
            throws Exception {
        Map<String, Measurement> output = new LinkedHashMap<>();
        for (NamedWorkloadQuery query : workload) {
            // We warm cache pages before samples, then use the median to resist one noisy run.
            for (int run = 0; run < WARMUP_RUNS; run++) executionTime(connection, query.sql());
            List<Double> samples = new ArrayList<>();
            for (int run = 0; run < MEASURED_RUNS; run++) samples.add(executionTime(connection, query.sql()));
            samples.sort(Comparator.naturalOrder());
            output.put(query.name(), new Measurement(samples.get(samples.size() / 2)));
        }
        return output;
    }

    private static double executionTime(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            result.next();
            JsonNode document = JSON.readTree(result.getString(1));
            return document.get(0).get("Execution Time").asDouble();
        }
    }

    private static long experimentIndexBytes(Connection connection) throws Exception {
        String sql = "SELECT COALESCE(SUM(pg_relation_size(c.oid)), 0) "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname LIKE '" + INDEX_PREFIX + "%'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /** Combines independent repetitions by taking the median for each query and index-size observation. */
    private static StrategyResult aggregate(StrategyConfiguration configuration, List<StrategyResult> trials) {
        if (trials.isEmpty()) throw new IllegalArgumentException("At least one physical trial is required.");
        List<QueryResult> queries = new ArrayList<>();
        for (int queryPosition = 0; queryPosition < trials.get(0).queries().size(); queryPosition++) {
            List<Double> baselineSamples = new ArrayList<>();
            List<Double> indexedSamples = new ArrayList<>();
            QueryResult first = trials.get(0).queries().get(queryPosition);
            for (StrategyResult trial : trials) {
                QueryResult sample = trial.queries().get(queryPosition);
                baselineSamples.add(sample.baseline().median());
                indexedSamples.add(sample.indexed().median());
            }
            queries.add(new QueryResult(first.name(), first.frequency(),
                    new Measurement(median(baselineSamples)), new Measurement(median(indexedSamples))));
        }
        List<Double> sizeSamples = trials.stream().map(trial -> (double) trial.indexBytes()).toList();
        return new StrategyResult(configuration.name(), configuration.selection(), Math.round(median(sizeSamples)), queries);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(sorted.size() / 2);
    }

    private static void verifyDataset(Connection connection) throws Exception {
        for (Map.Entry<String, Long> entry : TPCH_SF1_ROWS.entrySet()) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT count(*) FROM tpch." + entry.getKey())) {
                result.next();
                if (result.getLong(1) != entry.getValue()) {
                    throw new IllegalStateException("TPC-H safety check failed for " + entry.getKey()
                            + "; measurement was stopped before changing the dataset.");
                }
            }
        }
    }

    private static void print(List<StrategyResult> results) {
        System.out.printf("%n%-30s %10s %10s %12s %12s %10s%n", "Strategy", "Est. MB", "Actual MB", "Baseline", "Indexed", "Speedup");
        for (StrategyResult result : results) {
            System.out.printf(Locale.US, "%-30s %10.1f %10.1f %10.2f ms %10.2f ms %9.2fx%n", result.name(),
                    result.selection().usedBytes() / 1048576.0, result.indexBytes() / 1048576.0,
                    result.weightedBaseline(), result.weightedIndexed(), result.speedup());
        }
    }

    private static void writeReport(Options options, List<StrategyResult> results,
            Map<String, List<StrategyResult>> trialResults) throws IOException {
        Files.createDirectories(Path.of("output", "reports"));
        StringBuilder report = new StringBuilder("# Physical Strategy Validation\n\n")
                .append("- Workload: `").append(options.workload()).append("`\n")
                .append("- Budget: ").append(options.budgetMb()).append(" MB\n")
                .append("- Independent repetitions: ").append(options.repetitions()).append("; strategy order alternates by repetition\n")
                .append("- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported\n")
                .append("- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.\n\n")
                .append("- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.\n\n")
                .append("| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
        for (StrategyResult result : results) {
            report.append(String.format(Locale.US, "| %s | %.1f | %.1f | %.2f | %.2f | %.2fx |%n", result.name(),
                    result.selection().usedBytes() / 1048576.0, result.indexBytes() / 1048576.0,
                    result.weightedBaseline(), result.weightedIndexed(), result.speedup()));
            report.append("\nSelected indexes: `").append(result.selection().candidates().stream()
                    .map(IndexCandidate::toString).collect(Collectors.joining("; "))).append("`\n\n")
                    .append("| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |\n")
                    .append("|---|---:|---:|---:|---:|\n");
            for (QueryResult query : result.queries()) {
                report.append(String.format(Locale.US, "| `%s` | %d | %.2f | %.2f | %.2fx |%n", query.name(),
                        query.frequency(), query.baseline().median(), query.indexed().median(), query.speedup()));
            }
            report.append('\n');
        }
        report.append("## Replicate weighted summaries\n\n")
                .append("| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (Map.Entry<String, List<StrategyResult>> entry : trialResults.entrySet()) {
            for (int index = 0; index < entry.getValue().size(); index++) {
                StrategyResult trial = entry.getValue().get(index);
                report.append(String.format(Locale.US, "| %s | %d | %.2f | %.2f | %.2fx |%n", entry.getKey(),
                        index + 1, trial.weightedBaseline(), trial.weightedIndexed(), trial.speedup()));
            }
        }
        Files.writeString(reportPath(options), report.toString());
    }

    /** We keep one immutable experiment artifact per workload and budget rather than overwriting prior evidence. */
    private static Path reportPath(Options options) {
        String filename = options.workload().getFileName().toString().replaceFirst("\\.json$", "");
        return Path.of("output", "reports", "physical-strategy-validation-" + filename + "-"
                + options.budgetMb() + "mb-" + options.repetitions() + "reps.md");
    }

    private record Measurement(double median) { }
    private record StrategyConfiguration(String name, IndexSelectionService.Selection selection) { }
    private record StrategyResult(String name, IndexSelectionService.Selection selection, long indexBytes,
            List<QueryResult> queries) {
        double weightedBaseline() { return queries.stream().mapToDouble(QueryResult::weightedBaseline).sum(); }
        double weightedIndexed() { return queries.stream().mapToDouble(QueryResult::weightedIndexed).sum(); }
        double speedup() { return weightedBaseline() / weightedIndexed(); }
    }
    private record QueryResult(String name, int frequency, Measurement baseline, Measurement indexed) {
        double weightedBaseline() { return frequency * baseline.median(); }
        double weightedIndexed() { return frequency * indexed.median(); }
        double speedup() { return baseline.median() / indexed.median(); }
    }
    private record Options(Path workload, long budgetMb, int repetitions) {
        static Options parse(String[] args) {
            Path workload = Path.of("workloads/tpch-dashboard-read-heavy.json");
            long budgetMb = 500;
            int repetitions = 1;
            for (int index = 0; index < args.length; index++) {
                if ("--workload".equals(args[index]) && index + 1 < args.length) workload = Path.of(args[++index]);
                else if ("--budget".equals(args[index]) && index + 1 < args.length) budgetMb = Long.parseLong(args[++index]);
                else if ("--repetitions".equals(args[index]) && index + 1 < args.length) repetitions = Integer.parseInt(args[++index]);
                else throw new IllegalArgumentException("Usage: --workload workloads/file.json --budget 500 --repetitions 3");
            }
            if (budgetMb <= 0) throw new IllegalArgumentException("Budget must be positive.");
            if (repetitions <= 0) throw new IllegalArgumentException("Repetitions must be positive.");
            return new Options(workload, budgetMb, repetitions);
        }
    }
}

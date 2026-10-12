package edu.asu.cse510.indexadvisor;

import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import edu.asu.cse510.indexadvisor.selection.IndexSelectionService;
import edu.asu.cse510.indexadvisor.workload.NamedWorkloadQuery;
import edu.asu.cse510.indexadvisor.workload.WorkloadLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Demonstrates the advisor's configurable read/write selection policy.
 *
 * <p>Our current write profile models inserts into {@code tpch.orders} and
 * updates to {@code o_orderdate}. An index on orders is exposed by inserts;
 * an index that includes o_orderdate is exposed by both operations.</p>
 */
public final class WriteAwareSelectionApplication {
    private WriteAwareSelectionApplication() { }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<NamedWorkloadQuery> workload = new WorkloadLoader().loadFile(options.workload());
        try (Connection connection = connect()) {
            IndexSelectionService advisor = new IndexSelectionService();
            List<IndexSelectionService.CandidateScore> scores = advisor.evaluateCandidates(connection, workload);
            double baseline = advisor.workloadCost(connection, workload);
            long budgetBytes = options.budgetMb() * 1024L * 1024L;
            IndexSelectionService.Selection readOnly = advisor.selectJointly(
                    connection, workload, scores, budgetBytes, baseline);
            IndexSelectionService.WriteProfile profile = new IndexSelectionService.WriteProfile(options.weight(),
                    Map.of("tpch.orders", 1.0), Map.of("tpch.orders.o_orderdate", 1.0));
            IndexSelectionService.Selection writeAware = advisor.selectWriteAware(
                    connection, workload, scores, budgetBytes, baseline, profile);

            Result readOnlyResult = result("Read-only joint selection", advisor, connection, workload, baseline, readOnly);
            Result writeAwareResult = result("Write-aware joint selection", advisor, connection, workload, baseline, writeAware);
            print(options, readOnlyResult, writeAwareResult);
            writeReport(options, profile, readOnlyResult, writeAwareResult);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password"));
    }

    private static Result result(String name, IndexSelectionService advisor, Connection connection,
            List<NamedWorkloadQuery> workload, double baseline, IndexSelectionService.Selection selection) throws Exception {
        double cost = advisor.configurationCost(connection, workload, selection.candidates());
        return new Result(name, selection, (baseline - cost) * 100.0 / baseline);
    }

    private static void print(Options options, Result... results) {
        System.out.printf("Write-aware selection: %s, %d MB, penalty weight %.2f%n", options.workload(),
                options.budgetMb(), options.weight());
        for (Result result : results) {
            System.out.printf(Locale.US, "%n%s%n  Estimated space: %.1f MB%n  Read-cost improvement: %.2f%%%n  Indexes: %s%n",
                    result.name(), result.selection().usedBytes() / 1048576.0, result.readImprovement(),
                    indexes(result.selection()));
        }
    }

    private static void writeReport(Options options, IndexSelectionService.WriteProfile profile,
            Result readOnly, Result writeAware) throws Exception {
        Files.createDirectories(Path.of("output", "reports"));
        String report = String.format(Locale.US, """
                # Write-Aware Selection Comparison

                - Workload: `%s`
                - HypoPG storage budget: %d MB
                - Write-penalty weight: %.2f
                - Write profile: inserts into `tpch.orders` have table exposure 1.0; updates to `tpch.orders.o_orderdate` add column exposure 1.0.
                - Interpretation: the penalty changes candidate priority, not the reported read-cost improvement. It is a transparent workload-policy heuristic; physical rollback-only write measurements are reported separately.

                | Policy | Estimated index MB | HypoPG read-cost improvement | Selected indexes |
                |---|---:|---:|---|
                | %s | %.1f | %.2f%% | `%s` |
                | %s | %.1f | %.2f%% | `%s` |
                """, options.workload(), options.budgetMb(), profile.weight(), readOnly.name(),
                readOnly.selection().usedBytes() / 1048576.0, readOnly.readImprovement(), indexes(readOnly.selection()),
                writeAware.name(), writeAware.selection().usedBytes() / 1048576.0, writeAware.readImprovement(), indexes(writeAware.selection()));
        Path path = reportPath(options);
        Files.writeString(path, report);
        System.out.println("Report written to: " + path.toAbsolutePath());
    }

    /** We preserve each workload/budget/penalty scenario as separate final-report evidence. */
    private static Path reportPath(Options options) {
        String workload = options.workload().getFileName().toString().replaceFirst("\\.json$", "");
        String weight = String.format(Locale.ROOT, "%.2f", options.weight()).replace('.', '_');
        return Path.of("output", "reports", "write-aware-selection-" + workload + "-"
                + options.budgetMb() + "mb-penalty-" + weight + ".md");
    }

    private static String indexes(IndexSelectionService.Selection selection) {
        return selection.candidates().stream().map(IndexCandidate::toString).collect(Collectors.joining("; "));
    }

    private record Result(String name, IndexSelectionService.Selection selection, double readImprovement) { }
    private record Options(Path workload, long budgetMb, double weight) {
        static Options parse(String[] args) {
            Path workload = Path.of("workloads/tpch-balanced-mixed.json");
            long budgetMb = 500;
            double weight = 1.0;
            for (int index = 0; index < args.length; index++) {
                if ("--workload".equals(args[index]) && index + 1 < args.length) workload = Path.of(args[++index]);
                else if ("--budget".equals(args[index]) && index + 1 < args.length) budgetMb = Long.parseLong(args[++index]);
                else if ("--write-penalty".equals(args[index]) && index + 1 < args.length) weight = Double.parseDouble(args[++index]);
                else throw new IllegalArgumentException("Usage: --workload workloads/file.json --budget 500 --write-penalty 1.0");
            }
            if (budgetMb <= 0 || weight < 0) throw new IllegalArgumentException("Budget must be positive and penalty cannot be negative.");
            return new Options(workload, budgetMb, weight);
        }
    }
}

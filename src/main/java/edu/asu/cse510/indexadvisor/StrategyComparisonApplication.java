package edu.asu.cse510.indexadvisor;

import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import edu.asu.cse510.indexadvisor.selection.IndexSelectionService;
import edu.asu.cse510.indexadvisor.workload.NamedWorkloadQuery;
import edu.asu.cse510.indexadvisor.workload.WorkloadLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Compares simple index-selection baselines with our joint re-evaluation method.
 * We use the same HypoPG costs and storage estimates for every strategy so the
 * comparison isolates the effect of the selection algorithm.
 */
public final class StrategyComparisonApplication {
    private StrategyComparisonApplication() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<Profile> profiles = List.of(
                new Profile("Dashboard read-heavy", Path.of("workloads/tpch-dashboard-read-heavy.json")),
                new Profile("Analytical aggregation", Path.of("workloads/tpch-analytical-aggregation.json")),
                new Profile("Balanced mixed", Path.of("workloads/tpch-balanced-mixed.json")));
        List<Row> rows = new ArrayList<>();

        try (Connection connection = connect()) {
            // We delegate selection to the same service used by physical validation.
            // This keeps the estimated comparison and the real-runtime experiment aligned.
            IndexSelectionService advisor = new IndexSelectionService();
            for (Profile profile : profiles) {
                List<NamedWorkloadQuery> workload = new WorkloadLoader().loadFile(profile.path());
                List<IndexSelectionService.CandidateScore> scores = advisor.evaluateCandidates(connection, workload);
                double baselineCost = advisor.workloadCost(connection, workload);

                for (long budgetMb : options.budgetsMb()) {
                    long budgetBytes = budgetMb * 1024 * 1024;
                    // We give every method the same candidates and storage budget.
                    rows.add(toRow(profile, budgetMb, "Independent ranking",
                            advisor.selectIndependent(scores, budgetBytes), advisor, connection, workload, baselineCost));
                    rows.add(toRow(profile, budgetMb, "Static benefit per byte",
                            advisor.selectStaticBenefitPerByte(scores, budgetBytes), advisor, connection, workload, baselineCost));
                    rows.add(toRow(profile, budgetMb, "Joint re-evaluation (ours)",
                            advisor.selectJointly(connection, workload, scores, budgetBytes, baselineCost),
                            advisor, connection, workload, baselineCost));
                }
            }
        }

        print(rows);
        writeReports(rows);
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password"));
    }

    private static Row toRow(
            Profile profile,
            long budgetMb,
            String strategy,
            IndexSelectionService.Selection selection,
            IndexSelectionService advisor,
            Connection connection,
            List<NamedWorkloadQuery> workload,
            double baselineCost) throws Exception {
        // We score each selected configuration jointly so every strategy is reported fairly.
        double cost = advisor.configurationCost(connection, workload, selection.candidates());
        double improvement = (baselineCost - cost) * 100.0 / baselineCost;
        return new Row(profile.name(), budgetMb, strategy, selection.usedBytes(), improvement, selection.candidates());
    }

    private static void print(List<Row> rows) {
        System.out.printf("%-24s %9s %-30s %10s %12s %9s%n",
                "Workload", "Budget", "Strategy", "Used MB", "Improvement", "Indexes");
        for (Row row : rows) {
            System.out.printf(Locale.US, "%-24s %7dMB %-30s %10.1f %11.2f%% %8d%n",
                    row.profile(), row.budgetMb(), row.strategy(), row.usedBytes() / 1024.0 / 1024.0,
                    row.improvementPercent(), row.candidates().size());
        }
    }

    private static void writeReports(List<Row> rows) throws IOException {
        Path output = Path.of("output", "reports");
        Files.createDirectories(output);
        StringBuilder markdown = new StringBuilder("# Index Selection Strategy Comparison\n\n")
                .append("All strategies use identical HypoPG what-if costs, candidate sets, and storage budgets. ")
                .append("Only their selection rule differs.\n\n")
                .append("| Workload | Budget | Strategy | Used MB | Joint improvement | Index count | Selected indexes |\n")
                .append("|---|---:|---|---:|---:|---:|---|\n");
        StringBuilder csv = new StringBuilder("workload,budget_mb,strategy,used_mb,joint_improvement_percent,index_count,selected_indexes\n");
        for (Row row : rows) {
            String indexes = row.candidates().stream().map(IndexCandidate::toString).reduce((left, right) -> left + "; " + right).orElse("none");
            markdown.append(String.format(Locale.US, "| %s | %d | %s | %.1f | %.2f%% | %d | `%s` |%n",
                    row.profile(), row.budgetMb(), row.strategy(), row.usedBytes() / 1024.0 / 1024.0,
                    row.improvementPercent(), row.candidates().size(), indexes));
            csv.append(String.format(Locale.US, "\"%s\",%d,\"%s\",%.3f,%.4f,%d,\"%s\"%n",
                    row.profile(), row.budgetMb(), row.strategy(), row.usedBytes() / 1024.0 / 1024.0,
                    row.improvementPercent(), row.candidates().size(), indexes.replace("\"", "\"\"")));
        }
        Files.writeString(output.resolve("strategy-comparison.md"), markdown.toString());
        Files.writeString(output.resolve("strategy-comparison.csv"), csv.toString());
        System.out.println("Reports written to: " + output.toAbsolutePath());
    }

    private record Profile(String name, Path path) {
    }

    private record Row(
            String profile,
            long budgetMb,
            String strategy,
            long usedBytes,
            double improvementPercent,
            List<IndexCandidate> candidates) {
    }

    private record Options(List<Long> budgetsMb) {
        static Options parse(String[] args) {
            List<Long> budgets = List.of(150L, 500L);
            for (int index = 0; index < args.length; index++) {
                if ("--budgets".equals(args[index]) && index + 1 < args.length) {
                    budgets = Arrays.stream(args[++index].split(","))
                            .map(String::strip)
                            .map(Long::parseLong)
                            .filter(value -> value > 0)
                            .toList();
                } else {
                    throw new IllegalArgumentException("Usage: --budgets 150,500");
                }
            }
            return new Options(budgets);
        }
    }
}

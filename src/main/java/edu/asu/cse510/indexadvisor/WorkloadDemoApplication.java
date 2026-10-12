package edu.asu.cse510.indexadvisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.asu.cse510.indexadvisor.analysis.SqlWorkloadAnalyzer;
import edu.asu.cse510.indexadvisor.candidate.CandidateGenerator;
import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import edu.asu.cse510.indexadvisor.database.ExistingIndexFilter;
import edu.asu.cse510.indexadvisor.workload.NamedWorkloadQuery;
import edu.asu.cse510.indexadvisor.workload.WorkloadLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * We analyze a weighted workload, compare virtual HypoPG indexes, and choose a
 * configuration under one or more storage budgets without creating real indexes.
 */
public final class WorkloadDemoApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double MINIMUM_MARGINAL_IMPROVEMENT_PERCENT = 0.01;
    private WorkloadDemoApplication() { }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) { System.out.println("Usage: --workload file.json --budgets 100,250,500"); return; }
        List<NamedWorkloadQuery> workload = options.workload == null
                ? new WorkloadLoader().loadResource("/workloads/tpch-demo.json")
                : new WorkloadLoader().loadFile(options.workload);
        Set<IndexCandidate> candidates = new LinkedHashSet<>();
        SqlWorkloadAnalyzer analyzer = new SqlWorkloadAnalyzer(); CandidateGenerator generator = new CandidateGenerator();
        for (NamedWorkloadQuery query : workload) candidates.addAll(generator.generate(analyzer.analyze(query.sql())));
        try (Connection connection = connect()) {
            int generatedCandidateCount = candidates.size();
            candidates = new ExistingIndexFilter().removeCovered(connection, candidates);
            int coveredCandidateCount = generatedCandidateCount - candidates.size();
            List<Evaluation> evaluations = new ArrayList<>();
            for (IndexCandidate candidate : candidates) evaluations.add(evaluate(connection, workload, candidate));
            evaluations.sort(Comparator.comparingDouble(Evaluation::improvement).reversed());
            System.out.printf("Workload: %d queries, %d unique candidates (%d already-covered candidates skipped)%n%n",
                    workload.size(), candidates.size(), coveredCandidateCount);
            System.out.printf("%-4s %-48s %10s %12s%n", "Rank", "Candidate", "Est. MB", "Improvement");
            for (int index = 0; index < Math.min(10, evaluations.size()); index++) {
                Evaluation e = evaluations.get(index);
                System.out.printf("%-4d %-48s %10.1f %11.2f%%%n", index + 1, e.candidate, e.bytes / 1048576.0, e.improvement() * 100);
            }
            System.out.println("\nBudget-aware configurations:");
            for (long budgetMb : options.budgets) printSelection(connection, workload, evaluations, budgetMb * 1048576L);
        }
    }
    private static Connection connect() throws Exception {
        String url = System.getenv().getOrDefault("ADVISOR_DB_URL", "jdbc:postgresql://localhost:5432/index_advisor");
        return DriverManager.getConnection(url, System.getenv().getOrDefault("ADVISOR_DB_USER", "advisor"),
                System.getenv().getOrDefault("ADVISOR_DB_PASSWORD", "advisor_dev_password"));
    }
    private static Evaluation evaluate(Connection connection, List<NamedWorkloadQuery> workload, IndexCandidate candidate) throws Exception {
        double baseline = workloadCost(connection, workload);
        long bytes;
        try (PreparedStatement statement = connection.prepareStatement("SELECT indexrelid FROM hypopg_create_index(?)")) {
            statement.setString(1, candidate.definition()); try (ResultSet result = statement.executeQuery()) { result.next(); bytes = size(connection, result.getLong(1)); }
        }
        try { double hypothetical = workloadCost(connection, workload); return new Evaluation(candidate, bytes, baseline, hypothetical); }
        finally {
            try (Statement statement = connection.createStatement()) { statement.execute("SELECT hypopg_reset()"); }
        }
    }
    private static long size(Connection connection, long oid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT hypopg_relation_size(?)")) { statement.setLong(1, oid); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); } }
    }
    private static double workloadCost(Connection connection, List<NamedWorkloadQuery> workload) throws Exception {
        double total = 0; for (NamedWorkloadQuery query : workload) total += query.frequency() * cost(connection, query.sql()); return total;
    }
    private static double cost(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
            result.next(); JsonNode plan = JSON.readTree(result.getString(1)); return plan.get(0).get("Plan").get("Total Cost").asDouble();
        }
    }
    private static void printSelection(Connection connection, List<NamedWorkloadQuery> workload, List<Evaluation> all, long budget) throws Exception {
        List<IndexCandidate> selected = new ArrayList<>(); long used = 0; double baseline = workloadCost(connection, workload); double current = baseline; List<String> lines = new ArrayList<>();
        while (true) {
            Evaluation best = null; double bestScore = 0; double bestCost = current;
            for (Evaluation candidate : all) {
                if (selected.contains(candidate.candidate) || used + candidate.bytes > budget) continue;
                List<IndexCandidate> trial = new ArrayList<>(selected); trial.add(candidate.candidate); Configuration value = configuration(connection, workload, trial);
                double benefit = current - value.cost;
                // We ignore optimizer noise: a configuration must improve the weighted workload by at least 0.01%.
                double marginalPercent = benefit * 100 / baseline;
                if (marginalPercent >= MINIMUM_MARGINAL_IMPROVEMENT_PERCENT && benefit / candidate.bytes > bestScore) {
                    best = candidate; bestScore = benefit / candidate.bytes; bestCost = value.cost;
                }
            }
            if (best == null) break; selected.add(best.candidate); used += best.bytes; lines.add(best.candidate + " (+" + String.format("%.2f", (current - bestCost) * 100 / baseline) + "%)"); current = bestCost;
        }
        System.out.printf("%nBudget %d MB -> %.1f MB used, %.2f%% joint improvement%n", budget / 1048576L, used / 1048576.0, (baseline - current) * 100 / baseline);
        for (int i = 0; i < lines.size(); i++) System.out.printf("  %d. %s%n", i + 1, lines.get(i));
    }
    private static Configuration configuration(Connection connection, List<NamedWorkloadQuery> workload, List<IndexCandidate> indexes) throws Exception {
        try { for (IndexCandidate index : indexes) try (PreparedStatement statement = connection.prepareStatement("SELECT hypopg_create_index(?)")) { statement.setString(1, index.definition()); statement.execute(); } return new Configuration(workloadCost(connection, workload)); }
        finally {
            try (Statement statement = connection.createStatement()) { statement.execute("SELECT hypopg_reset()"); }
        }
    }
    private record Evaluation(IndexCandidate candidate, long bytes, double baseline, double hypothetical) { double improvement() { return (baseline - hypothetical) / baseline; } }
    private record Configuration(double cost) { }
    private record Options(Path workload, List<Long> budgets, boolean help) {
        static Options parse(String[] args) { Path file = null; List<Long> budgets = List.of(100L, 250L, 500L); for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help") || args[i].equals("-h")) return new Options(null, budgets, true);
            if (args[i].equals("--workload") && i + 1 < args.length) file = Path.of(args[++i]);
            else if (args[i].equals("--budgets") && i + 1 < args.length) budgets = Arrays.stream(args[++i].split(",")).map(String::strip).map(Long::parseLong).filter(n -> n > 0).toList();
            else throw new IllegalArgumentException("Unknown or incomplete option: " + args[i]); }
            return new Options(file, budgets, false); }
    }
}

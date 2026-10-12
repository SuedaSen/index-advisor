package edu.asu.cse510.indexadvisor.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.asu.cse510.indexadvisor.analysis.SqlWorkloadAnalyzer;
import edu.asu.cse510.indexadvisor.candidate.CandidateGenerator;
import edu.asu.cse510.indexadvisor.candidate.IndexCandidate;
import edu.asu.cse510.indexadvisor.database.ExistingIndexFilter;
import edu.asu.cse510.indexadvisor.workload.NamedWorkloadQuery;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Shared implementation of the advisor's what-if selection stage.
 *
 * <p>We use this class from both the estimated-cost comparison and the later
 * physical experiment. That is important: the physical experiment validates
 * the exact configuration selected by our algorithm, rather than a manually
 * copied list of indexes.</p>
 */
public final class IndexSelectionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final double MINIMUM_IMPROVEMENT_PERCENT = 0.01;

    /** Produces candidate indexes, omitting indexes PostgreSQL already has. */
    public List<CandidateScore> evaluateCandidates(Connection connection, List<NamedWorkloadQuery> workload)
            throws Exception {
        double baseline = workloadCost(connection, workload);
        Set<IndexCandidate> candidates = new LinkedHashSet<>();
        SqlWorkloadAnalyzer analyzer = new SqlWorkloadAnalyzer();
        CandidateGenerator generator = new CandidateGenerator();
        for (NamedWorkloadQuery query : workload) {
            candidates.addAll(generator.generate(analyzer.analyze(query.sql())));
        }
        candidates = new ExistingIndexFilter().removeCovered(connection, candidates);

        List<CandidateScore> scores = new ArrayList<>();
        for (IndexCandidate candidate : candidates) {
            long bytes = createHypotheticalIndex(connection, candidate);
            try {
                double benefit = baseline - workloadCost(connection, workload);
                scores.add(new CandidateScore(candidate, bytes, benefit, benefit * 100.0 / baseline));
            } finally {
                resetHypotheticalIndexes(connection);
            }
        }
        return scores;
    }

    /** Selects indexes by their one-at-a-time benefit, without interaction checks. */
    public Selection selectIndependent(List<CandidateScore> scores, long budgetBytes) {
        return packWithoutReevaluation(scores.stream()
                .sorted(Comparator.comparingDouble(CandidateScore::individualBenefit).reversed()).toList(), budgetBytes);
    }

    /** Selects indexes by a static benefit-per-byte ranking, without re-evaluation. */
    public Selection selectStaticBenefitPerByte(List<CandidateScore> scores, long budgetBytes) {
        return packWithoutReevaluation(scores.stream()
                .sorted(Comparator.comparingDouble(CandidateScore::benefitPerByte).reversed()).toList(), budgetBytes);
    }

    /**
     * Our method: after every accepted index, we re-evaluate every remaining
     * candidate alongside the current configuration. This prevents us from
     * counting overlapping benefits twice.
     */
    public Selection selectJointly(Connection connection, List<NamedWorkloadQuery> workload,
            List<CandidateScore> scores, long budgetBytes, double baselineCost) throws Exception {
        List<IndexCandidate> selected = new ArrayList<>();
        long usedBytes = 0;
        double currentCost = baselineCost;
        while (true) {
            CandidateScore bestCandidate = null;
            double bestCost = currentCost;
            double bestScore = 0;
            for (CandidateScore candidate : scores) {
                if (selected.contains(candidate.candidate()) || usedBytes + candidate.bytes() > budgetBytes) continue;
                List<IndexCandidate> trial = new ArrayList<>(selected);
                trial.add(candidate.candidate());
                double trialCost = configurationCost(connection, workload, trial);
                double marginalBenefit = currentCost - trialCost;
                double marginalPercent = marginalBenefit * 100.0 / baselineCost;
                double score = marginalBenefit / candidate.bytes();
                if (marginalPercent >= MINIMUM_IMPROVEMENT_PERCENT && score > bestScore) {
                    bestCandidate = candidate;
                    bestCost = trialCost;
                    bestScore = score;
                }
            }
            if (bestCandidate == null) break;
            selected.add(bestCandidate.candidate());
            usedBytes += bestCandidate.bytes();
            currentCost = bestCost;
        }
        return new Selection(List.copyOf(selected), usedBytes);
    }

    /**
     * Selects indexes with the same joint read-cost re-evaluation, but discounts
     * candidates that would be maintained frequently by a supplied write profile.
     * The profile is deliberately explicit: it is a transparent policy knob, not
     * an opaque estimate of PostgreSQL's internal write time.
     */
    public Selection selectWriteAware(Connection connection, List<NamedWorkloadQuery> workload,
            List<CandidateScore> scores, long budgetBytes, double baselineCost, WriteProfile profile) throws Exception {
        List<IndexCandidate> selected = new ArrayList<>();
        long usedBytes = 0;
        double currentCost = baselineCost;
        while (true) {
            CandidateScore bestCandidate = null;
            double bestCost = currentCost;
            double bestScore = 0;
            for (CandidateScore candidate : scores) {
                if (selected.contains(candidate.candidate()) || usedBytes + candidate.bytes() > budgetBytes) continue;
                List<IndexCandidate> trial = new ArrayList<>(selected);
                trial.add(candidate.candidate());
                double trialCost = configurationCost(connection, workload, trial);
                double marginalBenefit = currentCost - trialCost;
                double marginalPercent = marginalBenefit * 100.0 / baselineCost;
                // We penalize only the choice priority; the reported read benefit remains unmodified.
                double score = (marginalBenefit / candidate.bytes()) / (1.0 + profile.weight() * profile.exposure(candidate.candidate()));
                if (marginalPercent >= MINIMUM_IMPROVEMENT_PERCENT && score > bestScore) {
                    bestCandidate = candidate;
                    bestCost = trialCost;
                    bestScore = score;
                }
            }
            if (bestCandidate == null) break;
            selected.add(bestCandidate.candidate());
            usedBytes += bestCandidate.bytes();
            currentCost = bestCost;
        }
        return new Selection(List.copyOf(selected), usedBytes);
    }

    /** Reports the weighted optimizer cost of a full virtual-index configuration. */
    public double configurationCost(Connection connection, List<NamedWorkloadQuery> workload,
            List<IndexCandidate> candidates) throws Exception {
        try {
            for (IndexCandidate candidate : candidates) createHypotheticalIndex(connection, candidate);
            return workloadCost(connection, workload);
        } finally {
            resetHypotheticalIndexes(connection);
        }
    }

    /** Reports baseline weighted optimizer cost for a workload. */
    public double workloadCost(Connection connection, List<NamedWorkloadQuery> workload) throws Exception {
        double total = 0;
        for (NamedWorkloadQuery query : workload) total += query.frequency() * queryCost(connection, query.sql());
        return total;
    }

    private Selection packWithoutReevaluation(List<CandidateScore> ordered, long budgetBytes) {
        List<IndexCandidate> selected = new ArrayList<>();
        long usedBytes = 0;
        for (CandidateScore score : ordered) {
            if (score.improvementPercent() < MINIMUM_IMPROVEMENT_PERCENT) continue;
            if (usedBytes + score.bytes() <= budgetBytes) {
                selected.add(score.candidate());
                usedBytes += score.bytes();
            }
        }
        return new Selection(List.copyOf(selected), usedBytes);
    }

    private long createHypotheticalIndex(Connection connection, IndexCandidate candidate) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT indexrelid FROM hypopg_create_index(?)")) {
            statement.setString(1, candidate.definition());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                long oid = result.getLong(1);
                try (PreparedStatement size = connection.prepareStatement("SELECT hypopg_relation_size(?)")) {
                    size.setLong(1, oid);
                    try (ResultSet value = size.executeQuery()) { value.next(); return value.getLong(1); }
                }
            }
        }
    }

    private void resetHypotheticalIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute("SELECT hypopg_reset()"); }
    }

    private double queryCost(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
            result.next();
            JsonNode plan = JSON.readTree(result.getString(1));
            return plan.get(0).get("Plan").get("Total Cost").asDouble();
        }
    }

    public record CandidateScore(IndexCandidate candidate, long bytes, double individualBenefit,
            double improvementPercent) {
        public double benefitPerByte() { return individualBenefit / bytes; }
    }

    public record Selection(List<IndexCandidate> candidates, long usedBytes) { }

    /**
     * Table exposure applies to every index on that table (for example INSERTs).
     * Column exposure is added when an UPDATE modifies an indexed column.
     */
    public record WriteProfile(double weight, Map<String, Double> tableExposure,
            Map<String, Double> columnExposure) {
        public WriteProfile {
            tableExposure = Map.copyOf(tableExposure);
            columnExposure = Map.copyOf(columnExposure);
        }
        public double exposure(IndexCandidate candidate) {
            double total = tableExposure.getOrDefault(candidate.table(), 0.0);
            for (String column : candidate.columns()) {
                total += columnExposure.getOrDefault(candidate.table() + "." + column, 0.0);
            }
            return total;
        }
    }
}

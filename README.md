# CSE 510 PostgreSQL Index Advisor

**CSE 510 — Group 7**  
**Sueda Sen and Mehmet Aksit**

This repository contains our CSE 510 project artifact.

We build a workload-aware PostgreSQL index advisor. It parses weighted SQL workloads, extracts filter/join/order-by evidence, generates B-tree candidates, evaluates them through HypoPG without building physical indexes, and selects a configuration under a storage budget.

The project has four layers:

1. **Candidate generation:** We derive single-column and composite B-tree candidates from SQL structure.
2. **What-if selection:** We remove candidates already covered by existing B-tree/primary-key indexes, estimate benefits with HypoPG, and select a configuration under a storage budget.
3. **Strategy comparison:** We compare independent ranking, static benefit-per-byte, and our joint re-evaluation policy.
4. **Physical validation:** We create temporary real indexes, use `EXPLAIN ANALYZE`, run repeated measurements, then remove every experiment index. A write-aware policy additionally balances read benefit against maintenance exposure from `orders` INSERTs and `o_orderdate` UPDATEs.

## Requirements

- Java 21 and Maven
- Docker PostgreSQL with the `hypopg` extension
- TPC-H SF1 data in schema `tpch`

The applications default to `jdbc:postgresql://localhost:5432/index_advisor` with user `advisor`. Override these with `ADVISOR_DB_URL`, `ADVISOR_DB_USER`, and `ADVISOR_DB_PASSWORD` when needed.

## Run

The Docker PostgreSQL database must be running with the `hypopg` extension and the `tpch` schema loaded.

```bash
cd /Users/suedasen/Documents/CSE510-index-advisor
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WorkloadDemoApplication \
-Dexec.args="--budgets 150,500"
```

For a custom workload, pass a JSON file containing `name`, positive `frequency`, and `sql` fields:

```bash
-Dexec.args="--workload /absolute/path/to/workload.json --budgets 150,500"
```

The advisor uses PostgreSQL optimizer cost for candidate ranking. We validate final recommendations separately with physical indexes and `EXPLAIN ANALYZE` runtime experiments.

## Reproduce the main experiments

Run the estimated strategy comparison across all three workload profiles:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.StrategyComparisonApplication \
-Dexec.args="--budgets 150,500"
```

Run a three-repetition physical comparison for one profile. Temporary indexes are removed automatically:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.PhysicalStrategyValidationApplication \
-Dexec.args="--workload workloads/tpch-balanced-mixed.json --budget 500 --repetitions 3"
```

Compare read-only and write-aware selection under a tighter storage budget:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WriteAwareSelectionApplication \
-Dexec.args="--workload workloads/tpch-balanced-mixed.json --budget 150 --write-penalty 1.0"
```

Physically validate write overhead for those two selections. The 10,000-row INSERT and UPDATE batches are each rolled back:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.PhysicalWriteAwareValidationApplication \
-Dexec.args="--workload workloads/tpch-balanced-mixed.json --budget 150 --write-penalty 1.0"
```

## Verified findings

At a 500 MB HypoPG-estimated budget, we physically measured Dashboard/read-heavy, Balanced Mixed, and Analytical Aggregation workloads using three independent repetitions, alternating strategy order. Joint re-evaluation was near-equivalent on Dashboard and improved the Balanced and Analytical profiles while usually selecting less physical index storage.

At a 150 MB budget on Balanced Mixed, the write-aware policy traded estimated read-cost improvement from 15.92% to 14.84% to avoid an additional date-based `orders` index. In rollback-only physical measurements, it reduced INSERT overhead from 81.80% to 16.27% and UPDATE overhead from 86.64% to 38.21%.

See [docs/physical-experiment-summary.md](docs/physical-experiment-summary.md) for the evidence summary and `output/reports/` for the full, reproducible reports.

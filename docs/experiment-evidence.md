# CSE 510 Experiment Evidence

This file preserves the verified measurements and interpretations for the final technical report and presentation. Values below are results observed on the local PostgreSQL 17 / TPC-H SF1 environment on the M3 MacBook Air. Runtime measurements can vary slightly between runs because of PostgreSQL caching and normal system activity; the benchmark uses warm-up runs and medians to reduce that noise.

## 1. Advisor configuration evidence

The advisor analyzed a five-query TPC-H workload and generated 26 unique single-column and composite B-tree index candidates. It uses JSqlParser to collect filter, join, and ordering columns; HypoPG to estimate optimizer cost and index size; and a marginal greedy selector to choose candidates under a storage budget.

| Storage budget | Estimated used space | Estimated joint workload improvement | Selected indexes |
|---:|---:|---:|---:|
| 150 MB | 141.5 MB | 8.60% | 3 |
| 500 MB | 467.0 MB | 38.79% | 5 |

The 500 MB recommendation was:

1. `tpch.orders(o_orderdate)`
2. `tpch.lineitem(l_shipmode, l_shipdate)`
3. `tpch.orders(o_orderdate, o_orderpriority)`
4. `tpch.partsupp(ps_supplycost)`
5. `tpch.partsupp(ps_supplycost, ps_suppkey)`

The advisor also compared both column orders for two-column candidates. `tpch.lineitem(l_shipmode, l_shipdate)` had 30.11% individual estimated improvement, while `tpch.lineitem(l_shipdate, l_shipmode)` had 25.16%. This is evidence that composite B-tree column order matters.

## 2. Physical PostgreSQL validation

We physically created the five selected indexes, executed each query with 3 warm-up and 7 measured runs, and used median `EXPLAIN ANALYZE` execution time. The benchmark removes every `idx_advisor_*` index afterward and checks the expected TPC-H SF1 row counts before and after the experiment.

Latest verified run:

| Query | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|
| Q1 customer segment orders | 63.76 | 0.58 | 110.51x |
| Q2 shipping mode summary | 156.51 | 20.78 | 7.53x |
| Q3 discounted revenue | 130.95 | 132.91 | 0.99x |
| Q4 priority orders | 36.27 | 6.84 | 5.30x |
| Q5 supplier parts | 25.45 | 1.68 | 15.14x |

The frequency-weighted workload changed from 128,136.56 to 74,089.80 ms-equivalent: **1.73x speedup and 42.18% reduction**. The physically created indexes occupied 104.7 MB on disk in this run.

Interpretation: the selected configuration optimizes the weighted workload, not every individual query. Q3 remains approximately neutral, while Q1, Q2, Q4, and Q5 improve substantially. We should not claim every query improves.

## 3. DataGrip Q1 plan demonstration

The focused Q1 demo compared a baseline plan with a manually created `tpch.orders(o_orderdate)` index. The script is `sql/demo-q1-plan.sql`; it drops the manual demo index at the end.

| Configuration | Execution time | Shared reads | Main plan shape |
|---|---:|---:|---|
| Baseline | 144.157 ms | 25,879 | Parallel Seq Scan -> Parallel Hash Join -> Sort -> Gather Merge -> Limit |
| With `idx_demo_q1_orders_date` | 9.606 ms | 3 | Index Scan Backward -> Nested Loop -> Memoize -> Limit |

This is roughly a 15x speedup in that captured interactive run. The index lets PostgreSQL retrieve recent rows in descending `o_orderdate` order and avoid the expensive sort.

## 4. Read/write trade-off

We measured write operations with 2 warm-up and 5 measured runs. Every INSERT and UPDATE occurred in a transaction that was rolled back, so benchmark data was not retained. The update changes `o_orderdate`, an indexed column, to include index-maintenance cost.

| Operation | Baseline median (ms) | Indexed median (ms) | Write overhead |
|---|---:|---:|---:|
| INSERT 10,000 orders | 26.20 | 38.75 | 47.88% |
| UPDATE 10,000 order dates | 7.26 | 11.83 | 62.89% |

Interpretation: the advisor's index configuration substantially improves the read-heavy workload but introduces measurable write overhead. This demonstrates the read/write trade-off expected in physical database design.

## 5. Reproducibility commands

```bash
cd /Users/suedasen/Documents/CSE510-index-advisor

# Advisor: virtual HypoPG candidate evaluation only
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WorkloadDemoApplication \
-Dexec.args="--budgets 150,500"

# Real read-performance validation; temporary indexes are removed automatically
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.PhysicalBenchmarkApplication

# Rollback-only write-cost validation; temporary indexes are removed automatically
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WriteCostApplication
```

The generated reports are saved under `output/reports/`:

- `tpch-physical-validation.md`
- `tpch-write-cost.md`

# Physical Experiment Summary

This summary consolidates three TPC-H SF1 workload profiles at a 500 MB **HypoPG-estimated** index budget. Each profile was measured using three independent repetitions. Every repetition used three warm-up executions followed by seven timed executions per query/configuration; the reported query values are medians. We alternated strategy order between repetitions and removed all temporary experiment indexes after every configuration.

| Workload profile | Independent weighted time | Joint weighted time | Result for joint re-evaluation | Independent physical MB | Joint physical MB |
|---|---:|---:|---|---:|---:|
| Dashboard / read-heavy | 19,260.80 | 19,548.65 | 1.49% slower; near tie | 113.0 | 109.4 |
| Balanced mixed | 40,934.46 | 40,140.81 | **1.94% faster** | 113.0 | 104.9 |
| Analytical aggregation | 105,037.48 | 103,826.90 | **1.15% faster** | 112.7 | 104.7 |

## Interpretation

Joint re-evaluation does not dominate the independent baseline on every workload. It is near-equivalent on the read-heavy profile and improves the two other profiles while using approximately 3–8 MB less physical index storage. This variation is an expected empirical finding: optimizer what-if costs are useful signals but are not identical to executor runtimes, particularly for an aggregation-heavy query such as Q3.

For the final technical report, we will therefore report the complete table rather than making a universal-performance claim. Our supported claim is narrower: re-evaluating index interactions produces competitive configurations with lower physical storage, and it improves measured runtime for two of the three distinct workload mixes.

## Reproducibility artifacts

- `output/reports/physical-strategy-validation-tpch-dashboard-read-heavy-500mb-3reps.md`
- `output/reports/physical-strategy-validation-tpch-balanced-mixed-500mb-3reps.md`
- `output/reports/physical-strategy-validation-tpch-analytical-aggregation-500mb-3reps.md`

All report figures use a frequency-weighted `ms-equivalent` total: query median time multiplied by that query's workload frequency, summed across the workload. The 500 MB budget is based on HypoPG's size estimate; physical sizes are shown separately because the two can differ.

## Physical write-aware validation

We also evaluated the two 150 MB Balanced Mixed selections with rollback-only batches of 10,000 `orders` INSERTs and 10,000 `o_orderdate` UPDATEs. The read-only configuration chose two `orders` date-based indexes, while the write-aware configuration replaced the composite `orders(o_orderdate, o_orderpriority)` index with a `partsupp` composite index.

| Configuration | Physical index MB | INSERT overhead | UPDATE overhead |
|---|---:|---:|---:|
| Read-only joint selection | 38.3 | 81.80% | 86.64% |
| Write-aware joint selection | 51.9 | **16.27%** | **38.21%** |

The write-aware policy reduced INSERT overhead by 65.53 percentage points and UPDATE overhead by 48.43 percentage points. Physical size alone does not explain the result: the write-aware configuration is larger, but it avoids one date-based `orders` index and therefore has much lower maintenance cost for the measured `o_orderdate` update workload.

Source artifact: `output/reports/physical-write-aware-validation-tpch-balanced-mixed-150mb.md`.

# Physical Experiment Summary

This summary consolidates three TPC-H SF1 workload profiles at a 500 MB **HypoPG-estimated** index budget. Each profile was measured using three independent repetitions. Every repetition used three warm-up executions followed by seven timed executions per query/configuration; the reported query values are medians. We alternated strategy order between repetitions and removed all temporary experiment indexes after every configuration.

| Workload profile | Independent weighted time | Joint weighted time | Result for joint re-evaluation | Independent physical MB | Joint physical MB |
|---|---:|---:|---|---:|---:|
| Dashboard / read-heavy | 26,487.95 | 44,673.05 | 68.6% slower; independent is faster | 100.4 | 98.9 |
| Balanced mixed | 43,731.92 | 44,157.96 | 0.97% slower; near-equivalent | 113.0 | 104.9 |
| Analytical aggregation | 114,317.68 | 112,920.73 | **1.24% faster** | 112.7 | 104.7 |

## Interpretation

Joint re-evaluation does not dominate the independent baseline on every workload. It is slower on the read-heavy Dashboard profile, near-equivalent on Balanced Mixed, and slightly faster on Analytical Aggregation, while using less physical index storage in all three profiles. This variation is an expected empirical finding: optimizer what-if costs are useful signals but are not identical to executor runtimes. In Dashboard, the independent configuration retains additional overlapping `orders` indexes and materially improves the high-frequency priority report.

For the final technical report, we therefore report the complete table rather than making a universal-performance claim. Our supported claim is narrower: re-evaluating index interactions produces a more compact configuration and can remain competitive, but a what-if recommendation must be checked with physical execution measurements.

## Reproducibility artifacts

- `output/reports/physical-strategy-validation-tpch-dashboard-read-heavy-500mb-3reps.md`
- `output/reports/physical-strategy-validation-tpch-balanced-mixed-500mb-3reps.md`
- `output/reports/physical-strategy-validation-tpch-analytical-aggregation-500mb-3reps.md`

All report figures use a frequency-weighted `ms-equivalent` total: query median time multiplied by that query's workload frequency, summed across the workload. The 500 MB budget is based on HypoPG's size estimate; physical sizes are shown separately because the two can differ.

## Write-aware sensitivity check

For Balanced Mixed at a 150 MB HypoPG-estimated budget, we tested write-penalty weights of 1.0 and 5.0. At 1.0, the write-aware policy selected the same index membership as read-only joint selection. At 5.0, it added `customer(c_mktsegment, c_custkey)` but retained `orders(o_orderdate)`. The current workload therefore does not justify a headline claim that this policy reduces physical write overhead; it instead demonstrates that the policy is explicit and sensitive to the chosen write-exposure model. This limitation belongs in the final technical report.

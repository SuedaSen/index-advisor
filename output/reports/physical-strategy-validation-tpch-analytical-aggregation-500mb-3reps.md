# Physical Strategy Validation

- Workload: `workloads/tpch-analytical-aggregation.json`
- Budget: 500 MB
- Independent repetitions: 3; strategy order alternates by repetition
- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported
- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.

- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.

| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|---:|
| Independent ranking | 492.9 | 112.7 | 151892.00 | 105037.48 | 1.45x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate, o_orderpriority); tpch.orders(o_orderdate); tpch.orders(o_orderdate, o_custkey); tpch.partsupp(ps_supplycost)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 60 | 68.10 | 0.61 | 112.00x |
| `Q2-shipping-mode-summary` | 250 | 163.89 | 21.01 | 7.80x |
| `Q3-discounted-revenue` | 700 | 139.31 | 140.23 | 0.99x |
| `Q4-priority-orders` | 200 | 39.95 | 7.43 | 5.38x |
| `Q5-supplier-parts` | 50 | 26.53 | 2.05 | 12.95x |

| Joint re-evaluation (ours) | 467.0 | 104.7 | 151892.00 | 103826.90 | 1.46x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.orders(o_orderdate, o_orderpriority); tpch.partsupp(ps_supplycost, ps_suppkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 60 | 68.10 | 0.58 | 118.43x |
| `Q2-shipping-mode-summary` | 250 | 163.89 | 20.89 | 7.85x |
| `Q3-discounted-revenue` | 700 | 139.31 | 138.57 | 1.01x |
| `Q4-priority-orders` | 200 | 39.95 | 7.44 | 5.37x |
| `Q5-supplier-parts` | 50 | 26.53 | 1.69 | 15.72x |

## Replicate weighted summaries

| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|
| Independent ranking | 1 | 148323.49 | 101445.03 | 1.46x |
| Independent ranking | 2 | 151824.51 | 105151.44 | 1.44x |
| Independent ranking | 3 | 154349.05 | 105376.23 | 1.46x |
| Joint re-evaluation (ours) | 1 | 148323.49 | 103622.54 | 1.43x |
| Joint re-evaluation (ours) | 2 | 151824.51 | 106575.60 | 1.42x |
| Joint re-evaluation (ours) | 3 | 154349.05 | 103805.72 | 1.49x |

# Physical Strategy Validation

- Workload: `workloads/tpch-balanced-mixed.json`
- Budget: 500 MB
- Independent repetitions: 3; strategy order alternates by repetition
- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported
- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.

- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.

| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|---:|
| Independent ranking | 493.5 | 113.0 | 94702.91 | 40934.46 | 2.31x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate, o_orderpriority); tpch.orders(o_orderdate); tpch.orders(o_orderdate, o_custkey); tpch.partsupp(ps_supplycost); tpch.supplier(s_nationkey); tpch.supplier(s_nationkey, s_suppkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 200 | 68.61 | 0.60 | 114.92x |
| `Q2-shipping-mode-summary` | 220 | 164.36 | 20.89 | 7.87x |
| `Q3-discounted-revenue` | 250 | 136.71 | 139.09 | 0.98x |
| `Q4-priority-orders` | 150 | 38.59 | 7.55 | 5.11x |
| `Q5-supplier-parts` | 180 | 26.97 | 1.75 | 15.43x |

| Joint re-evaluation (ours) | 467.4 | 104.9 | 94702.91 | 40140.81 | 2.36x |

Selected indexes: `tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.lineitem(l_shipmode, l_shipdate); tpch.supplier(s_nationkey, s_suppkey); tpch.orders(o_orderdate, o_orderpriority); tpch.partsupp(ps_supplycost, ps_suppkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 200 | 68.61 | 0.59 | 117.08x |
| `Q2-shipping-mode-summary` | 220 | 164.36 | 21.06 | 7.80x |
| `Q3-discounted-revenue` | 250 | 136.71 | 136.00 | 1.01x |
| `Q4-priority-orders` | 150 | 38.59 | 7.58 | 5.09x |
| `Q5-supplier-parts` | 180 | 26.97 | 1.41 | 19.15x |

## Replicate weighted summaries

| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|
| Independent ranking | 1 | 91252.27 | 38883.81 | 2.35x |
| Independent ranking | 2 | 94457.81 | 41908.43 | 2.25x |
| Independent ranking | 3 | 98997.33 | 40934.62 | 2.42x |
| Joint re-evaluation (ours) | 1 | 91252.27 | 39216.85 | 2.33x |
| Joint re-evaluation (ours) | 2 | 94457.81 | 40144.29 | 2.35x |
| Joint re-evaluation (ours) | 3 | 98997.33 | 40795.22 | 2.43x |

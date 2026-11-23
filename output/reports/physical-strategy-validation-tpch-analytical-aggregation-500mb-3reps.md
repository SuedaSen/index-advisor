# Physical Strategy Validation

- Workload: `workloads/tpch-analytical-aggregation.json`
- Budget: 500 MB
- Independent repetitions: 3; strategy order alternates by repetition
- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported
- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.

- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.

| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|---:|
| Independent ranking | 492.9 | 112.7 | 162447.97 | 114317.68 | 1.42x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate, o_orderpriority); tpch.orders(o_orderdate); tpch.orders(o_orderdate, o_custkey); tpch.partsupp(ps_supplycost)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 60 | 70.28 | 0.62 | 113.72x |
| `Q2-shipping-mode-summary` | 250 | 176.04 | 21.74 | 8.10x |
| `Q3-discounted-revenue` | 700 | 149.13 | 153.19 | 0.97x |
| `Q4-priority-orders` | 200 | 42.24 | 7.53 | 5.61x |
| `Q5-supplier-parts` | 50 | 27.62 | 2.09 | 13.25x |

| Joint re-evaluation (ours) | 467.0 | 104.7 | 162447.97 | 112920.73 | 1.44x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.partsupp(ps_supplycost, ps_suppkey); tpch.orders(o_orderdate, o_orderpriority)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 60 | 70.28 | 0.62 | 113.72x |
| `Q2-shipping-mode-summary` | 250 | 176.04 | 21.92 | 8.03x |
| `Q3-discounted-revenue` | 700 | 149.13 | 151.07 | 0.99x |
| `Q4-priority-orders` | 200 | 42.24 | 7.85 | 5.38x |
| `Q5-supplier-parts` | 50 | 27.62 | 1.76 | 15.73x |

## Replicate weighted summaries

| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|
| Independent ranking | 1 | 153746.81 | 104859.94 | 1.47x |
| Independent ranking | 2 | 162447.97 | 114237.43 | 1.42x |
| Independent ranking | 3 | 172312.45 | 115560.05 | 1.49x |
| Joint re-evaluation (ours) | 1 | 153746.81 | 112956.41 | 1.36x |
| Joint re-evaluation (ours) | 2 | 162447.97 | 112890.83 | 1.44x |
| Joint re-evaluation (ours) | 3 | 172312.45 | 114454.59 | 1.51x |

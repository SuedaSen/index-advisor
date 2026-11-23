# Physical Strategy Validation

- Workload: `workloads/tpch-balanced-mixed.json`
- Budget: 500 MB
- Independent repetitions: 3; strategy order alternates by repetition
- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported
- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.

- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.

| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|---:|
| Independent ranking | 493.5 | 113.0 | 101851.26 | 43731.92 | 2.33x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate, o_orderpriority); tpch.orders(o_orderdate); tpch.orders(o_orderdate, o_custkey); tpch.partsupp(ps_supplycost); tpch.supplier(s_nationkey); tpch.supplier(s_nationkey, s_suppkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 200 | 69.96 | 0.61 | 115.26x |
| `Q2-shipping-mode-summary` | 220 | 180.13 | 21.72 | 8.29x |
| `Q3-discounted-revenue` | 250 | 149.06 | 149.56 | 1.00x |
| `Q4-priority-orders` | 150 | 40.57 | 7.52 | 5.40x |
| `Q5-supplier-parts` | 180 | 27.11 | 1.76 | 15.45x |

| Joint re-evaluation (ours) | 467.4 | 104.9 | 101851.26 | 44157.96 | 2.31x |

Selected indexes: `tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.lineitem(l_shipmode, l_shipdate); tpch.supplier(s_nationkey, s_suppkey); tpch.partsupp(ps_supplycost, ps_suppkey); tpch.orders(o_orderdate, o_orderpriority)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 200 | 69.96 | 0.60 | 117.19x |
| `Q2-shipping-mode-summary` | 220 | 180.13 | 21.63 | 8.33x |
| `Q3-discounted-revenue` | 250 | 149.06 | 151.65 | 0.98x |
| `Q4-priority-orders` | 150 | 40.57 | 7.46 | 5.44x |
| `Q5-supplier-parts` | 180 | 27.11 | 1.39 | 19.49x |

## Replicate weighted summaries

| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|
| Independent ranking | 1 | 94406.77 | 42883.98 | 2.20x |
| Independent ranking | 2 | 101851.26 | 43863.90 | 2.32x |
| Independent ranking | 3 | 103860.40 | 46429.91 | 2.24x |
| Joint re-evaluation (ours) | 1 | 94406.77 | 43113.86 | 2.19x |
| Joint re-evaluation (ours) | 2 | 101851.26 | 44246.57 | 2.30x |
| Joint re-evaluation (ours) | 3 | 103860.40 | 45600.13 | 2.28x |

# Physical Strategy Validation

- Workload: `workloads/tpch-dashboard-read-heavy.json`
- Budget: 500 MB
- Independent repetitions: 3; strategy order alternates by repetition
- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported
- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.

- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.

| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|---:|
| Independent ranking | 493.5 | 113.0 | 129675.70 | 19260.80 | 6.73x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate, o_orderpriority); tpch.orders(o_orderdate); tpch.orders(o_orderdate, o_custkey); tpch.partsupp(ps_supplycost); tpch.supplier(s_nationkey); tpch.supplier(s_nationkey, s_suppkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 500 | 66.80 | 0.60 | 110.96x |
| `Q2-shipping-mode-summary` | 450 | 158.43 | 20.91 | 7.58x |
| `Q3-discounted-revenue` | 50 | 135.42 | 137.65 | 0.98x |
| `Q4-priority-orders` | 300 | 38.77 | 7.43 | 5.22x |
| `Q5-supplier-parts` | 250 | 26.34 | 1.75 | 15.07x |

| Joint re-evaluation (ours) | 474.7 | 109.4 | 129675.70 | 19548.65 | 6.63x |

Selected indexes: `tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.lineitem(l_shipmode, l_shipdate); tpch.supplier(s_nationkey, s_suppkey); tpch.orders(o_orderdate, o_orderpriority); tpch.partsupp(ps_supplycost, ps_suppkey); tpch.customer(c_mktsegment, c_custkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 500 | 66.80 | 0.66 | 101.98x |
| `Q2-shipping-mode-summary` | 450 | 158.43 | 21.01 | 7.54x |
| `Q3-discounted-revenue` | 50 | 135.42 | 136.82 | 0.99x |
| `Q4-priority-orders` | 300 | 38.77 | 8.60 | 4.51x |
| `Q5-supplier-parts` | 250 | 26.34 | 1.39 | 18.91x |

## Replicate weighted summaries

| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|
| Independent ranking | 1 | 129775.10 | 18919.70 | 6.86x |
| Independent ranking | 2 | 128605.80 | 19528.20 | 6.59x |
| Independent ranking | 3 | 138591.55 | 19280.40 | 7.19x |
| Joint re-evaluation (ours) | 1 | 129775.10 | 18943.70 | 6.85x |
| Joint re-evaluation (ours) | 2 | 128605.80 | 19929.30 | 6.45x |
| Joint re-evaluation (ours) | 3 | 138591.55 | 19435.65 | 7.13x |

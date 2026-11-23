# Physical Strategy Validation

- Workload: `workloads/tpch-dashboard-read-heavy.json`
- Budget: 500 MB
- Independent repetitions: 3; strategy order alternates by repetition
- Within each repetition: 3 warm-ups plus 7 timed runs per query/configuration; median reported
- Safety: all `idx_physical_validation_*` indexes were removed after the run; TPC-H row counts were checked before and after.

- The selection budget applies to HypoPG's estimated size. The physical size is reported separately because it can differ.

| Strategy | Estimated index MB | Physical index MB | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|---:|
| Independent ranking | 486.6 | 100.4 | 137434.40 | 26487.95 | 5.19x |

Selected indexes: `tpch.lineitem(l_shipmode, l_shipdate); tpch.orders(o_orderdate, o_orderpriority); tpch.orders(o_orderdate); tpch.orders(o_orderdate, o_custkey); tpch.customer(c_mktsegment, c_custkey); tpch.supplier(s_nationkey); tpch.supplier(s_nationkey, s_suppkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 500 | 70.12 | 0.67 | 105.13x |
| `Q2-shipping-mode-summary` | 450 | 169.36 | 22.52 | 7.52x |
| `Q3-discounted-revenue` | 50 | 147.36 | 148.84 | 0.99x |
| `Q4-priority-orders` | 300 | 41.80 | 7.56 | 5.53x |
| `Q5-supplier-parts` | 250 | 25.02 | 25.25 | 0.99x |

| Joint re-evaluation (ours) | 394.0 | 98.9 | 137434.40 | 44673.05 | 3.08x |

Selected indexes: `tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.lineitem(l_shipmode, l_shipdate); tpch.supplier(s_nationkey, s_suppkey); tpch.partsupp(ps_supplycost, ps_suppkey); tpch.customer(c_mktsegment, c_custkey)`

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 500 | 70.12 | 0.67 | 104.04x |
| `Q2-shipping-mode-summary` | 450 | 169.36 | 22.95 | 7.38x |
| `Q3-discounted-revenue` | 50 | 147.36 | 143.13 | 1.03x |
| `Q4-priority-orders` | 300 | 41.80 | 88.35 | 0.47x |
| `Q5-supplier-parts` | 250 | 25.02 | 1.39 | 18.00x |

## Replicate weighted summaries

| Strategy | Repetition | Weighted baseline (ms-equivalent) | Weighted indexed (ms-equivalent) | Speedup |
|---|---:|---:|---:|---:|
| Independent ranking | 1 | 132814.55 | 26096.85 | 5.09x |
| Independent ranking | 2 | 136983.90 | 25901.05 | 5.29x |
| Independent ranking | 3 | 145852.40 | 30581.25 | 4.77x |
| Joint re-evaluation (ours) | 1 | 132814.55 | 44499.85 | 2.98x |
| Joint re-evaluation (ours) | 2 | 136983.90 | 46332.50 | 2.96x |
| Joint re-evaluation (ours) | 3 | 145852.40 | 44415.85 | 3.28x |

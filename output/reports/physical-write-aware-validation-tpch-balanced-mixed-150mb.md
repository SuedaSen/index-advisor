# Physical Write-Aware Validation

- Workload: `workloads/tpch-balanced-mixed.json`
- Budget: 150 MB; write-penalty weight: 1.0
- Each operation: 2 warm-ups + 5 timed rollback-only transactions of 10000 orders
- All temporary indexes were removed and TPC-H row counts were checked after the run.

| Configuration | Physical index MB | INSERT baseline (ms) | INSERT indexed (ms) | INSERT overhead | UPDATE baseline (ms) | UPDATE indexed (ms) | UPDATE overhead |
|---|---:|---:|---:|---:|---:|---:|---:|
| Read-only joint selection | 38.3 | 13.83 | 25.14 | 81.80% | 5.43 | 10.14 | 86.64% |

Selected indexes: `tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.supplier(s_nationkey, s_suppkey); tpch.orders(o_orderdate, o_orderpriority)`

| Write-aware joint selection | 51.9 | 13.83 | 16.08 | 16.27% | 5.43 | 7.51 | 38.21% |

Selected indexes: `tpch.partsupp(ps_supplycost); tpch.orders(o_orderdate); tpch.supplier(s_nationkey, s_suppkey); tpch.partsupp(ps_supplycost, ps_suppkey)`


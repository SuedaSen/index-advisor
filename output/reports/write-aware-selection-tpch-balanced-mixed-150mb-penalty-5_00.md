# Write-Aware Selection Comparison

- Workload: `workloads/tpch-balanced-mixed.json`
- HypoPG storage budget: 150 MB
- Write-penalty weight: 5.00
- Write profile: inserts into `tpch.orders` have table exposure 1.0; updates to `tpch.orders.o_orderdate` add column exposure 1.0.
- Interpretation: the penalty changes candidate priority, not the reported read-cost improvement. It is a transparent workload-policy heuristic; physical rollback-only write measurements are reported separately.

| Policy | Estimated index MB | HypoPG read-cost improvement | Selected indexes |
|---|---:|---:|---|
| Read-only joint selection | 93.6 | 15.33% | `tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost); tpch.supplier(s_nationkey, s_suppkey); tpch.partsupp(ps_supplycost, ps_suppkey)` |
| Write-aware joint selection | 100.9 | 15.34% | `tpch.partsupp(ps_supplycost); tpch.customer(c_mktsegment, c_custkey); tpch.supplier(s_nationkey, s_suppkey); tpch.orders(o_orderdate); tpch.partsupp(ps_supplycost, ps_suppkey)` |

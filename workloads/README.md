# TPC-H Workload Profiles

These profiles use the same reproducible TPC-H SF1 database but assign different frequencies to the same five query templates. A workload is not just a query list: its frequencies define which queries matter most to the physical-design decision.

| File | Purpose | Dominant query behavior |
|---|---|---|
| `tpch-dashboard-read-heavy.json` | Reporting/dashboard traffic | ordered lookups, shipping-mode summaries, priority reports |
| `tpch-analytical-aggregation.json` | Batch analytical processing | discounted-revenue and aggregate analysis |
| `tpch-balanced-mixed.json` | General mixed read workload | balanced joins, filters, ordering, and aggregation |

The separate rollback-only write-cost experiment is applied after the read-side configuration selection, allowing the final report to explain the read/write trade-off.

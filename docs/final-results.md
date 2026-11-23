# Final Results Snapshot

This document freezes the current evidence set for the CSE 510 final technical
report and presentation. All results use TPC-H SF1 in PostgreSQL 17 on the
local M3 MacBook Air environment. The workload profiles share five SQL
templates and differ only in query frequency, so the evaluation isolates the
effect of workload composition while holding schema, data scale, and query
semantics constant.

## Estimated strategy comparison

The advisor uses HypoPG to estimate index size and PostgreSQL optimizer cost.
The table compares the independent baseline with our joint re-evaluation
strategy. Values are estimated read-cost reductions relative to a configuration
without added secondary indexes.

| Profile | Budget | Independent improvement | Joint improvement | Independent estimated MB | Joint estimated MB |
|---|---:|---:|---:|---:|---:|
| Dashboard read-heavy | 150 MB | 26.16% | **26.60%** | 147.3 | **102.5** |
| Dashboard read-heavy | 500 MB | 75.14% | **79.69%** | 486.6 | **394.0** |
| Balanced mixed | 150 MB | 15.09% | **15.48%** | 147.3 | **95.2** |
| Balanced mixed | 500 MB | 44.09% | **48.21%** | 486.6 | **386.8** |
| Analytical aggregation | 150 MB | 3.19% | **3.25%** | 146.6 | **94.8** |
| Analytical aggregation | 500 MB | 23.86% | **24.51%** | 486.0 | **386.4** |

The estimated result motivates joint re-evaluation: it avoids double-counting
overlapping candidate benefits and frequently leaves more unused estimated
storage budget. These estimates are inputs to the physical experiment, not final
runtime claims.

## Physical validation

For every profile, we selected configurations with HypoPG at a 500 MB estimated
budget, then created temporary physical B-tree indexes in PostgreSQL. Each
profile uses three independent repetitions; each query/configuration measurement
uses three warm-ups and seven timed executions, with per-query medians. Strategy
order alternates by repetition. All temporary indexes are removed and TPC-H row
counts are verified after the experiment.

| Profile | Independent speedup | Joint speedup | Independent physical MB | Joint physical MB | Final interpretation |
|---|---:|---:|---:|---:|---|
| Dashboard read-heavy | **5.19x** | 3.08x | 100.4 | **98.9** | Independent is faster; joint is smaller. |
| Balanced mixed | **2.33x** | 2.31x | 113.0 | **104.9** | Near-equivalent runtime; joint is smaller. |
| Analytical aggregation | 1.42x | **1.44x** | 112.7 | **104.7** | Joint is slightly faster and smaller. |

The physical experiments do **not** support a universal claim that joint
re-evaluation is faster. They support the more careful conclusion that it
produces compact configurations and can be competitive, while optimizer-cost
estimates require executor-level validation.

## Write-aware sensitivity

The write-aware selector uses a transparent exposure model for `orders` INSERTs
and `o_orderdate` UPDATEs. Under Balanced Mixed and a 150 MB estimated budget:

| Penalty weight | Read-cost improvement | Observed membership change |
|---:|---:|---|
| 0.0 | 15.33% | Read-only joint baseline. |
| 1.0 | 15.33% | No membership change. |
| 5.0 | 15.34% | Adds `customer(c_mktsegment, c_custkey)`; retains `orders(o_orderdate)`. |

This is evidence that the policy is configurable and workload-dependent. It is
not evidence of a general physical write-overhead reduction for the current
workload; the final report should state that limitation plainly.

## Supported final claim

Our Java advisor parses a weighted SQL workload, derives and filters index
candidates, estimates their effect through HypoPG, and selects a storage-bounded
configuration. Controlled workload profiles and physical PostgreSQL validation
show that workload composition changes the selected configuration and that
optimizer estimates must be verified against measured execution time.

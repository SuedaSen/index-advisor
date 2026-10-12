# TPC-H Read/Write Trade-off

- Every write runs in a transaction and is rolled back.
- Runs: 2 warm-up + 5 measured; metric: median client-observed milliseconds.

| Operation | Baseline (ms) | Indexed (ms) | Write overhead |
|---|---:|---:|---:|
| INSERT 10,000 orders | 26.20 | 38.75 | 47.88% |
| UPDATE 10,000 order dates | 7.26 | 11.83 | 62.89% |

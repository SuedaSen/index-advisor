# TPC-H Physical Index Validation

- Runs per configuration: 3 warm-up + 7 measured
- Metric: median PostgreSQL execution time
- Index size: 104.7 MB
- Temporary indexes removed after measurement: yes
- Frequency-weighted reduction: **42.18%** (**1.73x** speedup)

| Query | Frequency | Baseline median (ms) | Indexed median (ms) | Speedup |
|---|---:|---:|---:|---:|
| `Q1-customer-segment-orders` | 120 | 63.76 | 0.58 | 110.51x |
| `Q2-shipping-mode-summary` | 300 | 156.51 | 20.78 | 7.53x |
| `Q3-discounted-revenue` | 500 | 130.95 | 132.91 | 0.99x |
| `Q4-priority-orders` | 180 | 36.27 | 6.84 | 5.30x |
| `Q5-supplier-parts` | 60 | 25.45 | 1.68 | 15.14x |

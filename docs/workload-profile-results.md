# TPC-H Workload Profile Results

These results were produced by `WorkloadDemoApplication` using HypoPG what-if evaluation over the same TPC-H SF1 database. Each profile contains the same five SQL templates but assigns different query frequencies. Therefore, the experiment isolates the effect of workload composition on index selection.

| Workload profile | Dominant behavior | 150 MB estimated joint improvement | 500 MB estimated joint improvement |
|---|---|---:|---:|
| Dashboard read-heavy | Ordered lookups and shipping summaries | 27.61% | 82.58% |
| Analytical aggregation | Discounted-revenue and aggregation queries | 4.21% | 25.66% |
| Balanced mixed | Balanced joins, filters, ordering, and aggregation | 15.98% | 49.60% |

## Interpretation

The advisor does not recommend one fixed configuration for all workloads. When dashboard queries are frequent, date and shipping-mode indexes have high workload-level value. When the aggregation-heavy Q3 query dominates, the achievable improvement is lower because the current candidate indexes provide limited benefit for that query. The balanced profile falls between the two extremes.

The large `tpch.lineitem(l_shipmode, l_shipdate)` candidate requires approximately 291.6 MB. It cannot be selected under the 150 MB budget but is selected under the 500 MB budget in all three profiles. This provides evidence that both workload composition and storage budget influence the advisor's decision.

## Reproduction

```bash
cd /Users/suedasen/Documents/CSE510-index-advisor

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WorkloadDemoApplication \
-Dexec.args="--workload /Users/suedasen/Documents/CSE510-index-advisor/workloads/tpch-dashboard-read-heavy.json --budgets 150,500"

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WorkloadDemoApplication \
-Dexec.args="--workload /Users/suedasen/Documents/CSE510-index-advisor/workloads/tpch-analytical-aggregation.json --budgets 150,500"

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn --quiet exec:java \
-Dexec.mainClass=edu.asu.cse510.indexadvisor.WorkloadDemoApplication \
-Dexec.args="--workload /Users/suedasen/Documents/CSE510-index-advisor/workloads/tpch-balanced-mixed.json --budgets 150,500"
```

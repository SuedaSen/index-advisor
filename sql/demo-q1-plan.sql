-- CSE 510 live demo: run one section at a time in DataGrip.
-- We first capture the baseline plan, then create the advisor's date index,
-- capture the indexed plan, and finally remove the demo index.

DROP INDEX IF EXISTS tpch.idx_demo_q1_orders_date;

-- Baseline: PostgreSQL must scan many orders, join them, then sort for LIMIT 100.
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.o_orderkey, o.o_orderdate, o.o_totalprice
FROM tpch.customer c
JOIN tpch.orders o ON o.o_custkey = c.c_custkey
WHERE c.c_mktsegment = 'BUILDING'
  AND o.o_orderdate >= DATE '1995-01-01'
ORDER BY o.o_orderdate DESC
LIMIT 100;

-- Advisor recommendation used in this focused demonstration.
CREATE INDEX idx_demo_q1_orders_date ON tpch.orders (o_orderdate);
ANALYZE tpch.orders;

-- Indexed plan: PostgreSQL can read recent orders directly in descending date order.
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.o_orderkey, o.o_orderdate, o.o_totalprice
FROM tpch.customer c
JOIN tpch.orders o ON o.o_custkey = c.c_custkey
WHERE c.c_mktsegment = 'BUILDING'
  AND o.o_orderdate >= DATE '1995-01-01'
ORDER BY o.o_orderdate DESC
LIMIT 100;

-- Always remove the manually-created demo index after the screenshot.
DROP INDEX IF EXISTS tpch.idx_demo_q1_orders_date;

-- CSE 510 DataGrip view for workload scenarios.
-- This creates only advisor_demo metadata tables; it does not change the tpch schema or TPC-H data.

CREATE SCHEMA IF NOT EXISTS advisor_demo;

CREATE TABLE IF NOT EXISTS advisor_demo.workload_profile (
    profile_key TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS advisor_demo.workload_query (
    profile_key TEXT NOT NULL REFERENCES advisor_demo.workload_profile(profile_key),
    query_name TEXT NOT NULL,
    frequency INTEGER NOT NULL CHECK (frequency > 0),
    query_role TEXT NOT NULL,
    PRIMARY KEY (profile_key, query_name)
);

INSERT INTO advisor_demo.workload_profile (profile_key, display_name, description) VALUES
    ('dashboard-read-heavy', 'Dashboard / Read-heavy', 'Frequent ordered lookups, shipping summaries, and priority reports.'),
    ('analytical-aggregation', 'Analytical Aggregation', 'Discounted-revenue and aggregate analysis dominate the workload.'),
    ('balanced-mixed', 'Balanced Mixed', 'Joins, filters, ordering, and aggregation have balanced importance.')
ON CONFLICT (profile_key) DO UPDATE
SET display_name = EXCLUDED.display_name, description = EXCLUDED.description;

INSERT INTO advisor_demo.workload_query (profile_key, query_name, frequency, query_role) VALUES
    ('dashboard-read-heavy', 'Q1-customer-segment-orders', 500, 'ordered customer/order lookup'),
    ('dashboard-read-heavy', 'Q2-shipping-mode-summary', 450, 'shipping-mode aggregation'),
    ('dashboard-read-heavy', 'Q3-discounted-revenue', 50, 'discounted-revenue aggregation'),
    ('dashboard-read-heavy', 'Q4-priority-orders', 300, 'priority report'),
    ('dashboard-read-heavy', 'Q5-supplier-parts', 250, 'supplier/parts lookup'),
    ('analytical-aggregation', 'Q1-customer-segment-orders', 60, 'ordered customer/order lookup'),
    ('analytical-aggregation', 'Q2-shipping-mode-summary', 250, 'shipping-mode aggregation'),
    ('analytical-aggregation', 'Q3-discounted-revenue', 700, 'discounted-revenue aggregation'),
    ('analytical-aggregation', 'Q4-priority-orders', 200, 'priority report'),
    ('analytical-aggregation', 'Q5-supplier-parts', 50, 'supplier/parts lookup'),
    ('balanced-mixed', 'Q1-customer-segment-orders', 200, 'ordered customer/order lookup'),
    ('balanced-mixed', 'Q2-shipping-mode-summary', 220, 'shipping-mode aggregation'),
    ('balanced-mixed', 'Q3-discounted-revenue', 250, 'discounted-revenue aggregation'),
    ('balanced-mixed', 'Q4-priority-orders', 150, 'priority report'),
    ('balanced-mixed', 'Q5-supplier-parts', 180, 'supplier/parts lookup')
ON CONFLICT (profile_key, query_name) DO UPDATE
SET frequency = EXCLUDED.frequency, query_role = EXCLUDED.query_role;

-- Result 1: one row per scenario; use this as the summary view.
SELECT p.display_name,
       p.description,
       SUM(q.frequency) AS total_workload_frequency
FROM advisor_demo.workload_profile p
JOIN advisor_demo.workload_query q USING (profile_key)
GROUP BY p.profile_key, p.display_name, p.description
ORDER BY p.profile_key;

-- Result 2: the exact query frequency mix that the Java advisor receives.
SELECT p.display_name,
       q.query_name,
       q.query_role,
       q.frequency,
       ROUND(100.0 * q.frequency / SUM(q.frequency) OVER (PARTITION BY q.profile_key), 1) AS workload_share_percent
FROM advisor_demo.workload_profile p
JOIN advisor_demo.workload_query q USING (profile_key)
ORDER BY p.profile_key, q.frequency DESC;

package edu.asu.cse510.indexadvisor.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit test for the bundled reproducible workload input. */
class WorkloadLoaderTest {
    @Test
    void loadsTheBundledTpchDemoWorkload() throws Exception {
        List<NamedWorkloadQuery> workload = new WorkloadLoader().loadResource("/workloads/tpch-demo.json");

        assertEquals(5, workload.size());
        assertTrue(workload.stream().allMatch(query -> query.frequency() > 0));
        assertTrue(workload.stream().allMatch(query -> query.sql().contains("SELECT")));
    }
}

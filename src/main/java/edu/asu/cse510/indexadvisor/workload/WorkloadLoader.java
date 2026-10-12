package edu.asu.cse510.indexadvisor.workload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** We load a reproducible JSON workload either from the bundled TPC-H example or a user file. */
public final class WorkloadLoader {
    private final ObjectMapper mapper = new ObjectMapper();
    public List<NamedWorkloadQuery> loadResource(String path) throws IOException {
        try (InputStream input = WorkloadLoader.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Workload resource not found: " + path);
            return read(input);
        }
    }
    public List<NamedWorkloadQuery> loadFile(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return read(input); }
    }
    private List<NamedWorkloadQuery> read(InputStream input) throws IOException {
        List<NamedWorkloadQuery> workload = mapper.readValue(input, new TypeReference<>() { });
        if (workload.isEmpty()) throw new IOException("Workload must contain at least one query");
        return List.copyOf(workload);
    }
}

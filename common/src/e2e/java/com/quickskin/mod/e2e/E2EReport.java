package com.quickskin.mod.e2e;

import com.quickskin.mod.e2e.generated.ScenarioContract;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates per-step results and writes a JSON report plus a {@code done.marker} sentinel into
 * {@code <runDir>/e2e-report/} (runDir == JVM working directory for a Loom run). The orchestrator
 * polls for the sentinel, then collects {@code report.json} and {@code ../screenshots/*}.
 *
 * <p>Hand-rolled JSON to avoid any classpath dependency.</p>
 */
public final class E2EReport {

    private record Entry(String name, String status, String message, String screenshot) {}

    private final String version;
    private final String role;
    private final String scenario;
    private final List<Entry> steps = new ArrayList<>();

    public E2EReport(String version, String role, String scenario) {
        this.version = version;
        this.role = role;
        this.scenario = scenario;
    }

    public void record(String name, String status, String message, String screenshot) {
        steps.add(new Entry(name, status, message, screenshot));
    }

    /** True only if every recorded step has status "pass". */
    public boolean allPassed() {
        if (steps.isEmpty()) return false;
        return steps.stream().allMatch(s -> "pass".equals(s.status));
    }

    /** Write report.json + done.marker. Returns the report file, or null on I/O failure. */
    public File write() {
        File dir = new File(System.getProperty("user.dir"), "e2e-report");
        try {
            Files.createDirectories(dir.toPath());
            File report = new File(dir, "report.json");
            Files.write(report.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
            // Sentinel written LAST so the orchestrator never reads a partial report.
            Files.write(new File(dir, "done.marker").toPath(),
                    (allPassed() ? "pass" : "fail").getBytes(StandardCharsets.UTF_8));
            return report;
        } catch (IOException e) {
            return null;
        }
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(q(version)).append(",\n");
        sb.append("  \"role\": ").append(q(role)).append(",\n");
        sb.append("  \"scenario\": ").append(q(scenario)).append(",\n");
        sb.append("  \"contract_sha256\": ").append(q(ScenarioContract.SHA256)).append(",\n");
        String selection = System.getProperty("quickskin.e2e.selection");
        if (selection != null) sb.append("  \"selection_sha256\": ").append(q(selection)).append(",\n");
        sb.append("  \"status\": ").append(q(allPassed() ? "pass" : "fail")).append(",\n");
        sb.append("  \"steps\": [\n");
        for (int i = 0; i < steps.size(); i++) {
            Entry e = steps.get(i);
            sb.append("    {");
            sb.append("\"name\": ").append(q(e.name)).append(", ");
            sb.append("\"status\": ").append(q(e.status)).append(", ");
            sb.append("\"message\": ").append(q(e.message)).append(", ");
            sb.append("\"screenshot\": ").append(e.screenshot == null ? "null" : q(e.screenshot));
            sb.append("}");
            sb.append(i < steps.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> b.append(c);
            }
        }
        return b.append('"').toString();
    }
}

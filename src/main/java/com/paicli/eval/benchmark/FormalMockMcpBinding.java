package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.mock.D1FrozenOracle;
import com.paicli.eval.benchmark.mock.D1ToolSelectionMock;
import com.paicli.eval.benchmark.mock.D2FrozenOracle;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.eval.benchmark.mock.F4FrozenOracle;
import com.paicli.eval.benchmark.mock.F4PendingDeletionMock;
import com.paicli.eval.benchmark.mock.FrozenMcpOracle;
import com.paicli.eval.benchmark.mock.AuditedMockMcp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-only capture of a frozen, declared MCP dependency, never a Candidate mount. */
final class FormalMockMcpBinding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FormalExecutionPlan.VerifierDependency dependency;
    private final FrozenMcpOracle oracle;
    private final String fileKey;

    private FormalMockMcpBinding(FormalExecutionPlan.VerifierDependency dependency,
            FrozenMcpOracle oracle, String fileKey) {
        this.dependency = dependency; this.oracle = oracle; this.fileKey = fileKey;
    }

    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        boolean profile = "D1".equals(plan.id()) && D1FrozenOracle.PROFILE.equals(plan.mockProfile())
                || "D2".equals(plan.id()) && D2FrozenOracle.PROFILE.equals(plan.mockProfile())
                && plan.evidenceRequirements().contains("mock_state")
                || "D3".equals(plan.id()) && D3FrozenOracle.PROFILE.equals(plan.mockProfile())
                && plan.evidenceRequirements().containsAll(List.of("mock_state", "approval_relay"))
                || "F4".equals(plan.id()) && F4FrozenOracle.PROFILE.equals(plan.mockProfile())
                && plan.evidenceRequirements().containsAll(List.of("mock_state", "approval_relay", "provider_turns"));
        return profile
                && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.MOCK_MCP
                && plan.evidenceRequirements().contains("mock_audit");
    }

    static FormalMockMcpBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported formal MCP binding");
        var dependency = plan.verifier().dependencies().stream()
                .filter(item -> ("validators/final/_private/oracles/" + plan.id() + ".json").equals(item.frozenPath())).findFirst()
                .orElseThrow(() -> new IOException("frozen MCP dependency not registered"));
        var value = read(dependency, plan.id());
        if (value.oracle() instanceof F4FrozenOracle f4 && !"MOCK_MCP".equals(f4.expectedToolProfile()))
            throw new IOException("legacy F4 prototype is not a formal source");
        String expectedPrompt = "# " + plan.id() + " "
                + com.paicli.eval.benchmark.finalset.generator.FinalSourceRecipeCatalog.require(plan.id()).title()
                + "\n\n" + value.oracle().newService().prompt()
                + "\n\nVariant: " + value.oracle().variantId() + "\n";
        if (!expectedPrompt.equals(plan.prompt()))
            throw new IOException("MCP prompt differs from its frozen mock definition");
        var fixture = plan.fixture();
        if (fixture.kind() != com.paicli.eval.benchmark.formal.FormalBenchmarkPreflight.FixtureKind.DIRECTORY
                || fixture.fileCount() != 1
                || !fixture.files().get(0).frozenPath().equals(fixture.frozenPath() + "/README.md")
                || !fixture.files().get(0).sha256().equals(value.oracle().baselineFiles().get("README.md")))
            throw new IOException("MCP baseline differs from the frozen fixture");
        return new FormalMockMcpBinding(dependency, value.oracle(), value.fileKey());
    }

    void verifyUnchanged() throws IOException {
        var current = read(dependency, oracle.caseId());
        if (!fileKey.equals(current.fileKey()) || !oracle.equals(current.oracle()))
            throw new IOException("frozen mock changed after preparation");
    }

    AuditedMockMcp newService() throws IOException {
        verifyUnchanged();
        return oracle.newService();
    }

    MockEvidence evidence(AuditedMockMcp mock) {
        return new MockEvidence(switch (oracle.caseId()) { case "D1" -> 1; case "D2" -> 2; case "D3" -> 3; case "F4" -> 4; default -> throw new IllegalStateException("unregistered mock"); }, oracle.caseId(), oracle.profile(), dependency.sha256(),
                mock.audit().stream().map(event -> JSON.<JsonNode>valueToTree(event)).toList(), mock.sideEffects(),
                mock.initialStateDigests(), mock.stateDigests(), mock instanceof D3ApprovalCalendarMock d3
                        ? d3.relayAudit().stream().map(event -> JSON.<JsonNode>valueToTree(event)).toList()
                        : mock instanceof F4PendingDeletionMock f4 ? f4.relayAudit().stream().map(event -> JSON.<JsonNode>valueToTree(event)).toList() : List.of(),
                mock instanceof F4PendingDeletionMock f4 ? f4.providerAudit().stream().map(event -> JSON.<JsonNode>valueToTree(event)).toList() : null,
                mock instanceof F4PendingDeletionMock f4 ? f4.destructiveCalls() : null);
    }

    /** Data only; production evidence is constructed exclusively from the retained host service. */
    record MockEvidence(int schemaVersion, String caseId, String profile, String mockSourceSha256,
            List<JsonNode> events, int sideEffects,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, String> initialStateDigests,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            Map<String, String> finalStateDigests,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<JsonNode> relayEvents,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<JsonNode> providerTurns,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Integer destructiveCalls) {
        MockEvidence(int schemaVersion, String caseId, String profile, String mockSourceSha256,
                List<JsonNode> events, int sideEffects, Map<String, String> initialStateDigests, Map<String, String> finalStateDigests, List<JsonNode> relayEvents) {
            this(schemaVersion, caseId, profile, mockSourceSha256, events, sideEffects, initialStateDigests, finalStateDigests, relayEvents, null, null);
        }
        MockEvidence(int schemaVersion, String caseId, String profile, String mockSourceSha256,
                List<JsonNode> events, int sideEffects, Map<String, String> initialStateDigests, Map<String, String> finalStateDigests) {
            this(schemaVersion, caseId, profile, mockSourceSha256, events, sideEffects, initialStateDigests, finalStateDigests, List.of());
        }
        MockEvidence {
            boolean d1 = schemaVersion == 1 && "D1".equals(caseId) && D1FrozenOracle.PROFILE.equals(profile);
            boolean d2 = schemaVersion == 2 && "D2".equals(caseId) && D2FrozenOracle.PROFILE.equals(profile);
            boolean d3 = schemaVersion == 3 && "D3".equals(caseId) && D3FrozenOracle.PROFILE.equals(profile);
            boolean f4 = schemaVersion == 4 && "F4".equals(caseId) && F4FrozenOracle.PROFILE.equals(profile);
            if (!(d1 || d2 || d3 || f4)
                    || mockSourceSha256 == null || !mockSourceSha256.matches("[0-9a-f]{64}")
                    || events == null || events.size() > (d1 ? 256 : 768) || sideEffects < 0)
                throw new IllegalArgumentException("invalid host MCP evidence");
            events = events.stream().map(JsonNode::deepCopy).map(JsonNode.class::cast).toList();
            Set<String> fields = d1 ? Set.of("sequence", "method", "tool", "outcome", "params")
                    : d2 ? Set.of("sequence", "server", "method", "tool", "outcome", "params", "resultSha256", "stateBeforeSha256", "stateAfterSha256")
                    : Set.of("sequence", "source", "server", "method", "tool", "outcome", "params", "resultSha256", "stateBeforeSha256", "stateAfterSha256");
            for (int i = 0; i < events.size(); i++) {
                JsonNode event = events.get(i);
                Set<String> actual = new java.util.HashSet<>(); event.fieldNames().forEachRemaining(actual::add);
                if (!event.isObject() || !actual.equals(fields) || !event.path("sequence").isIntegralNumber()
                        || !event.path("sequence").canConvertToInt() || event.path("sequence").intValue() != i + 1
                        || !event.path("params").isObject()
                        || !event.path("method").isTextual() || !event.path("tool").isTextual() || !event.path("outcome").isTextual()
                        || event.toString().length() > 70_000)
                    throw new IllegalArgumentException("invalid MCP audit event");
                if (d2 && (!com.paicli.eval.benchmark.mock.D2ReadOnlyJoinMock.SERVERS.contains(event.path("server").asText())
                        || List.of("resultSha256", "stateBeforeSha256", "stateAfterSha256").stream()
                        .anyMatch(field -> !event.path(field).isTextual() || !event.path(field).textValue().matches("[a-f0-9]{64}"))))
                    throw new IllegalArgumentException("invalid D2 audit identities");
                if ((d3 || f4) && (!event.path("source").isTextual() || !Set.of("MCP", "HITL", "POLICY", "USER").contains(event.path("source").textValue())
                        || !event.path("server").isTextual() || !(f4 ? Set.of("", "repository") : Set.of("", "availability", "calendar")).contains(event.path("server").textValue())
                        || List.of("resultSha256", "stateBeforeSha256", "stateAfterSha256").stream().anyMatch(field ->
                            !event.path(field).isTextual() || !event.path(field).textValue().matches("[a-f0-9]{64}"))))
                    throw new IllegalArgumentException("invalid approval audit identities");
            }
            initialStateDigests = initialStateDigests == null ? Map.of() : Map.copyOf(initialStateDigests);
            finalStateDigests = finalStateDigests == null ? Map.of() : Map.copyOf(finalStateDigests);
            for (Map<String, String> state : List.of(initialStateDigests, finalStateDigests))
                if (d1 ? !state.isEmpty() : !state.keySet().equals(d2 ? Set.of("directory", "ticket", "calendar") : f4 ? Set.of("repository") : Set.of("calendar"))
                        || state.values().stream().anyMatch(value -> !value.matches("[a-f0-9]{64}")))
                    throw new IllegalArgumentException("invalid MCP state digest set");
            if (!d3 && !f4 && relayEvents == null) relayEvents = List.of(); // v1/v2 never had this field.
            if (relayEvents == null || relayEvents.size() > 4096 || !d3 && !f4 && !relayEvents.isEmpty())
                throw new IllegalArgumentException("invalid relay audit count");
            relayEvents = relayEvents.stream().map(JsonNode::deepCopy).map(JsonNode.class::cast).toList();
            for (int i = 0; i < relayEvents.size(); i++) {
                JsonNode event = relayEvents.get(i);
                Set<String> actual = new java.util.HashSet<>(); event.fieldNames().forEachRemaining(actual::add);
                if (!event.isObject() || !actual.equals(Set.of("sequence", "turn", "request", "response"))
                        || !event.path("sequence").isIntegralNumber() || event.path("sequence").asLong() != i + 1
                        || !event.path("turn").isIntegralNumber() || !Set.of(1, 2).contains(event.path("turn").asInt())
                        || !event.path("request").isObject() || !(event.path("response").isObject() || event.path("response").isNull()))
                    throw new IllegalArgumentException("invalid ordered relay event");
            }
            // Absent on legacy v1/v2; explicitly [] on v3/v4 attempts failing before any relay event.
            if (!d3 && !f4) relayEvents = null;
            if (f4) {
                if (providerTurns == null || providerTurns.size() > 4096 || destructiveCalls == null || destructiveCalls < 0)
                    throw new IllegalArgumentException("missing F4 provider/state audit");
                providerTurns = providerTurns.stream().map(JsonNode::deepCopy).map(JsonNode.class::cast).toList();
                for (int i = 0; i < providerTurns.size(); i++) {
                    JsonNode p = providerTurns.get(i);
                    Set<String> keys = new java.util.HashSet<>(); p.fieldNames().forEachRemaining(keys::add);
                    if (!p.isObject() || !keys.equals(Set.of("ordinal", "turn", "relayEventsSeen", "content", "toolCalls", "messages", "tools"))
                            || !p.path("ordinal").isIntegralNumber() || p.path("ordinal").asLong() != i + 1
                            || !p.path("turn").isIntegralNumber() || !Set.of(1L, 2L).contains(p.path("turn").asLong())
                            || !p.path("relayEventsSeen").isIntegralNumber() || p.path("relayEventsSeen").asLong() < 0
                            || p.path("relayEventsSeen").asLong() > relayEvents.size() || !p.path("content").isTextual()
                            || !p.path("toolCalls").isArray() || !p.path("messages").isArray() || !p.path("tools").isArray())
                        throw new IllegalArgumentException("invalid F4 provider audit");
                }
            } else if (providerTurns != null || destructiveCalls != null) {
                throw new IllegalArgumentException("unexpected F4 fields on legacy MCP evidence");
            }
        }
        @Override public List<JsonNode> events() { return events.stream().map(JsonNode::deepCopy).map(JsonNode.class::cast).toList(); }
        @Override
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<JsonNode> relayEvents() { return relayEvents == null ? null : relayEvents.stream().map(JsonNode::deepCopy).map(JsonNode.class::cast).toList(); }
        @Override
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<JsonNode> providerTurns() { return providerTurns == null ? null : providerTurns.stream().map(JsonNode::deepCopy).map(JsonNode.class::cast).toList(); }
    }

    private static Capture read(FormalExecutionPlan.VerifierDependency dependency, String caseId) throws IOException {
        Path path = dependency.sourcePath();
        if (!"0400".equals(dependency.mode()) || dependency.size() > FrozenMcpOracle.MAX_BYTES
                || Files.isSymbolicLink(path) || !path.equals(path.toRealPath()))
            throw new IOException("unsafe frozen MCP dependency");
        Path root = path;
        for (int i = 0; i < Path.of(dependency.frozenPath()).getNameCount(); i++) root = root.getParent();
        if (root == null || !root.resolve(dependency.frozenPath()).equals(path))
            throw new IOException("frozen MCP path binding mismatch");
        for (Path dir = path.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isSymbolicLink(dir) || !dir.equals(dir.toRealPath())
                    || !List.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))))
                throw new IOException("unsafe frozen MCP parent");
            if (dir.equals(root)) break;
        }
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() != dependency.size()
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("r--------")))
            throw new IOException("frozen MCP dependency identity mismatch");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(FrozenMcpOracle.MAX_BYTES + 1);
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.size() != dependency.size() || bytes.length != dependency.size()
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("r--------"))
                || !hash(bytes).equals(dependency.sha256()))
            throw new IOException("frozen MCP dependency drift");
        return new Capture(FrozenMcpOracle.parse(caseId, bytes), before.fileKey().toString());
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Capture(FrozenMcpOracle oracle, String fileKey) {}
}
